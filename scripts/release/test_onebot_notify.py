#!/usr/bin/env python3
"""Local OneBot notification smoke test.

Edit the CONFIG section below, then run:

    python scripts/release/test_onebot_notify.py
"""

from __future__ import annotations

import sys
from pathlib import Path

from notify_onebot import send_group_msg


# ===== CONFIG: edit these values locally before running =====
ONEBOT_HTTP_URL = "http://45.207.201.35:3000"
ONEBOT_GROUP_ID = "1102410958"
ONEBOT_ACCESS_TOKEN = "R5sopd~PHLtP.Hm."
TEST_MESSAGE = "Fe 发布通知测试"
# ============================================================


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


def validate_config() -> None:
    missing: list[str] = []
    if not ONEBOT_HTTP_URL.strip() or "填" in ONEBOT_HTTP_URL:
        missing.append("ONEBOT_HTTP_URL")
    if not ONEBOT_GROUP_ID.strip() or "填" in ONEBOT_GROUP_ID:
        missing.append("ONEBOT_GROUP_ID")
    if not ONEBOT_ACCESS_TOKEN.strip() or "填" in ONEBOT_ACCESS_TOKEN:
        missing.append("ONEBOT_ACCESS_TOKEN")
    if missing:
        raise ValueError(f"请先在 {Path(__file__).name} 顶部填写：{', '.join(missing)}")


def main() -> int:
    try:
        validate_config()
        data = send_group_msg(
            api_url=ONEBOT_HTTP_URL,
            group_id=ONEBOT_GROUP_ID,
            message=TEST_MESSAGE,
            access_token=ONEBOT_ACCESS_TOKEN,
        )
    except Exception as exc:
        print(f"OneBot 测试发送失败：{exc}", file=sys.stderr)
        return 1

    message_id = (data.get("data") or {}).get("message_id")
    print(f"OneBot 测试发送成功：message_id={message_id}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
