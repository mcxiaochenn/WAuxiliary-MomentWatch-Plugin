#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""插件源码约束检查（把踩过的坑变成可回归的门禁）。

用法:
    python lint.py <插件目录>

检查项:
  1. 代码里不得出现 de.robv.android.xposed 下的类型名。
     WA 默认开启「Xposed API 调用保护」，脚本里命名 XC_MethodHook 这类类型会让插件加载直接失败。
  2. 分隔线必须用 mwUiAddDivider 添加，不能 addView(mwUiDivider(...))。
     裸 View 不覆写 onMeasure，在 AT_MOST 约束下 WRAP_CONTENT 会被解析成"整个可用高度"，
     第一条分隔线就会吃掉卡片全部剩余高度，把它后面的所有控件挤成 0 高度（表现为整块空白）。
  3. 好友行不要用平台 CheckBox（勾选指示器自绘，见 mwUiFriendRow）。
     CheckBox 的勾选图形来自宿主主题的 checkboxStyle，平台 CompoundButton 按该 drawable
     的固有尺寸绘制、还把视图 minHeight 设成它的固有高度，布局参数管不住。
     实测 Android 17 + 微信 cn.8.0.78 主题的勾选 drawable 勾选态固有尺寸异常，
     勾上后整个图标被拉伸。

注释与字符串里的出现不算违规（本项目在注释里解释这些坑）。
"""

import io
import os
import re
import sys


def strip_comments_and_strings(src):
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"':
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
            out.append('""')
            continue
        if c == "'":
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == "'":
                    i += 1
                    break
                i += 1
            out.append("''")
            continue
        out.append(c)
        i += 1
    return "".join(out)


RULES = [
    (
        re.compile(r"de\.robv\.android\.xposed|XC_MethodHook|XposedBridge"),
        "引用了 Xposed 类型：WA 的「Xposed API 调用保护」会让插件加载失败，"
        "请改用内置 hookAfter / hookBefore / hookReplace / unhook",
    ),
    (
        # 只拦「不给显式高度」的写法；mwUiAddDivider 内部带 LayoutParams 的调用是正确用法
        re.compile(r"addView\s*\(\s*mwUiDivider\s*\([^()]*\)\s*\)"),
        "用 addView 直接添加分隔线：裸 View 在 AT_MOST 约束下会撑满剩余高度，"
        "必须改用 mwUiAddDivider(ctx, parent)",
    ),
    (
        re.compile(r"\bnew\s+CheckBox\s*\("),
        "使用平台 CheckBox：勾选图形来自宿主主题的 checkboxStyle，按 drawable 固有尺寸绘制，"
        "布局参数管不住（实测微信主题下勾选后被拉伸）。请改用 mwUiFriendRow 自绘指示器",
    ),
]


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    root = sys.argv[1]

    files = []
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            if name.endswith(".java"):
                files.append(os.path.join(dirpath, name))
    files.sort()

    problems = []
    for path in files:
        with io.open(path, encoding="utf-8") as f:
            code = strip_comments_and_strings(f.read())
        for lineno, line in enumerate(code.split("\n"), start=1):
            for pattern, message in RULES:
                if pattern.search(line):
                    problems.append("%s:%d  %s\n              %s" % (path, lineno, message, line.strip()))

    for p in problems:
        print("FAIL  " + p)
    print("检查 %d 个文件，%s" % (len(files), "全部通过" if not problems else "%d 处违规" % len(problems)))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
