#!/usr/bin/env python3
"""Sync local release config to remote config repositories."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import quote


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


DEFAULT_LOCAL_CONFIG_FILE = "config/config.json"
DEFAULT_CONFIG_FILE = "config.json"
DEFAULT_CHANGELOG_FILE = "update.md"
DEFAULT_DOWNLOAD_URL = "https://oplist.lastudio.cc/Fe_release"

DEFAULT_GITHUB_REPO = "laststudio/Fe_config"
DEFAULT_GITHUB_BRANCH = "main"
DEFAULT_GITEE_REPO = "qiuqiqiuqid/fe_config"
DEFAULT_GITEE_BRANCH = "master"


def run_command(command: list[str], cwd: Path | None = None) -> None:
    subprocess.run(command, cwd=cwd, check=True, text=True)


def clone_url(provider: str, repo: str, username: str | None, token: str) -> str:
    if provider == "github":
        return f"https://x-access-token:{quote(token, safe='')}@github.com/{repo}.git"
    if provider == "gitee":
        if not username:
            raise ValueError("Gitee sync requires a username.")
        return f"https://{quote(username, safe='')}:{quote(token, safe='')}@gitee.com/{repo}.git"
    raise ValueError(f"Unknown provider: {provider}")


def clone_repo(provider: str, repo: str, branch: str, username: str | None, token: str, target_dir: Path) -> Path:
    repo_dir = target_dir / f"{provider}-config"
    run_command([
        "git",
        "clone",
        "--branch",
        branch,
        "--depth",
        "1",
        clone_url(provider, repo, username, token),
        str(repo_dir),
    ])
    return repo_dir


def load_json_file(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object.")
    return data


def read_text(path: Path | None) -> str:
    if path is None:
        return ""
    return path.read_text(encoding="utf-8").strip()


def raw_url(provider: str, repo: str, branch: str, file_path: str) -> str:
    normalized_path = file_path.strip().lstrip("/")
    if provider == "github":
        return f"https://raw.githubusercontent.com/{repo}/{branch}/{normalized_path}"
    if provider == "gitee":
        return f"https://raw.giteeusercontent.com/{repo}/raw/{branch}/{normalized_path}"
    raise ValueError(f"Unknown provider: {provider}")


def build_config(
    local_config_file: Path,
    version_code: int,
    apk_url: str,
    update_message: str,
    changelog_url: str,
) -> dict:
    config = load_json_file(local_config_file)
    config["latestVersionCode"] = version_code
    config["updateUrl"] = (config.get("updateUrl") or apk_url or DEFAULT_DOWNLOAD_URL).strip()
    config["updateMessage"] = update_message
    config.setdefault("isForce", False)
    config.setdefault("isKillSwitchOn", False)
    config.setdefault("noticeMessage", "")
    config.setdefault("announcementTitle", "公告")
    config.setdefault("announcementMessage", "")
    config.setdefault("announcementUrl", "")
    config.setdefault("changelogTitle", "更新日志")
    config["changelogUrl"] = (config.get("changelogUrl") or changelog_url).strip()
    config.setdefault("changelogSummary", update_message)
    config.setdefault("donateEnabled", True)
    return config


def write_if_changed(path: Path, content: str) -> bool:
    old_content = path.read_text(encoding="utf-8") if path.exists() else None
    if old_content == content:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return True


def sync_files(
    provider: str,
    repo: str,
    branch: str,
    username: str | None,
    token: str,
    version_name: str,
    config_file: str,
    changelog_file: str,
    generated_config: dict,
    source_changelog_file: Path,
) -> None:
    with tempfile.TemporaryDirectory(prefix=f"fe-{provider}-config-") as temp_dir:
        repo_dir = clone_repo(provider, repo, branch, username, token, Path(temp_dir))
        changed_files: list[str] = []

        config_content = json.dumps(generated_config, ensure_ascii=False, indent=2) + "\n"
        if write_if_changed(repo_dir / config_file, config_content):
            changed_files.append(config_file)

        changelog_content = source_changelog_file.read_text(encoding="utf-8")
        if write_if_changed(repo_dir / changelog_file, changelog_content):
            changed_files.append(changelog_file)

        if not changed_files:
            print(f"{provider} config repository has no changes.")
            return

        run_command(["git", "config", "user.name", "github-actions[bot]"], cwd=repo_dir)
        run_command(["git", "config", "user.email", "github-actions[bot]@users.noreply.github.com"], cwd=repo_dir)
        run_command(["git", "add", *changed_files], cwd=repo_dir)
        run_command(["git", "commit", "-m", f"Update Fe v{version_name} config"], cwd=repo_dir)
        run_command(["git", "push"], cwd=repo_dir)
        print(f"{provider} config repository updated: {', '.join(changed_files)}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync local config/config.json to remote config repositories")
    parser.add_argument("--version-code", required=True, type=int, help="Android versionCode")
    parser.add_argument("--version-name", required=True, help="Android versionName")
    parser.add_argument("--apk-url", default=DEFAULT_DOWNLOAD_URL, help="Default APK download URL")
    parser.add_argument("--message-file", default="current_changelog.md", help="Current changelog text file")
    parser.add_argument("--local-config-file", default=os.getenv("LOCAL_CONFIG_FILE", DEFAULT_LOCAL_CONFIG_FILE))
    parser.add_argument("--source-changelog-file", default=os.getenv("SOURCE_CHANGELOG_FILE", DEFAULT_CHANGELOG_FILE))
    parser.add_argument("--config-file", default=os.getenv("REMOTE_CONFIG_FILE", DEFAULT_CONFIG_FILE))
    parser.add_argument("--changelog-file", default=os.getenv("REMOTE_CHANGELOG_FILE", DEFAULT_CHANGELOG_FILE))

    parser.add_argument("--github-repo", default=os.getenv("CONFIG_GITHUB_REPO", DEFAULT_GITHUB_REPO))
    parser.add_argument("--github-branch", default=os.getenv("CONFIG_GITHUB_BRANCH", DEFAULT_GITHUB_BRANCH))
    parser.add_argument("--github-token", default=os.getenv("CONFIG_GITHUB_TOKEN") or os.getenv("GH_CONFIG_TOKEN"))

    parser.add_argument("--gitee-repo", default=os.getenv("GITEE_CONFIG_REPO", DEFAULT_GITEE_REPO))
    parser.add_argument("--gitee-branch", default=os.getenv("GITEE_CONFIG_BRANCH", DEFAULT_GITEE_BRANCH))
    parser.add_argument("--gitee-username", default=os.getenv("GITEE_USERNAME"))
    parser.add_argument("--gitee-token", default=os.getenv("GITEE_TOKEN"))
    args = parser.parse_args()

    try:
        local_config_file = Path(args.local_config_file)
        source_changelog_file = Path(args.source_changelog_file)
        if not local_config_file.exists():
            raise FileNotFoundError(f"Local config file not found: {local_config_file}")
        if not source_changelog_file.exists():
            raise FileNotFoundError(f"Source changelog file not found: {source_changelog_file}")

        update_message = read_text(Path(args.message_file) if args.message_file else None)
        github_changelog_url = raw_url("github", args.github_repo, args.github_branch, args.changelog_file)
        generated_config = build_config(
            local_config_file=local_config_file,
            version_code=args.version_code,
            apk_url=args.apk_url,
            update_message=update_message,
            changelog_url=github_changelog_url,
        )

        synced_any = False
        if args.github_token:
            sync_files(
                provider="github",
                repo=args.github_repo,
                branch=args.github_branch,
                username=None,
                token=args.github_token,
                version_name=args.version_name,
                config_file=args.config_file,
                changelog_file=args.changelog_file,
                generated_config=generated_config,
                source_changelog_file=source_changelog_file,
            )
            synced_any = True
        else:
            print("CONFIG_GITHUB_TOKEN is not configured; skipping GitHub config repository sync.")

        if args.gitee_username and args.gitee_token:
            gitee_config = dict(generated_config)
            if not load_json_file(local_config_file).get("changelogUrl"):
                gitee_config["changelogUrl"] = raw_url("gitee", args.gitee_repo, args.gitee_branch, args.changelog_file)
            sync_files(
                provider="gitee",
                repo=args.gitee_repo,
                branch=args.gitee_branch,
                username=args.gitee_username,
                token=args.gitee_token,
                version_name=args.version_name,
                config_file=args.config_file,
                changelog_file=args.changelog_file,
                generated_config=gitee_config,
                source_changelog_file=source_changelog_file,
            )
            synced_any = True
        else:
            print("GITEE_USERNAME or GITEE_TOKEN is not configured; skipping Gitee config repository sync.")

        if not synced_any:
            raise ValueError("No config repository credentials are configured.")
    except subprocess.CalledProcessError as exc:
        print(f"Command failed with exit code {exc.returncode}", file=sys.stderr)
        return exc.returncode or 1
    except Exception as exc:
        print(f"Config sync failed: {exc}", file=sys.stderr)
        return 1

    print("Remote config sync completed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
