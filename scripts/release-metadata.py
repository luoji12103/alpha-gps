#!/usr/bin/env python3
"""Validate the fork's single release manifest; never reads signing material."""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def metadata():
    values = {}
    for line in (ROOT / 'release.properties').read_text().splitlines():
        line = line.strip()
        if line and not line.startswith('#'):
            key, value = line.split('=', 1)
            if key in values:
                raise ValueError(f'Duplicate release field: {key}')
            values[key] = value
    patterns = {
        'upstreamVersion': r'[0-9]+\.[0-9]+\.[0-9]+',
        'upstreamCommit': r'[0-9a-f]{40}',
        'edition': r'power',
        'revision': r'[1-9][0-9]*',
        'versionCode': r'[1-9][0-9]*',
    }
    if values.keys() != patterns.keys():
        raise ValueError('Unexpected or missing release fields')
    for key, pattern in patterns.items():
        if not re.fullmatch(pattern, values[key]):
            raise ValueError(f'Invalid release field: {key}')
    if int(values['versionCode']) > 2_100_000_000:
        raise ValueError("versionCode exceeds Android's limit")
    return {
        **values,
        'versionCode': int(values['versionCode']),
        'tag': f"v{values['upstreamVersion']}+{values['edition']}.{values['revision']}",
        'apk': 'AlphaGPS-power-arm64.apk',
        'repository': 'luoji12103/alpha-gps',
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-tag')
    parser.add_argument('--github-output', action='store_true')
    args = parser.parse_args()
    release = metadata()
    if args.check_tag and args.check_tag != release['tag']:
        parser.error('Tag does not match release.properties')
    if args.github_output:
        for key, value in release.items():
            print(f'{key}={value}')
    else:
        print(json.dumps(release, indent=2))
