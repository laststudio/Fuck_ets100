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
    keep_kill_switch: bool,
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

    if not keep_kill_switch:
        config["isKillSwitchOn"] = False
    else:
        config.setdefault("isKillSwitchOn", False)

    updated = json.dumps(config, ensure_ascii=False, indent=2)
    config_file.write_text(updated + "\n", encoding="utf-8")

    current = json.dumps(config, ensure_ascii=False, sort_keys=True)
    return original != current


def commit_and_push(repo_dir: Path, version_name: str, config_file: str) -> None:
    """提交并推送 Gitee 配置变更。"""
    run_command(["git", "config", "user.name", "github-actions[bot]"], cwd=repo_dir)
    run_command(["git", "config", "user.email", "github-actions[bot]@users.noreply.github.com"], cwd=repo_dir)
    run_command(["git", "add", config_file], cwd=repo_dir)
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
    parser.add_argument("--force", default=os.getenv("RELEASE_FORCE_UPDATE", "false"), help="是否强制更新")
    parser.add_argument("--keep-kill-switch", action="store_true", help="保留远端 KillSwitch 当前值")
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
        is_force = parse_bool(args.force)

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
            changed = update_config_file(
                config_path,
                args.version_code,
                args.apk_url,
                update_message,
                is_force,
                args.notice_message,
                args.keep_kill_switch,
            )

            if not changed:
                print("Gitee 配置没有变化，不需要提交。")
                return 0

            commit_and_push(repo_dir, args.version_name, args.config_file)
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
