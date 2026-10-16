#!/usr/bin/env python3
"""Validate rendered same-site links and fragments under the GitHub Pages base path."""
from html.parser import HTMLParser
import json
from pathlib import Path
from urllib.parse import unquote, urljoin, urlsplit

ROOT = Path(__file__).resolve().parents[1] / 'website/build'
BASE = 'https://cs1302uga.github.io/cs1302-tracer/'


class Page(HTMLParser):
    def __init__(self, content):
        super().__init__()
        self.ids = set()
        self.links = []
        self.redirect = False
        self.feed(content)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'meta' and attrs.get('http-equiv', '').lower() == 'refresh':
            self.redirect = True
        if 'id' in attrs:
            self.ids.add(attrs['id'])
        if tag == 'a' and attrs.get('href'):
            self.links.append(attrs['href'])
        if tag in ('img', 'script') and attrs.get('src'):
            self.links.append(attrs['src'])
        if tag == 'link' and attrs.get('href'):
            self.links.append(attrs['href'])


def main():
    assert (ROOT / 'index.html').is_file(), 'Build the site first'
    pages = {path: Page(path.read_text()) for path in ROOT.rglob('*.html')}
    errors = []
    selection = ROOT.parent / 'published-versions.json'
    if not selection.exists():
        selection = ROOT.parent / 'versions.json'
    versions = json.loads(selection.read_text())
    for version in ['next', *versions]:
        for asset in ('index.html', 'search.html', 'element-list'):
            if not (ROOT / 'api' / version / asset).is_file():
                errors.append(f'Missing API reference asset: api/{version}/{asset}')
    for file, page in pages.items():
        relative = file.relative_to(ROOT).as_posix()
        if not relative.startswith('api/') and not page.redirect:
            section = relative.split('/')[0]
            version = section if section in ['next', *versions] else (versions[0] if versions else 'next')
            expected = f'/cs1302-tracer/api/{version}/index.html'
            if expected not in page.links:
                errors.append(f'{relative}: missing version-matched API link {expected}')
        route = relative[:-len('index.html')] if file.name == 'index.html' else relative
        for link in page.links:
            target = urlsplit(urljoin(BASE + route, link))
            if target.netloc != 'cs1302uga.github.io' or target.scheme not in ('http', 'https'):
                continue
            if not target.path.startswith('/cs1302-tracer/'):
                errors.append(f'{relative}: escapes project base path: {link}')
                continue
            dest = ROOT / unquote(target.path.removeprefix('/cs1302-tracer/'))
            if dest.is_dir():
                dest /= 'index.html'
            if not dest.is_file():
                errors.append(f'{relative}: missing {link}')
            elif target.fragment and dest in pages and unquote(target.fragment) not in pages[dest].ids:
                errors.append(f'{relative}: missing fragment {link}')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'Validated links and fragments in {len(pages)} rendered pages.')


if __name__ == '__main__':
    main()
