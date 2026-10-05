# 04 · 新动态检测与点击跳转方案

这两件事是本插件里唯二「依赖微信内部实现」的部分，因此单独成篇，把依据、风险和降级都写清楚。

## 一、新动态检测

### 1.1 候选方案与取舍

| 方案 | 依赖 | 判断 |
|---|---|---|
| Hook WCDB `SQLiteDatabase` 写入方法 | 类名、方法名、表名字符串 | ✅ 采用 |
| Hook `NetSceneSnsSync` 等网络回调 | 混淆类名 + protobuf 结构 | ❌ 需要 DexKit，版本敏感 |
| Hook `SnsInfoStorage` 插入逻辑 | 混淆类名 | ❌ 需要 DexKit，版本敏感 |
| 直接读 `SnsMicroMsg.db` | 文件权限、数据库独占 | ❌ 进程常驻时不可行，也无实时性 |

核心判断：**不论微信内部怎么重构，一条新朋友圈最终一定要以 `INSERT` 的形式落进 `SnsInfo` 表。**
`SQLiteDatabase.insert(String table, String nullColumnHack, ContentValues values)` 是 Android 标准 API，
WCDB 沿用了同样的签名；表名 `SnsInfo` 是数据库 schema 的一部分，不随代码混淆变化。
因此这条路径的稳定性远高于 Hook 微信业务类。

### 1.2 Hook 点

必须使用 WA 的**内置 Hook 接口**，不能用 Xposed 原生 `XposedBridge`（原因见 1.2.1）。

```java
ClassLoader cl = hostContext.getClassLoader();
Class db = Class.forName("com.tencent.wcdb.database.SQLiteDatabase", false, cl);
Method[] ms = db.getDeclaredMethods();
for (int i = 0; i < ms.length; i++) {
    if (!mwHookIsWriteMethod(ms[i].getName())) continue;      // insert / replace / update 等共 7 个方法名
    mwHookHandles.add(hookAfter(ms[i], param -> {             // param 不声明类型
        mwSnsHandleHookParam(param);
    }));
}
// 兼容层 com.tencent.wcdb.compat.SQLiteDatabase 同样处理
```

- **自己遍历 `getDeclaredMethods()` 逐个注册**，而不是找个"批量注册"接口：这样才能覆盖
  同一方法名的全部重载（`insert` 有 3 个），同时不必手写方法签名匹配。
- 两个类名都试，任一命中即可；都不命中时 `mwHookInstall` 返回 0，并在日志与 Toast 上明确报出。
- `Class.forName(name, false, cl)` 用 `initialize=false`：只做 dex 查找，不触发类初始化，
  因此即使朋友圈数据库还没打开，也能在插件加载阶段完成注册。

#### 1.2.1 为什么不能用 XposedBridge（实测踩坑）

WA 默认开启「Xposed API 调用保护」，插件脚本的命名空间里**没有** `de.robv.android.xposed.*`。
只要脚本里出现 `XC_MethodHook` 这类类型名，整个插件会在加载阶段失败：

```
Plugin[MomentWatch]: load Failed: Sourced file: eval stream :
  Typed variable declaration : Class: XC_MethodHook not found in namespace
	at XC_MethodHook (eval stream:34)
```

在 WA `1.2.7.r1499` / 微信 `8.0.78` 上实测确认，且社区插件「僵尸粉检测」在同版本上同样报
`Unknown class: XC_MethodHook`。WA 官方示例 `plugins/v127/Hd/HookDemo` 也把内置 Hook 与原生 Hook
分成两条路，并注明原生方式"需关闭 Xposed API 调用保护"。

因此本项目的写法是：

1. 注册只用 `hookAfter` / `unhook`；
2. 回调里 `param` **不声明类型**，`args` / `method` / 返回值全部用反射读取，
   代码里不出现任何 Xposed 类型名。

反射读取有一个额外好处：回调参数的结构如果跟预期不同，插件会打印一次实际类型（见
[02-code-structure.md](02-code-structure.md) 的判定侧），下次适配时能直接定位。

### 1.3 参数解析

写入方法的参数里，`args[0]` 是表名，`ContentValues` 可能是第 2 或第 3 个参数（不同重载不同），
所以用类型扫描而不是固定下标：

```java
if (!"SnsInfo".equalsIgnoreCase(String.valueOf(args[0]))) return;   // 最先执行的廉价过滤
ContentValues cv = null;
for (int i = 0; i < args.length; i++)
    if (args[i] instanceof ContentValues) { cv = (ContentValues) args[i]; break; }
```

**性能约束**：这个 Hook 是全局的 —— 微信所有数据库写入都会进来。所以第一道判断必须是
「取 `args[0]` 转字符串比大小」，在它之前不能有任何分配、反射或日志操作。

### 1.4 `localId` 的来源（精确跳转的关键）

`SnsInfo` 表**没有 `localId` 列**，它的"本地 ID"就是这行的隐式 `rowid`。
而 `SQLiteDatabase.insert(...)` / `insertWithOnConflict(...)` / `replace(...)` 的返回值正好是 `rowid`。
所以：

```java
// 只在 insert 系列方法上取用；update 返回的是影响行数，不是 rowid
boolean insertLike = methodName.indexOf("insert") >= 0
        || methodName.equals("replace") || methodName.equals("replaceOrThrow");
long rowid = ((Number) result).longValue();
localId = (insertLike && rowid > 0 && rowid <= Integer.MAX_VALUE) ? (int) rowid : -1;
```

依据来自微信自身的代码：时间线点击一条动态进入详情页时，构造的正是
`putExtra("INTENT_SNS_LOCAL_ID", s.v("sns_table_", <localId>))`，
其中的 `<localId>` 就是 `SnsInfo` 的本地行号。

### 1.5 正文解码

`ContentValues` 里的 `content` 是 protobuf BLOB，直接 `String.valueOf` 只能得到乱码。
复用微信自己的解码路径：

```java
Object info = SnsInfo.class.newInstance();
SnsInfo.class.getDeclaredMethod("convertFrom", ContentValues.class).invoke(info, cv);
Object timeline = SnsInfo.class.getDeclaredMethod("getTimeLine").invoke(info);
String text = readField(timeline, "ContentDesc");   // 依次尝试 ContentDesc/contentDesc/desc/description
```

失败即返回空串。**通知照发，只是少了摘要** —— 这是刻意的降级，不让一个展示细节拖垮主功能。

### 1.6 干扰抑制

刚装好插件、第一次打开朋友圈时，微信会整页同步并插入大量历史动态。如果不加约束会瞬间刷屏。
三道约束：

| 约束 | 规则 | 目的 |
|---|---|---|
| 监视起点 | `createSec >= mwMonitorStartSec - 30` | 每次插件加载重置。早于它的都是历史数据 |
| 时效窗口 | `createSec >= now - mwMaxAgeSec`（默认 24 小时） | 长时间离线/补收后不会一次性轰炸 |
| 去重账本 | 同一 `snsId` 只提醒一次 | 同一条动态被重复写入（更新、重同步）不会重复提醒 |

监视起点用的是**每次加载都重置**而不是持久化：这样「重启微信后」不会把上次会话期间的动态当成新动态；
时效窗口则负责兜住"离线期间产生的真实新动态"，两者互补。

> 边界情况：`createTime` 取不到时（`<= 0`）不做时间过滤，直接进入去重与提醒流程。
> 选择"宁多勿漏"，因为拿不到时间戳本身就很罕见，而漏掉用户明确关注的动态更糟。

### 1.7 已知局限

- **时效依赖微信自身的同步**：插件捕获的是"写入本地库"这个时刻。若微信长时间未同步，
  动态会在下次同步时被捕获，此时可能已超出时效窗口。要缩短这个窗口需要主动触发朋友圈刷新，
  那必须 Hook 混淆类（DexKit 匹配），成本与脆弱度都显著更高，当前版本不做。
- **不做主动刷新**：同上。
- **只覆盖当前登录账号**：数据库路径由账号派生，插件未做多账号区分。

## 二、点击跳转

### 2.1 目标页面的三种可能

| 层级 | 目标 | 组件 | 精确度 |
|---|---|---|---|
| 一级 | 那条朋友圈的详情页 | `SnsCommentDetailUI` | 精确 |
| 二级 | 该作者的朋友圈相册 | `SnsUserUI` | 定位到人 |
| 三级 | 朋友圈首页 | `SnsTimeLineUI` | 只到入口 |

需求是"跳到对应朋友圈"，所以一级是主路径，二三级是降级。

### 2.2 一级：详情页

依据是微信源码里时间线点击的写法（多个微信版本的反编译结果一致）：

```java
Intent i = new Intent();
i.setClass(context, SnsCommentDetailUI.class);
i.putExtra("INTENT_TALKER", snsInfo.field_userName);
i.putExtra("INTENT_SNS_LOCAL_ID", "sns_table_" + localId);
i.putExtra("INTENT_FROMGALLERY", ...);
```

本项目在此基础上补一个兜底键：

```java
it.putExtra("INTENT_TALKER", userName);
if (localId > 0) it.putExtra("INTENT_SNS_LOCAL_ID", "sns_table_" + localId);
if (snsId != 0L) it.putExtra("INTENT_SNSID", String.valueOf(snsId));   // 部分版本支持仅凭 snsId 定位
it.putExtra("INTENT_FROMGALLERY", false);
it.putExtra("INTENT_NEED_RPT_FEED", true);
```

详情页里两条定位路径并存：优先用 `INTENT_SNS_LOCAL_ID`（本地行号），
`INTENT_SNSID`（全局 ID）是它的备用来源。两个都给，覆盖不同版本的读取顺序。

> **风险点（最高）**：如果某版本的详情页把 `INTENT_SNS_LOCAL_ID` 当作别的含义，
> 可能跳到错误的动态。因此这一项被列为真机必测项第一名，见 [06-test-plan.md](06-test-plan.md)。

### 2.3 二级：作者朋友圈

```java
Intent i = new Intent();
i.setComponent(new ComponentName(pkg, "com.tencent.mm.plugin.sns.ui.SnsUserUI"));
i.putExtra("sns_userName", wxid);
```

这是社区通行的稳定做法，多个微信版本可用。`localId` 拿不到时（例如走了 `update` 路径）用它。

### 2.4 三级：朋友圈首页

组件名 `com.tencent.mm.plugin.sns.ui.SnsTimeLineUI`，无 extra。兜底中的兜底。

### 2.5 启动方式

```java
Intent home = ...;      // LauncherUI，作为任务栈底
Intent[] opens = new Intent[] { home, target };
PendingIntent pi = PendingIntent.getActivities(hostContext, reqCode, opens,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
```

- 用 `getActivities` 而不是 `getActivity`：先把微信首页放进任务栈，再把详情页压在上面，
  这样点击通知后退出详情页能回到正常的微信界面，而不是留下一个孤立的 Activity。
- `requestCode` 由 `snsId` 派生，保证不同动态的通知不会互相复用 `PendingIntent`。
- `FLAG_IMMUTABLE` 是 Android 12+ 的强制要求（API 31 起可变 `PendingIntent` 必须显式声明）。
- 因为 `PendingIntent` 用的是宿主 `hostContext`，启动的是同包 Activity，
  不受 `android:exported` 限制。

### 2.6 降级策略一览

```
localId > 0 ?
  ├── 是 → [LauncherUI, SnsCommentDetailUI]      精确到那一条
  └── 否 → userName 非空 ?
            ├── 是 → [LauncherUI, SnsUserUI]     定位到人
            └── 否 → [LauncherUI, SnsTimeLineUI] 只到入口
```

降级只在构造阶段决定，运行期不做二次尝试（通知点击是系统行为，插件没有介入点）。
插件在构造时会记一条日志说明本次用的哪一级，便于排障：

```
[MomentWatch] 跳转模式=精确 localId=12876 snsId=-3707261564527312320
```

## 三、版本适配的排查路径

按这个顺序查，能最快定位到分层：

1. 看插件日志有没有 `数据库写入 Hook 注册完成，共 N 个方法`，`N` 是否为 0。
2. 发一条动态，看有没有 `命中关注对象: ...`。
3. 有命中但没通知 → 通知权限 / 渠道问题，看有没有 `已发出通知` 与 `发出通知失败`。
4. 通知弹出但落点不对 → 看 `跳转模式=` 是哪一级；
   若是「精确」但跳错了，说明 `INTENT_SNS_LOCAL_ID` 的语义变了，需要按新版本反编译结果修正；
   若是「作者朋友圈」或「朋友圈首页」，说明 `localId` 没取到，检查 1.4 的方法名匹配。

对应的接口清单在 [03-interfaces.md](03-interfaces.md) 第 2 节与第 5 节。
