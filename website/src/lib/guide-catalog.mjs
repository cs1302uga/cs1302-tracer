// Catalog paths are site-relative URLs, never origins, executable URLs, or HTML.
function relativeRoute(value) {
  return typeof value === 'string' && (value === ''
    || (/^[A-Za-z0-9_.~/-]+$/.test(value)
      && !value.startsWith('/') && !value.includes('//')
      && !value.split('/').some(segment => segment === '.' || segment === '..')));
}

function validCatalog(value, baseUrl) {
  if (!value || value.schema !== 1 || value.baseUrl !== baseUrl
      || !Array.isArray(value.versions) || !value.versions.length) return false;
  const names = new Set();
  for (const entry of value.versions) {
    if (!entry || typeof entry.name !== 'string'
        || !/^(current|\d+\.\d+\.\d+)$/.test(entry.name) || names.has(entry.name)
        || typeof entry.label !== 'string' || !entry.label
        || typeof entry.docsUrl !== 'string' || !entry.docsUrl.startsWith(baseUrl)
        || !entry.docsUrl.endsWith('/')
        || !relativeRoute(entry.docsUrl.slice(baseUrl.length))
        || typeof entry.javadocUrl !== 'string' || !entry.javadocUrl.startsWith(`${baseUrl}api/`)
        || !relativeRoute(entry.javadocUrl.slice(baseUrl.length))
        || !Array.isArray(entry.routes) || !entry.routes.every(relativeRoute)) return false;
    names.add(entry.name);
  }
  return value.latestVersion === null || names.has(value.latestVersion);
}

function directoryRoute(path) {
  // The site uses trailingSlash:true; development metadata can omit that slash.
  return path.endsWith('/') ? path : `${path}/`;
}

function versionTarget(destination, active, location) {
  const pathname = directoryRoute(location.pathname);
  const relative = active && pathname.startsWith(active.docsUrl)
    ? pathname.slice(active.docsUrl.length) : null;
  const matched = relative !== null && destination.routes.includes(relative);
  return `${destination.docsUrl}${matched ? relative : ''}${location.search}${matched ? location.hash : ''}`;
}

function withCompiledRoutes(catalog, versions) {
  return {...catalog, versions: catalog.versions.map(entry => {
    const compiled = versions.find(version => version.name === entry.name);
    if (!compiled) return entry;
    // These are Docusaurus permalinks, including custom slugs and generated indexes.
    const routes = compiled.docs.map(doc => directoryRoute(doc.path))
      .filter(path => path.startsWith(entry.docsUrl))
      .map(path => path.slice(entry.docsUrl.length))
      .filter(relativeRoute);
    return {...entry, routes: [...new Set(routes)]};
  })};
}

export {relativeRoute, validCatalog, versionTarget, withCompiledRoutes};
