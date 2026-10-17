import {useEffect, useMemo, useState} from 'react';
import {useVersions} from '@docusaurus/plugin-content-docs/client';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {validCatalog, withCompiledRoutes} from '../lib/guide-catalog.mjs';

export default function useGuideCatalog() {
  const {siteConfig} = useDocusaurusContext();
  const fallback = siteConfig.customFields.guideCatalog;
  const versions = useVersions();
  const [catalog, setCatalog] = useState(null);
  useEffect(() => {
    const controller = new AbortController();
    fetch(`${fallback.baseUrl}guide-catalog.json`, {signal: controller.signal})
      .then(response => response.ok ? response.json() : null)
      .then(value => {
        if (!controller.signal.aborted && validCatalog(value, fallback.baseUrl)) setCatalog(value);
      })
      // Offline previews and unavailable catalogs retain the embedded links.
      .catch(() => {});
    return () => controller.abort();
  }, [fallback]);
  // Local metadata works during SSR, offline previews, and the live development server.
  // A fetched catalog supplies routes for versions compiled in other applications.
  return useMemo(() => withCompiledRoutes(catalog || fallback, versions), [catalog, fallback, versions]);
}
