#!/usr/bin/env python3
"""静态校验脚本：在没有 Android SDK 的机器上尽可能提前发现编译期错误。

检查项：
1. 所有 XML 是否合法（manifest / res / drawable / values）
2. manifest 中的 @string/@xml/@drawable/@mipmap/@style/@color 引用是否存在
3. manifest 中的 android:name=".Xxx" 是否能在源码里找到对应类
4. Kotlin 源码：字符串/注释剔除后的括号配平、字符串是否闭合
5. Kotlin 源码：package 声明是否与目录结构一致
6. Kotlin 源码：项目内部 import 是否能解析到具体文件
7. Kotlin 源码：R.string/R.drawable/... 引用是否存在
8. Gradle：libs.* 版本目录引用是否在 libs.versions.toml 中定义
9. 跨源集重复类名（仅允许 flavor 之间刻意重复）

用法：python tools/validate_project.py
退出码：0 全部通过；1 存在错误
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app"
SRC = APP / "src"
MAIN = SRC / "main"
NS = "com.couponwatch.detector"

errors: list[str] = []
warnings: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


# --------------------------------------------------------------------------- #
# 1. XML 合法性
# --------------------------------------------------------------------------- #

def check_xml() -> dict[Path, ET.Element]:
    trees: dict[Path, ET.Element] = {}
    for path in sorted(ROOT.rglob("*.xml")):
        if any(part in {"build", ".gradle", ".idea"} for part in path.parts):
            continue
        try:
            trees[path] = ET.parse(path).getroot()
        except ET.ParseError as exc:
            err(f"[XML] {path.relative_to(ROOT)} 解析失败：{exc}")
    return trees


# --------------------------------------------------------------------------- #
# 资源索引
# --------------------------------------------------------------------------- #

VALUE_TYPES = ("string", "color", "style", "dimen", "bool", "integer", "string-array")


def build_resource_index(trees: dict[Path, ET.Element]) -> tuple[set[str], set[str]]:
    """返回 (values 资源 "type/name" 集合, 文件资源 "dir/name" 集合)。"""
    values: set[str] = set()
    files: set[str] = set()

    for path, root in trees.items():
        rel = path.relative_to(MAIN)
        parts = rel.parts
        if parts[0] != "res":
            continue
        kind = parts[1]
        if kind.startswith("values"):
            for child in root:
                tag = child.tag
                name = child.attrib.get("name")
                if tag in VALUE_TYPES and name:
                    values.add(f"{tag}/{name}")
        elif kind.startswith("drawable"):
            files.add(f"drawable/{path.stem}")
        elif kind.startswith("mipmap"):
            files.add(f"mipmap/{path.stem}")
        elif kind == "xml":
            files.add(f"xml/{path.stem}")
        elif kind == "layout":
            files.add(f"layout/{path.stem}")

    return values, files


# --------------------------------------------------------------------------- #
# 2. manifest 资源引用
# --------------------------------------------------------------------------- #

REF_RE = re.compile(r"^@(?:(\w+):)?(\w+)/(\w+)$")


def check_manifest_refs(trees: dict[Path, ET.Element], values: set[str], files: set[str]) -> None:
    manifest = MAIN / "AndroidManifest.xml"
    if manifest not in trees:
        err("[Manifest] 找不到 app/src/main/AndroidManifest.xml")
        return

    for path, root in trees.items():
        if path.parent.name != "main" and "res" not in path.parts:
            continue
        for node in root.iter():
            for attr, value in node.attrib.items():
                if not isinstance(value, str) or not value.startswith("@"):
                    continue
                m = REF_RE.match(value)
                if not m:
                    continue
                pkg, kind, name = m.groups()
                if pkg == "android":
                    continue  # 系统资源，不做检查
                key = f"{kind}/{name}"
                if kind in ("string", "color", "style", "dimen", "bool", "integer"):
                    if key not in values:
                        err(f"[Res] {path.relative_to(ROOT)} 引用了不存在的 @{key}")
                elif kind in ("drawable", "mipmap", "xml", "layout"):
                    if key not in files:
                        err(f"[Res] {path.relative_to(ROOT)} 引用了不存在的 @{key}")
                else:
                    warn(f"[Res] 未识别的资源类型 @{kind}/{name}（{path.name}）")


# --------------------------------------------------------------------------- #
# Kotlin 词法预处理
# --------------------------------------------------------------------------- #

def strip_kotlin(text: str, rel: str) -> tuple[str, list[str]]:
    """去掉注释与字符串内容，保留换行与括号，用于结构检查。"""
    out: list[str] = []
    problems: list[str] = []
    i, n, line = 0, len(text), 1

    def blank(segment: str) -> str:
        return re.sub(r"[^\n]", " ", segment)

    while i < n:
        ch = text[i]
        if ch == "\n":
            out.append(ch)
            line += 1
            i += 1
            continue

        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j == -1 else j
            out.append(blank(text[i:j]))
            i = j
            continue

        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            if j == -1:
                problems.append(f"{rel}:{line} 块注释未闭合")
                j = n
            else:
                j += 2
            out.append(blank(text[i:j]))
            i = j
            continue

        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            if j == -1:
                problems.append(f"{rel}:{line} 原始字符串未闭合")
                j = n
            else:
                j += 3
            out.append(blank(text[i:j]))
            i = j
            continue

        if ch == '"':
            j = i + 1
            closed = False
            while j < n:
                c = text[j]
                if c == "\\":
                    j += 2
                    continue
                if c == "\n":
                    break
                if c == '"':
                    closed = True
                    j += 1
                    break
                j += 1
            if not closed:
                problems.append(f"{rel}:{line} 字符串未闭合（检查是否混入了半角双引号）")
            out.append(blank(text[i:j]))
            i = j
            continue

        if ch == "'":
            j = i + 1
            closed = False
            while j < n:
                c = text[j]
                if c == "\\":
                    j += 2
                    continue
                if c == "\n":
                    break
                if c == "'":
                    closed = True
                    j += 1
                    break
                j += 1
            if closed:
                out.append(blank(text[i:j]))
                i = j
                continue
            out.append(ch)
            i += 1
            continue

        out.append(ch)
        i += 1

    return "".join(out), problems


PAIRS = {"(": ")", "{": "}", "[": "]"}


def check_brackets(code: str, rel: str) -> None:
    stack: list[tuple[str, int]] = []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in PAIRS:
            stack.append((ch, line))
        elif ch in PAIRS.values():
            if not stack:
                err(f"[Kotlin] {rel}:{line} 多余的 '{ch}'")
                return
            open_ch, open_line = stack.pop()
            if PAIRS[open_ch] != ch:
                err(f"[Kotlin] {rel}:{line} 括号不匹配：第 {open_line} 行 '{open_ch}' 与 '{ch}'")
                return
    if stack:
        open_ch, open_line = stack[-1]
        err(f"[Kotlin] {rel}:{open_line} '{open_ch}' 未闭合")


# --------------------------------------------------------------------------- #
# Kotlin 源码索引
# --------------------------------------------------------------------------- #

DECL_KEYWORDS = ("class", "object", "interface", "typealias", "fun", "val", "var")

# AGP 生成的类，源码里不存在但可以正常 import
GENERATED_CLASSES = {"com.couponwatch.detector.BuildConfig", "com.couponwatch.detector.R"}


def find_decls(code: str) -> list[tuple[str, str]]:
    """提取声明名，只认顶层 fun/val/var 与任意层级的类型声明。

    这样可以避开函数体内的局部变量（否则 `val csv = ...` 会被误判为声明）。
    """
    found: list[tuple[str, str]] = []
    for line in code.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("@", "*", "/*", "//")):
            continue
        is_top_level = bool(line) and not line[0].isspace()
        tokens = stripped.split()
        for index, token in enumerate(tokens):
            if token not in DECL_KEYWORDS:
                continue
            if token in ("fun", "val", "var") and not is_top_level:
                break
            if index + 1 >= len(tokens):
                break
            raw = tokens[index + 1]
            raw = re.split(r"[<(]", raw)[0]      # 去掉泛型/参数列表
            raw = raw.rstrip(":=,")              # 去掉类型标注与赋值
            name = raw.split(".")[-1]            # 扩展接收者只取最后一段
            if re.fullmatch(r"[A-Za-z_]\w*", name):
                found.append((token, name))
            break
    return found


PKG_RE = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)
IMPORT_RE = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", re.MULTILINE)
R_REF_RE = re.compile(r"\bR\.(\w+)\.(\w+)")


def kotlin_files() -> list[Path]:
    out: list[Path] = []
    for path in sorted(SRC.rglob("*.kt")):
        if "build" in path.parts:
            continue
        out.append(path)
    return out


def source_set_of(path: Path) -> str:
    rel = path.relative_to(SRC)
    return rel.parts[0]


def check_kotlin(trees: dict[Path, ET.Element], values: set[str], files: set[str]) -> None:
    decl_index: dict[str, list[Path]] = {}
    pkg_files: dict[str, list[Path]] = {}

    for path in kotlin_files():
        rel = str(path.relative_to(ROOT)).replace("\\", "/")
        text = path.read_text(encoding="utf-8")

        code, problems = strip_kotlin(text, rel)
        for problem in problems:
            err(f"[Kotlin] {problem}")
        check_brackets(code, rel)

        pkg_match = PKG_RE.search(code)
        if not pkg_match:
            err(f"[Kotlin] {rel} 缺少 package 声明")
            continue
        pkg = pkg_match.group(1)
        pkg_files.setdefault(pkg, []).append(path)

        expected_dir = pkg.replace(".", "/")
        normalized = str(path.parent).replace("\\", "/")
        if not normalized.endswith(expected_dir):
            err(f"[Kotlin] {rel} 的 package '{pkg}' 与目录不匹配")

        for _kind, name in find_decls(code):
            decl_index.setdefault(f"{pkg}.{name}", []).append(path)

    for path in kotlin_files():
        rel = str(path.relative_to(ROOT)).replace("\\", "/")
        code, _ = strip_kotlin(path.read_text(encoding="utf-8"), rel)
        for fq, _alias in IMPORT_RE.findall(code):
            if not fq.startswith(NS + "."):
                continue
            if fq in GENERATED_CLASSES or fq.endswith(".R") or ".R." in fq:
                continue
            if fq in decl_index:
                continue
            parts = fq.split(".")
            resolved = False
            for cut in range(len(parts), 0, -1):
                if ".".join(parts[:cut]) in decl_index:
                    resolved = True
                    break
            if not resolved:
                err(f"[Kotlin] {rel} 的 import 无法解析：{fq}")

    for path in kotlin_files():
        rel = str(path.relative_to(ROOT)).replace("\\", "/")
        code, _ = strip_kotlin(path.read_text(encoding="utf-8"), rel)
        for kind, name in R_REF_RE.findall(code):
            if kind in ("string", "color", "style", "dimen"):
                if f"{kind}/{name}" not in values:
                    err(f"[Kotlin] {rel} 引用了不存在的 R.{kind}.{name}")
            elif kind in ("drawable", "mipmap", "xml", "layout"):
                if f"{kind}/{name}" not in files:
                    err(f"[Kotlin] {rel} 引用了不存在的 R.{kind}.{name}")

    for fq, paths in sorted(decl_index.items()):
        if len(paths) < 2:
            continue
        sets = {source_set_of(path) for path in paths}
        if sets <= {"lite", "full"}:
            continue
        err("[Kotlin] 重复声明 " + fq + "：" + ", ".join(str(x.relative_to(ROOT)) for x in paths))


# --------------------------------------------------------------------------- #
# manifest 类名解析
# --------------------------------------------------------------------------- #

def namespace() -> str:
    text = (APP / "build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r'namespace\s*=\s*"([\w.]+)"', text)
    return m.group(1) if m else NS


def check_manifest_classes(trees: dict[Path, ET.Element]) -> None:
    manifest = MAIN / "AndroidManifest.xml"
    root = trees.get(manifest)
    if root is None:
        return

    ns = namespace()
    declared: set[str] = set()
    for path in kotlin_files():
        text = path.read_text(encoding="utf-8")
        code, _ = strip_kotlin(text, path.name)
        pkg = PKG_RE.search(code)
        if not pkg:
            continue
        for _kind, name in find_decls(code):
            declared.add(f"{pkg.group(1)}.{name}")

    for node in root.iter():
        name = node.attrib.get("{http://schemas.android.com/apk/res/android}name")
        if not name or not name.startswith("."):
            continue
        fq = ns + name
        if fq not in declared:
            err(f"[Manifest] 声明的组件找不到对应源码：{fq}")


# --------------------------------------------------------------------------- #
# Gradle 版本目录
# --------------------------------------------------------------------------- #

def check_version_catalog() -> None:
    toml_path = ROOT / "gradle" / "libs.versions.toml"
    if not toml_path.is_file():
        err("[Gradle] 缺少 gradle/libs.versions.toml")
        return

    try:
        import tomllib
    except ModuleNotFoundError:
        warn("[Gradle] 当前 Python 无 tomllib，跳过版本目录检查")
        return

    data = tomllib.loads(toml_path.read_text(encoding="utf-8"))
    libraries = set(data.get("libraries", {}).keys())
    plugins = set(data.get("plugins", {}).keys())
    versions = set(data.get("versions", {}).keys())

    for gradle_file in ROOT.rglob("*.gradle.kts"):
        if "build" in gradle_file.parts:
            continue
        text = gradle_file.read_text(encoding="utf-8")
        for ref in re.findall(r"libs\.plugins\.([\w.]+)", text):
            key = ref.replace(".", "-")
            if key not in plugins:
                err(f"[Gradle] {gradle_file.name} 引用了未定义的插件 libs.plugins.{ref}")
        for ref in re.findall(r"libs\.(?!plugins\.|versions\.)([\w.]+)", text):
            key = ref.replace(".", "-")
            if key not in libraries:
                err(f"[Gradle] {gradle_file.name} 引用了未定义的库 libs.{ref}")
        for ref in re.findall(r"version\.ref\s*=\s*\"([\w.]+)\"", text):
            if ref not in versions:
                err(f"[Gradle] version.ref '{ref}' 未在 [versions] 中定义")

    for name, spec in data.get("libraries", {}).items():
        ref = spec.get("version", {}).get("ref") if isinstance(spec.get("version"), dict) else None
        if ref and ref not in versions:
            err(f"[Gradle] 库 {name} 的 version.ref '{ref}' 未定义")


# --------------------------------------------------------------------------- #
# 资产与 flavor 完整性
# --------------------------------------------------------------------------- #

def check_assets_and_flavors() -> None:
    html = MAIN / "assets" / "mock_page.html"
    if not html.is_file():
        err("[Assets] 缺少 app/src/main/assets/mock_page.html")
    else:
        text = html.read_text(encoding="utf-8")
        for fn in ("showDialog", "hideDialog", "autoDemo"):
            if f"function {fn}" not in text:
                err(f"[Assets] mock_page.html 缺少 JS 函数 {fn}()")
        if "onclick" not in text:
            warn("[Assets] mock_page.html 没有任何 onclick 绑定")

    for flavor in ("lite", "full"):
        factory = SRC / flavor / "java" / NS.replace(".", "/") / "ocr" / "OcrEngineFactory.kt"
        if not factory.is_file():
            err(f"[Flavor] {flavor} 变体缺少 OcrEngineFactory.kt")


# --------------------------------------------------------------------------- #
# 主流程
# --------------------------------------------------------------------------- #

def main() -> int:
    if not APP.is_dir():
        print("找不到 app/ 目录，请在 android-app 根目录运行本脚本")
        return 2

    trees = check_xml()
    values, files = build_resource_index(trees)
    check_manifest_refs(trees, values, files)
    check_manifest_classes(trees)
    check_kotlin(trees, values, files)
    check_version_catalog()
    check_assets_and_flavors()

    kt = kotlin_files()
    total_lines = sum(len(p.read_text(encoding="utf-8").splitlines()) for p in kt)

    print("=" * 66)
    print(" Android 工程静态校验")
    print("=" * 66)
    print(f" Kotlin 文件      : {len(kt)}")
    print(f" Kotlin 总行数    : {total_lines}")
    print(f" XML 文件         : {len(trees)}")
    print(f" values 资源      : {len(values)}")
    print(f" 文件型资源       : {len(files)}")
    print("-" * 66)

    if warnings:
        print(f" 警告 {len(warnings)} 条：")
        for w in warnings:
            print("   ! " + w)
    if errors:
        print(f" 错误 {len(errors)} 条：")
        for e in errors:
            print("   x " + e)
        print("=" * 66)
        return 1

    print(" 结果：全部检查通过")
    print("=" * 66)
    return 0


if __name__ == "__main__":
    sys.exit(main())
