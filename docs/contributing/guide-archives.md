# Retained guide maintenance

Retained release prose lives in `docs/releases/VERSION/`. The current Maven Site
renderer builds every selected guide locally and publishes it under
`docs/VERSION/`. Software identity comes from the verified release tag; builder
identity comes from the current checkout and renderer. Frozen historical prose
does not become current simply because its rendering changes.

## Archived guide applications

The former Docusaurus ZIP applications, archive catalog, active/all compilation
modes and archive-only publisher are retired. Builds no longer download or compose
guide archives. Private backups of the historical publications retain rollback
evidence and are not required build inputs. Optional Javadoc caching remains independent.

`src/site/versions.json` owns the retained version list. Each guide has a matching
`src/site/releases/VERSION-navigation.json`. `src/site/release-provenance.json`
pins its software commit and source/navigation digest, preserving release-retention
and retarget protection independently from the retired archive transport.

### Roll out archived guides

For a reviewed historical correction, change only the affected release's prose
and navigation, explain why that correction applies to that release, and update
its provenance digest after reviewing the exact bytes. Retain its software commit;
never move a published software tag. Keep current documentation separate.
Run the metadata, complete site and rendered link checks, and compare old deep
links and fragments. The `/VERSION/` and `/next/` compatibility redirects and
legacy downloadable JSON mapping in `src/site/legacy-assets.json` remain part of
the site contract. Hash changes to legacy assets fail assembly.

### Compatibility with existing software releases

Old software tags need no Maven Site configuration. Their frozen guides use the
current renderer while their Javadoc continues to use verified release artifacts
or the supported exact tagged-source fallback. Deployment's published-release
selection must retain every pinned software release and commit. Missing or
reclassified release records fail publication instead of silently dropping guides.

## Retired publication URLs and future release pins

The old `docs-*` release pages and guide ZIP download URLs are retired publication
interfaces. The three documentation archive release entries and their 27 assets
were removed on 2026-11-08 after verifying the Maven-site cutover. Private rollback
backups are separate from the live site. Software releases and their assets are
unchanged. During rendering, `src/site/retired-publications.json` maps only
the three reviewed documentation-release page URLs to their retained Git tag
trees. The tags remain available, and frozen Markdown keeps its original bytes.
Old guide ZIP download URLs are no longer supported.

New software releases do not automatically edit the provenance catalog. After
publication, add the new version to `src/site/release-provenance.json` in a reviewed
follow-up change. Record its exact `vVERSION^{commit}` as `releaseCommit`, the same
commit as the initial `documentationCommit`, and the exact snapshot/navigation
digest returned by `Project(Path.cwd()).snapshot_digest("VERSION")` from
`scripts/docs_release.py`. Verify that the frozen snapshot matches that release's
committed snapshot before pinning it. Run metadata and full site checks, then
review the catalog change with the release evidence. Existing pins continue to
reject deletion, reclassification and retargeting throughout this process. The
version list independently preserves all prepared snapshots in Git.
