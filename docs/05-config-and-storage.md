# 05 · 配置与存储

## 存储位置

全部配置保存在插件目录下的 `config.prop`，由 WAuxiliary 的配置接口读写。

- 文件由 WA 在**首次调用写入接口时自动创建**，因此本插件不预置 `config.prop`，
  仓库里也不提交该文件（它属于运行时数据）。
- 插件本身不直接做文件 IO —— 所有读写都经过 `getString` / `putString` / `getBoolean` /
  `putBoolean` / `getLong` / `putLong` 六个接口，由 WA 负责落盘。

## 配置项

| 键 | 类型 | 默认值 | 取值范围 | 说明 |
|---|---|---|---|---|
| `mw_enable` | boolean | `true` | — | 总开关。关闭后 Hook 仍在，但不产生任何通知 |
| `mw_watch_list` | String | `""` | — | 关注名单，逗号分隔的 `wxid` |
| `mw_max_age_sec` | long | `86400` | `>= 0` | 提醒时效窗口（秒），`0` 表示不限 |
| `mw_seen_ids` | String | `""` | — | 已提醒过的 `snsId` 账本，逗号分隔，上限 500 条 |

`mw_seen_ids` 是内部维护的账本，**不建议手工编辑**。设置界面提供「清空已提醒记录」入口。

## 存储格式

### 名单 / 账本为什么用逗号分隔的字符串

WA 也提供 `getStringSet` / `putStringSet`，本项目仍选择字符串，原因是：

1. 在 BeanShell 下不用处理 `Set<String>` 的泛型与类型转换；
2. 文件内容直接可读，用户排障时 `cat config.prop` 就能确认名单；
3. 解析与序列化逻辑集中在 `mwUtilParseSet` / `mwUtilJoinSet` 两个函数里，成本很低。

### `mw_watch_list`

- 写入前由 `mwUtilJoinSet(selected)` 序列化：逗号分隔、无空格、无末尾逗号。
- 读取时用 `mwUtilParseSet` 解析，**容错多种分隔符**：`,` `，` `;` `；` 换行 `\r`。
  这样用户手工粘贴一行 `wxid_a，wxid_b` 或换行分隔的名单也能正确读入。
- 元素是 `wxid`，不是昵称。昵称会变，`wxid` 不会。

### `mw_seen_ids`

- 序列化时**保持写入顺序**（依赖 `mwSeenOrder` 这个 `ArrayList`，而不是直接遍历 `HashSet`），
  这样淘汰规则是确定的「先入先出」，行为可复现、可测试。
- 单条 `snsId` 是 64 位长整型，可能为负；用 `Long.valueOf` / `String.valueOf` 往返。
- 容量 500 条：按微信朋友圈的更新频率，够覆盖相当长时间的去重需求，
  同时把 `config.prop` 的体积压在几 KB 以内。

## 读写时机

| 时机 | 动作 |
|---|---|
| 插件 `onLoad` | `mwConfigLoad()` 读全部配置；把 `mwMonitorStartSec` 重置为当前时间 |
| 修改总开关 | 立即写 `mw_enable` |
| 保存关注名单 | 立即写 `mw_watch_list` |
| 切换提醒时效 | 立即写 `mw_max_age_sec` |
| 命中一条动态 | 写 `mw_seen_ids`（每次命中都落盘，见下方权衡） |
| 插件 `onUnload` | 再落盘一次 `mw_seen_ids` 兜底 |

### 为什么命中即落盘

`mwSeenMark` 在登记成功后就调用 `mwConfigSaveSeen()`，而不是攒到卸载时统一写。
权衡：每次命中多一次文件写入（一次朋友圈提醒的开销远大于一次小文件写入），
换来的是**微信进程被系统杀掉时不会丢失去重记录**、不会在下次启动后重复提醒。

## 并发

访问者有两个线程：

1. **Hook 线程**：微信的数据库写入线程，命中时读写去重账本；
2. **UI 线程**：设置界面「清空已提醒记录」。

保护方式：

- 用 `mwLock` 这个 `Object` 做监视器；
- `mwSeenMark` 把「判重 + 登记 + 淘汰 + 落盘」放在**同一段同步块**里，
  保证不会出现两次并发调用同时判定为"首次"从而重复提醒；
- `mwSeenContains` 是同步块外的廉价预检查，用来让绝大多数重复写入**不进锁**；
- `mwSeenClear` 同样在同步块内，避免与正在进行的登记互相撕裂。

`HashSet` / `ArrayList` 都不是线程安全的，因此对这两个结构的每一次读写都在 `mwLock` 保护下。
`mwConfigLoad` 只在 `onLoad`（单线程）里执行，不做额外加锁。

## 淘汰策略

```
mwSeenMark(snsId):
    if (mwSeenSet.contains(snsId)) return false      // 已登记 → 不提醒
    mwSeenSet.add(snsId); mwSeenOrder.add(snsId)
    while (mwSeenOrder.size() > 500) {
        old = mwSeenOrder.remove(0)                  // 取最早的那条
        mwSeenSet.remove(old)                        // 同步从集合里删掉
    }
    mwConfigSaveSeen()
    return true
```

- 用「集合 + 顺序表」的组合，而不是单纯 `HashSet`：`HashSet` 无法回答"谁是最早的"，
  而直接 `clear()` 会一次性丢掉全部去重记录，导致短时间内的动态全部重新提醒。
- 淘汰的量级是 500 条，因此在命中频率很低的情况下基本不会触发；
  触发时也是精确淘汰最早的一条，不影响近期记录。

## 配置损坏时的行为

所有读取都提供默认值，并且解析失败时单独跳过坏元素而不是整段放弃：

| 情况 | 行为 |
|---|---|
| `mw_enable` 不是合法布尔 | 取默认 `true` |
| `mw_max_age_sec` 不是合法数字 | 取默认 `86400`；负数归零 |
| `mw_max_age_sec` 是合法但不在候选值里 | 设置界面循环切换从第一个候选值重新开始，不影响提醒 |
| `mw_watch_list` 里混入非法片段 | 该片段被忽略，其余正常解析 |
| `mw_seen_ids` 里混入非法数字 | 该条被忽略，其余正常解析 |
| `config.prop` 整体不存在 | 相当于全部取默认值，插件可正常启动（此时名单为空，不提醒） |

## 隐私

- 关注名单只存在本机 `config.prop`，不联网、不上传。
- 去重账本只存 `snsId`（一串数字 ID），不含正文、不含图片，也不含时间线内容。
- 插件没有任何网络请求代码。
