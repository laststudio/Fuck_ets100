#!/usr/bin/env python3
"""读取 Android Gradle 版本号。"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    # 强制 UTF-8 输出，避免 Windows 控制台编码影响中文日志。
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


VERSION_CODE_PATTERN = re.compile(r"versionCode\s*=\s*(\d+)")
VERSION_NAME_PATTERN = re.compile(r"versionName\s*=\s*[\"']([^\"']+)[\"']")


def parse_version(build_file: Path) -> tuple[int, str]:
    """从 Gradle 文件读取 versionCode 和 versionName。"""
    content = build_file.read_text(encoding="utf-8")
    version_code_match = VERSION_CODE_PATTERN.search(content)
    version_name_match = VERSION_NAME_PATTERN.search(content)

    if version_code_match is None:
        raise ValueError(f"没有在 {build_file} 找到 versionCode，无法发布。")
    if version_name_match is None:
        raise ValueError(f"没有在 {build_file} 找到 versionName，无法命名 APK。")

    return int(version_code_match.group(1)), version_name_match.group(1)


def write_github_output(version_code: int, version_name: str, output_file: Path | None) -> None:
    """把版本号写入 GitHub Actions output。"""
    if output_file is None:
        return

    with output_file.open("a", encoding="utf-8") as output:
        output.write(f"version_code={version_code}\n")
        output.write(f"version_name={version_name}\n")
        output.write(f"tag_name=v{version_name}\n")
        output.write(f"apk_name=Fe_{version_name}.apk\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="读取 Android 项目版本号")
    parser.add_argument(
        "--build-file",
        default="app/build.gradle.kts",
        help="Gradle 构建文件路径",
    )
    parser.add_argument(
        "--github-output",
        default=None,
        help="GitHub Actions 输出文件路径",
    )
    args = parser.parse_args()

    build_file = Path(args.build_file).resolve()
    output_file = Path(args.github_output).resolve() if args.github_output else None

    try:
        version_code, version_name = parse_version(build_file)
    except Exception as exc:
        print(f"读取版本号失败：{exc}", file=sys.stderr)
        return 1

    write_github_output(version_code, version_name, output_file)
    print(f"版本号读取完成：versionCode={version_code}, versionName={version_name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
