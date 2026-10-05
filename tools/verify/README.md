# 验证工具链

不依赖真机的静态与逻辑验证，用来在改动插件后快速回归。

## 运行

```bash
bash tools/verify/run.sh
```

前置条件：JDK（`javac` + `java`，8 及以上）、Python 3。首次运行会从 BeanShell 官方 Releases 下载
`bsh-3.0.0b1.jar`（也可用 `BSH_JAR=/path/to/bsh.jar` 指定本地文件）。

## 三步验证

### 1. BeanShell 解析校验

WA 按 BeanShell 规则执行 `.java` 脚本。这一步用真实的 BeanShell 解析器（`bsh-3.0.0b1`，
与社区在用插件的解析行为一致）逐个解析插件的 8 个脚本，确认语法在 WA 运行时下成立。
任何 `FAIL` 都表示该文件在真机上会加载失败。

### 2. 源码约束检查（`lint.py`）

把踩过的坑变成门禁，命中即失败：

| 规则 | 为什么 |
|---|---|
| 代码里不得出现 `de.robv.android.xposed` / `XC_MethodHook` / `XposedBridge` | WA 的「Xposed API 调用保护」会让插件在加载阶段直接失败 |
| 不得 `addView(mwUiDivider(...))`，必须用 `mwUiAddDivider(ctx, parent)` | 裸 `View` 不覆写 `onMeasure`，在 `AT_MOST` 约束下 `WRAP_CONTENT` 会被解析成"整个可用高度"，第一条分隔线就会吃掉卡片全部剩余高度，后面的控件全被挤成 0 高度 |

注释与字符串里的出现不算违规（本项目在注释里解释这些坑）。

### 3. 核心逻辑校验

在本地以桩类替换 Android / WA 接口，把模块按 `loadJava` 顺序拼装后真实执行，
覆盖 71 项断言：

| 分组 | 断言数 | 覆盖内容 |
|---|---|---|
| 工具函数 | 6 | 名单分隔符解析、正文清洗、截断、时长格式化、空值判定、`trim` 容错 |
| 配置读写 | 6 | 默认值、写入后内存同步、从 `config.prop` 重读、时效循环切换 |
| 命中判定 | 10 | 非目标表不触发、未关注的人不触发、命中并携带 localId、同一 snsId 去重、早于监视起点不触发、超出时效不触发、时效不限时可触发、`update` 无行号时降级、总开关关闭不触发 |
| 参数解析 | 9 | `type` → 类型映射、`insert`/`insertWithOnConflict` 返回行号作 localId、`update` 返回值不作 localId、表名大小写不敏感 |
| 跳转组装 | 9 | 精确跳转的组件与三个 extra、Intent 数组首元素、两级降级路径、组件包名 |
| Hook 注册与回调 | 22 | 枚举并注册全部 8 个写入重载、覆盖 insert/replace/update、不注册 delete、重复安装幂等；用字段形态与 getter 形态的伪回调参数各端到端驱动一次「新动态落库」、`insert` 取返回值作 localId、`update` 不使用行数、非目标表不触发；非法参数不抛异常且只告警一次；`unhook` 逐个释放、卸载清空句柄、目标类缺失时安全返回 0 |
| 去重账本 | 7 | 上限淘汰、顺序表长度一致、最旧淘汰、最近保留、重复登记语义、清空 |
| 集合互转 | 2 | 名单集合与字符串往返一致、空集合序列化 |

## 目录说明

```
tools/verify/
├── run.sh           编排入口
├── assemble.py      把模块与用例拼装成单个可执行脚本（复现 loadJava 的共享命名空间）
├── lint.py          源码约束检查（禁 Xposed 类型、禁裸 View 当分隔线）
├── BshCheck.java    基于 bsh.Parser 的解析校验工具
├── stubs/           仅用于本地执行的桩类
│   ├── android/     ContentValues / Intent / ComponentName / Context / Handler / Toast ...
│   ├── com/tencent/wcdb/database/  SQLiteDatabase（8 个写入重载 + delete，用于验证 Hook 枚举）
│   ├── me/hd/       FriendInfo
│   └── mwtest/      FakeHookParam / GetterHookParam（两种回调参数形态，插件全用反射读）
└── cases/
    ├── support.bsh  WA 全局接口与宿主环境桩（config 存储、好友列表、通知拦截等）
    └── core.bsh     71 项断言
```

## 覆盖边界

- **覆盖**：不依赖真实 Android UI 的全部纯逻辑，即命中判定、去重、时效、参数解析、跳转组装，
  以及 Hook 的注册、回调解析与释放链路（用桩类与伪回调参数端到端驱动）。
- **不覆盖**：真实通知的展示与点击、好友多选界面交互、微信数据库 Hook 是否真的拦到写入、以及跳转是否真的落到指定那条朋友圈。
  这几项依赖宿主行为，只能在真机验证，见 [docs/06-test-plan.md](../../docs/06-test-plan.md)。

`stubs/` 只是为了让脚本能在 JVM 上跑起来，**不参与插件运行**，插件在 WA 里使用的始终是真实的
Android / WA 接口。

注意：插件本身**不引用**任何 Xposed 类型（WA 的「Xposed API 调用保护」会拦下来），
所以桩类里也不需要 `de.robv.android.xposed.*`；Hook 的注册与释放由 `cases/support.bsh` 里的
`hookAfter` / `unhook` 桩模拟。
