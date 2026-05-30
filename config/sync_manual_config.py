#!/usr/bin/env python3
"""Sync manually edited config fields to remote config repositories."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path


DEFAULT_LOCAL_CONFIG = Path(__file__).with_name("config.json")
DEFAULT_CONFIG_FILE = "config.json"
DEFAULT_GITHUB_REPO = "laststudio/Fe_config"
DEFAULT_GITHUB_BRANCH = "main"
DEFAULT_GITEE_REPO = "qiuqiqiuqid/fe_config"
DEFAULT_GITEE_BRANCH = "master"

AUTO_FIELDS = {
    "latestVersionCode",
    "updateUrl",
    "updateMessage",
    "changelogUrl",
    "changelogSummary",
}


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


def run(command: list[str], cwd: Path | None = None) -> None:
    subprocess.run(command, cwd=cwd, check=True, text=True)


def repo_url(provider: str, repo: str) -> str:
    if provider == "github":
        return f"https://github.com/{repo}.git"
    if provider == "gitee":
        return f"https://gitee.com/{repo}.git"
    raise ValueError(f"Unknown provider: {provider}")


def load_json(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object.")
    return data


def manual_fields(local_config: dict) -> dict:
    return {
        key: value
        for key, value in local_config.items()
        if key not in AUTO_FIELDS
    }


def write_json_if_changed(path: Path, data: dict) -> bool:
    new_content = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    old_content = path.read_text(encoding="utf-8") if path.exists() else None
    if old_content == new_content:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(new_content, encoding="utf-8")
    return True


def sync_repo(
    provider: str,
    repo: str,
    branch: str,
    config_file: str,
    fields: dict,
    dry_run: bool,
) -> bool:
    with tempfile.TemporaryDirectory(prefix=f"fe-{provider}-manual-config-") as temp_dir:
        repo_dir = Path(temp_dir) / f"{provider}-config"
        run(["git", "clone", "--branch", branch, "--depth", "1", repo_url(provider, repo), str(repo_dir)])

        config_path = repo_dir / config_file
        remote_config = load_json(config_path) if config_path.exists() else {}
        updated_config = dict(remote_config)
        updated_config.update(fields)

        if not write_json_if_changed(config_path, updated_config):
            print(f"{provider}: config has no manual-field changes.")
            return False

        print(f"{provider}: updated manual fields: {', '.join(fields.keys())}")
        if dry_run:
            print(f"{provider}: dry run, not committing.")
            return True

        run(["git", "config", "user.name", "local-config-sync"], cwd=repo_dir)
        run(["git", "config", "user.email", "local-config-sync@users.noreply.github.com"], cwd=repo_dir)
        run(["git", "add", config_file], cwd=repo_dir)
        run(["git", "commit", "-m", "Update manual Fe config"], cwd=repo_dir)
        run(["git", "push"], cwd=repo_dir)
        return True


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync manual config fields using local git credentials.")
    parser.add_argument("--local-config", default=str(DEFAULT_LOCAL_CONFIG), help="Local manual config JSON.")
    parser.add_argument("--config-file", default=DEFAULT_CONFIG_FILE, help="Remote config path.")
    parser.add_argument("--github-repo", default=DEFAULT_GITHUB_REPO, help="GitHub owner/repo.")
    parser.add_argument("--github-branch", default=DEFAULT_GITHUB_BRANCH, help="GitHub branch.")
    parser.add_argument("--gitee-repo", default=DEFAULT_GITEE_REPO, help="Gitee owner/repo.")
    parser.add_argument("--gitee-branch", default=DEFAULT_GITEE_BRANCH, help="Gitee branch.")
    parser.add_argument("--only", choices=["github", "gitee"], help="Sync only one provider.")
    parser.add_argument("--dry-run", action="store_true", help="Show changes without pushing.")
    args = parser.parse_args()

    try:
        local_config = load_json(Path(args.local_config))
        fields = manual_fields(local_config)
        if not fields:
            raise ValueError("No manual fields found in local config.")

        providers = [args.only] if args.only else ["github", "gitee"]
        changed = False
        for provider in providers:
            if provider == "github":
                changed = sync_repo(
                    provider="github",
                    repo=args.github_repo,
                    branch=args.github_branch,
                    config_file=args.config_file,
                    fields=fields,
                    dry_run=args.dry_run,
                ) or changed
            else:
                changed = sync_repo(
                    provider="gitee",
                    repo=args.gitee_repo,
                    branch=args.gitee_branch,
                    config_file=args.config_file,
                    fields=fields,
                    dry_run=args.dry_run,
                ) or changed

        if not changed:
            print("Remote manual config is already up to date.")
    except subprocess.CalledProcessError as exc:
        print(f"Command failed with exit code {exc.returncode}", file=sys.stderr)
        return exc.returncode or 1
    except Exception as exc:
        print(f"Manual config sync failed: {exc}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
