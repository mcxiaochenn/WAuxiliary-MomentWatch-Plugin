#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把插件模块与测试用例拼装成一个可由 BeanShell 直接执行的脚本。

用法:
    python assemble.py <插件目录> <工具目录>

为什么需要拼装：
    WA 用 loadJava(...) 在同一个解释器命名空间里执行各模块，本项目用等价方式在本地复现——
    把模块文件按加载顺序拼接，并把 import 统一提到文件头部（BeanShell 要求 import 位于顶层）。
"""

import io
import os
import sys

HEADER_IMPORTS = [
    "import android.app.*;",
    "import android.content.*;",
    "import android.os.*;",
    "import android.widget.*;",
    "import java.util.*;",
    "import de.robv.android.xposed.*;",
    "import me.hd.wauxv.data.bean.info.FriendInfo;",
]

# 与 main.java 的 loadJava 顺序保持一致（只取可在本机桩环境下执行的部分）
MODULES = ["Util.java", "Config.java", "Jumper.java", "SnsHook.java"]


def read(path):
    with io.open(path, encoding="utf-8") as f:
        return f.read()


def strip_imports(text):
    return "\n".join(l for l in text.split("\n") if not l.strip().startswith("import "))


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    plugin_dir = sys.argv[1]
    tool_dir = sys.argv[2]
    src = os.path.join(plugin_dir, "src")

    for name in MODULES:
        path = os.path.join(src, name)
        if not os.path.exists(path):
            print("模块缺失: " + path)
            return 2

    parts = ["\n".join(HEADER_IMPORTS), ""]
    parts.append(strip_imports(read(os.path.join(tool_dir, "cases", "support.bsh"))))
    for name in MODULES:
        parts.append("\n// ---------- module: %s ----------\n" % name)
        parts.append(strip_imports(read(os.path.join(src, name))))
    parts.append(strip_imports(read(os.path.join(tool_dir, "cases", "core.bsh"))))

    out = os.path.join(tool_dir, "combined.bsh")
    with io.open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(parts))
    print("已生成 " + out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
