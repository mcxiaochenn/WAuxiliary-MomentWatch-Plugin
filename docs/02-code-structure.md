# 02 · 代码结构

## 目录

```
plugins/v127/mcxiaochen/MomentWatch/
├── info.prop          插件元信息
├── main.java          入口脚本
├── readme.md          插件说明
└── src/
    ├── Util.java
    ├── Config.java
    ├── Jumper.java
    ├── Notifier.java
    ├── SnsHook.java
    ├── SettingsUi.java
    └── Service.java
```

- `info.prop` 与 `main.java` 是 WA 的必需文件；`config.prop` 由配置接口在首次写入时自动生成，不预置。
- 相对路径一律以 `pluginDir` 为基准，这是 WA `loadJava` 的解析规则。

## main.java

**职责**：模块加载顺序 + WA 回调转发。**不含任何业务逻辑与状态**。

| 符号 | 类型 | 说明 |
|---|---|---|
| `onLoad()` | WA 回调 | 转发到 `mwServiceOnLoad()` |
| `onUnload()` | WA 回调 | 转发到 `mwServiceOnUnload()` |
| `openSettings()` | WA 回调 | 转发到 `mwServiceOpenSettings()` |

```java
loadJava("src/Util.java");
loadJava("src/Config.java");
loadJava("src/Jumper.java");
loadJava("src/Notifier.java");
loadJava("src/SnsHook.java");
loadJava("src/SettingsUi.java");
loadJava("src/Service.java");
```

**入口脚本里唯一需要格外小心的事**：不要 `import` 或命名 `de.robv.android.xposed.*` 下的任何类型。
WA 默认开启「Xposed API 调用保护」，插件脚本解析 `XC_MethodHook` / `XposedBridge` 这类名字时会直接
加载失败（实测报错 `Class: XC_MethodHook not found in namespace`，表现为插件被自动关闭）。
Hook 一律使用 WA 内置的 `hookAfter` / `unhook`，且回调参数用反射读取 —— 详见 `SnsHook.java`。

> Hook 句柄为什么不放在入口：WA 内置 Hook 接口是「注册即返回句柄」的形式，
> 注册点与释放点在同一个模块（`SnsHook`）里更内聚，也避免了入口脚本出现匿名类。
> `main.java` 保持纯粹的回调转发，最不容易出错。

## src/Util.java — 通用工具

不依赖任何业务状态，被所有模块调用。

| 函数 | 说明 |
|---|---|
| `mwUtilIsEmpty(String)` | null / 空白判定 |
| `mwUtilTrim(String)` | null 安全的 trim |
| `mwUtilCleanText(String)` | 去 HTML 实体与标签、去控制字符、压缩空白（用于通知正文） |
| `mwUtilCut(String, int)` | 超长截断并加省略号 |
| `mwUtilCjkCount(String)` | 统计中日韩字符数（用于判断正文是否值得展示） |
| `mwUtilParseSet(String)` → `HashSet` | 解析 `,` `，` `;` `；` 换行分隔的字符串为集合 |
| `mwUtilJoinSet(Collection)` → `String` | 集合序列化为逗号分隔字符串 |
| `mwUtilFormatTime(long)` | 秒级时间戳 → `MM-dd HH:mm` |
| `mwUtilFormatDuration(long)` | 秒 → `1 天` / `6 小时` / 不限 |
| `mwUtilToastContext()` / `mwUtilToast(String)` | 切主线程弹 Toast，关掉 Toast 的线程兼容问题 |
| `mwUtilLog(String)` | 统一加 `[MomentWatch]` 前缀，便于在插件日志里过滤 |

## src/Config.java — 配置与运行状态

内存模型 + 持久化。**所有对 `config.prop` 的写入都集中在这个文件**。

### 内存状态

| 变量 | 类型 | 说明 |
|---|---|---|
| `mwEnabled` | `boolean` | 总开关 |
| `mwWatchRaw` / `mwWatchSet` | `String` / `HashSet` | 关注名单的原始串与解析后的集合 |
| `mwMaxAgeSec` | `long` | 提醒时效窗口（秒），`0` 表示不限 |
| `mwSeenOrder` / `mwSeenSet` | `ArrayList` / `HashSet` | 去重账本：顺序表负责按写入顺序淘汰，集合负责 O(1) 命中 |
| `mwMonitorStartSec` | `long` | 本次监视起点（秒），每次插件加载重置 |
| `mwLock` | `Object` | 保护去重账本（Hook 线程与 UI 线程并发访问） |

### 函数

| 函数 | 说明 |
|---|---|
| `mwConfigLoad()` | 从 `config.prop` 读全部配置，重建集合与账本 |
| `mwConfigSetEnabled(boolean)` | 写总开关 |
| `mwConfigSetWatchList(String)` | 写关注名单，同步更新内存集合 |
| `mwConfigSetMaxAge(long)` / `mwConfigMaxAgeLabel()` / `mwConfigCycleMaxAge()` | 时效的写入、展示文案、按候选值循环切换 |
| `mwSeenMark(long)` → `boolean` | 登记一条 `snsId`；**返回 `true` 表示首次登记（应提醒）**，`false` 表示已登记过 |
| `mwSeenContains(long)` | 预检查，避免重复进入加锁分支 |
| `mwSeenClear()` | 清空账本并持久化 |
| `mwConfigSaveSeen()` | 把账本序列化写回 `config.prop` |

> `mwSeenMark` 的返回值语义是这套设计的核心：**判定与登记合成一次原子操作**，
> 避免「先查后写」在并发下重复提醒。

## src/Jumper.java — 跳转组装

只负责构造 Intent，不发通知。

| 函数 | 说明 |
|---|---|
| `mwJumpHostPkg()` | 取宿主包名（容错改包名的微信） |
| `mwJumpNewIntent(String)` | 按组件名创建 Intent |
| `mwJumpMakeHome()` | 微信首页（作为任务栈底） |
| `mwJumpMakeTimeline()` | 朋友圈首页 |
| `mwJumpMakeUser(String)` | 指定作者的朋友圈页面 |
| `mwJumpMakeDetail(String, long, int)` | **精确跳转**到某条朋友圈详情页 |
| `mwJumpBuild(String, long, int)` → `Intent[]` | 按三级降级策略组装 `PendingIntent.getActivities` 需要的数组 |

## src/Notifier.java — 通知

| 函数 | 说明 |
|---|---|
| `mwNotifyTypeName(int)` | 类型 → `文字动态` / `图文动态` / `视频动态` / `新动态` |
| `mwNotifyDisplayName(String)` | 显示名：备注 → 昵称 → 综合名 → `wxid` |
| `mwNotifyEnsureChannel(...)` | 首次使用时创建通知渠道（API 26+） |
| `mwNotifyNewPost(...)` | 构造并发出通知，挂上跳转 `PendingIntent` |
| `mwNotifyTest()` | 设置界面用的测试通知 |

通知 ID 与 `PendingIntent` 的 `requestCode` 都用 `snsId` 派生，因此同一条动态重复提醒会复用同一条通知，
不同动态互不覆盖。

## src/SnsHook.java — Hook 与判定

### Hook 侧

| 符号 | 说明 |
|---|---|
| `MW_DB_CLASSES` | `com.tencent.wcdb.database.SQLiteDatabase`、`com.tencent.wcdb.compat.SQLiteDatabase` |
| `MW_DB_WRITE_METHODS` | `insert` / `insertOrThrow` / `insertWithOnConflict` / `replace` / `replaceOrThrow` / `update` / `updateWithOnConflict` |
| `mwHookHandles` | 注册返回的句柄列表，`onUnload` 逐个 `unhook()` |
| `mwHookInstall()` → `int` | 遍历类上全部同名重载，逐个用 WA 内置 `hookAfter` 注册；返回成功注册的方法数 |
| `mwHookIsWriteMethod(String)` | 方法名是否属于要 Hook 的写入方法集合 |
| `mwHookUninstall()` | 释放全部句柄并清空列表 |

`mwHookInstall` 返回 0 时不置「已安装」，允许后续重试；`Service` 会在日志与 Toast 上给出明确提示。
**代码里不出现任何 Xposed 类型名**：回调写成 `hookAfter(method, param -> { mwSnsHandleHookParam(param); })`，
`param` 不声明类型。

### 判定侧

| 函数 | 说明 |
|---|---|
| `mwSnsHandleHookParam(Object)` | Hook 回调入口：解析出 `args` / 返回值 / 方法名，转交判定主流程 |
| `mwSnsReadParamArgs(Object)` | 分层解析回调入参：`param` 本身是数组 → 字段 `args` → `getArgs()` → `getArguments()` → `arguments` → 一次性探测并缓存访问器 |
| `mwSnsProbeArgsAccessor(Object)` | 探测访问器：扫无参方法，取返回「首元素为字符串的数组/列表」的那个（DB 写入的 `args[0]` 必为表名） |
| `mwSnsReadParamMember` / `mwSnsReadParamResult` | 取被 Hook 的成员 / 返回值，同样字段与 getter 都试 |
| `mwSnsListFields` / `mwSnsListMethods` | 诊断用：读不到入参时把真实类型、字段表、方法表打进日志 |
| `mwSnsHandleDbWrite(Object[], Object, String)` | 判定主流程（见下） |
| `mwSnsIsTargetTable(Object)` | 表名是否 `SnsInfo`（大小写不敏感） |
| `mwSnsPickValues(Object[])` | 从参数里找出 `ContentValues` |
| `mwSnsPickString` / `mwSnsPickLong` | 按候选键列表取值（兼容列名与 Java 字段名两种风格） |
| `mwSnsDetectType(ContentValues)` | `type` → `0` 纯文字 / `1` 图文 / `2` 视频 / `-1` 未知 |
| `mwSnsLocalIdFrom(Object, String)` | 从写入返回值取本地行号作为 `localId`；`update` 的返回值是行数，不作数 |
| `mwSnsExtractText(ContentValues)` | 借用微信 `SnsInfo` 解码正文；失败返回空串 |
| `mwSnsReadField(Object, String)` | 反射读字段（先 public 后 declared） |
| `mwSnsCallNoArg(Object, String)` | 反射调无参方法 |

`args` 读不到时会**只提示一次**参数实际类型，便于下次排查 WA 回调结构变化：

```
[MomentWatch] Hook 回调参数不符合预期，无法读取 args，实际类型: xxx
```

### 判定主流程

```
mwSnsHandleHookParam(param)
  └─ 反射取 args / getResult() / method.getName()
       └─ mwSnsHandleDbWrite(args, result, methodName)
            ├─ 总开关关？                        → 放过
            ├─ args[0] 不是 "SnsInfo"？          → 放过        ← 最先做的廉价过滤
            ├─ 取不到 ContentValues？            → 放过
            ├─ userName 为空？                   → 放过
            ├─ 关注名单为空 / 不包含 userName？  → 放过
            ├─ snsId 取不到或为 0？              → 放过
            ├─ createTime > 0 时：
            │    ├─ 早于 监视起点 - 30s？        → 放过（历史动态）
            │    └─ 早于 now - 时效窗口？        → 放过（超时效补收）
            ├─ mwSeenContains(snsId)？           → 放过（并发预检查）
            ├─ mwSeenMark(snsId) 返回 false？    → 放过（原子去重）
            ├─ 解析 localId / postType / text
            └─ mwNotifyNewPost(...)
```

过滤顺序按「代价从低到高」排列：字符串比较在最前，需要反射解码的正文提取放在最后且只在真正命中时执行。
这条路径上的 Hook 是全局的（所有 SQLite 写入都会经过），所以第一道 `表名` 判断必须极廉价。

## src/SettingsUi.java — 设置界面

界面全部用代码构建，不引用宿主布局资源，避免版本间资源 id 变化。

| 分组 | 函数 |
|---|---|
| 视图基元 | `mwUiDp` / `mwUiRound` / `mwUiTitle` / `mwUiHint` / `mwUiDivider` / `mwUiAddDivider` / `mwUiRow` / `mwUiButton` / `mwUiMakeCard` |
| 弹窗外壳 | `mwUiNewDialog` / `mwUiAttachCard` / `mwUiShowDialog` / `mwUiHideSoftInput` |
| 设置主页 | `mwUiShowSettings` / `mwUiWatchSummary` / `mwUiStateSummary` |
| 好友多选 | `mwUiPickFriends` / `mwUiBuildFriendPicker` / `mwUiFillFriendList` |

设计要点：

- **好友列表在后台线程读取**，先弹一个「正在读取好友列表…」的轻量弹窗，读完切回主线程构建列表。
  好友数量通常上百，同步读取会卡住设置界面。
- **筛选与全选**：`mwUiFilteredIds` 保存当前筛选条件下可见的 `wxid`，「全选 / 反选」只作用于这批人，
  避免用户以为筛选后全选只选当前页、结果却选了全部好友。
- **渲染上限** `MW_UI_MAX_ROWS = 300`：超出时只渲染前 300 行并提示用筛选缩小范围，
  避免上千个 `CheckBox` 拖慢界面。
- **分隔线必须用 `mwUiAddDivider`**：`mwUiDivider` 返回的是裸 `View`，裸 View 不覆写 `onMeasure`，
  在 `AT_MOST` 约束下 `WRAP_CONTENT` 会被 `View.getDefaultSize()` 解析成"整个可用高度"。
  卡片本身是 `WRAP_CONTENT`，于是第一条分隔线会吃掉全部剩余高度，把它后面所有行挤成 0 高度 ——
  真机表现就是"设置页只有标题和说明，下面一整块浅灰、点不动"。`mwUiAddDivider` 显式给 1dp 高度。
  该约束已写进 `tools/verify/lint.py`。
- **写配置统一走 `Config`**：界面只收集 `selected` 集合，保存时调用 `mwConfigSetWatchList`。

## src/Service.java — 生命周期

| 函数 | 说明 |
|---|---|
| `mwServiceOnLoad()` | 载入配置 → 重置监视起点 → 注册 Hook → 按结果记录日志 / 提示 |
| `mwServiceOnUnload()` | 卸载 Hook → 落盘去重账本 |
| `mwServiceOpenSettings()` | 打开设置界面 |

`onLoad` 与 `onUnload` 严格成对：`onLoad` 注册的东西只有 Hook，`onUnload` 里全部释放；
账本在卸载时落盘，确保重载后不会重复提醒。

## 一次完整触发路径

以「关注的张三刚发了一条图文动态」为例：

```
[微信进程] SNS 同步线程收到新动态
   └─ SnsInfoStorage 组装 ContentValues(userName=wxid_zhangsan, snsId=-3707261564527312320,
                                        createTime=1791193178, type=1, content=<blob>, ...)
      └─ WCDB SQLiteDatabase.insert("SnsInfo", null, cv)      ← 被 WA 内置 Hook 拦下
         └─ SnsHook: hookAfter 注册的回调(param)
            ├─ 反射取 args / result(=rowid 12876) / methodName="insert"
            └─ mwSnsHandleDbWrite(args, 12876, "insert")
               ├─ 表名 "SnsInfo"                    ✓
               ├─ userName = wxid_zhangsan          ✓ 在关注名单
               ├─ snsId = -3707261564527312320      ✓
               ├─ createTime 在时效窗口内           ✓
               ├─ mwSeenMark(snsId) → true          ✓ 首次
               ├─ localId = 12876（insert 返回行号）
               ├─ postType = 1（图文）
               └─ text = "今天去看了海"（SnsInfo.decode 得到，失败则为空）
                  └─ Notifier: mwNotifyNewPost(...)
                     ├─ 显示名：备注 → "张三"
                     ├─ 标题 "张三 发布了图文动态"，正文 "今天去看了海"
                     ├─ Jumper: mwJumpBuild(wxid_zhangsan, snsId, 12876)
                     │    └─ [LauncherUI, SnsCommentDetailUI{INTENT_TALKER, INTENT_SNS_LOCAL_ID="sns_table_12876", INTENT_SNSID}]
                     └─ NotificationManager.notify(hash("mw_notify_"+snsId), 通知)
[用户点击通知]
   └─ PendingIntent.getActivities 依序启动 → 微信 → 该条朋友圈详情页
```

## 命名与风格约定

- 顶层函数与全局变量一律带模块前缀，避免 WA 共享命名空间下的重名覆盖：
  `mwUtil*` / `mwConfig*` / `mwSeen*` / `mwJump*` / `mwNotify*` / `mwSns*` / `mwHook*` / `mwUi*` / `mwService*`。
- 常量用 `MW_` 前缀。
- 所有会对宿主产生副作用或依赖版本结构的调用都包在 `try/catch (Throwable)` 里，
  单个环节失败只降级不中断（例如正文解码失败就发一条没有摘要的通知）。
- 顶部 import 用通配写法（`android.widget.*`），与 WA 插件生态的既有风格一致。
- **不引用 `de.robv.android.xposed.*` 下的任何类型**：WA 默认开启「Xposed API 调用保护」，
  命名 `XC_MethodHook` 之类的类型会让整个插件加载失败。需要 Hook 时用 WA 内置的
  `hookAfter` / `hookBefore` / `hookReplace` / `unhook`，回调参数保持不声明类型、用反射读字段。
- **Hook 回调的调用链上不得给脚本级变量赋值**：被 lambda 捕获的脚本变量在 BeanShell 里是 final，
  赋值会抛 `Cannot re-assign final variable`，而且异常在 lambda 调用边界抛出、回调内部的 `try/catch`
  拦不住，表现为每次数据库写入都报一次错。需要在回调间保持的可变状态一律用**长度 1 的数组**承载
  （数组元素赋值不受限制），例如 `mwHookParamWarned` / `mwHookArgsAccessor`。
- **不给裸 `View` 当布局占位**：同 `mwUiAddDivider` 的说明，这类控件在 `AT_MOST` 下会撑满剩余空间。
- 不使用 BeanShell 兼容性存疑的语法：不用 enhanced-for、不给方法参数加 `final`；
  Hook 回调用 Lambda（与 WA 官方 HookDemo 一致），其它需要匿名类的地方一律用匿名内部类。
