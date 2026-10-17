// Keep build selection separate from the complete, provenance-checked release manifest.
function guideBuild(release, selection) {
  const build = selection === undefined ? {kind: 'all', versions: release.versions.map(entry => entry.name)} : selection;
  if (!build || !['all', 'active', 'archive'].includes(build.kind)
      || !Array.isArray(build.versions) || !build.versions.length
      || new Set(build.versions).size !== build.versions.length) {
    throw new Error('Invalid guide build selection');
  }
  const included = build.versions.map(name => {
    const entry = release.versions.find(candidate => candidate.name === name);
    if (!entry) throw new Error(`Unknown guide version: ${name}`);
    return entry;
  });
  const archive = build.kind === 'archive';
  if (archive && (included.length !== 1 || included[0].name === 'current'
      || build.version !== included[0].name)) {
    throw new Error('An archive build requires exactly its named stable version');
  }
  const latest = archive ? included[0].name : release.latestVersion;
  if (!included.some(entry => entry.name === (latest || 'current'))) {
    throw new Error('The guide build must include its latest version');
  }
  // Frozen bundles embed no list of future releases or mutable latest-version choice.
  const embedded = archive ? {...release, latestVersion: latest, versions: included} : release;
  return {
    archive,
    included,
    latest,
    baseUrl: archive ? included[0].docsUrl : release.baseUrl,
    release: embedded,
    catalog: {
      schema: 1,
      baseUrl: release.baseUrl,
      latestVersion: archive ? null : release.latestVersion,
      versions: embedded.versions.map(({name, label, docsUrl, javadocUrl}) => ({
        name, label, docsUrl, javadocUrl, routes: [''],
      })),
    },
  };
}

module.exports = {guideBuild};
