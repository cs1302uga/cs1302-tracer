# Documentation maintenance and releases

Choose the guide for the task you need to complete. Current documentation lives
in `docs/` and is rendered by **Docusaurus**; retained snapshots describe their
published releases. README introduces the project, and HACKING is the contributor
entry point.

| Task | Guide |
| --- | --- |
| Edit prose, executable examples, or CLI documentation | [Author documentation](documentation-authoring.md) |
| Build, preview, select versions, or inspect cache timing | [Build and preview documentation](documentation-builds.md) |
| Publish or correct archived guide applications | [Maintain guide archives](guide-archives.md) |
| Maintain verified release API artifacts | [Maintain **Javadoc** artifacts](javadoc-artifacts.md) |
| Choose versions, prepare, publish, or recover a software release | [Prepare and publish releases](releases.md) |
| Download API documentation for offline use | [Read the API documentation offline](../guides/offline-javadoc.md) |

The section links below preserve bookmarks to the former combined runbook.
Each points to its current instructions.

## Local checks

[Build and preview the site, generate API references, and run local checks.](documentation-builds.md#local-checks)

## Archived guide applications

[Understand active guide selection, durable archives, and provenance requirements.](guide-archives.md#archived-guide-applications)

### Roll out archived guides

[Build, verify, publish, or replace a reviewed guide archive.](guide-archives.md#roll-out-archived-guides)

### Compatibility with existing software releases

[Check historical asset availability and backfill boundaries.](guide-archives.md#compatibility-with-existing-software-releases)

## Published Javadoc artifacts

[Maintain API artifacts, verify provenance, and preserve published bytes on retries.](javadoc-artifacts.md#published-javadoc-artifacts)

## Documentation build cache and timing

[Configure generated-output caching and interpret build-stage timing.](documentation-builds.md#documentation-build-cache-and-timing)

### Compare cold and warm builds

[Measure equivalent builds with controlled cache conditions.](documentation-builds.md#compare-cold-and-warm-builds)

## Prepare a stable release

[Use the signed release workflow and its supported command interface.](releases.md#prepare-a-stable-release)

### Select versions and prepare documentation

[Classify compatibility, choose versions, reconcile the changelog, and prepare snapshots.](releases.md#select-versions-and-prepare-documentation)

### Prepare the signed release locally

[Configure signing and prepare the verified version-transition commits and tag.](releases.md#prepare-the-signed-release-locally)

## Publishing and retrying

[Validate the actual release commits, publish authorized refs, and verify assets and live docs.](releases.md#publishing-and-retrying)

### Recover according to the recorded and remote state

[Inspect local and remote evidence before resuming or repairing a release.](releases.md#recover-according-to-the-recorded-and-remote-state)

## Release rehearsal

[Exercise release or documentation orchestration in a disposable environment.](releases.md#release-rehearsal)

## Repository rollout

[Check repository settings, deployment permissions, and the rendered site.](documentation-builds.md#repository-rollout)
