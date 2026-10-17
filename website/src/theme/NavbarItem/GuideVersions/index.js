import React from 'react';
import {useActiveVersion, useLatestVersion} from '@docusaurus/plugin-content-docs/client';
import {useLocation} from '@docusaurus/router';
import DropdownNavbarItem from '@theme/NavbarItem/DropdownNavbarItem';
import useGuideCatalog from '../../../components/useGuideCatalog';
import {versionTarget} from '../../../lib/guide-catalog.mjs';

export default function GuideVersions(props) {
  const activeVersion = useActiveVersion();
  const latestVersion = useLatestVersion();
  const version = (activeVersion || latestVersion).name;
  const catalog = useGuideCatalog();
  const location = useLocation();
  const active = catalog.versions.find(entry => entry.name === version);
  // A full-document load crosses the independent routers of active and archived guides.
  const link = (label, href) => ({label, to: `pathname://${href}`, target: '_self', autoAddBaseUrl: false});
  const items = [
    ...catalog.versions.map(entry => link(entry.label, versionTarget(entry, active, location))),
    link('Latest documentation', catalog.baseUrl),
    link('All versions', `${catalog.baseUrl}all-versions/`),
  ];
  return <DropdownNavbarItem {...props} label={active?.label || version} items={items} />;
}
