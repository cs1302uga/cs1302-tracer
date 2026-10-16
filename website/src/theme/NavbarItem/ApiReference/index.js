import React from 'react';
import {useActiveVersion, useLatestVersion} from '@docusaurus/plugin-content-docs/client';
import useBaseUrl from '@docusaurus/useBaseUrl';
import DefaultNavbarItem from '@theme/NavbarItem/DefaultNavbarItem';

export default function ApiReference(props) {
  const active = useActiveVersion();
  const latest = useLatestVersion();
  const version = (active || latest).name;
  const href = useBaseUrl(`/api/${version === 'current' ? 'next' : version}/index.html`);
  // Javadoc is static HTML outside the React router; use a full-page navigation.
  return <DefaultNavbarItem {...props} href={`pathname://${href}`} target="_self" />;
}
