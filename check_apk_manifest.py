#!/usr/bin/env python3
"""Validate the compiled discovery resource in a built APK with Android aapt2."""
import argparse
import json
import pathlib
import re
import subprocess
import sys


def extract_manifest(dump: str) -> dict:
    lines = dump.splitlines()
    for i, line in enumerate(lines):
        if not re.search(r'\bstring/plugin_manifest\s*$', line):
            continue
        for candidate in lines[i + 1:]:
            if re.search(r'\bresource\s+0x', candidate):
                break
            match = re.match(r'^\s*\([^)]*\)\s+"(.*)"\s*$', candidate)
            if not match:
                continue
            value = match.group(1)
            try:
                obj = json.loads(value)
            except json.JSONDecodeError:
                # Some aapt2 versions escape quotes in their textual dump.
                obj = json.loads(json.loads('"' + value + '"'))
            if not isinstance(obj, dict):
                raise ValueError('plugin_manifest must be a JSON object')
            return obj
        raise ValueError('plugin_manifest has no string value')
    raise ValueError('plugin_manifest resource not found')


def validate(manifest: dict) -> None:
    if manifest.get('id') != 'com.clipisle.bt' or manifest.get('apiVersion') != 1:
        raise ValueError('unexpected plugin id or API version')
    contributions = manifest.get('uiContributions')
    if not isinstance(contributions, list):
        raise ValueError('missing uiContributions')
    for slot, action in [('home.inputActions', 'importTorrent'), ('plugin.settings', 'settings')]:
        if not any(isinstance(c, dict) and c.get('slot') == slot and c.get('action') == action for c in contributions):
            raise ValueError(f'missing contribution {slot}/{action}')
    if not isinstance(manifest.get('inputMatchers'), list):
        raise ValueError('missing inputMatchers')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=pathlib.Path)
    parser.add_argument('--aapt2', required=True, help='path to Android SDK build-tools aapt2 executable')
    args = parser.parse_args()
    try:
        result = subprocess.run([args.aapt2, 'dump', 'resources', str(args.apk)], check=True, capture_output=True, encoding='utf-8')
        manifest = extract_manifest(result.stdout)
        validate(manifest)
    except (OSError, subprocess.CalledProcessError, ValueError) as exc:
        print(f'Invalid compiled plugin manifest: {exc}', file=sys.stderr)
        return 1
    print(f"Compiled plugin manifest OK: {manifest['id']} v{manifest.get('version', '?')}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
