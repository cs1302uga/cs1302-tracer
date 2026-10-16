"""Release decisions through the project interface, using real files and Git tags."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import docs_release


class ReleaseSelectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.site = self.root / 'website'
        self.site.mkdir()
        (self.root / 'docs').mkdir()
        (self.root / 'docs/index.md').write_text('# Current\n')
        (self.site / 'sidebars.js').write_text('module.exports = {docs: ["index"]};')
        self.git('init', '-q')
        self.snapshot('1.0.0')
        self.commit_version('1.0.0', 'v1.0.0')
        self.snapshot('2.0.0')
        (self.root / 'pom.xml').write_text('<project><version>2.0.0</version></project>')
        (self.site / 'versions.json').write_text('["2.0.0", "1.0.0"]')

    def git(self, *args):
        return subprocess.check_output(['git', '-c', 'user.name=Docs Test',
                                        '-c', 'user.email=docs@example.invalid',
                                        '-c', 'commit.gpgsign=false', '-c', 'tag.gpgSign=false',
                                        *args], cwd=self.root, text=True).strip()

    def snapshot(self, version):
        directory = self.site / f'versioned_docs/version-{version}'
        directory.mkdir(parents=True)
        (directory / 'index.md').write_text('# Current\n')
        sidebars = self.site / 'versioned_sidebars'
        sidebars.mkdir(exist_ok=True)
        (sidebars / f'version-{version}-sidebars.json').write_text('{"docs":["index"]}')

    def commit_version(self, version, tag):
        (self.root / 'pom.xml').write_text(f'<project><version>{version}</version></project>')
        self.git('add', '.')
        self.git('commit', '-qm', version)
        self.git('tag', tag)
        return self.git('rev-parse', 'HEAD')

    def test_preview_ignores_stale_published_selection_and_keeps_pending_snapshot(self):
        (self.site / 'published-versions.json').write_text('["1.0.0"]')
        plan = docs_release.Project(self.root).plan('preview')
        self.assertEqual([v['name'] for v in plan['versions']], ['current', '2.0.0', '1.0.0'])
        self.assertEqual(plan['latestVersion'], '2.0.0')
        self.assertEqual(plan['versions'][0]['docsUrl'], '/cs1302-tracer/next/')
        self.assertEqual(plan['versions'][1]['javadocUrl'], '/cs1302-tracer/api/2.0.0/index.html')
        self.assertIsNone(plan['versions'][1]['sourceCommit'])
        self.assertEqual(plan['versions'][2]['sourceCommit'], self.git('rev-parse', 'v1.0.0^{commit}'))

    def test_preview_requires_tagged_sources_for_older_snapshots(self):
        self.git('tag', '-d', 'v1.0.0')
        with self.assertRaisesRegex(ValueError, 'Missing release tag for 1.0.0'):
            docs_release.Project(self.root).plan('preview')

    def test_published_selection_excludes_pending_and_uses_release_tags(self):
        plan = docs_release.Project(self.root).plan('published', releases=[
            {'tag_name': 'v2.0.0', 'draft': True}, {'tag_name': 'v1.0.0'},
            {'tag_name': 'v0.9.0'}, {'tag_name': 'v2.0.0', 'prerelease': True},
        ])
        self.assertEqual([v['name'] for v in plan['versions']], ['current', '1.0.0'])
        self.assertEqual(plan['latestVersion'], '1.0.0')
        self.assertEqual(plan['versions'][1]['sourceCommit'], self.git('rev-parse', 'v1.0.0^{commit}'))

    def test_published_current_version_cannot_fall_back_to_working_sources(self):
        with self.assertRaisesRegex(ValueError, 'Missing release tag for 2.0.0'):
            docs_release.Project(self.root).plan('published', releases=[{'tag_name': 'v2.0.0'}])

    def test_conflicting_prefixed_and_unprefixed_tags_are_rejected(self):
        self.commit_version('2.0.0', '1.0.0')
        with self.assertRaisesRegex(ValueError, 'Ambiguous release tags for 1.0.0'):
            docs_release.Project(self.root).plan('preview')

    def test_tag_must_contain_the_matching_project_version(self):
        self.git('tag', 'v2.0.0')
        with self.assertRaisesRegex(ValueError, '2.0.0 contains Maven version 1.0.0'):
            docs_release.Project(self.root).plan('preview')

    def test_publishing_a_new_release_retains_older_versions_on_retry(self):
        self.commit_version('2.0.0', 'v2.0.0')
        project = docs_release.Project(self.root)
        records = [{'tag_name': 'v1.0.0'}, {'tag_name': 'v2.0.0'}]
        plan = project.plan('published', records)
        self.assertEqual([v['name'] for v in plan['versions']], ['current', '2.0.0', '1.0.0'])
        self.assertEqual(project.plan('published', list(reversed(records))), plan)

    def test_no_published_releases_keeps_development_at_the_root(self):
        plan = docs_release.Project(self.root).plan('published', [])
        self.assertIsNone(plan['latestVersion'])
        self.assertEqual([v['name'] for v in plan['versions']], ['current'])
        self.assertEqual(plan['versions'][0]['docsUrl'], '/cs1302-tracer/')
        self.assertEqual(plan['versions'][0]['javadocUrl'], '/cs1302-tracer/api/next/index.html')

    def test_published_mode_requires_explicit_release_records(self):
        with self.assertRaisesRegex(ValueError, 'requires release records'):
            docs_release.Project(self.root).plan('published')

    def test_preview_rejects_accidental_release_records(self):
        with self.assertRaisesRegex(ValueError, 'require published mode'):
            docs_release.Project(self.root).plan('preview', [])

    def test_equivalent_tag_spellings_can_name_the_same_release(self):
        self.git('tag', '1.0.0', 'v1.0.0')
        plan = docs_release.Project(self.root).plan('preview')
        self.assertEqual(plan['versions'][2]['sourceCommit'], self.git('rev-parse', 'v1.0.0^{commit}'))


if __name__ == '__main__':
    unittest.main()
