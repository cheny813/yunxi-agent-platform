#!/usr/bin/env python3
"""架构门禁：能力归属声明检查。

框架是唯一的推理运行时，应用侧新增的通用能力会与框架职责重叠。为避免同一份
横切关切出现两个实现位置，凡在应用侧新增框架性能力（中间件、拦截器、算子、
SPI、存储等），都必须在变更说明中写明归属判断结论与理由。

本脚本检查指定文件的内容中是否包含归属声明；在持续集成环境中输入为
本次变更的 PR 描述或提交信息，在本地环境中输入为指定文件。仅当变更涉及
框架性能力时才会要求声明，业务代码、文档、测试、构建脚本不受约束。

用法：
    # 检查一个描述文件
    python scripts/architecture-guard/ownership_declaration_check.py --input pr_body.txt

    # 显式给出本次变更的文件清单
    python scripts/architecture-guard/ownership_declaration_check.py \
        --input pr_body.txt --files "src/main/java/com/example/MyMiddleware.java"

退出码：0 通过；1 缺少声明；2 用法或输入错误。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent

# 归属声明需包含的内容：一个结论标记加一段理由。
# 标记取值对应「接线 / 下沉 / 留在应用层 / 临时兼容」四种处置。
MARKER_RE = re.compile(
    r"(归属|ownership)\s*[:：]|"
    r"(接线|下沉|留在应用层|临时兼容物|wire[- ]?up|sink|stay|temporary)",
    re.IGNORECASE,
)
REASON_RE = re.compile(r"(理由|reason)\s*[:：]", re.IGNORECASE)

# 触发声明的变更特征：新增框架性能力。
TRIGGER_PATH_RE = re.compile(
    r"(middleware|interceptor|operator|spi|store|filter|hook|plugin)",
    re.IGNORECASE,
)

SOURCE_SUFFIXES = (".java", ".kt", ".scala")
IGNORE_PARTS = {"target", "build", "out", ".git"}


def changed_files_from_git() -> list[str] | None:
    """取本次变更涉及的文件。

    返回 None 表示无法判定变更范围（无版本库、或处于合并提交等场景），
    调用方应转为提示而非通过，避免静默跳过检查。
    """
    for cmd in (
        ["git", "diff", "--name-only", "HEAD~1", "HEAD"],
        ["git", "diff", "--name-only", "HEAD"],
        ["git", "diff", "--name-only", "--cached"],
        ["git", "show", "--name-only", "--pretty=format:", "HEAD"],
    ):
        try:
            out = subprocess.run(
                cmd,
                cwd=str(REPO_ROOT),
                capture_output=True,
                text=True,
                timeout=30,
            )
        except (OSError, subprocess.SubprocessError):
            continue
        if out.returncode == 0 and out.stdout.strip():
            return [line.strip() for line in out.stdout.splitlines() if line.strip()]
    return None


def is_framework_capability(path: str) -> bool:
    """判断变更文件是否声明了框架性能力。"""
    lowered = path.replace("\\", "/").lower()
    if any(part in lowered.split("/") for part in IGNORE_PARTS):
        return False
    if not lowered.endswith(SOURCE_SUFFIXES):
        return False
    return bool(TRIGGER_PATH_RE.search(lowered))


def check_declaration(text: str) -> tuple[bool, str]:
    """检查声明文本是否同时给出结论与理由。"""
    if not text.strip():
        return False, "变更说明为空"
    if not MARKER_RE.search(text):
        return False, "未找到归属结论（应写明 接线 / 下沉 / 留在应用层 / 临时兼容物）"
    if not REASON_RE.search(text):
        return False, "未找到理由（应写明「理由：____」）"
    return True, ""


def read_input(args: argparse.Namespace) -> str:
    if args.input:
        path = Path(args.input)
        if not path.exists():
            print(f"[FAIL] 输入文件不存在：{path}", file=sys.stderr)
            raise SystemExit(2)
        return path.read_text(encoding="utf-8", errors="ignore")
    env_body = os.environ.get("PR_BODY") or os.environ.get("COMMIT_MESSAGE")
    if env_body:
        return env_body
    return ""


def main() -> int:
    parser = argparse.ArgumentParser(description="Ownership declaration gate")
    parser.add_argument("--input", default="", help="变更说明文本文件")
    parser.add_argument(
        "--files",
        default="",
        help="本次变更的文件列表，逗号或换行分隔；缺省时从版本库读取",
    )
    args = parser.parse_args()

    if args.files:
        files = [f.strip() for f in re.split(r"[,\n]", args.files) if f.strip()]
    else:
        files = changed_files_from_git()

    print("=" * 72)
    print("架构门禁：能力归属声明检查")
    print(f"  本次变更文件数：{'无法判定' if files is None else len(files)}")
    print("=" * 72)

    if files is not None and not files:
        print("[PASS] 未检测到文件变更，跳过归属声明检查。")
        return 0

    if files is None:
        text = read_input(args)
        ok, reason = check_declaration(text)
        print()
        if not ok:
            print(f"[FAIL] 无法从版本库判定变更范围，且变更说明未提供归属声明：{reason}")
            print("[HINT] 请通过 --files 显式给出本次变更的文件清单，")
            print("[HINT] 或按模板在变更说明中写明归属结论与理由。")
            return 1
        print("[PASS] 已提供归属声明与理由（变更范围由说明承担）。")
        return 0

    triggered = [f for f in files if is_framework_capability(f)]
    if not triggered:
        print("[PASS] 本次变更未新增框架性能力，无需归属声明。")
        return 0

    print(f"检出 {len(triggered)} 个框架性能力相关文件，需在变更说明中声明归属：")
    for path in triggered:
        print(f"    - {path}")

    text = read_input(args)
    ok, reason = check_declaration(text)
    print()
    if not ok:
        print(f"[FAIL] {reason}")
        print("[HINT] 请在变更说明中写明归属结论与理由，例如：")
        print("[HINT]   归属：留在应用层　理由：依赖本项目的业务概念，非通用能力。")
        print("[HINT]   归属：下沉　理由：任何 Agent 应用都需要，将在框架侧实现后移除本处。")
        return 1

    print("[PASS] 已提供归属声明与理由。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
