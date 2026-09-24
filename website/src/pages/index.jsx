import React from 'react';
import Head from '@docusaurus/Head';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

// Enabled only after a stable documentation release exists.
export default function StableLanding() {
  const {siteConfig} = useDocusaurusContext();
  const version = siteConfig.customFields.latestDocVersion;
  const target = `${siteConfig.baseUrl}${version}/`;
  return (
    <>
      <Head>
        <title>cs1302-tracer documentation</title>
        <meta httpEquiv="refresh" content={`0;url=${target}`} />
        <link rel="canonical" href={`${siteConfig.url}${target}`} />
      </Head>
      <main>
        <a href={target}>Latest release documentation ({version})</a>
      </main>
    </>
  );
}
