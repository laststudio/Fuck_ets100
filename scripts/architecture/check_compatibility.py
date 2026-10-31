#!/usr/bin/env python3
"""Guard compatibility-sensitive identifiers while modules are extracted."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


def read(root: Path, relative: str) -> str:
    path = root / relative
    if not path.is_file():
        raise AssertionError(f"missing compatibility source: {relative}")
    return path.read_text(encoding="utf-8")


def require(text: str, needle: str, label: str) -> str | None:
    if needle not in text:
        return f"{label}: missing {needle!r}"
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    root = args.root.resolve()
    errors: list[str] = []

    manifest = read(root, "app/src/main/AndroidManifest.xml")
    for needle in (
        'android:name=".MainActivity"',
        'android:name=".ChangyanWebLoginActivity"',
        'android:name=".ChangyanWebLoginOpaqueActivity"',
        'android:authorities="${applicationId}.shizuku"',
        'android:authorities="${applicationId}.fileprovider"',
    ):
        if (error := require(manifest, needle, "manifest")) is not None:
            errors.append(error)

    sources = {
        "core/settings/src/main/java/com/shuaiqiu/fuckets100/SettingsManager.kt": [
            '"fe_settings"',
        ],
        "core/settings/src/main/java/com/shuaiqiu/fuckets100/ThemeManager.kt": [
            '"fe_settings"', '"app_theme"', '"dark_mode"', '"auto_dark_mode"',
            '"dynamic_color"', '"palette_style"', '"color_spec"', '"amoled"', '"key_color"',
        ],
        "data/ets100/src/main/java/com/shuaiqiu/fuckets100/ETS100AuthManager.kt": ['"ets100_auth"'],
        "data/ets100/src/main/java/com/shuaiqiu/fuckets100/ETS100SecureTokenStore.kt": [
            '"ets100_secure"', '"token"', '"ets100_token_v1"',
        ],
        "data/account/src/main/java/com/shuaiqiu/fuckets100/FeSecureSessionStore.kt": [
            '"fe_account_secure"', '"refresh_token"', '"cached_session"', '"fe_account_refresh_v1"',
        ],
        "feature/read/src/main/java/com/shuaiqiu/fuckets100/feature/read/ReadPageStateStore.kt": ['"read_page_state"'],
        "data/content/src/main/java/com/shuaiqiu/fuckets100/LegalAgreementRepository.kt": ['"fe_legal_agreement_cache"'],
    }
    for relative, needles in sources.items():
        text = read(root, relative)
        for needle in needles:
            if (error := require(text, needle, relative)) is not None:
                errors.append(error)

    urls = {
        "data/content/src/main/java/com/shuaiqiu/fuckets100/RemoteConfigManager.kt": [
            "https://api.lastudio.cc/software/fe/config",
        ],
        "data/account/src/main/java/com/shuaiqiu/fuckets100/FeAccountApi.kt": ['"https://api.lastudio.cc"'],
        "feature/settings/src/main/java/com/shuaiqiu/fuckets100/feature/settings/AboutScreen.kt": [
            "https://oplist.lastudio.cc/Fe_android",
        ],
        "scripts/release/update_remote_config.py": [
            "https://lastudio.cc/dl/Fe/latest",
        ],
    }
    for relative, needles in urls.items():
        text = read(root, relative)
        for needle in needles:
            if (error := require(text, needle, relative)) is not None:
                errors.append(error)

    # 发布标识与签名校验字段：改动会直接影响更新检查与签名校验，必须锁定。
    app_gradle = read(root, "app/build.gradle.kts")
    for needle in (
        'applicationId = "com.shuaiqiu.fuckets100"',
        "versionCode = 25",
        'versionName = "2.3.0.1-fix"',
        'EXPECTED_SIGNATURE_SHA256',
        'EXPECTED_PACKAGE_NAME',
    ):
        if (error := require(app_gradle, needle, "app/build.gradle.kts")) is not None:
            errors.append(error)

    proguard = read(root, "app/proguard-rules.pro")
    for needle in (
        "com.shuaiqiu.fuckets100.ResolverNative",
        "com.shuaiqiu.fuckets100.CloudNative",
        "com.shuaiqiu.fuckets100.BuildConfig",
    ):
        if (error := require(proguard, needle, "proguard")) is not None:
            errors.append(error)

    if errors:
        print("Compatibility guard failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print("Compatibility guard passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
