#!/usr/bin/env python3
"""架构门禁：扫描与 AgentScope 框架同名的本地公开类型。

框架是唯一的推理运行时。凡是在应用侧重新声明、且与框架运行时本体同名的类型，
都意味着同一份职责出现了两个实现位置，应当收敛到框架或由框架提供扩展点。
本脚本把应用侧声明的类型名与框架类型名索引求交集，命中即失败。

分级口径：
  - BLOCK：命中 agentscope-core / agentscope-harness（框架运行时本体）
  - WARN ：命中 extensions / examples 等非本体模块，多为通用命名巧合，仅提示

确因语义不同而需要保留的同名项，须在 agentscope_synonym_allowlist.txt 中逐条写明
理由与删除条件；缺理由的白名单条目会被判失败，避免白名单沦为免检通道。

用法：
    python scripts/architecture-guard/agentscope_synonym_scan.py
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent

DEFAULT_INDEX = HERE / "agentscope_class_index.txt"
DEFAULT_ALLOWLIST = HERE / "agentscope_synonym_allowlist.txt"

SCAN_ROOTS = [
    "agent-core/src/main/java",
    "agent-config/src/main/java",
    "agent-spi/src/main/java",
    "agent-app/src/main/java",
    "agent-muse/src/main/java",
    "agent-text2sql/src/main/java",
]

TYPE_RE = re.compile(
    r"^[ \t]*(?:(?:public|protected|private|static|final|abstract|sealed|strictfp)\s+)*"
    r"(?:class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)

COMMENT_RE = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)

RUNTIME_MARKERS = ("agentscope-core", "agentscope-harness")


def read_index(path: Path) -> tuple[dict[str, str], dict[str, str]]:
    """读取 AgentScope-Java 索引，返回「类型名 -> 层级」与「类型名 -> 例子路径」。"""
    tiers: dict[str, str] = {}
    sample: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#"):
            continue
        if "\t" not in line:
            continue
        name, _, rel = line.partition("\t")
        name = name.strip()
        rel = rel.strip()
        tier = (
            "runtime"
            if any(marker in rel.lower() for marker in RUNTIME_MARKERS)
            else "non-runtime"
        )
        sample.setdefault(name, rel)
        if tiers.get(name) != "runtime":
            tiers[name] = tier
    return tiers, sample


def read_allowlist(path: Path) -> dict[str, str]:
    """读取白名单，返回「类型名 -> 理由」，缺理由的条目算无效豁免。"""
    entries: dict[str, str] = {}
    if not path.exists():
        return entries
    for raw in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        name, sep, reason = line.partition("#")
        name = name.strip()
        if not name:
            continue
        entries[name] = reason.strip() if sep else ""
    return entries


def collect_declared_types(root: Path) -> dict[str, str]:
    """收集 yunxi 侧声明的 Java 类型名，返回「类型名 -> 声明文件相对路径」。"""
    declared: dict[str, str] = {}
    for rel in SCAN_ROOTS:
        base = root / rel
        if not base.is_dir():
            continue
        for java_file in base.rglob("*.java"):
            if "target" in java_file.parts:
                continue
            try:
                source = java_file.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            for match in TYPE_RE.finditer(COMMENT_RE.sub("", source)):
                name = match.group(1)
                declared.setdefault(name, str(java_file.relative_to(root)))
    return declared


def main() -> int:
    parser = argparse.ArgumentParser(description="AgentScope-Java synonym gate (rule 2)")
    parser.add_argument("--index", default=str(DEFAULT_INDEX))
    parser.add_argument("--allowlist", default=str(DEFAULT_ALLOWLIST))
    args = parser.parse_args()

    index_path = Path(args.index)
    if not index_path.exists():
        print(f"[FAIL] 找不到类型名索引：{index_path}", file=sys.stderr)
        print("[HINT] 先运行 build_agentscope_index.py 生成索引", file=sys.stderr)
        return 2

    tiers, sample = read_index(index_path)
    allowlist = read_allowlist(Path(args.allowlist))
    declared = collect_declared_types(REPO_ROOT)

    print("=" * 72)
    print("架构门禁：框架同名类型扫描")
    print(f"  类型名索引：{index_path.name}（{len(tiers)} 个类型名）")
    print(f"  本仓声明类型名：{len(declared)} 个")
    print("=" * 72)

    hits = {
        name: tiers[name] for name in declared if name in tiers
    }
    runtime_hits = sorted(n for n, t in hits.items() if t == "runtime")
    weak_hits = sorted(n for n, t in hits.items() if t == "non-runtime")

    if weak_hits:
        print(f"[WARN] {len(weak_hits)} 个与非本体模块同名（多为通用命名巧合，不阻断）：")
        for name in weak_hits:
            print(f"    - {name}  <- 框架: {sample.get(name, '')}")
        print()

    if not runtime_hits:
        print("[PASS] 无框架运行时同名项。")
        return 0

    print(f"[FAIL] 检出 {len(runtime_hits)} 个与框架运行时本体同名的类型名：\n")
    blocked: list[str] = []
    for name in runtime_hits:
        reason = allowlist.get(name)
        location = declared[name]
        print(f"  - {name}")
        print(f"      本仓：{location}")
        print(f"      框架：{sample.get(name, '')}")
        if reason is None:
            print("      处置：未登记。应收敛到框架，或补登记理由。")
            blocked.append(name)
        elif reason:
            print(f"      处置：已豁免 —— {reason}")
        else:
            print("      处置：白名单条目缺理由，视为未登记。")
            blocked.append(name)

    print()
    if blocked:
        print(f"[FAIL] 未通过，共 {len(blocked)} 项：{', '.join(blocked)}")
        print("[HINT] 同名意味着同一职责有两个实现位置：优先收敛到框架；")
        print("[HINT] 确因语义不同需保留的，在 agentscope_synonym_allowlist.txt 写明理由与删除条件。")
        return 1

    print(f"[PASS] {len(runtime_hits)} 项同名均已登记，请定期复核删除条件。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
