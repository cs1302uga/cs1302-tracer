#!/usr/bin/env python3
"""Preview the site with GitHub Pages paths and unchanged Javadoc HTML URLs."""
import argparse
import json
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=3000)
    args = parser.parse_args()
    build = Path(__file__).resolve().parents[1] / 'website/build'
    if not (build / 'index.html').is_file():
        parser.error('Build the documentation site first')
    plan = json.loads((build / 'release-manifest.json').read_text())
    base = plan['baseUrl']
    # Docusaurus serve rewrites .html URLs, breaking Javadoc relative links under
    # a project base path. A static server preserves the deployed URL structure.
    with tempfile.TemporaryDirectory(prefix='tracer-site-preview-') as temporary:
        mount = Path(temporary) / base.strip('/')
        mount.parent.mkdir(parents=True, exist_ok=True)
        mount.symlink_to(build, target_is_directory=True)
        handler = partial(SimpleHTTPRequestHandler, directory=temporary)
        with ThreadingHTTPServer((args.host, args.port), handler) as server:
            print(f'Serving http://{args.host}:{server.server_port}{base}', flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                # Ctrl-C is a normal preview shutdown; the context managers close
                # the server and remove its temporary mount without a traceback.
                pass


if __name__ == '__main__':
    main()
