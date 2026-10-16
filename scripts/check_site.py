#!/usr/bin/env python3
"""Validate rendered same-site links and fragments under the GitHub Pages base path."""
from html.parser import HTMLParser
import json
from pathlib import Path
from urllib.parse import unquote, urljoin, urlsplit

ROOT = Path(__file__).resolve().parents[1] / 'website/build'


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
    plan = json.loads((ROOT / 'release-manifest.json').read_text())
    base = plan['origin'] + plan['baseUrl']
    host = urlsplit(base).netloc
    entries = plan['versions']
    default = next(v for v in entries if v['name'] == (plan['latestVersion'] or 'current'))
    for entry in entries:
        for asset in ('index.html', 'search.html', 'element-list'):
            if not (ROOT / entry['javadocPath'] / asset).is_file():
                errors.append(f"Missing API reference asset: {entry['javadocPath']}/{asset}")
    for file, page in pages.items():
        relative = file.relative_to(ROOT).as_posix()
        route = relative[:-len('index.html')] if file.name == 'index.html' else relative
        api_page = any(relative.startswith(v['javadocPath'] + '/') for v in entries)
        if not api_page and not page.redirect:
            matches = [v for v in entries if (plan['baseUrl'] + route).startswith(v['docsUrl'])]
            entry = max(matches, key=lambda v: len(v['docsUrl'])) if matches else default
            if entry['javadocUrl'] not in page.links:
                errors.append(f"{relative}: missing version-matched API link {entry['javadocUrl']}")
        for link in page.links:
            target = urlsplit(urljoin(base + route, link))
            if target.netloc != host or target.scheme not in ('http', 'https'):
                continue
            if not target.path.startswith(plan['baseUrl']):
                errors.append(f'{relative}: escapes project base path: {link}')
                continue
            dest = ROOT / unquote(target.path.removeprefix(plan['baseUrl']))
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
