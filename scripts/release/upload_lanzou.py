#!/usr/bin/env python3
"""Upload a release APK to LanZouCloud."""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

import requests
from requests_toolbelt import MultipartEncoder
from urllib3 import disable_warnings
from urllib3.exceptions import InsecureRequestWarning


ACCOUNT_URL = "https://pc.woozooo.com/account.php"
DOUPLOAD_URL = "https://pc.woozooo.com/doupload.php"
FILEUP_URL = "https://pc.woozooo.com/fileup.php"

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    ),
    "Referer": ACCOUNT_URL,
    "Accept-Language": "zh-CN,zh;q=0.9",
}


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


def login(username: str, password: str) -> tuple[requests.Session, str]:
    session = requests.Session()
    login_page = session.get(ACCOUNT_URL, headers=HEADERS, timeout=20, verify=False)
    formhash = re.search(r'name="formhash" value="(.+?)"', login_page.text)
    if not formhash:
        raise RuntimeError("Could not read LanZouCloud login formhash.")

    login_data = {
        "action": "login",
        "task": "login",
        "ref": "",
        "setSessionId": "",
        "setToken": "",
        "setSig": "",
        "setScene": "",
        "formhash": formhash.group(1),
        "username": username,
        "password": password,
    }
    response = session.post(ACCOUNT_URL, data=login_data, headers=HEADERS, timeout=20, verify=False)
    response.encoding = "utf-8"

    cookies = session.cookies.get_dict()
    uid = cookies.get("ylogin")
    if not uid:
        raise RuntimeError(f"LanZouCloud login failed, status={response.status_code}.")

    return session, uid


def find_root_folder_id(session: requests.Session, uid: str, folder_name: str) -> int:
    response = session.post(
        f"{DOUPLOAD_URL}?uid={uid}",
        data={"task": 47, "folder_id": -1},
        headers=HEADERS,
        timeout=20,
        verify=False,
    )
    response.raise_for_status()
    data = response.json()

    for folder in data.get("text", []):
        if folder.get("name", "").lower() == folder_name.lower():
            return int(folder["fol_id"])

    raise RuntimeError(f"LanZouCloud folder not found in root: {folder_name}")


def upload_file(session: requests.Session, file_path: Path, folder_id: int) -> int:
    if not file_path.is_file():
        raise FileNotFoundError(f"Upload file not found: {file_path}")

    filename = file_path.name
    with file_path.open("rb") as file_obj:
        multipart = MultipartEncoder(
            {
                "task": "1",
                "vie": "2",
                "ve": "2",
                "id": "WU_FILE_0",
                "folder_id_bb_n": str(folder_id),
                "name": filename,
                "upload_file": (filename, file_obj, "application/octet-stream"),
            }
        )

        upload_headers = HEADERS.copy()
        upload_headers["Content-Type"] = multipart.content_type
        response = session.post(
            FILEUP_URL,
            data=multipart,
            headers=upload_headers,
            timeout=3600,
            verify=False,
        )

    response.raise_for_status()
    data = response.json()
    if data.get("zt") != 1:
        raise RuntimeError(f"LanZouCloud upload failed: {data}")

    uploaded_id = data.get("text", [{}])[0].get("id")
    if uploaded_id is None:
        raise RuntimeError(f"LanZouCloud upload response missing file id: {data}")

    return int(uploaded_id)


def main() -> int:
    parser = argparse.ArgumentParser(description="Upload release APK to LanZouCloud")
    parser.add_argument("--file", required=True, help="Local APK path")
    parser.add_argument("--folder-id", type=int, default=None, help="Target LanZouCloud folder id")
    parser.add_argument("--folder-name", default=os.getenv("LANZOU_FOLDER_NAME", "fe"), help="Root folder name")
    parser.add_argument("--username", default=os.getenv("LANZOU_USERNAME"), help="LanZouCloud username")
    parser.add_argument("--password", default=os.getenv("LANZOU_PASSWORD"), help="LanZouCloud password")
    args = parser.parse_args()

    try:
        if not args.username or not args.password:
            raise ValueError("Missing LANZOU_USERNAME or LANZOU_PASSWORD.")

        disable_warnings(InsecureRequestWarning)
        session, uid = login(args.username, args.password)
        folder_id = args.folder_id
        if folder_id is None:
            folder_id = find_root_folder_id(session, uid, args.folder_name)

        uploaded_id = upload_file(session, Path(args.file).resolve(), folder_id)
    except Exception as exc:
        print(f"LanZouCloud upload failed: {exc}", file=sys.stderr)
        return 1

    print(f"LanZouCloud upload completed: file_id={uploaded_id}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
