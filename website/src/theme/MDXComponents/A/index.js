const React = require('react');
const OriginalA = require('@theme-original/MDXComponents/A').default;

module.exports = function A(props) {
  const href = props.href;
  // Markdown file assets need file URLs; route rewriting adds a trailing slash.
  const fileAsset = typeof href === 'string'
    && href.startsWith('/') && href.includes('/assets/files/');
  return React.createElement(OriginalA, {
    ...props,
    href: fileAsset ? `pathname://${href}` : href,
  });
};
