"""Freeze signed Central bundles and resume publication without rebuilding uploaded bytes."""

import argparse
import base64
import hashlib
import io
import json
import os
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from javadoc_artifact import identity, validate_archive
from javadoc_publish import github

BUNDLE = "central-bundle.zip"
STATE = "central-deployment.json"
API = "https://central.sonatype.com/api/v1/publisher/"
REPOSITORY = "https://repo.maven.apache.org/maven2/"
MAX_SIZE = 128 * 1024 * 1024


def required(root: Path, commit: str) -> bool:
    """Use the tagged capability marker so historical releases retain their original contract."""
    pom = subprocess.check_output(["git", "show", commit + ":pom.xml"], cwd=root)
    marker = ET.fromstring(pom).find("./{*}properties/{*}release.central.schema")
    if marker is None:
        return False
    if marker.text != "1":
        raise ValueError("Unsupported Central publication schema")
    return True


def contents(path: Path, version: str, commit: str, pom: bytes) -> dict[str, bytes]:
    """Validate the exact bundle inventory, checksums, coordinates, and tagged library identity."""
    identity(version, commit)
    prefix = f"io/github/cs1302uga/cs1302-tracer/{version}/cs1302-tracer-{version}"
    artifacts = [prefix + suffix for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar")]
    expected = {name + suffix for name in artifacts for suffix in ("", ".asc", ".md5", ".sha1", ".sha256", ".sha512")}
    if path.stat().st_size > MAX_SIZE:
        raise ValueError("Central bundle exceeds size limit")
    with zipfile.ZipFile(path) as archive:
        entries = [entry for entry in archive.infolist() if not entry.is_dir()]
        if {entry.filename for entry in entries} != expected or len(entries) != len(expected):
            raise ValueError("Central bundle must contain only the four signed Maven artifacts and checksums")
        if sum(entry.file_size for entry in entries) > MAX_SIZE:
            raise ValueError("Central bundle exceeds expansion limit")
        files = {entry.filename: archive.read(entry) for entry in entries}
    if files[prefix + ".pom"] != pom:
        raise ValueError("Central POM differs from the tagged POM")
    tree = ET.fromstring(pom)
    if [tree.findtext("{*}" + field) for field in ("groupId", "artifactId", "version")] != [
        "io.github.cs1302uga",
        "cs1302-tracer",
        version,
    ]:
        raise ValueError("Central coordinates differ from the stable release")
    for name in artifacts:
        if not files[name + ".asc"].startswith(b"-----BEGIN PGP SIGNATURE-----"):
            raise ValueError("Missing armored PGP signature")
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            if files[name + "." + algorithm].decode().strip() != hashlib.new(algorithm, files[name]).hexdigest():
                raise ValueError("Central bundle checksum mismatch")
    with zipfile.ZipFile(io.BytesIO(files[prefix + ".jar"])) as jar:
        metadata = jar.read("cs1302/tracer/version.properties").decode()
    values = dict(line.split("=", 1) for line in metadata.splitlines())
    if any(
        values.get(key) != value
        for key, value in {"version": version, "git.commit": commit, "git.dirty": "false"}.items()
    ):
        raise ValueError("Central library must identify the clean tagged commit")
    return files


def freeze(root: Path, version: str, commit: str, repository: str) -> None:
    """Retain the first bundle in the draft release and restore its shared artifact bytes on retries."""
    identity(version, commit)
    target = "github.com/" + repository
    tag = "v" + version
    tags = github(
        root, "api", "--hostname", "github.com", "--paginate", f"repos/{repository}/releases", "--jq", ".[].tag_name"
    ).splitlines()
    if tag not in tags:
        github(
            root,
            "release",
            "create",
            tag,
            "--repo",
            target,
            "--verify-tag",
            "--draft",
            "--title",
            tag,
            "--notes",
            "",
        )
    assets = github(
        root, "release", "view", tag, "--repo", target, "--json", "assets", "--jq", ".assets[].name"
    ).splitlines()
    path = root / "target" / BUNDLE
    if BUNDLE in assets:
        github(
            root,
            "release",
            "download",
            tag,
            "--repo",
            target,
            "--pattern",
            BUNDLE,
            "--dir",
            str(path.parent),
            "--clobber",
        )
    else:
        path.write_bytes((root / "target/central-publishing" / BUNDLE).read_bytes())
    pom = subprocess.check_output(["git", "show", commit + ":pom.xml"], cwd=root)
    files = contents(path, version, commit, pom)
    for name, data in files.items():
        (path.parent / name.rsplit("/", 1)[1]).write_bytes(data)
    for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar"):
        artifact = path.parent / f"cs1302-tracer-{version}{suffix}"
        subprocess.run(["gpg", "--batch", "--verify", str(artifact) + ".asc", str(artifact)], check=True)
    validate_archive(path.parent / f"cs1302-tracer-{version}-javadoc.jar", version, commit)
    if BUNDLE not in assets:
        github(root, "release", "upload", tag, str(path), "--repo", target)


def verify(root: Path, path: Path, version: str, commit: str) -> bool:
    """Compare public Central bytes to the retained bundle, returning false only for propagation delay."""
    pom = subprocess.check_output(["git", "show", commit + ":pom.xml"], cwd=root)
    files = contents(path, version, commit, pom)
    for suffix in (".jar", "-javadoc.jar"):
        name = f"cs1302-tracer-{version}{suffix}"
        if (path.parent / name).read_bytes() != files[f"io/github/cs1302uga/cs1302-tracer/{version}/{name}"]:
            raise ValueError("GitHub and Central shared artifacts differ")
    for name, expected in files.items():
        try:
            with urllib.request.urlopen(REPOSITORY + name, timeout=30) as response:
                actual = response.read(MAX_SIZE + 1)
        except urllib.error.HTTPError as error:
            error.close()
            if error.code == 404:
                return False
            raise
        if actual != expected:
            raise ValueError(f"Published Central bytes differ: {name}")
    return True


def request(endpoint: str, data: bytes, content_type: str = "application/octet-stream") -> bytes:
    """Call the Portal with credentials from the environment, never command arguments or logs."""
    credentials = os.environ["MAVEN_CENTRAL_USERNAME"] + ":" + os.environ["MAVEN_CENTRAL_TOKEN"]
    token = base64.b64encode(credentials.encode()).decode()
    operation = urllib.request.Request(
        API + endpoint, data=data, headers={"Authorization": "Bearer " + token, "Content-Type": content_type}
    )
    with urllib.request.urlopen(operation, timeout=120) as response:
        return response.read(MAX_SIZE)


def publish(root: Path, version: str, commit: str, repository: str) -> None:
    """Persist upload intent and deployment identity; ambiguous interrupted uploads require recovery."""
    path = root / "target" / BUNDLE
    if verify(root, path, version, commit):
        return
    target = "github.com/" + repository
    tag = "v" + version
    state_path = path.parent / STATE
    assets = github(
        root, "release", "view", tag, "--repo", target, "--json", "assets", "--jq", ".assets[].name"
    ).splitlines()
    fingerprint = hashlib.sha256(path.read_bytes()).hexdigest()
    state = {"commit": commit, "version": version, "sha256": fingerprint, "deployment": ""}
    if STATE in assets:
        github(
            root,
            "release",
            "download",
            tag,
            "--repo",
            target,
            "--pattern",
            STATE,
            "--dir",
            str(path.parent),
            "--clobber",
        )
        recorded = json.loads(state_path.read_text())
        if any(recorded.get(key) != state[key] for key in ("commit", "version", "sha256")):
            raise ValueError("Central deployment record does not match the frozen bundle")
        state = recorded
        if not state["deployment"]:
            raise ValueError("Upload outcome unknown; inspect Central and recover the deployment ID before retrying")
    else:
        state_path.write_text(json.dumps(state) + "\n")
        github(root, "release", "upload", tag, str(state_path), "--repo", target)
        boundary = uuid.uuid4().hex
        data = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="{BUNDLE}"\r\n'
            "Content-Type: application/octet-stream\r\n\r\n"
        ).encode()
        data += path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        endpoint = "upload?" + urllib.parse.urlencode(
            {"name": f"cs1302-tracer-{version}-{commit}", "publishingType": "AUTOMATIC"}
        )
        state["deployment"] = str(
            uuid.UUID(request(endpoint, data, "multipart/form-data; boundary=" + boundary).decode().strip())
        )
        state_path.write_text(json.dumps(state) + "\n")
        github(root, "release", "upload", tag, str(state_path), "--repo", target, "--clobber")
    deployment = str(uuid.UUID(state["deployment"]))
    for _ in range(60):
        status = json.loads(request("status?id=" + deployment, b""))
        if status.get("deploymentId") != deployment:
            raise ValueError("Central status identifies a different deployment")
        phase = status["deploymentState"]
        if phase == "PUBLISHED" and verify(root, path, version, commit):
            return
        if phase not in ("PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED"):
            raise ValueError(f"Central deployment requires inspection: {status}")
        time.sleep(30)
    raise TimeoutError("Central publication pending; retry with the saved deployment ID")


def main() -> None:
    """Run a publisher phase on the stable tagged checkout."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("freeze", "publish"))
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--repository", required=True)
    args = parser.parse_args()
    identity(args.version, args.commit)
    {"freeze": freeze, "publish": publish}[args.action](Path.cwd(), args.version, args.commit, args.repository)


if __name__ == "__main__":
    main()
