import React from 'react';
import {useActiveVersion, useLatestVersion} from '@docusaurus/plugin-content-docs/client';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import DefaultNavbarItem from '@theme/NavbarItem/DefaultNavbarItem';

export default function ApiReference(props) {
  const active = useActiveVersion();
  const latest = useLatestVersion();
  const version = (active || latest).name;
  const {siteConfig} = useDocusaurusContext();
  const href = siteConfig.customFields.documentationRelease.versions
    .find(entry => entry.name === version).javadocUrl;
  // Javadoc is static HTML outside the React router; use a full-page navigation.
  return <DefaultNavbarItem {...props} to={`pathname://${href}`} target="_self" autoAddBaseUrl={false} />;
}
