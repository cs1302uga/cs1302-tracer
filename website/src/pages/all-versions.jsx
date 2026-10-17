import React from 'react';
import Layout from '@theme/Layout';
import useGuideCatalog from '../components/useGuideCatalog';

// Live development serves this page; completed sites replace it with a static portal.
export default function AllVersions() {
  const catalog = useGuideCatalog();
  return (
    <Layout title="Documentation versions" description="Guides and API references for every available Code Tracer version.">
      <main className="container margin-vert--lg">
        <h1>Documentation versions</h1>
        <p>Choose documentation matching the version of Code Tracer you are using.</p>
        <p><a href={catalog.baseUrl}>Latest documentation</a></p>
        <table>
          <thead><tr><th scope="col">Version</th><th scope="col">Guides</th><th scope="col">API reference</th></tr></thead>
          <tbody>
            {catalog.versions.map(entry => (
              <tr key={entry.name}>
                <th scope="row">{entry.label}{entry.name === catalog.latestVersion ? ' (latest release)' : ''}</th>
                <td><a href={entry.docsUrl}>Read guides for {entry.label}</a></td>
                <td><a href={entry.javadocUrl}>Read API reference for {entry.label}</a></td>
              </tr>
            ))}
          </tbody>
        </table>
      </main>
    </Layout>
  );
}
