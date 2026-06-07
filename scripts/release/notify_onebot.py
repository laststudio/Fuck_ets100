#!/usr/bin/env python3
"""Send a release notification through OneBot HTTP API."""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


def build_action_url(api_url: str, action: str) -> str:
    normalized = api_url.strip().rstrip("/")
    if not normalized:
        raise ValueError("OneBot HTTP API URL is empty.")
    if normalized.endswith(f"/{action}"):
        return normalized
    return f"{normalized}/{action}"


def read_json_response(request: urllib.request.Request, step_name: str, timeout: int = 20) -> dict:
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{step_name} failed: status={exc.code}, body={body[:500]}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"{step_name} failed: {exc.reason}") from exc

    try:
        data = json.loads(body)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"{step_name} response is not JSON: {body[:500]}") from exc

    if data.get("status") != "ok" or data.get("retcode") not in (0, "0"):
        raise RuntimeError(f"{step_name} failed: {data}")

    return data


def send_group_msg(api_url: str, group_id: str, message: str, access_token: str | None) -> dict:
    payload = json.dumps(
        {
            "group_id": int(group_id),
            "message": message,
        },
        ensure_ascii=False,
    ).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    if access_token:
        headers["Authorization"] = f"Bearer {access_token}"

    request = urllib.request.Request(
        build_action_url(api_url, "send_group_msg"),
        data=payload,
        headers=headers,
        method="POST",
    )
    return read_json_response(request, "OneBot send_group_msg")


def upload_group_file(api_url: str, group_id: str, file_ref: str, name: str, access_token: str | None) -> dict:
    file_value = file_ref.strip()
    if not file_value:
        raise ValueError("Group upload file reference is empty.")

    local_path = Path(file_value)
    if local_path.exists():
        file_value = str(local_path.resolve())

    payload = json.dumps(
        {
            "group_id": str(int(group_id)),
            "file": file_value,
            "name": name,
        },
        ensure_ascii=False,
    ).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    if access_token:
        headers["Authorization"] = f"Bearer {access_token}"

    request = urllib.request.Request(
        build_action_url(api_url, "upload_group_file"),
        data=payload,
        headers=headers,
        method="POST",
    )
    return read_json_response(request, "OneBot upload_group_file", timeout=120)


def main() -> int:
    parser = argparse.ArgumentParser(description="Send OneBot group release notification")
    parser.add_argument("--api-url", required=True, help="OneBot HTTP API base URL or /send_group_msg URL")
    parser.add_argument("--group-id", required=True, help="Target QQ group id")
    parser.add_argument("--message-file", default="", help="Message text file")
    parser.add_argument("--upload-file", default="", help="Local file path to upload to the group")
    parser.add_argument("--upload-name", default="", help="Remote group file name")
    parser.add_argument("--access-token", default="", help="OneBot access token")
    args = parser.parse_args()

    try:
        if args.message_file:
            message = Path(args.message_file).read_text(encoding="utf-8").strip()
            if message:
                data = send_group_msg(args.api_url, args.group_id, message, args.access_token or None)
                message_id = (data.get("data") or {}).get("message_id")
                print(f"OneBot notification sent: message_id={message_id}")
            else:
                print("OneBot notification skipped: message is empty.")

        if args.upload_file:
            upload_name = args.upload_name or Path(args.upload_file).name
            data = upload_group_file(args.api_url, args.group_id, args.upload_file, upload_name, args.access_token or None)
            print(f"OneBot group file uploaded: {upload_name}, response={data.get('data')}")
    except Exception as exc:
        print(f"OneBot notification failed: {exc}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
