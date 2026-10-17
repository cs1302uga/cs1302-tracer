"""Verify exact CI revisions and published release artifacts without waiting or publishing."""

import json
import re
import subprocess
import tarfile
import urllib.error
import urllib.request
import zipfile
from typing import TYPE_CHECKING, Any, cast

from javadoc_artifact import requires_artifact, verify_release_files

if TYPE_CHECKING:
    from pathlib import Path

type Record = dict[str, Any]

EXPECTED_JOBS = {
    "Documentation",
    "Scripts quality",
    *(f"Java ({platform}, {version})" for platform in ("ubuntu-latest", "macos-latest") for version in (21, 25)),
    *(f"Windows packaging and launchers (Java {version})" for version in (21, 25)),
}
MANIFEST_URL = "https://cs1302uga.github.io/cs1302-tracer/release-manifest.json"
JAR_MEMBER = "code-tracer/code-tracer.jar"
PROPERTIES_MEMBER = "cs1302/tracer/version.properties"


def gh_json(root: Path, *arguments: str) -> object:
    """Read GitHub CLI JSON, preserving authentication and transport failures."""
    result = subprocess.run(["gh", *arguments], cwd=root, check=True, capture_output=True, text=True, timeout=60)
    return json.loads(result.stdout)


def repository(root: Path) -> str:
    """Resolve the explicit origin, ignoring ambient GitHub repository and host defaults."""
    origin = subprocess.run(
        ["git", "remote", "get-url", "origin"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
        timeout=60,
    ).stdout.strip()
    identity = cast("Record", gh_json(root, "repo", "view", origin, "--json", "nameWithOwner,url"))
    name = identity["nameWithOwner"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", name) or identity["url"] != f"https://github.com/{name}":
        raise ValueError("Release origin must resolve to an explicit github.com repository")
    return name


def ci_passed(root: Path, branch: str, commit: str, *, publisher: bool = False) -> bool:
    """Require the latest branch or tag push run, its matrix, and optionally its publisher."""
    target = "github.com/" + repository(root)
    runs = cast(
        "list[Record]",
        gh_json(
            root,
            "run",
            "list",
            "--workflow",
            "build.yml",
            "--event",
            "push",
            "--branch",
            branch,
            "--commit",
            commit,
            "--limit",
            "100",
            "--json",
            "databaseId,attempt,event,headBranch,headSha,status,conclusion",
            "--repo",
            target,
        ),
    )
    matching = [
        run for run in runs if run["event"] == "push" and run["headBranch"] == branch and run["headSha"] == commit
    ]
    if not matching:
        return False
    latest = max(matching, key=lambda run: (run["databaseId"], run["attempt"]))
    # Fetch the current attempt again: a rerun may have started since listing runs.
    run = cast(
        "Record",
        gh_json(
            root,
            "run",
            "view",
            str(latest["databaseId"]),
            "--json",
            "event,headBranch,headSha,status,conclusion,jobs",
            "--repo",
            target,
        ),
    )
    if (run["event"], run["headBranch"], run["headSha"]) != ("push", branch, commit):
        raise ValueError("CI evidence does not match the exact release branch and commit")
    if run["status"] != "completed":
        return False
    if run["conclusion"] != "success":
        raise ValueError(f"CI run {latest['databaseId']} did not pass: {run['conclusion']}")
    expected = EXPECTED_JOBS | {"release"} if publisher else EXPECTED_JOBS
    jobs = [job for job in run["jobs"] if job["name"] in expected]
    if len(jobs) != len(expected) or {job["name"] for job in jobs} != expected:
        raise ValueError("CI evidence is missing or duplicates required OS/JDK jobs")
    if any(job["status"] != "completed" or job["conclusion"] != "success" for job in jobs):
        raise ValueError("Every required CI job must complete successfully; skipped jobs do not qualify")
    return True


def jar_metadata(path: Path) -> bytes:
    """Read the unambiguous build identity embedded in a release JAR."""
    try:
        with zipfile.ZipFile(path) as jar:
            entries = [entry for entry in jar.infolist() if entry.filename == PROPERTIES_MEMBER]
            if len(entries) != 1:
                raise ValueError(f"Missing or duplicate build metadata in {path.name}")
            return jar.read(entries[0])
    except zipfile.BadZipFile as error:
        raise ValueError(f"Invalid release JAR: {path.name}") from error


def archived_jar(path: Path) -> bytes:
    """Read one regular JAR member without extracting untrusted archive paths."""
    try:
        if path.suffix == ".zip":
            with zipfile.ZipFile(path) as archive:
                entries = [entry for entry in archive.infolist() if entry.filename == JAR_MEMBER]
                if len(entries) != 1:
                    raise ValueError(f"Missing or duplicate executable JAR in {path.name}")
                return archive.read(entries[0])
        with tarfile.open(path) as archive:
            members = [member for member in archive.getmembers() if member.name == JAR_MEMBER]
            if len(members) != 1 or not members[0].isfile():
                raise ValueError(f"Missing, duplicate, or nonregular executable JAR in {path.name}")
            stream = archive.extractfile(members[0])
            assert stream is not None  # isfile() above excludes members without content.
            with stream:
                return stream.read()
    except (zipfile.BadZipFile, tarfile.TarError) as error:
        raise ValueError(f"Invalid release archive: {path.name}") from error


def publication_verified(root: Path, version: str, commit: str) -> bool:
    """Verify published bytes and live documentation, returning false while still pending."""
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("Publication verification requires a stable x.y.z version")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Publication verification requires a full commit SHA")
    tag = "v" + version
    if not ci_passed(root, tag, commit, publisher=True):
        return False
    repository_name = repository(root)
    target = "github.com/" + repository_name
    releases = cast(
        "list[Record]",
        gh_json(
            root,
            "release",
            "list",
            "--limit",
            "100",
            "--json",
            "tagName",
            "--repo",
            target,
        ),
    )
    if not any(release["tagName"] == tag for release in releases):
        return False
    release = cast(
        "Record",
        gh_json(
            root,
            "release",
            "view",
            tag,
            "--json",
            "tagName,isDraft,isPrerelease,publishedAt,assets",
            "--repo",
            target,
        ),
    )
    if release["tagName"] != tag or release["isPrerelease"]:
        raise ValueError("Published release identity or stable-release classification is incorrect")
    if release["isDraft"] or not release["publishedAt"]:
        return False
    library = f"cs1302-tracer-{version}.jar"
    archives = [
        "code-tracer-bin.tar.gz",
        "code-tracer-bin.tar.xz",
        "code-tracer-bin.zip",
    ]
    javadoc = f"cs1302-tracer-{version}-javadoc.jar"
    javadoc_assets = [javadoc, javadoc + ".sha256", javadoc + ".sigstore.json"]
    assets: dict[str, int] = {}
    if not isinstance(release["assets"], list):
        raise ValueError("Release assets must be a list of unique named files")
    for asset in release["assets"]:
        if (
            not isinstance(asset, dict)
            or not isinstance(asset.get("name"), str)
            or not asset["name"]
            or not isinstance(asset.get("size"), int)
            or isinstance(asset["size"], bool)
            or asset["size"] < 0
            or asset["name"] in assets
        ):
            raise ValueError("Release assets must be unique named files with nonnegative sizes")
        assets[asset["name"]] = asset["size"]
    require_javadoc = requires_artifact(root, commit) or any(name in assets for name in javadoc_assets)
    names = ["code-tracer.jar", *archives, library]
    if require_javadoc:
        names.extend(javadoc_assets)
    if any(assets.get(name, 0) <= 0 for name in names):
        return False
    git_path = subprocess.run(
        ["git", "rev-parse", "--git-path", f"release-assets/{version}"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
        timeout=60,
    ).stdout.strip()
    destination = root / git_path
    destination.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "gh",
            "release",
            "download",
            tag,
            "--repo",
            target,
            "--dir",
            str(destination),
            "--clobber",
            *(argument for name in names for argument in ("--pattern", name)),
        ],
        cwd=root,
        check=True,
        timeout=60,
    )
    metadata = jar_metadata(destination / names[0])
    if metadata != jar_metadata(destination / library):
        raise ValueError("Library and executable release JARs contain different build metadata")
    values = dict(line.split("=", 1) for line in metadata.decode().splitlines())
    if any(
        values.get(key) != value
        for key, value in {
            "version": version,
            "git.commit": commit,
            "git.dirty": "false",
        }.items()
    ):
        raise ValueError("Release JAR metadata must identify the expected clean tagged revision")
    executable = (destination / names[0]).read_bytes()
    for name in archives:
        if archived_jar(destination / name) != executable:
            raise ValueError(f"{name} contains a different executable JAR")
    if require_javadoc:
        verify_release_files(root, destination / javadoc, version, commit, repository_name)
    request = urllib.request.Request(MANIFEST_URL, headers={"Cache-Control": "no-cache"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            manifest = json.load(response)
    except urllib.error.HTTPError as error:
        error.close()
        if error.code == 404:
            return False
        raise
    if manifest["mode"] != "published":
        raise ValueError("The live documentation manifest is not a published build")
    entries = [entry for entry in manifest["versions"] if entry["name"] == version]
    if not entries:
        return False
    if len(entries) != 1 or entries[0]["sourceCommit"] != commit or entries[0]["banner"] != "none":
        raise ValueError("Live release documentation does not identify the expected tagged revision")
    return True
