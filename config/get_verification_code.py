#!/usr/bin/env python3
"""Fetch and print the remote Fe verification code."""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request


DEFAULT_CONFIG_URLS = [
    "https://raw.githubusercontent.com/laststudio/Fe_config/main/config.json",
    "https://raw.giteeusercontent.com/qiuqiqiuqid/fe_config/raw/master/config.json",
]


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


def fetch_json(url: str, timeout: float) -> dict:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "Fe-config-verification-fetcher/1.0"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        charset = response.headers.get_content_charset() or "utf-8"
        payload = response.read().decode(charset)
    data = json.loads(payload)
    if not isinstance(data, dict):
        raise ValueError("remote config must be a JSON object")
    return data


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch remote config and print verificationCode.")
    parser.add_argument(
        "--url",
        action="append",
        dest="urls",
        help="Remote config URL. Can be passed multiple times. Defaults to GitHub raw then Gitee raw.",
    )
    parser.add_argument("--timeout", type=float, default=10.0, help="Request timeout in seconds.")
    parser.add_argument("--show-source", action="store_true", help="Print the source URL too.")
    args = parser.parse_args()

    urls = args.urls or DEFAULT_CONFIG_URLS
    errors: list[str] = []

    for url in urls:
        try:
            config = fetch_json(url, args.timeout)
            code = str(config.get("verificationCode", ""))
            if args.show_source:
                print(f"source: {url}")
            print(code)
            return 0
        except (OSError, urllib.error.URLError, json.JSONDecodeError, ValueError) as exc:
            errors.append(f"{url}: {exc}")

    print("Failed to fetch verificationCode from all remote config sources.", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
