#!/usr/bin/env python3
"""Report unavailable HTTP links without becoming a PR merge gate."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def check(url):
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'cs1302-tracer-docs-link-check'})
        with urllib.request.urlopen(request, timeout=20) as response:
            return url, response.status, ''
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        return url, 'FAIL', str(error)


def main():
    paths = [ROOT / 'README.md', ROOT / 'HACKING.md', *sorted((ROOT / 'docs').rglob('*.md'))]
    urls = sorted({url.split('#')[0] for path in paths
                   for url in re.findall(r'\]\((https?://[^\s)]+)\)', path.read_text())})
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(check, urls))
    lines = ['# External documentation links', '', '| URL | Status | Detail |', '| --- | --- | --- |']
    for url, status, error in results:
        lines.append(f'| {url} | {status} | {error.replace(chr(10), " ").replace("|", "/")} |')
    (ROOT / 'external-links-report.md').write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return int(any(status == 'FAIL' for _, status, _ in results))


if __name__ == '__main__':
    sys.exit(main())
