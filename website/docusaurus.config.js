const release = require('./release-manifest.json');
const latest = release.latestVersion;

module.exports = {
  title: 'cs1302-tracer',
  tagline: 'Trace Java execution and inspect memory states',
  url: release.origin,
  baseUrl: release.baseUrl,
  trailingSlash: true,
  staticDirectories: ['static', 'generated-api'],
  customFields: {latestDocVersion: latest, documentationRelease: release},
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
      onlyIncludeVersions: release.versions.map(version => version.name),
      versions: Object.fromEntries(release.versions.map(version => [version.name, {
        label: version.label, path: version.docsPath, banner: version.banner,
      }])),
    },
    blog: false,
    pages: latest ? {path: 'src/pages'} : false,
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
        {type: 'docsVersionDropdown', position: 'right'},
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
