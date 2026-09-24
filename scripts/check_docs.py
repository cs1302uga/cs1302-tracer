#!/usr/bin/env python3
"""Check executable documentation; --update-cli refreshes CLI help only."""
import argparse
import difflib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'target/code-tracer-jar-with-dependencies.jar'


def run(args, source=None, cwd=ROOT):
    process = subprocess.Popen(args, cwd=cwd, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, start_new_session=True)
    try:
        out, err = process.communicate(source, timeout=40)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.communicate()
        raise RuntimeError(f'Documentation command timed out: {args}') from None
    if process.returncode:
        raise RuntimeError(f'{args}: exit {process.returncode}\n{err}')
    return out


def trace(*args, source=None):
    return run(['java', '-jar', str(JAR), *args], source)


def cli_reference():
    text = '# CLI reference\n\nGenerated from the executable by `python3 scripts/check_docs.py --update-cli`.\nDo not edit help blocks by hand. `trace` is an explicit subcommand.\n\n'
    for command in ['', 'trace', 'batch-trace', 'list-breakpoints', 'show-licenses']:
        help_text = run(['java', '-Dpicocli.ansi=false', '-Dpicocli.usage.width=100',
                         '-jar', str(JAR), *([command] if command else []), '--help'])
        text += f'## {command or "Root command"}\n\n```text\n{help_text.rstrip()}\n```\n\n'
    return text + ('## Effective budgets\n\nOrdinary tracing uses finite defaults; envelope and batch requests use unlimited\n'
                   'omitted limits. Zero disables a limit. See [bounded tracing](../BOUNDED_TRACING.md)\n'
                   'for default values, inspection policies, exit codes, and partial results.\n')


def equal(actual, expected, name):
    if actual != expected:
        difference = ''.join(difflib.unified_diff(expected.splitlines(True), actual.splitlines(True),
                                                 fromfile=name, tofile='actual'))
        raise AssertionError(difference)


def block(text, marker):
    section = text.split(f'<!-- {marker}:start -->', 1)[1].split(f'<!-- {marker}:end -->', 1)[0]
    return re.search(r'```[^\n]*\n(.*?)\n```', section, re.S).group(1)


def check_links():
    """Check repository-relative Markdown destinations, including root entry points."""
    paths = [ROOT / 'README.md', ROOT / 'HACKING.md', *sorted((ROOT / 'docs').rglob('*.md')),
             *sorted((ROOT / 'examples').rglob('README.md'))]
    errors = []
    for path in paths:
        text = re.sub(r'```.*?```', '', path.read_text(), flags=re.S)
        for target in re.findall(r'\]\(([^\s)]+)(?:\s+"[^"]*")?\)', text):
            url = urlsplit(target.strip('<>'))
            if url.scheme or url.netloc or not url.path:
                continue
            dest = (path.parent / unquote(url.path)).resolve()
            if not dest.exists():
                errors.append(f'{path.relative_to(ROOT)}: missing {target}')
    if errors:
        raise AssertionError('\n'.join(errors))


def check_examples():
    readme = (ROOT / 'README.md').read_text()
    tutorial = (ROOT / 'docs/tutorials/first-trace.md').read_text()
    command = block(readme, 'quickstart')
    equal(block(tutorial, 'quickstart'), command, 'tutorial quickstart')
    # Run exactly the documented shell block in a fresh directory with only its inputs.
    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)
        (work / 'target').mkdir()
        (work / 'target' / JAR.name).symlink_to(JAR)
        (work / 'examples').symlink_to(ROOT / 'examples', target_is_directory=True)
        result = json.loads(run(['sh', '-eu', '-c', command], cwd=work))
    assert result['format'] == 'modern' and result['steps']
    assert result['entryFile'] in result['sources']
    assert any('Person' in str(step['heap']) for step in result['steps'])

    reference = (ROOT / 'docs/reference/output.md').read_text()
    source = block(reference, 'source') + '\n'
    spec = importlib.util.spec_from_file_location('verify', ROOT / 'examples/verify.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    for fmt in ('modern', 'pytutor'):
        result = json.loads(trace('trace', '--format', fmt, '--all-breakpoints', source=source))
        expected = json.loads(block(reference, fmt))
        equal(module.render(module.normalize(result)), module.render(module.normalize(expected)),
              f'{fmt} JSON example')
    envelope = json.loads(trace('trace', '--format', 'modern', '--all-breakpoints',
                               '--result-envelope', '--timeout-ms', '5000', source=source))
    assert envelope['schemaVersion'] == 1 and envelope['status'] == 'completed'
    assert envelope['complete'] and envelope['trace']['steps']
    assert envelope['limits']['timeoutMillis'] == 5000
    assert envelope['limits']['snapshots'] == 0, 'Envelope omitted limits must match docs'

    batch = (ROOT / 'docs/reference/batch.md').read_text()
    request = re.search(r'```json\n(.*?)\n```', batch, re.S).group(1)
    response = json.loads(trace('batch-trace', source=request + '\n'))
    assert response['id'] == 'hello' and response['result']['status'] == 'completed'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--update-cli', action='store_true')
    args = parser.parse_args()
    if not JAR.is_file():
        raise SystemExit('Build the JAR first: ./mvnw -B -ntp package -DskipTests')
    generated = cli_reference()
    path = ROOT / 'docs/reference/cli.md'
    if args.update_cli:
        path.write_text(generated)
    else:
        equal(generated, path.read_text(), str(path.relative_to(ROOT)))
    check_links()
    check_examples()
    print('Documentation links, CLI, quickstart, JSON examples, and batch request passed.')


if __name__ == '__main__':
    main()
