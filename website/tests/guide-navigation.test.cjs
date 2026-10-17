const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {test} = require('node:test');
const {guideBuild} = require('../src/lib/guide-build');
const {relativeRoute, validCatalog, versionTarget, withCompiledRoutes} = require('../src/lib/guide-catalog.mjs');

const baseUrl = '/cs1302-tracer/';
const entry = name => ({
  name, label: name, docsPath: name === 'current' ? 'next' : name,
  docsUrl: `${baseUrl}${name === 'current' ? 'next' : name}/`,
  javadocUrl: `${baseUrl}api/${name === 'current' ? 'next' : name}/index.html`,
  banner: name === 'current' ? 'unreleased' : 'none',
});
const release = {
  mode: 'published', origin: 'https://cs1302uga.github.io', baseUrl,
  latestVersion: '4.1.0', versions: ['current', '4.1.0', '4.0.0', '3.2.1'].map(entry),
};
const catalog = () => ({
  schema: 1, baseUrl, latestVersion: '4.1.0',
  versions: release.versions.map(value => ({...value, routes: ['', 'guides/installation/']})),
});

test('missing selection preserves all existing versions and canonical paths', () => {
  const result = guideBuild(release);
  assert.equal(result.archive, false);
  assert.equal(result.baseUrl, baseUrl);
  assert.deepEqual(result.included, release.versions);
  assert.equal(result.release, release);
  assert.equal(result.catalog.latestVersion, '4.1.0');
});

test('active selection bounds compilation while retaining every catalog version', () => {
  const result = guideBuild(release, {kind: 'active', versions: ['current', '4.1.0', '4.0.0']});
  assert.deepEqual(result.included.map(value => value.name), ['current', '4.1.0', '4.0.0']);
  assert.deepEqual(result.catalog.versions.map(value => value.name), release.versions.map(value => value.name));
});

test('archive selection embeds only its own version and stable root location', () => {
  const result = guideBuild(release, {kind: 'archive', version: '3.2.1', versions: ['3.2.1']});
  assert.equal(result.archive, true);
  assert.equal(result.latest, '3.2.1');
  assert.equal(result.baseUrl, `${baseUrl}3.2.1/`);
  assert.deepEqual(result.release.versions, [entry('3.2.1')]);
  assert.equal(result.release.latestVersion, '3.2.1');
  assert.equal(result.catalog.latestVersion, null);
  assert.equal(result.catalog.baseUrl, baseUrl);
  assert.deepEqual(result.catalog.versions.map(value => value.name), ['3.2.1']);
  const future = {...release, latestVersion: '5.0.0', versions: [entry('5.0.0'), ...release.versions]};
  assert.deepEqual(guideBuild(future, {kind: 'archive', version: '3.2.1', versions: ['3.2.1']}), result);
});

test('development-only sites remain supported', () => {
  const development = {...release, latestVersion: null, versions: [entry('current')]};
  assert.equal(guideBuild(development).latest, null);
});

test('invalid or inconsistent build selections stop before generation', () => {
  for (const selection of [
    null,
    {kind: 'invalid', versions: ['current']},
    {kind: 'all'}, {kind: 'all', versions: []},
    {kind: 'all', versions: ['current', 'current']},
    {kind: 'active', versions: ['missing']},
    {kind: 'active', versions: ['current']},
    {kind: 'archive', version: '4.1.0', versions: ['4.1.0', '4.0.0']},
    {kind: 'archive', version: 'current', versions: ['current']},
    {kind: 'archive', version: '4.0.0', versions: ['4.1.0']},
  ]) assert.throws(() => guideBuild(release, selection));
});

test('catalog validation accepts complete catalogs and isolated fallbacks', () => {
  assert.equal(validCatalog(catalog(), baseUrl), true);
  assert.equal(validCatalog(guideBuild(release).catalog, baseUrl), true);
  assert.equal(validCatalog(guideBuild(release, {kind: 'archive', version: '3.2.1', versions: ['3.2.1']}).catalog, baseUrl), true);
});

test('catalog validation refuses malformed metadata and executable or escaping paths', () => {
  for (const value of [null, {}, {...catalog(), schema: 2}, {...catalog(), baseUrl: '/other/'},
    {...catalog(), versions: null}, {...catalog(), versions: []}, {...catalog(), latestVersion: 'missing'},
    {...catalog(), versions: [catalog().versions[0], catalog().versions[0]]}]) {
    assert.equal(validCatalog(value, baseUrl), false);
  }
  for (const change of [
    null, {name: 1}, {name: '../escape'}, {label: null}, {label: ''},
    {docsUrl: null}, {docsUrl: 'https://example.com/'}, {docsUrl: `${baseUrl}4.1.0`},
    {docsUrl: `${baseUrl}../outside/`}, {javadocUrl: null},
    {javadocUrl: '/other/api/index.html'}, {javadocUrl: `${baseUrl}api/../index.html`},
    {routes: null}, {routes: ['javascript:alert(1)']},
  ]) {
    const value = catalog();
    value.versions[0] = change === null ? null : {...value.versions[0], ...change};
    assert.equal(validCatalog(value, baseUrl), false, JSON.stringify(change));
  }
  for (const route of [1, '/absolute/', 'bad//path/', './path/', 'path/../other/', '%2e%2e/', 'path?query']) {
    assert.equal(relativeRoute(route), false, String(route));
  }
  for (const route of ['', 'guide/', 'page.html', 'a-b_c~d/', 'package.name/']) {
    assert.equal(relativeRoute(route), true, route);
  }
});

test('version switching preserves known pages, query and fragments', () => {
  const [active, destination] = catalog().versions;
  assert.equal(versionTarget(destination, active, {
    pathname: `${active.docsUrl}guides/installation/`, search: '?mode=demo', hash: '#java',
  }), `${destination.docsUrl}guides/installation/?mode=demo#java`);
  assert.equal(versionTarget(destination, active, {
    pathname: active.docsUrl, search: '', hash: '',
  }), destination.docsUrl);
});

test('unknown destination pages and non-guide routes fall back to the version home', () => {
  const [active, destination] = catalog().versions;
  for (const [source, pathname] of [
    [active, `${active.docsUrl}new-page/`], [active, `${baseUrl}all-versions/`], [undefined, baseUrl],
  ]) assert.equal(versionTarget(destination, source, {pathname, search: '?mode=demo', hash: '#absent'}), `${destination.docsUrl}?mode=demo`);
});

test('compiled permalinks preserve matching routes without a published catalog', () => {
  const fallback = guideBuild(release).catalog;
  const compiled = ['current', '4.1.0'].map(name => ({name, docs: [
    {id: 'index', path: entry(name).docsUrl.slice(0, -1)},
    {id: 'source-file-with-a-custom-slug', path: `${entry(name).docsUrl}custom-route`},
    {id: '/generated-category', path: `${entry(name).docsUrl}category/generated/`},
    {id: 'duplicate-route', path: `${entry(name).docsUrl}custom-route/`},
  ]}));
  const result = withCompiledRoutes(fallback, compiled);
  assert.deepEqual(result.versions[0].routes, ['', 'custom-route/', 'category/generated/']);
  assert.deepEqual(fallback.versions[0].routes, ['']);
  assert.equal(versionTarget(result.versions[1], result.versions[0], {
    pathname: `${baseUrl}next/custom-route/`, search: '?preview=1', hash: '#details',
  }), `${baseUrl}4.1.0/custom-route/?preview=1#details`);
  assert.equal(versionTarget(result.versions[1], result.versions[0], {
    pathname: `${baseUrl}next/custom-route`, search: '', hash: '',
  }), `${baseUrl}4.1.0/custom-route/`);
  assert.equal(versionTarget(result.versions[1], result.versions[0], {
    pathname: `${baseUrl}next`, search: '', hash: '#overview',
  }), `${baseUrl}4.1.0/#overview`);
  assert.equal(validCatalog(result, baseUrl), true);
});

test('compiled routes override stale local metadata and preserve remote archive routes', () => {
  const published = catalog();
  const result = withCompiledRoutes(published, [{name: 'current', docs: [
    {id: 'new', path: `${baseUrl}next/new-page/`},
    {id: 'outside-version', path: `${baseUrl}4.1.0/wrong/`},
    {id: 'unsafe', path: `${baseUrl}next/../escape/`},
  ]}]);
  assert.deepEqual(result.versions[0].routes, ['new-page/']);
  assert.equal(result.versions[1], published.versions[1]);
  assert.deepEqual(result.versions[1].routes, ['', 'guides/installation/']);
  assert.deepEqual(withCompiledRoutes(published, []), published);
});

test('actual Docusaurus config isolates archives and supports active/default builds', t => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'tracer-guide-config-'));
  t.after(() => fs.rmSync(temporary, {recursive: true, force: true}));
  fs.cpSync(path.join(__dirname, '../src'), path.join(temporary, 'src'), {recursive: true});
  for (const file of ['docusaurus.config.js', 'sidebars.js']) {
    fs.copyFileSync(path.join(__dirname, '..', file), path.join(temporary, file));
  }
  fs.writeFileSync(path.join(temporary, 'release-manifest.json'), JSON.stringify(release));
  const configPath = path.join(temporary, 'docusaurus.config.js');
  const read = selection => {
    if (selection) fs.writeFileSync(path.join(temporary, 'guide-build.json'), JSON.stringify(selection));
    delete require.cache[require.resolve(configPath)];
    return require(configPath);
  };
  const original = read();
  assert.equal(original.baseUrl, baseUrl);
  assert.equal(original.presets[0][1].docs.onlyIncludeVersions.length, 4);
  assert.deepEqual(original.presets[0][1].pages, {path: 'src/pages', exclude: []});
  const active = read({kind: 'active', versions: ['current', '4.1.0', '4.0.0']});
  assert.equal(active.presets[0][1].docs.onlyIncludeVersions.length, 3);
  assert.equal(active.customFields.guideCatalog.versions.length, 4);
  const archive = read({kind: 'archive', version: '3.2.1', versions: ['3.2.1']});
  assert.equal(archive.baseUrl, `${baseUrl}3.2.1/`);
  assert.deepEqual(archive.staticDirectories, ['static']);
  assert.equal(archive.storage.namespace, true);
  assert.equal(archive.presets[0][1].pages, false);
  assert.equal(archive.presets[0][1].docs.includeCurrentVersion, false);
  assert.equal(archive.presets[0][1].docs.lastVersion, '3.2.1');
  assert.deepEqual(archive.presets[0][1].docs.versions, {'3.2.1': {label: '3.2.1', path: '', banner: 'none'}});
  const manifestPath = path.join(temporary, 'release-manifest.json');
  fs.writeFileSync(manifestPath, JSON.stringify({
    ...release, latestVersion: null, versions: [{...entry('current'), docsPath: '', docsUrl: baseUrl}],
  }));
  delete require.cache[require.resolve(manifestPath)];
  const development = read({kind: 'all', versions: ['current']});
  assert.deepEqual(development.presets[0][1].pages, {path: 'src/pages', exclude: ['**/index.jsx']});
  assert.equal(development.presets[0][1].docs.versions.current.path, '');
});
