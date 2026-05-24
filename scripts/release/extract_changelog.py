#!/usr/bin/env python3
"""从 update.md 提取指定版本更新日志。"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    # 强制 UTF-8 输出，避免 Windows 控制台编码影响中文日志。
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


HEADING_PATTERN = re.compile(r"^##\s+(?:\[?v?(?P<version>\d+\.\d+\.\d+)\]?)(?:\s*-\s*(?P<date>\d{4}-\d{2}-\d{2}))?.*$")


def normalize_version(version: str) -> str:
    """去掉版本号前面的 v，统一比较口径。"""
    return version.removeprefix("v").removeprefix("V")


def extract_changelog(changelog_file: Path, version: str) -> str:
    """只提取当前版本章节，避免把完整更新日志写入 Release。"""
    target_version = normalize_version(version)
    lines = changelog_file.read_text(encoding="utf-8").splitlines()
    collecting = False
    collected: list[str] = []

    for line in lines:
        heading_match = HEADING_PATTERN.match(line.strip())
        if heading_match:
            current_version = normalize_version(heading_match.group("version"))
            if collecting:
                break
            collecting = current_version == target_version

        if collecting:
            collected.append(line)

    while collected and not collected[-1].strip():
        collected.pop()

    if not collected:
        raise ValueError(f"没有在 {changelog_file} 找到版本 {target_version} 的更新日志。")

    return "\n".join(collected).strip() + "\n"


def build_release_notes(version_name: str, apk_url: str, changelog: str) -> str:
    """生成 Release 正文。"""
    return (
        f"# Fe v{version_name}\n\n"
        f"## 下载地址\n"
        f"- APK：{apk_url}\n\n"
        f"## 本次更新\n\n"
        f"{changelog}\n\n"
        f"## 注意事项\n"
        f"- 本软件仅供学习交流使用，请勿用于考试作弊等违规行为。\n"
        f"- 云端模式登录可能导致官方客户端被顶号，请避开考试、练习、录音提交等关键场景。\n"
    )


def write_github_output(changelog: str, output_file: Path | None) -> None:
    """用多行 output 写给后续 Gitee 更新脚本。"""
    if output_file is None:
        return

    with output_file.open("a", encoding="utf-8") as output:
        output.write("changelog<<EOF\n")
        output.write(changelog)
        if not changelog.endswith("\n"):
            output.write("\n")
        output.write("EOF\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="提取版本更新日志并生成 Release 正文")
    parser.add_argument("--changelog-file", default="update.md", help="更新日志文件路径")
    parser.add_argument("--version", required=True, help="目标版本号")
    parser.add_argument("--apk-url", required=True, help="APK 下载链接")
    parser.add_argument("--release-notes", default="release_notes.md", help="Release 正文输出路径")
    parser.add_argument("--changelog-output", default="current_changelog.md", help="当前版本日志输出路径")
    parser.add_argument("--github-output", default=None, help="GitHub Actions 输出文件路径")
    args = parser.parse_args()

    changelog_file = Path(args.changelog_file).resolve()
    release_notes_file = Path(args.release_notes).resolve()
    changelog_output_file = Path(args.changelog_output).resolve()
    output_file = Path(args.github_output).resolve() if args.github_output else None

    try:
        changelog = extract_changelog(changelog_file, args.version)
        release_notes = build_release_notes(normalize_version(args.version), args.apk_url, changelog)
    except Exception as exc:
        print(f"提取更新日志失败：{exc}", file=sys.stderr)
        return 1

    release_notes_file.write_text(release_notes, encoding="utf-8")
    changelog_output_file.write_text(changelog, encoding="utf-8")
    write_github_output(changelog, output_file)
    print(f"已生成发布说明：{release_notes_file}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
