#!/usr/bin/env python3
"""生成 AgentScope 框架源码的公开类型名索引快照。

该索引供 agentscope_synonym_scan.py 做同名扫描。构建环境不一定能拉到框架源码，
因此把框架的类型名做成可版本化的快照文件随仓库分发；
框架版本升级后必须重新生成本索引，否则扫描会失去时效。

每条记录为「类型名<TAB>相对路径」，供扫描脚本按模块分级：
  - agentscope-core / agentscope-harness：框架运行时本体，同名即失败
  - extensions / examples 等：非本体模块，同名仅提示

用法：
    python scripts/architecture-guard/build_agentscope_index.py \
        --agentscope-root /path/to/agentscope-java \
        --out scripts/architecture-guard/agentscope_class_index.txt
"""

from __future__ import annotations

import argparse
import datetime as _dt
import sys
from pathlib import Path

SKIP_DIRS = {"target", "build", ".git", ".idea", "node_modules", "out"}

RUNTIME_MODULES = ("agentscope-core", "agentscope-harness")


def collect_types(agentscope_root: Path) -> dict[str, list[str]]:
    """收集 AgentScope-Java 源码中的 Java 类型名及其相对路径（Java 规范：文件名即主类名）。"""
    found: dict[str, list[str]] = {}
    for path in agentscope_root.rglob("*.java"):
        parts = set(path.parts)
        if parts & SKIP_DIRS:
            continue
        rel = path.relative_to(agentscope_root).as_posix()
        found.setdefault(path.stem, []).append(rel)
    return found


def tier_of(rel_path: str) -> str:
    """按模块判定所属层级：runtime 为运行时本体，non-runtime 为非本体。"""
    lowered = rel_path.lower()
    return "runtime" if any(m in lowered for m in RUNTIME_MODULES) else "non-runtime"


def main() -> int:
    parser = argparse.ArgumentParser(description="Build AgentScope public type index")
    parser.add_argument("--agentscope-root", required=True, help="AgentScope 源码根目录")
    parser.add_argument(
        "--out",
        default=str(Path(__file__).resolve().parent / "agentscope_class_index.txt"),
        help="索引输出路径",
    )
    args = parser.parse_args()

    agentscope_root = Path(args.agentscope_root)
    if not agentscope_root.is_dir():
        print(f"[FAIL] 框架源码根目录不存在：{agentscope_root}", file=sys.stderr)
        return 2

    found = collect_types(agentscope_root)
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    runtime_count = sum(
        1 for paths in found.values() if any(tier_of(p) == "runtime" for p in paths)
    )
    today = _dt.date.today().isoformat()

    lines = [
        "# AgentScope public type index snapshot",
        f"# generated: {today}",
        f"# total: {len(found)}",
        f"# runtime: {runtime_count}",
        "# 由 build_agentscope_index.py 生成，框架升级后须重新生成。",
        "# 每行格式：类型名<TAB>相对路径；路径用于判定模块层级。",
    ]
    for name in sorted(found):
        for rel in sorted(found[name]):
            lines.append(f"{name}\t{rel}")
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"[OK] 索引已生成：{out_path}")
    print(f"[OK] 源码根目录：{agentscope_root}")
    print(f"[OK] 类型名总数：{len(found)}，其中运行时本体：{runtime_count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
