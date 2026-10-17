const release = require('./release-manifest.json');
const fs = require('node:fs');
const path = require('node:path');
const {guideBuild} = require('./src/lib/guide-build');
const selectionPath = path.join(__dirname, 'guide-build.json');
const selection = fs.existsSync(selectionPath) ? JSON.parse(fs.readFileSync(selectionPath, 'utf8')) : undefined;
const guide = guideBuild(release, selection);
const latest = guide.latest;

module.exports = {
  title: 'cs1302-tracer',
  tagline: 'Trace Java execution and inspect memory states',
  url: release.origin,
  baseUrl: guide.baseUrl,
  trailingSlash: true,
  storage: {type: 'localStorage', namespace: true},
  staticDirectories: guide.archive ? ['static'] : ['static', 'generated-api'],
  customFields: {latestDocVersion: latest, documentationRelease: guide.release, guideCatalog: guide.catalog},
  organizationName: 'cs1302uga',
  projectName: 'cs1302-tracer',
  onBrokenLinks: 'throw',
  markdown: {
    format: 'md',
    mermaid: true,
    hooks: { onBrokenMarkdownLinks: 'throw', onBrokenMarkdownImages: 'throw' },
  },
  themes: ['@docusaurus/theme-mermaid'],
  presets: [['classic', {
    docs: {
      path: '../docs',
      routeBasePath: '/',
      sidebarPath: require.resolve('./sidebars.js'),
      lastVersion: latest || 'current',
      onlyIncludeVersions: guide.included.map(version => version.name),
      includeCurrentVersion: guide.included.some(version => version.name === 'current'),
      versions: Object.fromEntries(guide.included.map(version => [version.name, {
        label: version.label, path: guide.archive ? '' : version.docsPath, banner: version.banner,
      }])),
    },
    blog: false,
    pages: guide.archive ? false : {path: 'src/pages', exclude: latest ? [] : ['**/index.jsx']},
  }]],
  themeConfig: {
    announcementBar: latest ? undefined : {
      id: 'development-only',
      content: 'Development documentation from main. Versioned documentation begins with the next prepared release.',
      isCloseable: false,
    },
    navbar: {
      title: 'cs1302-tracer',
      items: [
        {type: 'docSidebar', sidebarId: 'docs', label: 'Documentation', position: 'left'},
        {type: 'custom-apiReference', label: 'API Reference', position: 'left'},
        {type: 'custom-guideVersions', position: 'right'},
        {href: 'https://github.com/cs1302uga/cs1302-tracer', label: 'GitHub', position: 'right'},
      ],
    },
    footer: {
      style: 'dark',
      copyright: 'Copyright © 2024–present Michael E. Cotterell and the University of Georgia. MIT licensed.',
    },
    prism: {additionalLanguages: ['java', 'bash', 'json']},
  },
};
