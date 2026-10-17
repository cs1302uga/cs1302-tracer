# Installation

Install a full **stable JDK 21 or later**, including the Java compiler and debugger modules.
CI tests JDK 21 and 25 on Ubuntu and macOS. JDK 25 is the local development default.
The tracer requires Java 21 or later; submitted programs are compiled using the active
JDK, so newer language/library features require a suitable runtime and parser support.

## Choose an installation

| Your task | Download or build | Next step |
| --- | --- | --- |
| Run a downloaded release on any supported platform | Standalone `code-tracer.jar` | Run `java -jar code-tracer.jar --help`. |
| Use a launcher on Linux or macOS | Binary tar.gz or tar.xz archive, when provided | Extract it and run the **Bash** launcher. |
| Use a launcher on Windows | Binary ZIP archive, when provided | Extract it and use **PowerShell 7.4+** or **Command Prompt**. |
| Develop or modify the tracer | Source checkout | Build with the included **Maven** wrapper. |

Check `java --version` and `javac --version` before starting. Their complete release
numbers must match and be stable JDK 21 or later releases. The launcher uses a
nonempty `JAVA_HOME` in preference to `PATH`; both `java` and `javac` must be
executable under that selection. Every command, including help and version,
requires the [JDK environment preflight](../reference/cli.md#jdk-environment-preflight)
to pass. Direct JAR execution must use the same JDK selected by the environment.
Use the documentation version matching your downloaded release; `/next/` describes
development builds, whose artifacts may differ from older releases.

## Download a release

Open the [latest stable software release](https://github.com/cs1302uga/cs1302-tracer/releases/latest),
or select a particular version from the
[release history](https://github.com/cs1302uga/cs1302-tracer/releases).
Software releases use `vX.Y.Z` tags. Separate `docs-*` prereleases publish historical
guide applications; use a software release when downloading the tracer itself.

Choose an asset by its purpose. Availability depends on the release: older releases
may provide only the executable and library JARs. Binary distributions and **Javadoc**
bundles described here are produced by the current workflow and may be absent from
your selected release.

| Asset | Purpose |
| --- | --- |
| `code-tracer.jar` | Executable CLI with its runtime dependencies bundled. Start here for direct Java invocation. |
| `code-tracer-bin.tar.gz`, `code-tracer-bin.tar.xz`, or `code-tracer-bin.zip` | The executable JAR, shell launchers, and license in one directory. Choose one archive for your platform. |
| `cs1302-tracer-VERSION.jar` | Library for [Java integration](../tutorials/java-integration.md). It requires its runtime dependencies; use Maven to resolve them. |
| `cs1302-tracer-VERSION-javadoc.jar` | Javadoc HTML for offline API reading, with matching checksum and provenance companions when supplied by the current workflow. See [offline API documentation](offline-javadoc.md). |
| `cs1302-tracer-VERSION-guides.zip` | Historical guide application from a separate `docs-*` publication. The [documentation site](https://cs1302uga.github.io/cs1302-tracer/) already serves these guides at their versioned URLs. |

For the simplest installation, download `code-tracer.jar` and run
`java -jar code-tracer.jar --help` in its directory. A full JDK is still required.
Use documentation matching the downloaded version. The
[first-trace tutorial](../tutorials/first-trace.md) works beside that JAR or from an
extracted binary distribution; no source checkout is needed.

### Binary distributions

Releases that include `code-tracer-bin.tar.gz`, `code-tracer-bin.tar.xz`, and
`code-tracer-bin.zip` provide
all three launchers, the bundled executable JAR, and `LICENSE` in a `code-tracer/`
directory. On Linux or macOS, extract the tar archive and run:

```sh
tar -xzf code-tracer-bin.tar.gz
./code-tracer/code-tracer --help
```

Alternatively, extract the XZ-compressed archive with `tar -xJf code-tracer-bin.tar.xz`
and run the same launcher command. Choose one tar archive; their contents are identical.

Both tar formats preserve executable permissions on `code-tracer.sh` and include
the relative symlink `code-tracer -> code-tracer.sh`. The ZIP includes a regular,
identical copy at `code-tracer` so extraction does not depend on symlink support.
Unix source builds produce all three archives; native Windows builds produce the ZIP.
On Windows,
extract the ZIP with **File Explorer** or PowerShell, then run the appropriate script:

```powershell
Expand-Archive .\code-tracer-bin.zip -DestinationPath .
.\code-tracer\code-tracer.ps1 --help
```

From Command Prompt after extraction, run `code-tracer\code-tracer.cmd --help`.
You can move the extracted directory as a unit. The standalone JAR remains
available for direct Java invocation. Older releases may provide fewer artifacts;
use the installation instructions for that release.

Continue with [your first trace](../tutorials/first-trace.md); no checkout is needed.

### Offline API documentation

Releases produced by the current workflow also include
`cs1302-tracer-VERSION-javadoc.jar`, its `.jar.sha256` checksum, and its
`.jar.sigstore.json` provenance bundle. These contain Javadoc for library
integration; they are separate from the executable and library JARs. Older
releases may not provide them.

Download the three matching files and follow the
[offline API documentation guide](offline-javadoc.md)
to check their source identity and trusted build provenance. Extract the verified
Javadoc JAR into an empty directory and open `index.html` in a browser. The HTML,
navigation, and search are bundled; links to other projects can still need network
access. Do not run this JAR with `java -jar` or add it as a runtime dependency.

## Build from source

```sh
git clone https://github.com/cs1302uga/cs1302-tracer.git
cd cs1302-tracer
./mvnw -B -ntp clean package
```

The wrapper downloads the configured Maven distribution. Windows users can use
`mvnw.cmd`. CI checks wrapper bootstrap, packaging, and Windows launchers on
JDK 21 and 25; the full Java test and fixture matrix targets Ubuntu and macOS.

The standalone JAR is `target/code-tracer.jar`. Packaging also retains the
identical `target/code-tracer-jar-with-dependencies.jar` for existing scripts:

```sh
java -jar target/code-tracer.jar --version
java -jar target/code-tracer.jar --help
```

The [version report](../reference/cli.md#version-and-runtime-details) includes the
application version and source commit, followed by OS and Java runtime details.
Include this report when diagnosing installation or JDK differences.

Continue with [your first trace](../tutorials/first-trace.md). That tutorial creates
its own source file and explains which directory to use for each installation.

## Advanced launcher configuration

The following sections describe shell behavior and relocation. They are optional
for a first run with the launcher and JAR kept together.

### Bash launcher

Packaging creates `target/code-tracer.sh`, an executable Bash 3.2+ launcher.
On Linux and macOS, `target/code-tracer` is a relative symlink to it, so existing
extensionless commands still work:

```sh
./target/code-tracer --version
./target/code-tracer --help
./target/code-tracer trace -i examples/example0/Driver.java --all-breakpoints
```

You can also invoke `./target/code-tracer.sh` directly. The launcher finds
`code-tracer.jar` beside its resolved script path by default. Keep the `.sh` file, JAR, and
extensionless link together when relocating them; a symlink to the launcher can be placed
on `PATH`. Relative source paths and classpaths are resolved from the caller's
working directory, which the launcher preserves. Internal launcher-directory
resolution ignores `CDPATH`.

If `JAVA_HOME` is nonempty, the launcher uses `$JAVA_HOME/bin/java` and reports an
error if that executable or its sibling `javac` is unavailable. Otherwise both
`java` and `javac` must be executable on `PATH`.
Select a full JDK 21 or later; the launcher does not install Java. It forwards all
arguments to the tracer and preserves standard streams and the Java exit status.
It has no launcher-specific flags and does not interpret `JAVA_OPTS`.

### Windows launchers

Packaging creates `target/code-tracer.ps1` for PowerShell 7.4 or later and
`target/code-tracer.cmd` for Command Prompt. Both invoke Java directly; the batch
launcher does not require PowerShell. Use the launcher for your shell:

```powershell
# PowerShell 7.4+
.\target\code-tracer.ps1 --version
.\target\code-tracer.ps1 --help
.\target\code-tracer.ps1 trace -i examples/example0/Driver.java --all-breakpoints
```

```bat
rem Command Prompt
target\code-tracer.cmd --version
target\code-tracer.cmd --help
target\code-tracer.cmd trace -i examples\example0\Driver.java --all-breakpoints
```

By default, keep each launcher beside `code-tracer.jar`. PowerShell resolves a file symlink
to the installed script; the batch launcher locates the JAR beside the invoked
batch file. Both preserve the caller's working directory, standard streams, and
Java exit code. A nonempty `JAVA_HOME` selects `bin\java.exe` under that directory;
both it and `bin\javac.exe` are required. An invalid selection fails without
falling back. Otherwise both tools are found on `PATH`.
Select a full JDK 21 or later. There are no launcher-specific flags or `JAVA_OPTS`
parsing. Quote arguments according to your shell; PowerShell preserves empty
arguments and embedded quotes, while batch uses Command Prompt's quoting and
expansion rules. From another batch script, use `call code-tracer.cmd ...` if that
script must continue afterward.

The scripts do not change execution policy. If Windows marks a downloaded
PowerShell script as blocked, review it and use `Unblock-File` on that file when
permitted by your organization's policy. The batch launcher and direct
`java -jar code-tracer.jar` invocation are also available.

### Store the JAR separately

All three launchers accept **`CODE_TRACER_JAR_DIR`**, the directory containing
`code-tracer.jar`. The filename remains fixed. This allows launchers on `PATH`
to use a JAR installed elsewhere.

| Variable value | Selected JAR |
| --- | --- |
| Unset or empty | `code-tracer.jar` beside the script, using the default symlink behavior described above. |
| Absolute directory | `code-tracer.jar` in that directory. |
| Relative directory | `code-tracer.jar` in that directory relative to the caller's working directory. |

An invalid nonempty override fails with a diagnostic on stderr and status `1`;
it never falls back to the adjacent JAR. Spaces and trailing directory separators
are supported. Values are literal: the launcher does not expand a stored `~`,
embedded environment-variable names, or shell expressions. The calling shell may
perform its own expansion when you assign the variable. Use absolute paths for
persistent settings so the result does not depend on your current directory.

From the repository root, these examples select the built JAR using a relative
directory (replace `./target` with your installation directory as needed):

```bash
CODE_TRACER_JAR_DIR='./target' ./target/code-tracer --version
```

```powershell
$env:CODE_TRACER_JAR_DIR = './target'
.\target\code-tracer.ps1 --version
Remove-Item Env:CODE_TRACER_JAR_DIR
```

```bat
set "CODE_TRACER_JAR_DIR=.\target"
target\code-tracer.cmd --version
set "CODE_TRACER_JAR_DIR="
```

The Bash assignment applies to that invocation. The PowerShell and Command Prompt
examples clear the session setting afterward. This variable selects only the JAR
directory; `JAVA_HOME` still selects Java. Source paths, classpaths, standard
streams, and Java's exit status retain their existing behavior. Direct
`java -jar` invocation does not consult this launcher variable.
