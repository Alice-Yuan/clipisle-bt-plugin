#!/usr/bin/env python3
"""Create registry metadata from an already signed APK; never signs artifacts."""
import argparse, hashlib, json, pathlib, re, subprocess
p = argparse.ArgumentParser()
p.add_argument('apk', type=pathlib.Path)
p.add_argument('--url', required=True)
p.add_argument('--apksigner', required=True)
p.add_argument('--allow-debug', action='store_true', help='local/prerelease testing only')
p.add_argument('--output', type=pathlib.Path, default=pathlib.Path('clipisle-plugin.json'))
a = p.parse_args()
result = subprocess.run([a.apksigner, 'verify', '--print-certs', str(a.apk)], check=True, capture_output=True, text=True)
if not a.allow_debug and 'CN=Android Debug' in result.stdout:
    raise SystemExit('Debug certificate refused. Supply an independently signed release APK.')
match = re.search(r'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)', result.stdout)
if not match or 'Signer #2 certificate' in result.stdout:
    raise SystemExit('Expected exactly one APK signing certificate')
data = {'formatVersion': 1, 'id': 'com.clipisle.bt', 'name': '磁力 / BT 下载', 'version': '1.0.2', 'packageName': 'com.clipisle.btplugin', 'apiVersion': 1, 'apk': {'url': a.url, 'sha256': hashlib.sha256(a.apk.read_bytes()).hexdigest(), 'certificateSha256': match.group(1).replace(':', '').lower()}, 'permissions': ['internet', 'networkState', 'backgroundTransfer', 'notifications']}
a.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
