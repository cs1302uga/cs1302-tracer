"""Prepare signed Maven releases locally and publish them through checked Git history."""

import argparse
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path

from docs_release import VERSION, Project, maven_version
from release_evidence import ci_passed, publication_verified, repository

ROOT = Path(__file__).resolve().parents[1]
SIGNING_KEYS = ("user.name", "user.email", "user.signingKey", "gpg.format", "gpg.ssh.allowedSignersFile")


def command(root: Path, *args: str) -> str:
    """Run a checked external command without shell interpolation."""
    return subprocess.check_output(args, cwd=root, text=True).strip()


def git(root: Path, *args: str) -> str:
    """Read or update Git state with an explicit argument list."""
    return command(root, "git", *args)


def require(condition: bool, message: str) -> None:
    """Reject a release whose evidence does not satisfy the next operation."""
    if not condition:
        raise ValueError(message)


def version_tuple(value: str) -> tuple[int, ...]:
    """Parse a stable three-component version."""
    require(VERSION.fullmatch(value) is not None, f"Expected a stable x.y.z version: {value}")
    return tuple(map(int, value.split(".")))


def check_versions(current: str, previous: str, release: str, development: str, change: str) -> None:
    """Enforce explicit versions and the declared compatibility classification."""
    old, new = version_tuple(previous), version_tuple(release)
    require(current == release + "-SNAPSHOT", "Prepare from the matching development snapshot")
    require(development.endswith("-SNAPSHOT"), "Next development version must end in -SNAPSHOT")
    require(version_tuple(development.removesuffix("-SNAPSHOT")) > new, "Next development version must increase")
    require(new > old, "Release version must exceed the latest published stable release")
    minimum = {"major": 0, "minor": 1, "patch": 2}[change]
    changed = next(index for index, (before, after) in enumerate(zip(old, new, strict=True)) if before != after)
    require(changed <= minimum, f"A {change} change requires at least a {change} version increment")


def clean(root: Path) -> None:
    """Require committed sources, including untracked files."""
    require(not git(root, "status", "--porcelain"), "Release sources must be clean, including untracked files")


def remote_ref(root: Path, ref: str) -> str:
    """Read exactly one remote ref without following annotated tags."""
    output = git(root, "ls-remote", "--refs", "origin", ref)
    return output.split()[0] if output else ""


def origin(root: Path) -> str:
    """Require one identical effective fetch and push destination."""
    fetch = git(root, "remote", "get-url", "--all", "origin")
    require("\n" not in fetch, "Release origin must have exactly one fetch URL")
    require(
        git(root, "remote", "get-url", "--push", "--all", "origin") == fetch,
        "Release origin must have the same single fetch and push URL",
    )
    return fetch


def push(root: Path, source: str, destination: str) -> None:
    """Push one ref normally, preventing implicit annotated-tag publication."""
    subprocess.run(["git", "push", "--no-follow-tags", "origin", f"{source}:{destination}"], cwd=root, check=True)


def pom_at(root: Path, revision: str) -> str:
    """Read the project version from a specific committed tree."""
    return maven_version(git(root, "show", f"{revision}:pom.xml"))


def pom_shape(root: Path, revision: str) -> str:
    """Compare the POM while permitting only Maven's version and SCM-tag rewrites."""
    element = ET.fromstring(git(root, "show", f"{revision}:pom.xml"))
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    for path in ("m:version", "m:scm/m:tag"):
        node = element.find(path, namespace)
        require(node is not None, f"Missing release-owned POM element: {path}")
        assert node is not None
        node.text = "RELEASE_OWNED"
    return ET.canonicalize(ET.tostring(element, encoding="unicode"), strip_text=True)


def main_integrated(root: Path, state: Release, commit: str) -> bool:
    """Accept later main commits only when they preserve the prepared development commit."""
    if commit == state.base:
        return False
    git(root, "fetch", "origin", "main")
    ancestor = git(root, "merge-base", state.development_commit, commit)
    require(ancestor == state.development_commit, "Remote main changed; inspect and reprepare")
    return True


def signing(root: Path) -> dict[str, str]:
    """Require explicit SSH signing identity and a preexisting trusted signers file."""
    settings = {key: git(root, "config", "--get", key) for key in SIGNING_KEYS}
    require(settings["gpg.format"] == "ssh", "Release signing requires the agreed SSH signing configuration")
    trusted = Path(settings["gpg.ssh.allowedSignersFile"]).expanduser()
    if not trusted.is_absolute():
        trusted = root / trusted
    require(trusted.is_file(), "Configure gpg.ssh.allowedSignersFile with trusted release signers")
    settings["gpg.ssh.allowedSignersFile"] = str(trusted.resolve())
    key = settings["user.signingKey"]
    if not key.startswith("key::"):
        key_path = Path(key).expanduser()
        settings["user.signingKey"] = str((root / key_path).resolve())
    return settings


@dataclass
class Release:
    """Persist immutable release coordinates and resumable local preparation state."""

    version: str
    development: str
    previous: str
    change: str
    base: str
    origin: str
    branch: str
    tag: str
    phase: str = "rehearsal"
    release_commit: str = ""
    development_commit: str = ""
    tag_object: str = ""

    def save(self, root: Path) -> None:
        """Keep the release report outside tracked sources and Maven clean output."""
        path = root / ".git/code-tracer-release.json"
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(asdict(self), indent=2) + "\n")
        temporary.replace(path)

    @classmethod
    def load(cls, root: Path) -> Release:
        """Load a report produced by prepare."""
        return cls(**json.loads((root / ".git/code-tracer-release.json").read_text()))

    def maven(self, root: Path, *, dry_run: bool = False, resume: bool = False) -> None:
        """Invoke the pinned plugin, retaining diagnostics outside target."""
        args = [
            "./mvnw",
            "-B",
            "-ntp",
            "release:prepare",
            f"-DreleaseVersion={self.version}",
            f"-DdevelopmentVersion={self.development}",
            f"-Dtag={self.tag}",
            "-DpushChanges=false",
            "-DsignTag=true",
            f"-Dresume={str(resume).lower()}",
        ]
        if dry_run:
            # Maven simulates POM rewrites but executes these goals against the original snapshot.
            args += ["-DdryRun=true", "-Drelease.preparationGoals=validate", "-Drelease.preparationProfiles="]
        logs = root / ".git/release-logs"
        logs.mkdir(exist_ok=True)
        log = logs / f"{self.phase}-{len(list(logs.iterdir())) + 1}.log"
        print(f"Running {'POM/SCM rehearsal' if dry_run else 'release preparation'}; log: {log}", flush=True)
        with log.open("w") as output:
            subprocess.run(args, cwd=root, stdout=output, stderr=subprocess.STDOUT, check=True)

    def finish(self, root: Path) -> None:
        """Record and verify both signed commits and the signed annotated tag."""
        self.release_commit = git(root, "rev-parse", f"{self.tag}^{{commit}}")
        self.development_commit = git(root, "rev-parse", "HEAD")
        self.tag_object = git(root, "rev-parse", f"refs/tags/{self.tag}")
        self.verify(root)
        self.phase = "prepared"
        self.save(root)

    def verify(self, root: Path) -> None:
        """Verify the exact local graph, version transitions, signatures, and clean checkout."""
        clean(root)
        require(origin(root) == self.origin, "Release remote changed")
        require(git(root, "branch", "--show-current") == self.branch, "Release branch changed")
        require(git(root, "rev-parse", "HEAD") == self.development_commit, "Release checkout HEAD changed")
        require(git(root, "rev-parse", f"{self.release_commit}^") == self.base, "Release base changed")
        require(
            git(root, "rev-parse", f"{self.development_commit}^") == self.release_commit,
            "Expected exactly the release and next-development commits",
        )
        for before, after in ((self.base, self.release_commit), (self.release_commit, self.development_commit)):
            require(
                git(root, "diff", "--name-only", before, after) == "pom.xml",
                "Version-transition commits must change only pom.xml",
            )
            require(pom_shape(root, before) == pom_shape(root, after), "Version transition changed POM configuration")
        require(pom_at(root, self.release_commit) == self.version, "Release commit has the wrong Maven version")
        require(pom_at(root, self.development_commit) == self.development, "Next-development Maven version changed")
        require(git(root, "rev-parse", f"refs/tags/{self.tag}") == self.tag_object, "Release tag object changed")
        require(git(root, "rev-parse", f"{self.tag}^{{commit}}") == self.release_commit, "Release tag target changed")
        for commit in (self.release_commit, self.development_commit):
            git(root, "verify-commit", commit)
        git(root, "verify-tag", self.tag)


def prepare(source: Path, directory: Path, version: str, development: str, change: str) -> Release:
    """Create an isolated signed release without pushing any remote refs."""
    clean(source)
    require(not directory.exists(), "Release directory must not already exist")
    remote = origin(source)
    settings = signing(source)
    git(source, "fetch", "origin", "main", "--tags")
    base = git(source, "rev-parse", "origin/main")
    require(git(source, "rev-parse", "HEAD") == base, "Prepare from the current origin/main commit")
    releases = json.loads(
        command(
            source,
            "gh",
            "api",
            "--hostname",
            "github.com",
            "--paginate",
            "--slurp",
            f"repos/{repository(source)}/releases?per_page=100",
        )
    )
    published = [
        record["tag_name"].removeprefix("v")
        for page in releases
        for record in page
        if not record["draft"] and not record["prerelease"] and VERSION.fullmatch(record["tag_name"].removeprefix("v"))
    ]
    require(bool(published), "Cannot determine the latest published stable release")
    previous = max(published, key=version_tuple)
    check_versions(Project(source).project_version(), previous, version, development, change)
    require(not git(source, "tag", "--list", version, "v" + version), "Target release tag already exists")
    Project(source).check(pending=version)
    state = Release(version, development, previous, change, base, remote, "release/" + version, "v" + version)
    require(not remote_ref(source, "refs/heads/" + state.branch), "Remote release branch already exists")
    subprocess.run(["git", "clone", "--no-hardlinks", "--no-checkout", str(source), str(directory)], check=True)
    git(directory, "remote", "set-url", "origin", remote)
    git(directory, "checkout", "-b", state.branch, base)
    for key, value in settings.items():
        git(directory, "config", key, value)
    git(directory, "config", "commit.gpgSign", "true")
    git(directory, "config", "tag.gpgSign", "true")
    # Keep the existing staged-index hook unchanged; do not activate unrelated hooks.
    shutil.copy2(directory / ".githooks/pre-commit", directory / ".git/hooks/pre-commit")
    git(directory, "config", "core.hooksPath", str(directory / ".git/hooks"))
    state.save(directory)
    state.maven(directory, dry_run=True)
    subprocess.run(["./mvnw", "-B", "-ntp", "release:clean"], cwd=directory, check=True)
    state.phase = "preparing"
    state.save(directory)
    state.maven(directory)
    state.finish(directory)
    return state


def resume(root: Path) -> Release:
    """Resume only an interrupted real preparation in its isolated checkout."""
    state = Release.load(root)
    require(state.phase == "preparing", "Only interrupted real preparation can be resumed")
    require(origin(root) == state.origin, "Release remote changed")
    require(git(root, "branch", "--show-current") == state.branch, "Release branch changed")
    require(not remote_ref(root, "refs/heads/" + state.branch), "Preparation branch has already been pushed")
    require(not remote_ref(root, "refs/tags/" + state.tag), "Release tag has already been pushed")
    state.maven(root, resume=True)
    state.finish(root)
    return state


def publish(root: Path) -> bool:
    """Advance only verified remote states; return false while CI or deployment is pending."""
    state = Release.load(root)
    require(state.phase in ("prepared", "publishing", "published"), "Complete preparation before publishing")
    state.verify(root)
    main = remote_ref(root, "refs/heads/main")
    integrated = main_integrated(root, state, main)
    tag = remote_ref(root, "refs/tags/" + state.tag)
    require(tag in ("", state.tag_object), "Remote release tag differs from the verified local tag")
    branch_ref = "refs/heads/" + state.branch
    branch = remote_ref(root, branch_ref)
    require(branch in ("", state.release_commit, state.development_commit), "Remote release branch changed")
    require(not tag or integrated, "Remote tag exists before main integration")
    state.phase = "publishing"
    state.save(root)
    if not branch:
        push(root, state.release_commit, branch_ref)
        branch = state.release_commit
    if not ci_passed(root, state.branch, state.release_commit):
        print("Pending: CI for the stable release commit. Rerun publish when it completes.")
        return False
    if branch == state.release_commit:
        push(root, state.development_commit, branch_ref)
    if not ci_passed(root, state.branch, state.development_commit):
        print("Pending: CI for the next-development commit. Rerun publish when it completes.")
        return False
    # Re-read after the external checks; a normal push also rejects concurrent divergence.
    main = remote_ref(root, "refs/heads/main")
    if not main_integrated(root, state, main):
        push(root, state.development_commit, "refs/heads/main")
    require(main_integrated(root, state, remote_ref(root, "refs/heads/main")), "Remote main verification failed")
    if not tag:
        push(root, "refs/tags/" + state.tag, "refs/tags/" + state.tag)
    require(remote_ref(root, "refs/tags/" + state.tag) == state.tag_object, "Remote tag verification failed")
    if not publication_verified(root, state.version, state.release_commit):
        print("Pending: published release assets and documentation deployment. Rerun publish to verify.")
        return False
    state.phase = "published"
    state.save(root)
    print(f"Verified published release {state.tag} at {state.release_commit}.")
    return True


def ci(root: Path, branch: str, output: Path) -> None:
    """Validate release-branch shape and expose an immutable pending-docs commit to CI."""
    project = Project(root)
    current = project.project_version()
    if branch == "main":
        missing = [
            version
            for version in project.versions()
            if not git(root, "tag", "--list", version, "v" + version) and version != current.removesuffix("-SNAPSHOT")
        ]
        if not missing:
            with output.open("a") as destination:
                destination.write("pending_commit=\n")
            return
        require(len(missing) == 1, "Main has multiple untagged historical snapshots")
        version = missing[0]
    else:
        require(branch.startswith("release/"), "Expected main or a release/x.y.z branch")
        version = branch.removeprefix("release/")
    version_tuple(version)
    pending = ""
    if current != version:
        require(current.endswith("-SNAPSHOT"), "Release branch contains an unexpected Maven version")
        require(
            version_tuple(current.removesuffix("-SNAPSHOT")) > version_tuple(version),
            "Next-development version must follow the release",
        )
        pending = git(root, "rev-parse", "HEAD^")
        require(pom_at(root, pending) == version, "Next-development parent must be the stable release commit")
        Project(root).plan("preview", pending_commit=pending)
    else:
        Project(root).check(tag="v" + version)
    with output.open("a") as destination:
        destination.write(f"pending_commit={pending}\n")


def main() -> int:
    """Run an explicit preparation, recovery, publication, report, or CI operation."""
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    prep = sub.add_parser("prepare", help="Create a local release in a new isolated checkout")
    prep.add_argument("--source", type=Path, default=ROOT)
    prep.add_argument("--directory", type=Path, required=True)
    prep.add_argument("--release-version", required=True)
    prep.add_argument("--development-version", required=True)
    prep.add_argument("--change-kind", choices=("major", "minor", "patch"), required=True)
    for action in ("resume", "publish", "status"):
        sub.add_parser(action).add_argument("--directory", type=Path, required=True)
    check = sub.add_parser("ci")
    check.add_argument("--source", type=Path, default=ROOT)
    check.add_argument("--branch", required=True)
    check.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.action == "prepare":
            state = prepare(
                args.source.resolve(),
                args.directory.resolve(),
                args.release_version,
                args.development_version,
                args.change_kind,
            )
            print(json.dumps(asdict(state), indent=2))
        elif args.action == "resume":
            print(json.dumps(asdict(resume(args.directory.resolve())), indent=2))
        elif args.action == "publish":
            return 0 if publish(args.directory.resolve()) else 3
        elif args.action == "status":
            print(json.dumps(asdict(Release.load(args.directory.resolve())), indent=2))
        else:
            ci(args.source.resolve(), args.branch, args.github_output)
    except (ValueError, OSError, subprocess.CalledProcessError, AssertionError) as error:
        parser.exit(1, f"Release stopped: {error}\nInspect the report and .git/release-logs before recovery.\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
