# plugins/

本目录按 [HdShare/WAuxiliary_Plugin](https://github.com/HdShare/WAuxiliary_Plugin) 的约定组织插件：

```
plugins/
└── <WA 插件目录版本>/
    └── <作者名>/
        └── <插件名>/
            ├── info.prop    必需：插件元信息
            ├── main.java    必需：入口脚本
            └── readme.md    必需：插件说明
```

本插件的位置是 `plugins/v127/mcxiaochen/MomentWatch/`。

## 当前状态

**该目录当前在远端为空。**

插件运行代码（`info.prop` / `main.java` / `src/` / `readme.md`）已完成实现并通过本地验证，
但按交付约定，需等真机测试通过后再推送。`.gitignore` 中有一条对应规则：

```gitignore
/plugins/v127/mcxiaochen/MomentWatch/
```

真机验证通过后删除该规则即可正常提交。验证清单见 [../docs/06-test-plan.md](../docs/06-test-plan.md)。
