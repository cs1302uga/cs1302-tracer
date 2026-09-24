const fs = require('node:fs');
const path = require('node:path');

const read = (name, fallback) => {
  const file = path.join(__dirname, name);
  return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : fallback;
};
const snapshots = read('versions.json', []);
// Deployment supplies the released subset. Local builds preview every snapshot.
const released = read('published-versions.json', snapshots);
for (const version of released) {
  if (!snapshots.includes(version)) throw new Error(`Missing snapshot: ${version}`);
}
const latest = released[0];

module.exports = {
  title: 'cs1302-tracer',
  tagline: 'Trace Java execution and inspect memory states',
  url: 'https://cs1302uga.github.io',
  baseUrl: '/cs1302-tracer/',
  trailingSlash: true,
  customFields: {latestDocVersion: latest || null},
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
      onlyIncludeVersions: ['current', ...released],
      versions: {
        current: { label: 'Development (main)', path: latest ? 'next' : '/', banner: latest ? 'unreleased' : 'none' },
        ...Object.fromEntries(released.map(version => [version, {
          label: version, path: version, banner: 'none',
        }])),
      },
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
