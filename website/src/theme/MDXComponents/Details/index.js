const React = require('react');
const OriginalDetails = require('@theme-original/MDXComponents/Details').default;

// Keep generated inventory disclosures native; all other Markdown keeps its theme behavior.
module.exports = function Details(props) {
  const native = (props.className || '').split(/\s+/).includes('dependency-details');
  return React.createElement(native ? 'details' : OriginalDetails, props);
};
