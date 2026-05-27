#!/usr/bin/env python3
"""更新 Gitee 远程 config.json 的发布脚本。"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    # 强制 UTF-8 输出，CI 和本地都能稳定打印中文。
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


DEFAULT_REPO = "qiuqiqiuqid/fe_config"
DEFAULT_BRANCH = "master"
DEFAULT_CONFIG_FILE = "config.json"
DEFAULT_CHANGELOG_FILE = "update.md"


def run_command(command: list[str], cwd: Path | None = None) -> None:
    """执行 git 命令，失败时立刻停止，避免远程配置写入错误。"""
    subprocess.run(command, cwd=cwd, check=True, text=True)


def mask_remote_url(username: str, token: str, repo: str) -> str:
    """拼出带 token 的 Gitee 地址，只在内存里使用，不打印。"""
    return f"https://{username}:{token}@gitee.com/{repo}.git"


def clone_config_repo(username: str, token: str, repo: str, branch: str, target_dir: Path) -> Path:
    """克隆 Gitee 配置仓库。"""
    repo_dir = target_dir / "gitee-config"
    remote_url = mask_remote_url(username, token, repo)
    run_command(["git", "clone", "--branch", branch, "--depth", "1", remote_url, str(repo_dir)])
    return repo_dir


def read_message(message: str | None, message_file: str | None) -> str:
    """支持直接传文本或从文件读取更新日志。"""
    if message_file:
        return Path(message_file).read_text(encoding="utf-8").strip()
    return (message or "").strip()


def build_raw_gitee_url(repo: str, branch: str, file_path: str) -> str:
    """生成 Gitee 原始文件地址，供客户端直接读取。"""
    normalized_path = file_path.strip().lstrip("/")
    return f"https://raw.giteeusercontent.com/{repo}/raw/{branch}/{normalized_path}"


def parse_bool(value: str | bool | None, default: bool = False) -> bool:
    """把字符串开关安全转成布尔值。"""
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    normalized = value.strip().lower()
    if normalized in {"1", "true", "yes", "y", "on"}:
        return True
    if normalized in {"0", "false", "no", "n", "off", ""}:
        return False
    raise ValueError(f"布尔值格式不认识：{value}，请使用 true/false。")


def update_config_file(
    config_file: Path,
    version_code: int,
    apk_url: str,
    update_message: str,
    is_force: bool,
    notice_message: str,
    is_kill_switch_on: bool,
    announcement_title: str,
    announcement_message: str,
    announcement_updated_at: str,
    announcement_url: str,
    changelog_url: str,
    changelog_title: str,
    changelog_summary: str,
    donate_enabled: bool,
) -> bool:
    """精准更新远程配置字段，保留未知字段避免误伤。"""
    if config_file.exists():
        config = json.loads(config_file.read_text(encoding="utf-8"))
    else:
        config = {}

    if not isinstance(config, dict):
        raise ValueError(f"{config_file} 不是 JSON 对象，无法安全修改。")

    original = json.dumps(config, ensure_ascii=False, sort_keys=True)

    config["latestVersionCode"] = version_code
    config["updateUrl"] = apk_url
    config["isForce"] = is_force
    config["updateMessage"] = update_message
    config["noticeMessage"] = notice_message
    config["isKillSwitchOn"] = is_kill_switch_on
    config["announcementTitle"] = announcement_title
    config["announcementMessage"] = announcement_message
    config["announcementUpdatedAt"] = announcement_updated_at
    config["announcementUrl"] = announcement_url
    config["changelogUrl"] = changelog_url
    config["changelogTitle"] = changelog_title
    config["changelogSummary"] = changelog_summary
    config["donateEnabled"] = donate_enabled

    updated = json.dumps(config, ensure_ascii=False, indent=2)
    config_file.write_text(updated + "\n", encoding="utf-8")

    current = json.dumps(config, ensure_ascii=False, sort_keys=True)
    return original != current


def sync_changelog_file(source_file: Path, repo_dir: Path, changelog_file: str) -> bool:
    """同步本仓库 update.md 到 Gitee 配置仓库，供国内用户读取。"""
    if not source_file.exists():
        raise FileNotFoundError(f"更新日志文件不存在：{source_file}")

    target_file = repo_dir / changelog_file
    target_file.parent.mkdir(parents=True, exist_ok=True)
    new_content = source_file.read_text(encoding="utf-8")
    old_content = target_file.read_text(encoding="utf-8") if target_file.exists() else None

    if old_content == new_content:
        return False

    target_file.write_text(new_content, encoding="utf-8")
    return True


def commit_and_push(repo_dir: Path, version_name: str, changed_files: list[str]) -> None:
    """提交并推送 Gitee 配置变更。"""
    run_command(["git", "config", "user.name", "github-actions[bot]"], cwd=repo_dir)
    run_command(["git", "config", "user.email", "github-actions[bot]@users.noreply.github.com"], cwd=repo_dir)
    run_command(["git", "add", *changed_files], cwd=repo_dir)
    run_command(["git", "commit", "-m", f"更新 Fe v{version_name} 远程配置"], cwd=repo_dir)
    run_command(["git", "push"], cwd=repo_dir)


def main() -> int:
    parser = argparse.ArgumentParser(description="更新 Gitee config.json")
    parser.add_argument("--version-code", required=True, type=int, help="Android versionCode")
    parser.add_argument("--version-name", required=True, help="Android versionName")
    parser.add_argument("--apk-url", required=True, help="APK 下载链接")
    parser.add_argument("--message", default=None, help="更新提示文本")
    parser.add_argument("--message-file", default=None, help="更新提示文件")
    parser.add_argument("--notice-message", default=os.getenv("RELEASE_NOTICE_MESSAGE", ""), help="公告文本")
    parser.add_argument("--announcement-title", default=os.getenv("RELEASE_ANNOUNCEMENT_TITLE", ""), help="首页公告标题")
    parser.add_argument("--announcement-message", default=os.getenv("RELEASE_ANNOUNCEMENT_MESSAGE", ""), help="首页公告正文")
    parser.add_argument("--announcement-message-file", default=os.getenv("RELEASE_ANNOUNCEMENT_MESSAGE_FILE"), help="首页公告正文文件")
    parser.add_argument("--announcement-updated-at", default=os.getenv("RELEASE_ANNOUNCEMENT_UPDATED_AT", ""), help="首页公告更新时间")
    parser.add_argument("--announcement-url", default=os.getenv("RELEASE_ANNOUNCEMENT_URL", ""), help="公告详情远程地址")
    parser.add_argument("--changelog-url", default=os.getenv("RELEASE_CHANGELOG_URL", ""), help="更新日志远程地址")
    parser.add_argument("--changelog-title", default=os.getenv("RELEASE_CHANGELOG_TITLE", "更新日志"), help="首页更新日志标题")
    parser.add_argument("--changelog-summary", default=os.getenv("RELEASE_CHANGELOG_SUMMARY", ""), help="首页更新日志摘要")
    parser.add_argument("--changelog-summary-file", default=os.getenv("RELEASE_CHANGELOG_SUMMARY_FILE"), help="首页更新日志摘要文件")
    parser.add_argument("--changelog-file", default=os.getenv("GITEE_CHANGELOG_FILE", DEFAULT_CHANGELOG_FILE), help="Gitee 更新日志路径")
    parser.add_argument("--source-changelog-file", default=os.getenv("SOURCE_CHANGELOG_FILE", DEFAULT_CHANGELOG_FILE), help="本仓库更新日志路径")
    parser.add_argument("--donate-enabled", default=os.getenv("RELEASE_DONATE_ENABLED", "true"), help="是否展示捐赠入口")
    parser.add_argument("--force", default=os.getenv("RELEASE_FORCE_UPDATE", "false"), help="是否强制更新")
    parser.add_argument("--kill-switch", default=os.getenv("RELEASE_KILL_SWITCH", "false"), help="是否开启 KillSwitch")
    parser.add_argument("--gitee-username", default=os.getenv("GITEE_USERNAME"), help="Gitee 用户名")
    parser.add_argument("--gitee-token", default=os.getenv("GITEE_TOKEN"), help="Gitee Token")
    parser.add_argument("--gitee-repo", default=os.getenv("GITEE_CONFIG_REPO", DEFAULT_REPO), help="Gitee 仓库 owner/name")
    parser.add_argument("--gitee-branch", default=os.getenv("GITEE_CONFIG_BRANCH", DEFAULT_BRANCH), help="Gitee 分支")
    parser.add_argument("--config-file", default=os.getenv("GITEE_CONFIG_FILE", DEFAULT_CONFIG_FILE), help="配置 JSON 路径")
    args = parser.parse_args()

    try:
        if not args.gitee_username or not args.gitee_token:
            raise ValueError("缺少 GITEE_USERNAME 或 GITEE_TOKEN，无法推送。")

        update_message = read_message(args.message, args.message_file)
        announcement_message = read_message(args.announcement_message, args.announcement_message_file)
        changelog_summary = read_message(args.changelog_summary, args.changelog_summary_file)
        is_force = parse_bool(args.force)
        is_kill_switch_on = parse_bool(args.kill_switch)
        donate_enabled = parse_bool(args.donate_enabled, default=True)

        if not announcement_message:
            announcement_message = args.notice_message.strip()
        announcement_title = args.announcement_title.strip()
        if not announcement_title and announcement_message:
            announcement_title = "公告"
        changelog_url = args.changelog_url.strip() or build_raw_gitee_url(
            args.gitee_repo,
            args.gitee_branch,
            args.changelog_file,
        )
        if not changelog_summary:
            changelog_summary = update_message

        with tempfile.TemporaryDirectory(prefix="fe-gitee-config-") as temp_dir:
            temp_path = Path(temp_dir)
            repo_dir = clone_config_repo(
                args.gitee_username,
                args.gitee_token,
                args.gitee_repo,
                args.gitee_branch,
                temp_path,
            )
            config_path = repo_dir / args.config_file
            changed_files: list[str] = []
            config_changed = update_config_file(
                config_path,
                args.version_code,
                args.apk_url,
                update_message,
                is_force,
                args.notice_message,
                is_kill_switch_on,
                announcement_title,
                announcement_message,
                args.announcement_updated_at.strip(),
                args.announcement_url.strip(),
                changelog_url,
                args.changelog_title.strip(),
                changelog_summary,
                donate_enabled,
            )
            if config_changed:
                changed_files.append(args.config_file)

            changelog_changed = sync_changelog_file(
                source_file=Path(args.source_changelog_file),
                repo_dir=repo_dir,
                changelog_file=args.changelog_file,
            )
            if changelog_changed:
                changed_files.append(args.changelog_file)

            if not changed_files:
                print("Gitee 配置和更新日志没有变化，不需要提交。")
                return 0

            commit_and_push(repo_dir, args.version_name, changed_files)
    except subprocess.CalledProcessError as exc:
        print(f"执行命令失败，退出码 {exc.returncode}", file=sys.stderr)
        return exc.returncode or 1
    except Exception as exc:
        print(f"更新 Gitee 配置失败：{exc}", file=sys.stderr)
        return 1

    print("Gitee 远程更新配置已经发布完成。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
