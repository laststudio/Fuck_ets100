#!/usr/bin/env python3
"""Validate that the app version matches the latest changelog entry."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from read_version import parse_version


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


LATEST_HEADING_PATTERN = re.compile(
    r"^##\s+(?:\[?v?(?P<version>\d+\.\d+\.\d+)\]?)(?:\s*-\s*(?P<date>\d{4}-\d{2}-\d{2}))?.*$"
)
VERSION_CODE_PATTERN = re.compile(r"内部版本号[：:]\s*(?P<version_code>\d+)")


def read_latest_changelog_version(changelog_file: Path) -> tuple[str, int | None]:
    lines = changelog_file.read_text(encoding="utf-8").splitlines()
    latest_version: str | None = None
    latest_version_code: int | None = None

    for line in lines:
        heading_match = LATEST_HEADING_PATTERN.match(line.strip())
        if heading_match:
            if latest_version is not None:
                break
            latest_version = heading_match.group("version")
            continue

        if latest_version is not None and latest_version_code is None:
            version_code_match = VERSION_CODE_PATTERN.search(line)
            if version_code_match:
                latest_version_code = int(version_code_match.group("version_code"))

    if latest_version is None:
        raise ValueError(f"没有在 {changelog_file} 找到最新版本更新日志标题。")

    return latest_version, latest_version_code


def main() -> int:
    parser = argparse.ArgumentParser(description="校验 App 版本与最新更新日志是否一致")
    parser.add_argument("--build-file", default="app/build.gradle.kts", help="Gradle 构建文件路径")
    parser.add_argument("--changelog-file", default="update.md", help="更新日志路径")
    args = parser.parse_args()

    try:
        version_code, version_name = parse_version(Path(args.build_file))
        changelog_version, changelog_version_code = read_latest_changelog_version(Path(args.changelog_file))

        if changelog_version != version_name:
            raise ValueError(
                f"最新更新日志版本 {changelog_version} 与 app versionName {version_name} 不一致。"
            )

        if changelog_version_code is not None and changelog_version_code != version_code:
            raise ValueError(
                f"最新更新日志内部版本号 {changelog_version_code} 与 app versionCode {version_code} 不一致。"
            )
    except Exception as exc:
        print(f"发布校验失败：{exc}", file=sys.stderr)
        return 1

    print(f"发布校验通过：versionName={version_name}, versionCode={version_code}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
