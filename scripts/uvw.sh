#!/usr/bin/env bash
# uvw.sh -- project-local, version-pinned wrapper for Astral's uv.
#
# Usage: ./uvw.sh [uv arguments...]
#   ./uvw.sh sync --locked
#   ./uvw.sh run python
#   ./uvw.sh run pytest
#   ./uvw.sh self update [VERSION] [--dry-run] [--token TOKEN]
# Symlinks named uv and uvx dispatch to the corresponding cached executable.
# Direct invocation as uvw.sh retains uv behavior.
#
# Defaults, unless the caller explicitly supplies a different uv flag/env value:
#   UV_SYSTEM_PYTHON=0, --project "$SCRIPT_DIR", --managed-python
# --project does not change cwd and has no effect on the uv pip interface.
# A --python version/path does not by itself override the managed-Python policy.
#
# Version precedence: UVW_VERSION, .uv-version beside the resolved wrapper,
# then DEFAULT_UV_VERSION below. Pins must be exact X.Y.Z releases.
# .uv-version is read as data, never sourced; comments/blank lines are allowed.
#
# Environment:
#   UVW_CACHE_DIR     uv/uvx binary cache; default: "$SCRIPT_DIR/.uv".
#                     Relative overrides are relative to the caller's cwd.
#                     This is NOT uv's package cache (UV_CACHE_DIR).
#   UVW_VERSION       Nonempty temporary version override.
#   UVW_SHA256        Optional trusted SHA-256 of the platform release archive.
#                     Unset before a real self update: it pins the OLD archive.
#   UVW_OFFLINE       1/true prevents wrapper downloads. Defaults to UV_OFFLINE.
#   UVW_LOCK_TIMEOUT  Lock wait in seconds; default 120.
#   UVW_GIT_COMMIT    ask (default), never, or always (explicit preauthorization).
#                     ask requires stderr and /dev/tty; it never consumes stdin.
#                     CI/noninteractive runs skip the prompt unless set to always.
#
# Initialization creates "$SCRIPT_DIR/.uv" (also used for wrapper metadata),
# creates the selected cache, and adds /.uv/ to "$SCRIPT_DIR/.gitignore" as needed.
# Existing ignore contents and permissions are retained. Custom cache paths are
# not automatically ignored; add your own rule when an override is inside a repo.
# Git is optional. Already-tracked cache files are never removed from the index.
#
# When uvw.sh is tracked and wrapper files have pending changes, offer to stage
# and commit ONLY uvw.sh and .gitignore. A successful self update also explicitly
# lists .uv-version in the offer if that optional file was updated, to keep pins
# consistent. All working-tree changes in the listed files will be committed;
# unrelated staged changes are excluded. No pushing, identity changes, hook
# bypasses, or signing overrides. Git failures do not undo a successful update.
# No commit offers for help, self-update dry runs/failures, detached HEADs,
# conflicted/in-progress operations, symlinked wrappers/pins, or redirected Git
# repositories/indexes. Declining leaves both working files and index untouched.
#
# Self update runs uv's NATIVE updater against a temporary verified copy, not
# against a shared versioned cache. The resulting version is independently
# checksum-verified/cached before changing the embedded pin and .uv-version.
# Each pin file is replaced atomically, preserving modes and symlinks; the two
# replacements are not one filesystem transaction. Failed updates do not edit
# pins. Help/dry-run may still initialize the cache and .gitignore.
#
# Requirements: Bash 3.2+, Unix utilities; bootstrap also needs curl or GNU wget,
# tar/gzip, and sha256sum or shasum. No installed Python, sudo, or global uv needed.
# Normal bootstrap never executes a downloaded installer. Explicit self update
# DOES run uv's native downloaded installer, isolated with UV_UNMANAGED_INSTALL.
# Checksums fetched from the publisher verify integrity, not independent trust.
# Cached binaries are trusted. Linux and macOS supported; Windows via WSL only.
#
# References:
# https://docs.astral.sh/uv/reference/cli/
# https://docs.astral.sh/uv/reference/installer/
# https://git-scm.com/docs/git-commit

if [ -z "${BASH_VERSION:-}" ]; then
    printf '%s\n' 'uvw: run this script with Bash, not sh.' >&2
    exit 1
fi
if [[ ${BASH_SOURCE[0]} != "$0" ]]; then
    printf '%s\n' 'uvw: execute this script; do not source it.' >&2
    return 1
fi
set -euo pipefail
DEFAULT_UV_VERSION='0.13.1'
RELEASE_BASE='https://releases.astral.sh/github/uv/releases/download'
log() { printf 'uvw: %s\n' "$*" >&2; }
die() { log "$*"; exit 1; }

# Keep the invocation name before resolving the wrapper's symlinks.
case "${0##*/}" in
    uvx) executable_name=uvx ;;
    *)   executable_name=uv ;;
esac

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd -P)" ||
    die 'Cannot determine the wrapper project directory.'
resolve_file() {
    local path=$1 parent links=0
    [[ $path == /* ]] || path="$PWD/$path"
    while [[ -L $path ]]; do
        links=$((links + 1))
        (( links <= 40 )) || die 'Too many symlinks resolving a wrapper file.'
        parent=$(CDPATH='' cd -P -- "$(dirname -- "$path")" && pwd) || return 1
        path=$(readlink "$path") || return 1
        [[ $path == /* ]] || path="$parent/$path"
    done
    parent=$(CDPATH='' cd -P -- "$(dirname -- "$path")" && pwd) || return 1
    printf '%s/%s\n' "$parent" "$(basename -- "$path")"
}
script=$(resolve_file "${BASH_SOURCE[0]}") || die 'Cannot resolve the wrapper.'
script_dir=$(dirname -- "$script")
version_file="$script_dir/.uv-version"
export UV_SYSTEM_PYTHON="${UV_SYSTEM_PYTHON-0}"
project_override=0
policy_override=0
[[ ${UV_PROJECT+x} ]] && project_override=1
case "${UV_NO_PROJECT:-0}" in 1|true|TRUE) project_override=1 ;; esac
if [[ ${UV_MANAGED_PYTHON+x} || ${UV_NO_MANAGED_PYTHON+x} || ${UV_PYTHON_PREFERENCE+x} ]]; then
    policy_override=1
fi
# Inspect only uv's arguments, never arguments belonging to a child program.
# For future value-taking run options not in this table, place wrapper overrides
# before `run` / `tool run`, or use --option=value for the new option.
option_takes_value() {
    case "$1" in
        --project|--directory|--cache-dir|--config-file|--color|--preview-features|\
        --python-preference|--python-fetch|--allow-insecure-host|--trusted-host|\
        --token|-t|--username|-u|--password|--python|-p|--python-version|--python-platform|\
        --extra|--no-extra|--group|--no-group|--only-group|--with|-w|--with-editable|\
        --with-requirements|--env-file|--package|--from|--index|--default-index|\
        --index-url|-i|--extra-index-url|--find-links|-f|--index-strategy|--keyring-provider|\
        --exclude-newer|--exclude-newer-package|--resolution|--prerelease|--fork-strategy|\
        --upgrade-package|-P|--reinstall-package|--link-mode|--config-setting|-C|\
        --config-settings-package|--no-build-isolation-package|--no-build-package|\
        --no-binary-package|--refresh-package|--no-sources-package|--torch-backend|\
        --exclude|--dependency-metadata|--overrides|--constraints|--build-constraints)
            return 0 ;;
        *) return 1 ;;
    esac
}
args=("$@")
self_update_requested=0
update_dry_run=0
show_help=0
cli_offline=0
root_command=''
namespace=''
external_command=0
skip_value=0
if [[ $executable_name == uvx ]]; then
    # uvx is already `uv tool run`; its first positional argument is the tool.
    root_command=uvx
    external_command=1
fi
arg_count=$#
for (( i=0; i<arg_count; i++ )); do
    arg=${args[$i]}
    if (( skip_value )); then skip_value=0; continue; fi
    [[ $arg != -- ]] || break
    case "$arg" in
        --project|--project=*|--no-project) project_override=1 ;;
        --managed-python|--no-managed-python|--python-preference|--python-preference=*) policy_override=1 ;;
        --help|-h|--version|-V) show_help=1 ;;
        --offline) cli_offline=1 ;;
        --dry-run) update_dry_run=1 ;;
    esac
    case "$arg" in
        --*=*) continue ;;
        --*) option_takes_value "$arg" && skip_value=1; continue ;;
        -?*)
            short_options=${arg#-}
            while [[ -n $short_options ]]; do
                short_option="-${short_options:0:1}"
                short_options=${short_options:1}
                case "$short_option" in -h|-V) show_help=1 ;; esac
                if option_takes_value "$short_option"; then
                    [[ -n $short_options ]] || skip_value=1
                    break
                fi
            done
            continue ;;
    esac
    if [[ -z $root_command ]]; then
        root_command=$arg
        case "$arg" in
            self|tool) namespace=$arg ;;
            run|r|x) external_command=1 ;;
            help) show_help=1; break ;;
        esac
    elif [[ -n $namespace ]]; then
        case "$namespace:$arg" in
            self:update) self_update_requested=1 ;;
            self:upgrade) die 'Use "self update"; uvw does not alias "self upgrade".' ;;
            tool:run) external_command=1 ;;
        esac
        namespace=''
    elif (( external_command )); then
        break
    fi
done
# The + expansion handles empty arrays with nounset on Bash 3.2 / 4.3.
(( policy_override )) || args=(--managed-python ${args[@]+"${args[@]}"})
(( project_override )) || args=(--project "$SCRIPT_DIR" ${args[@]+"${args[@]}"})

version=${UVW_VERSION:-}
if [[ -z $version && ( -e $version_file || -L $version_file ) ]]; then
    [[ -f $version_file && -r $version_file ]] || die "Cannot read $version_file"
    while IFS= read -r line || [[ -n $line ]]; do
        line=${line%%#*}
        line="${line#"${line%%[![:space:]]*}"}"
        line="${line%"${line##*[![:space:]]}"}"
        [[ -n $line ]] || continue
        [[ -z $version ]] || die "$version_file must contain exactly one version."
        version=$line
    done < "$version_file"
    [[ -n $version ]] || die "$version_file contains no version."
fi
version=${version:-$DEFAULT_UV_VERSION}
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    die "Invalid uv version '$version'; use an exact release such as 0.13.1."

os=$(uname -s)
arch=$(uname -m)
case "$os:$arch" in
    Darwin:arm64|Darwin:aarch64) target='aarch64-apple-darwin' ;;
    Darwin:x86_64)              target='x86_64-apple-darwin' ;;
    Linux:x86_64|Linux:amd64)   target='x86_64-unknown-linux-musl' ;;
    Linux:aarch64|Linux:arm64)  target='aarch64-unknown-linux-musl' ;;
    Linux:i386|Linux:i486|Linux:i586|Linux:i686)
                               target='i686-unknown-linux-musl' ;;
    Linux:armv7l|Linux:armv8l)  target='armv7-unknown-linux-musleabihf' ;;
    Linux:armv6l)              target='arm-unknown-linux-musleabihf' ;;
    Linux:riscv64)             target='riscv64gc-unknown-linux-musl' ;;
    Linux:ppc64le)             target='powerpc64le-unknown-linux-gnu' ;;
    Linux:s390x)               target='s390x-unknown-linux-gnu' ;;
    *) die "Unsupported platform: $os / $arch" ;;
esac

cache_root=${UVW_CACHE_DIR:-"$SCRIPT_DIR/.uv"}
[[ $cache_root == /* ]] || cache_root="$PWD/$cache_root"
install_dir="$cache_root/$version/$target"

pinned_sha=${UVW_SHA256:-}
if [[ -n $pinned_sha ]]; then
    [[ $pinned_sha =~ ^[[:xdigit:]]{64}$ ]] || die 'UVW_SHA256 must be 64 hex digits.'
    pinned_sha=$(printf '%s' "$pinned_sha" | tr '[:upper:]' '[:lower:]')
fi
case "${UVW_OFFLINE:-${UV_OFFLINE:-0}}" in
    1|true|TRUE) offline=1 ;;
    0|false|FALSE) offline=0 ;;
    *) die 'UVW_OFFLINE / UV_OFFLINE must be 0, 1, false, or true.' ;;
esac
(( cli_offline == 0 )) || offline=1
lock_timeout=${UVW_LOCK_TIMEOUT:-120}
[[ $lock_timeout =~ ^[0-9]{1,5}$ ]] ||
    die 'UVW_LOCK_TIMEOUT must be a nonnegative integer of at most five digits.'
lock_timeout=$((10#$lock_timeout))

git_commit_mode=${UVW_GIT_COMMIT:-ask}
case "$git_commit_mode" in ask|never|always) ;; *) die 'UVW_GIT_COMMIT must be ask, never, or always.' ;; esac
state_dir="$SCRIPT_DIR/.uv"
ignore_file="$SCRIPT_DIR/.gitignore"
[[ ! -L $state_dir ]] || die 'The project .uv state directory must not be a symlink.'
(umask 077; mkdir -p -- "$state_dir" "$cache_root") || die 'Cannot create wrapper state/cache directories.'

git_here() { git --literal-pathspecs -C "$SCRIPT_DIR" "$@"; }
git_environment_safe() {
    [[ -z ${GIT_DIR:-}${GIT_WORK_TREE:-}${GIT_INDEX_FILE:-}${GIT_COMMON_DIR:-} ]]
}
ensure_gitignore() (
    # The lock is inside the ignored local state directory, never in the index.
    lock="$state_dir/gitignore.lock"
    owns_lock=0
    # Invoked by the EXIT trap in this subshell.
    # shellcheck disable=SC2329
    cleanup_ignore() { (( owns_lock == 0 )) || rmdir -- "$lock" 2>/dev/null || :; }
    trap cleanup_ignore EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
    waited=0
    until mkdir -- "$lock" 2>/dev/null; do
        [[ -d $lock ]] || die "Cannot lock $ignore_file"
        (( waited < lock_timeout )) || die "Timed out waiting for $lock; remove only if no wrapper is running."
        sleep 1; waited=$((waited + 1))
    done
    owns_lock=1
    [[ ! -L $ignore_file ]] || die "Refusing to edit symlinked .gitignore: $ignore_file"
    if [[ -e $ignore_file ]]; then
        [[ -f $ignore_file && -r $ignore_file ]] || die "Cannot read $ignore_file"
        if grep -Eq $'^/?\\.uv/?\r?$' "$ignore_file"; then
            # Git detects whether a later negation overrides an existing entry.
            if ! command -v git >/dev/null 2>&1 || ! git_environment_safe ||
                ! git_here rev-parse --is-inside-work-tree >/dev/null 2>&1; then
                exit 0
            fi
            if git --no-literal-pathspecs -C "$SCRIPT_DIR" check-ignore --no-index -q -- .uv/; then exit 0; fi
        fi
    fi
    # Append without truncation. Preserve prior bytes, adding a missing newline.
    [[ ! -e $ignore_file || -w $ignore_file ]] || die "Cannot write $ignore_file"
    if [[ -s $ignore_file && -n $(tail -c 1 -- "$ignore_file") ]]; then
        printf '\n' >> "$ignore_file" || die "Cannot append to $ignore_file"
    fi
    printf '/.uv/\n' >> "$ignore_file" || die "Cannot create/update $ignore_file"
    log "Added /.uv/ to $ignore_file"
)

pin_file_updated=0
offer_git_commit() (
    [[ $git_commit_mode != never ]] || return 0
    command -v git >/dev/null 2>&1 || return 0
    git_environment_safe || { log 'Git commit offer skipped: repository/index environment overrides are set.'; return 0; }
    [[ -f $SCRIPT_DIR/uvw.sh && ! -L $SCRIPT_DIR/uvw.sh && $script == "$SCRIPT_DIR/uvw.sh" ]] || return 0
    git_here ls-files --error-unmatch -- uvw.sh >/dev/null 2>&1 || return 0
    files=(uvw.sh .gitignore)
    if (( pin_file_updated )); then
        if [[ $version_file != "$SCRIPT_DIR/.uv-version" || -L $version_file ]]; then
            log 'Commit the wrapper and its external/symlinked .uv-version pin manually.'
            return 0
        fi
        files+=(.uv-version)
    fi
    changes=$(git_here status --porcelain --untracked-files=all -- "${files[@]}") || return 0
    [[ -n $changes ]] || return 0
    git_here symbolic-ref -q HEAD >/dev/null 2>&1 || { log 'Git commit offer skipped: detached HEAD.'; return 0; }
    for operation in MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD rebase-merge rebase-apply sequencer; do
        state=$(git_here rev-parse --git-path "$operation") || return 0
        [[ $state == /* ]] || state="$SCRIPT_DIR/$state"
        if [[ -e $state ]]; then
            log 'Git commit offer skipped: finish the current Git operation first.'
            return 0
        fi
    done
    unmerged=$(git_here ls-files --unmerged) || return 0
    [[ -z $unmerged ]] || { log 'Git commit offer skipped: unresolved conflicts.'; return 0; }
    if [[ $git_commit_mode == ask ]]; then
        case "${CI:-}" in ''|0|false|FALSE) ;; *) return 0 ;; esac
        [[ -t 2 ]] || return 0
        { exec 9<> /dev/tty; } 2>/dev/null || return 0
        printf '\nuvw: Pending wrapper files in %s:\n%s\n' "$SCRIPT_DIR" "$changes" >&9
        printf 'uvw: Stage and commit ALL current changes in' >&9
        printf ' %s' "${files[@]}" >&9
        printf '? [y/N] ' >&9
        reply=''
        IFS= read -r reply <&9 || return 0
        case "$reply" in y|Y|yes|YES|Yes) ;; *) return 0 ;; esac
    fi
    # Scope BOTH operations. --only excludes unrelated paths already staged.
    # Hooks and the user's normal commit-signing configuration remain enabled.
    if ! git_here add -- "${files[@]}" </dev/null >&2; then
        log 'Git staging failed; the uv operation is not rolled back. Review the index manually.'
        return 0
    fi
    message="chore: update uv wrapper (uv $version)"
    if ! git_here commit --only -m "$message" -- "${files[@]}" </dev/null >&2; then
        log 'Git commit failed; wrapper changes remain staged. The uv operation is not rolled back.'
    fi
    return 0
)

ensure_gitignore
if command -v git >/dev/null 2>&1 && git_environment_safe; then
    tracked_cache=$(git_here ls-files -- .uv 2>/dev/null) || tracked_cache=''
    [[ -z $tracked_cache ]] || log '.uv already contains tracked files; .gitignore does not untrack them.'
fi

version_matches() {
    local output
    [[ -f $1 && -x $1 && ! -L $1 ]] || return 1
    output=$("$1" --version </dev/null 2>/dev/null) || return 1
    case "$output" in
        "uv $version"|"uv $version "*) return 0 ;;
        *) return 1 ;;
    esac
}

installed() {
    local receipt
    [[ -d $install_dir && ! -L $install_dir ]] || return 1
    [[ -f $install_dir/uvx && -x $install_dir/uvx && ! -L $install_dir/uvx ]] || return 1
    [[ -f $install_dir/.archive-sha256 ]] || return 1
    IFS= read -r receipt < "$install_dir/.archive-sha256" || return 1
    [[ $receipt =~ ^[0-9a-f]{64}$ ]] || return 1
    [[ -z $pinned_sha || $receipt == "$pinned_sha" ]] || return 1
    version_matches "$install_dir/uv"
}

download() {
    # Keep bootstrap output on stderr and do not consume the caller's stdin.
    if command -v curl >/dev/null 2>&1; then
        curl --disable --fail --location --silent --show-error \
            --proto '=https' --proto-redir '=https' --tlsv1.2 \
            --connect-timeout 15 --max-time 300 --retry 2 \
            --output "$2" "$1" </dev/null || die "Download failed: $1"
    elif command -v wget >/dev/null 2>&1; then
        wget --https-only --timeout=30 --tries=3 --quiet \
            --output-document="$2" "$1" </dev/null || die "Download failed: $1"
    else
        die 'Bootstrapping uv requires curl or GNU wget.'
    fi
}

bootstrap() (
    # Isolate traps and umask so the eventual uv process inherits neither.
    umask 077
    stage=''
    lock="$install_dir.lock"
    owns_lock=0
    # Invoked by the EXIT trap in this subshell.
    # shellcheck disable=SC2329
    cleanup() {
        if [[ -n $stage ]]; then rm -rf -- "$stage" || :; fi
        if (( owns_lock )); then rmdir -- "$lock" 2>/dev/null || :; fi
        return 0
    }
    trap cleanup EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM

    mkdir -p -- "$cache_root/$version" || die "Cannot create cache: $cache_root"
    waited=0
    while ! mkdir -- "$lock" 2>/dev/null; do
        installed && exit 0
        [[ -d $lock ]] || die "Cannot create installation lock: $lock"
        if (( waited >= lock_timeout )); then
            die "Timed out waiting for $lock. If no bootstrap is running, remove that lock directory and retry."
        fi
        (( waited != 0 )) || log 'Waiting for another uv bootstrap to finish.'
        sleep 1
        waited=$((waited + 1))
    done
    owns_lock=1
    installed && exit 0

    command -v tar >/dev/null 2>&1 || die 'Bootstrapping uv requires tar.'
    if command -v sha256sum >/dev/null 2>&1; then
        hasher=(sha256sum)
    elif command -v shasum >/dev/null 2>&1; then
        hasher=(shasum -a 256)
    else
        die 'Bootstrapping uv requires sha256sum or shasum.'
    fi

    stage=$(mktemp -d "$cache_root/$version/.uvw-$target.XXXXXXXX") ||
        die 'Cannot create a temporary installation directory.'
    archive="uv-$target.tar.gz"
    url="$RELEASE_BASE/$version/$archive"
    log "Downloading uv $version for $target ..."
    download "$url" "$stage/$archive"
    expected=$pinned_sha
    if [[ -z $expected ]]; then
        download "$url.sha256" "$stage/checksum"
        # Parse only the hash, not a downloaded filename or a shell command.
        IFS=' ' read -r expected _ < "$stage/checksum" || [[ -n $expected ]] ||
            die 'Empty checksum file.'
        [[ $expected =~ ^[[:xdigit:]]{64}$ ]] || die 'Invalid published SHA-256.'
        expected=$(printf '%s' "$expected" | tr '[:upper:]' '[:lower:]')
    fi
    actual=$("${hasher[@]}" < "$stage/$archive") || die 'Cannot calculate SHA-256.'
    actual=${actual%% *}
    [[ $actual == "$expected" ]] || die "SHA-256 mismatch for $archive; refusing to execute it."

    mkdir -- "$stage/install"
    # Extract only the two expected members, not arbitrary archive paths.
    tar -xzf "$stage/$archive" -C "$stage/install" \
        --strip-components=1 --no-same-owner --no-same-permissions \
        "uv-$target/uv" "uv-$target/uvx" || die 'Cannot extract uv release.'
    for executable in uv uvx; do
        [[ -f $stage/install/$executable && ! -L $stage/install/$executable ]] ||
            die "Archive does not contain a regular $executable executable."
        chmod 700 "$stage/install/$executable"
    done
    version_matches "$stage/install/uv" ||
        die "Downloaded uv does not run or does not report version $version."
    printf '%s\n' "$expected" > "$stage/install/.archive-sha256"

    # Publish only after successful verification; all wrapper writers hold
    # the lock. A failed bootstrap leaves no partially installed uv behind.
    if [[ -e $install_dir || -L $install_dir ]]; then
        rm -rf -- "$install_dir" || die 'Cannot replace invalid cached uv.'
    fi
    mv -- "$stage/install" "$install_dir" || die 'Cannot publish uv installation.'
    log "Cached uv $version in $install_dir"
)

if ! installed; then
    (( offline == 0 )) || die "uv $version is not usable in $install_dir and bootstrap downloads are disabled."
    bootstrap
    installed || die 'uv bootstrap finished without a usable installation.'
fi

json_quote() {
    local text=$1 char code i LC_ALL=C
    printf '"'
    for (( i=0; i<${#text}; i++ )); do
        char=${text:$i:1}
        case "$char" in
            '"') printf '\\"' ;;
            \\) printf '%s' "\\\\" ;;
            *) printf -v code '%d' "'$char"
               # Bash 3.2 can report bytes above 127 as signed values.
               code=$((code & 255))
               if (( code < 32 )); then printf '\\u%04x' "$code"; else printf '%s' "$char"; fi ;;
        esac
    done
    printf '"'
}
self_update() {
    original_umask=$(umask)
    umask 077
    stage=''; script_edit=''; version_edit=''; native_pid=''
    pin_lock="$script.uvw-update.lock"
    owns_pin_lock=0
    cleanup_update() {
        [[ -z $native_pid ]] || { kill -TERM "$native_pid" 2>/dev/null || :; wait "$native_pid" 2>/dev/null || :; }
        [[ -z $stage ]] || rm -rf -- "$stage" || :
        [[ -z $script_edit ]] || rm -rf -- "$script_edit" || :
        [[ -z $version_edit ]] || rm -rf -- "$version_edit" || :
        (( owns_pin_lock == 0 )) || rmdir -- "$pin_lock" 2>/dev/null || :
        return 0
    }
    trap cleanup_update EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
    (( offline == 0 )) || die 'Self update requires network access; offline mode is enabled.'
    if (( update_dry_run == 0 )) && [[ -n $pinned_sha ]]; then
        die 'Unset UVW_SHA256 before self update; that checksum pins the current archive, not the new release.'
    fi
    version_target=''
    if (( update_dry_run == 0 )); then
        [[ -f $script && -r $script && -w $script && -w $script_dir ]] ||
            die "Self update requires a writable wrapper and directory: $script"
        waited=0
        until mkdir -- "$pin_lock" 2>/dev/null; do
            [[ -d $pin_lock ]] || die "Cannot create update lock: $pin_lock"
            (( waited < lock_timeout )) || die "Timed out waiting for $pin_lock; remove only if no update is running."
            sleep 1; waited=$((waited + 1))
        done
        owns_pin_lock=1
        script_edit=$(mktemp -d "$script_dir/.uvw-pin.XXXXXXXX") || die 'Cannot stage wrapper edit.'
        cp -p -- "$script" "$script_edit/original"
        cp -p -- "$script" "$script_edit/new"
        grep -Fqx "DEFAULT_UV_VERSION='$DEFAULT_UV_VERSION'" "$script_edit/original" ||
            die 'The wrapper pin changed while this process was starting; retry the update.'
        if [[ -e $version_file || -L $version_file ]]; then
            version_target=$(resolve_file "$version_file") || die 'Cannot resolve .uv-version.'
            version_parent=$(dirname -- "$version_target")
            [[ -f $version_target && -r $version_target && -w $version_target && -w $version_parent ]] ||
                die "Self update requires a writable pin file and directory: $version_target"
            version_edit=$(mktemp -d "$version_parent/.uvw-version.XXXXXXXX") || die 'Cannot stage .uv-version.'
            cp -p -- "$version_target" "$version_edit/original"
            cp -p -- "$version_target" "$version_edit/new"
        fi
    fi
    stage=$(mktemp -d "$cache_root/.uvw-update.XXXXXXXX") || die 'Cannot stage native updater.'
    stage=$(CDPATH='' cd -P -- "$stage" && pwd)
    mkdir -- "$stage/bin" "$stage/receipt"
    cp -- "$install_dir/uv" "$stage/bin/uv"
    cp -- "$install_dir/uvx" "$stage/bin/uvx"
    chmod 700 "$stage/bin/uv" "$stage/bin/uvx"
    {
        printf '{"install_prefix":'; json_quote "$stage/bin"
        printf ',"binaries":["uv","uvx"],"version":"%s",' "$version"
        printf '"provider":{"source":"uvw","version":"1.0.0"},'
        printf '"source":{"app_name":"uv","name":"uv","owner":"astral-sh","release_type":"github"},'
        printf '"modify_path":false}\n'
    } > "$stage/receipt/uv-receipt.json"
    (
        unset AXOUPDATER_CONFIG_WORKING_DIR
        export AXOUPDATER_CONFIG_PATH="$stage/receipt"
        export UV_UNMANAGED_INSTALL="$stage/bin" UV_INSTALL_DIR="$stage/bin"
        export CARGO_DIST_FORCE_INSTALL_DIR="$stage/bin" UV_NO_MODIFY_PATH=1
        exec env PATH="$stage/bin${PATH:+:$PATH}" "$stage/bin/uv" "$@"
    ) <&0 &
    native_pid=$!
    status=0
    wait "$native_pid" || status=$?
    native_pid=''
    (( status == 0 )) || exit "$status"
    (( update_dry_run == 0 )) || exit 0
    output=$("$stage/bin/uv" --version </dev/null) || die 'Cannot read updated uv version.'
    new_version=${output#uv }; new_version=${new_version%% *}
    [[ $output == "uv "* && $new_version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
        die "Updater returned an unsupported version: $output"
    old_version=$version
    version=$new_version
    install_dir="$cache_root/$version/$target"
    # Re-verify the exact platform archive instead of trusting a mutable cache
    # or publishing an installer-selected libc build under another target name.
    if ! installed; then bootstrap; fi
    installed || die 'Cannot verify the updated version in the wrapper cache.'
    awk -v pin="$new_version" '
        /^DEFAULT_UV_VERSION=/ { $0 = "DEFAULT_UV_VERSION=\047" pin "\047"; count++ }
        { print }
        END { if (count != 1) exit 1 }
    ' "$script_edit/original" > "$script_edit/new" || die 'Cannot rewrite embedded version pin.'
    "$BASH" -n "$script_edit/new" || die 'Updated wrapper failed Bash syntax validation.'
    if [[ -n $version_target ]]; then
        awk -v pin="$new_version" '
            {
                content=$0; sub(/#.*/, "", content)
                gsub(/^[[:space:]]+|[[:space:]]+$/, "", content)
                if (content != "") {
                    if (content !~ /^[0-9]+\.[0-9]+\.[0-9]+$/) { invalid=1; exit 1 }
                    sub(/[0-9]+\.[0-9]+\.[0-9]+/, pin); count++
                }
                print
            }
            END { if (invalid || count != 1) exit 1 }
        ' "$version_edit/original" > "$version_edit/new" || die 'Cannot safely rewrite .uv-version.'
        if [[ $(resolve_file "$version_file") != "$version_target" ]] ||
            ! cmp -s -- "$version_target" "$version_edit/original"; then
            die '.uv-version changed; no pins written.'
        fi
    else
        [[ ! -e $version_file && ! -L $version_file ]] || die '.uv-version appeared; no pins written.'
    fi
    cmp -s -- "$script" "$script_edit/original" || die 'Wrapper changed during update; no pins written.'
    mv -f -- "$script_edit/new" "$script" || die 'Cannot commit embedded pin.'
    if [[ -n $version_target ]]; then
        if ! mv -f -- "$version_edit/new" "$version_target"; then
            if ! mv -f -- "$script_edit/original" "$script"; then
                log "Wrapper rollback failed; backup retained at $script_edit/original"
                script_edit=''
            fi
            die 'Cannot update .uv-version; attempted to restore embedded pin.'
        fi
        pin_file_updated=1
    fi
    log "Pinned uv $new_version in $script (previously running $old_version)."
    [[ -z ${UVW_VERSION:-} ]] || log "UVW_VERSION=$UVW_VERSION still overrides persistent pins until unset."
    cleanup_update
    trap - EXIT HUP INT TERM
    umask "$original_umask"
}

export PATH="$install_dir${PATH:+:$PATH}"
if (( self_update_requested && ! show_help )); then
    self_update ${args[@]+"${args[@]}"}
    offer_git_commit || log 'Optional Git step failed; uv was updated successfully.'
else
    if (( ! show_help )); then
        offer_git_commit || log 'Optional Git step failed; continuing with uv.'
    fi
    exec "$install_dir/$executable_name" ${args[@]+"${args[@]}"}
fi
