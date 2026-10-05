# 03 · 接口定义

本文档是接口契约的唯一出处。任何一次 WA 或微信侧的接口变更，都应先更新这里再改代码。

分三类：

1. **WA 侧接口**：宿主 WAuxiliary 提供的全局变量、配置接口、联系人接口与回调。
2. **微信侧接口**：本项目 Hook 的方法、读取的表结构、启动的页面与 Intent 键。
3. **插件内部接口**：模块之间调用的函数契约。

## 1. WA 侧接口

来源：WAuxiliary 官方插件文档 `docs/api/`（`PluginGlobal.md`、`PluginConfigMethod.md`、
`PluginContactMethod.md`、`PluginOtherMethod.md`、`PluginCallback.md`、`PluginHookMethod.md`）。

### 1.1 全局变量

| 名称 | 类型 | 用途 |
|---|---|---|
| `hostContext` | `android.content.Context` | 宿主 Context。本项目用于：取包名、取 `ClassLoader`、取 `NotificationManager`、作为 `PendingIntent` 的上下文 |

未使用 `pluginDir` / `pluginName` / `cacheDir` / `hostVerName` 等其它全局量。

### 1.2 配置接口

| 签名 | 用途 |
|---|---|
| `String getString(String key, String defValue)` | 读关注名单原始串、去重账本 |
| `boolean getBoolean(String key, boolean defValue)` | 读总开关 |
| `long getLong(String key, long defValue)` | 读提醒时效 |
| `void putString(String key, String value)` | 写关注名单、去重账本 |
| `void putBoolean(String key, boolean value)` | 写总开关 |
| `void putLong(String key, long value)` | 写提醒时效 |

约束：配置存放在插件目录下的 `config.prop`，由 WA 在首次写入时自动创建，因此插件不预置该文件。
读取一律提供默认值，避免首次运行时拿到 null。

> 本项目**没有**使用 `getStringSet` / `putStringSet`：集合统一序列化成逗号分隔字符串，
> 好处是 BeanShell 下不用处理泛型，且直接可读、方便排障（见 [05](05-config-and-storage.md)）。

### 1.3 联系人接口

| 签名 | 用途 |
|---|---|
| `List<FriendInfo> getFriendList()` | 好友多选列表的数据源 |
| `String getFriendRemarkName(String friendWxid)` | 通知标题优先取备注 |
| `String getFriendNickName(String friendWxid)` | 备注为空时取昵称 |
| `String getFriendName(String friendWxid)` | 上面两者都拿不到时的综合名 |

`FriendInfo` 使用到的成员：`String getWxid()`、`String getNickname()`。

### 1.4 其它接口

| 签名 | 用途 |
|---|---|
| `void log(Object msg)` | 统一日志输出，本插件统一加 `[MomentWatch]` 前缀 |
| `void toast(String text)` | Toast 兜底路径（优先走原生 Toast） |
| `Activity getTopActivity()` | 弹设置界面前的上下文获取，可能为 `null` |
| `void loadJava(String path)` | 仅 `main.java` 使用，按 `pluginDir` 为基准加载模块 |

未使用 `notify(title, text)`：它无法自定义点击行为，无法满足「点通知跳到对应朋友圈」。

### 1.5 回调

| 签名 | 本项目实现 |
|---|---|
| `void onLoad()` | 定义在 `main.java`，转发到 `mwServiceOnLoad()` |
| `void onUnload()` | 定义在 `main.java`，转发到 `mwServiceOnUnload()` |
| `void openSettings()` | 定义在 `main.java`，转发到 `mwServiceOpenSettings()`，再打开设置界面 |

未实现 `onHandleMsg` / `onClickSendBtn` / `onMemberChange` / `onNewFriend` / `onRecvPayMsg` /
`onCreate*Menu` —— 本插件的触发源是数据库写入而不是消息事件，不需要这些回调。

### 1.6 Hook 接口

本项目使用 WA 的**内置 Hook 封装**，不使用 Xposed 原生接口：

| 签名 | 用途 |
|---|---|
| `Object hookAfter(Member member, Consumer callback)` | 在原方法执行后回调；返回注册句柄 |
| `void unhook(Object handle)` | 释放 `hookAfter` 返回的句柄，在 `onUnload` 中调用 |

**为什么不用 `XposedBridge` / `XC_MethodHook`**：WA 默认开启「Xposed API 调用保护」，
插件脚本无法解析 `de.robv.android.xposed.*` 下的类型。实测（WA `1.2.7.r1499`）一旦在脚本里命名
`XC_MethodHook`，整个插件会在加载阶段直接失败：

```
Plugin[MomentWatch]: load Failed: Sourced file: eval stream :
  Typed variable declaration : Class: XC_MethodHook not found in namespace
	at XC_MethodHook (eval stream:34)
```

社区里同类插件（如「僵尸粉检测」）在相同版本上也报 `Unknown class: XC_MethodHook`。
WA 官方示例 `plugins/v127/Hd/HookDemo` 明确区分了两条路：

```java
// 内置Hook方法(hookBefore / hookAfter / hookReplace)
onBeforeHook = hookBefore(method, param -> { log("onResume Before") });

// 原生Hook方法(需关闭 Xposed API 调用保护)
onAfterHook = XposedBridge.hookMethod(method, new XC_MethodHook() { ... });
```

因此本插件的 Hook 侧遵守三条约束：

1. 只用 `hookAfter` / `unhook`；
2. 回调里**不声明 `param` 的类型**，一律用反射读 `args` / `method` / `getResult()`，
   代码里不出现任何 Xposed 类型名；
3. 回调调用链上**不得给脚本级变量赋值** —— 被 lambda 捕获的脚本变量是 final，
   赋值会抛 `Cannot re-assign final variable`，且异常在 lambda 调用边界抛出，
   回调内部的 `try/catch` 拦不住（实测表现为每次数据库写入都报一次错、功能全失效）。
   需要在回调间保持的可变状态一律用长度 1 的数组承载。

**回调参数的结构是版本敏感项，不要假设成 `XC_MethodHook.MethodHookParam`。**
实测 WA `1.2.7.r1499` 的 APK 里**完全没有** `de.robv.android.xposed`（它基于 libxposed API
+ YukiHookAPI），按"公开字段 `args`"读入参会失败。因此 `mwSnsReadParamArgs` 采用分层解析：

| 顺序 | 尝试 |
|---|---|
| 1 | `param` 本身就是入参数组 |
| 2 | 字段 `args`（含继承链，`setAccessible`） |
| 3 | `getArgs()` / `getArguments()` / 字段 `arguments` |
| 4 | 一次性探测：扫无参访问器，取返回「首元素为字符串的数组/列表」的那个，并缓存 |

探测判据很稳：数据库写入方法的 `args[0]` 必然是表名字符串。探测结果缓存在长度 1 的数组里，
只做一次，不会给每次写入增加负担。读不到时会把真实类型、字段表、方法表打进日志，便于下次适配。

要覆盖同一方法名的全部重载（`insert` 有多个重载），本项目自行遍历 `getDeclaredMethods()`
逐个注册，而不是依赖 `hookAllMethods`。

## 2. 微信侧接口

这部分的**每一项都是版本敏感项**，改动或升级微信后应优先复核。

### 2.1 被 Hook 的类与方法

| 类名 | 方法 |
|---|---|
| `com.tencent.wcdb.database.SQLiteDatabase` | `insert`、`insertOrThrow`、`insertWithOnConflict`、`replace`、`replaceOrThrow`、`update`、`updateWithOnConflict` |
| `com.tencent.wcdb.compat.SQLiteDatabase` | 同上 |

类名找不到时跳过该分支，不影响另一分支。

**稳定性依据**：依赖的只有「类名 + 方法名 + 第一个参数是表名字符串」这三件事。
类名本身在 WCDB 里未混淆（腾讯自研库，非业务包），方法名是 Android 标准 API；表名 `SnsInfo` 是数据库 schema 的一部分。

### 2.2 读取的表结构

`SnsMicroMsg.db` 的 `SnsInfo` 表（列名与微信源码/schema 一致）：

```sql
CREATE TABLE SnsInfo (
    snsId LONG,          -- 朋友圈 ID（64 位，可能为负）
    userName TEXT,       -- 发布者 wxid
    localFlag INTEGER,
    createTime INTEGER,  -- 发布时间（秒）
    head INTEGER,
    localPrivate INTEGER,
    type INTEGER,        -- 1=图文 2=纯文字 15=视频
    sourceType INTEGER,
    likeFlag INTEGER,
    pravited INTEGER,
    stringSeq TEXT,
    withTa TEXT,
    withTaHasOther INTEGER,
    content BLOB,        -- protobuf，需解码
    attrBuf BLOB,
    postBuf BLOB,
    subType INTEGER,
    serverExtFlag INTEGER
)
```

关键点：**该表没有 `localId` 列**。所谓"本地 ID"就是这一行隐式的 `rowid`，
也就是 `insert` / `insertWithOnConflict` / `replace` 的返回值。
这一点决定了 `localId` 的来源（见 2.4）。

`ContentValues` 取值时，本项目对每个字段都提供「列名」与「`field_` 开头的 Java 字段名」两种候选：

| 目标 | 候选键 |
|---|---|
| 发布者 | `userName`、`field_userName` |
| snsId | `snsId`、`field_snsId`、`svrId` |
| 发布时间 | `createTime`、`field_createTime`、`create_time`、`timestamp`、`field_timestamp` |
| 类型 | `type`、`field_type` |

### 2.3 正文解码

| 调用 | 说明 |
|---|---|
| `com.tencent.mm.plugin.sns.storage.SnsInfo#<init>()` | 空构造 |
| `#convertFrom(ContentValues)` | 用写入的 `ContentValues` 填充对象 |
| `#getTimeLine()` | 取 `TimeLineObject` |
| `TimeLineObject` 的 `ContentDesc` / `contentDesc` / `desc` / `description` 字段 | 正文文本 |

全部通过反射调用，**任一环节失败即返回空串**，只影响通知正文的摘要，不影响提醒本身。

### 2.4 页面与 Intent 键

| 常量 | 值 |
|---|---|
| 详情页 | `com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI` |
| 作者朋友圈 | `com.tencent.mm.plugin.sns.ui.SnsUserUI` |
| 朋友圈首页 | `com.tencent.mm.plugin.sns.ui.SnsTimeLineUI` |
| 微信首页 | `com.tencent.mm.ui.LauncherUI` |

详情页读取的 extra（与微信 Smali 中的字符串常量一致）：

| 键 | 值 | 说明 |
|---|---|---|
| `INTENT_TALKER` | 发布者 `wxid` | 详情页用于定位与展示 |
| `INTENT_SNS_LOCAL_ID` | `"sns_table_" + localId` | **精确跳转的关键**，微信时间线点击走的就是这个 |
| `INTENT_SNSID` | `String.valueOf(snsId)` | 兜底键，部分版本支持仅凭 snsId 定位 |
| `INTENT_FROMGALLERY` | `false` | 与从时间线进入的行为一致 |
| `INTENT_NEED_RPT_FEED` | `true` | 上报位，与微信自身调用一致 |

`SnsUserUI` 读取的 extra：`sns_userName` = 发布者 `wxid`。

组合方式：`PendingIntent.getActivities(ctx, requestCode, Intent[] { 首页, 目标页 }, flags)` ——
数组首元素作为任务栈底，末元素是真正被启动的页面。

## 3. 插件内部接口

模块间只通过下列函数交互。调用方向受加载顺序约束（只能调用已加载模块）。

### 3.1 `Util` 对外

```java
boolean mwUtilIsEmpty(String s)
String  mwUtilTrim(String s)
String  mwUtilCleanText(String s)
String  mwUtilCut(String s, int max)
int     mwUtilCjkCount(String s)
HashSet mwUtilParseSet(String raw)
String  mwUtilJoinSet(Collection set)
String  mwUtilFormatTime(long sec)
String  mwUtilFormatDuration(long seconds)
void    mwUtilToast(String msg)
void    mwUtilLog(String msg)
```

### 3.2 `Config` 对外

```java
void    mwConfigLoad()
void    mwConfigSetEnabled(boolean v)
void    mwConfigSetWatchList(String raw)
void    mwConfigSetMaxAge(long sec)
String  mwConfigMaxAgeLabel()
void    mwConfigCycleMaxAge()
boolean mwSeenMark(long snsId)      // true = 首次登记，应提醒
boolean mwSeenContains(long snsId)
void    mwSeenClear()
void    mwConfigSaveSeen()
```

对外暴露的全局状态：`mwEnabled`、`mwWatchSet`、`mwMaxAgeSec`、`mwMonitorStartSec`、
`mwSeenSet`、`mwSeenOrder`。

### 3.3 `Jumper` 对外

```java
Intent[] mwJumpBuild(String userName, long snsId, int localId)
```

返回至少含 1 个元素的数组；全部构造失败返回 `null`。契约：
- `localId > 0` → 末元素为 `SnsCommentDetailUI`
- `localId <= 0` 且 `userName` 非空 → 末元素为 `SnsUserUI`
- 两者都不可用 → 末元素为 `SnsTimeLineUI`

### 3.4 `Notifier` 对外

```java
void mwNotifyNewPost(String userName, long snsId, int localId, String text, int postType)
void mwNotifyTest()
```

### 3.5 `SnsHook` 对外

```java
int  mwHookInstall()      // 返回成功注册的方法数；0 表示当前版本不兼容
void mwHookUninstall()
void mwSnsHandleHookParam(Object param)   // 解析回调参数后转交判定主流程
void mwSnsHandleDbWrite(Object[] args, Object result, String methodName)
```

对外暴露的全局状态：`mwHookHandles`、`mwHookInstalled`。

### 3.6 `SettingsUi` 对外

```java
void mwUiShowSettings()
```

### 3.7 `Service` 对外（供 `main.java` 回调转发）

```java
void mwServiceOnLoad()
void mwServiceOnUnload()
void mwServiceOpenSettings()
```

## 4. 内部数据契约

BeanShell 脚本里不定义跨模块传递的数据类（避免额外的类解析开销与命名空间污染），
一次「命中」用一组普通值在模块间传递。这条契约等同于一个结构体：

| 字段 | 类型 | 来源 | 用途 |
|---|---|---|---|
| `userName` | `String` | `ContentValues` 的 `userName` | 关注名单匹配、通知显示名、跳转 |
| `snsId` | `long` | `ContentValues` 的 `snsId` | 去重键、通知 ID、`PendingIntent` requestCode |
| `localId` | `int` | 写入方法的返回值（rowid） | 精确跳转；`<= 0` 表示不可用 |
| `createSec` | `long` | `ContentValues` 的 `createTime` | 时效判定；`<= 0` 表示未知 |
| `postType` | `int` | `ContentValues` 的 `type` 映射 | 通知文案 |
| `text` | `String` | `SnsInfo` 解码 | 通知正文摘要；可为空串 |

传递签名依次为：

```
mwSnsHandleDbWrite(Object[] args, Object result, String methodName)   // 解析出入参
    → mwNotifyNewPost(String userName, long snsId, int localId, String text, int postType)
        → mwJumpBuild(String userName, long snsId, int localId)
```

## 5. 接口变更应对清单

| 现象 | 优先检查 |
|---|---|
| 日志里没有「命中关注对象」 | 2.1 类名/方法名是否仍存在；`Service` 日志里的「Hook N 个方法」是否为 0 |
| 有命中但通知没弹出 | 通知权限、通知渠道是否被用户关闭；`Notifier` 是否抛异常 |
| 通知能弹但点不开 | 2.4 组件名是否仍存在 |
| 点通知只到朋友圈首页 | `localId` 是否为 `-1`（即 `update` 路径或 rowid 不可用），或 2.4 的 extra 键名变化 |
| 点通知跳到错误的动态 | 2.2 的 rowid 语义是否变化（详情页把 `INTENT_SNS_LOCAL_ID` 当成了别的含义） |
| 通知正文为空 | 2.3 的解码链路变化（此时提醒仍然可用，只是没有摘要） |
