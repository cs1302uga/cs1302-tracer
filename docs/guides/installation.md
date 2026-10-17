# Installation

Install a full **JDK 21 or later**, including the Java compiler and debugger modules.
CI tests JDK 21 and 25 on Ubuntu and macOS. JDK 25 is the local development default.
The tracer targets Java 21 bytecode; submitted programs are compiled using the active
JDK, so newer language/library features require a suitable runtime and parser support.

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

## Bash launcher

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
working directory, which the launcher preserves.

If `JAVA_HOME` is nonempty, the launcher uses `$JAVA_HOME/bin/java` and reports an
error if that executable is unavailable. Otherwise it uses `java` from `PATH`.
Select a full JDK 21 or later; the launcher does not install Java. It forwards all
arguments to the tracer and preserves standard streams and the Java exit status.
It has no launcher-specific flags and does not interpret `JAVA_OPTS`.

## Windows launchers

Packaging creates `target/code-tracer.ps1` for **PowerShell 7.4 or later** and
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
an invalid selection fails without falling back. Otherwise Java is found on `PATH`.
Select a full JDK 21 or later. There are no launcher-specific flags or `JAVA_OPTS`
parsing. Quote arguments according to your shell; PowerShell preserves empty
arguments and embedded quotes, while batch uses Command Prompt's quoting and
expansion rules. From another batch script, use `call code-tracer.cmd ...` if that
script must continue afterward.

The scripts do not change execution policy. If Windows marks a downloaded
PowerShell script as blocked, review it and use `Unblock-File` on that file when
permitted by your organization's policy. The batch launcher and direct
`java -jar code-tracer.jar` invocation are also available.

## Store the JAR separately

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

## Download a release

Download `code-tracer.jar` from the desired
[GitHub release](https://github.com/cs1302uga/cs1302-tracer/releases), then run
`java -jar code-tracer.jar --help`. A full JDK is still required.
Use documentation matching the downloaded version. Source-build examples use the
`target/` JAR path; substitute your downloaded JAR path when following them.

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
extract the ZIP with File Explorer or PowerShell, then run the appropriate script:

```powershell
Expand-Archive .\code-tracer-bin.zip -DestinationPath .
.\code-tracer\code-tracer.ps1 --help
```

From Command Prompt after extraction, run `code-tracer\code-tracer.cmd --help`.
You can move the extracted directory as a unit. The standalone JAR remains
available for direct Java invocation. Older releases may provide fewer artifacts;
use the installation instructions for that release.

Continue with the [first trace tutorial](../tutorials/first-trace.md).
