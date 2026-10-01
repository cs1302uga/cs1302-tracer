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
`mvnw.cmd`; the documented shell scripts and CI matrix target Unix-like systems.

The standalone JAR is `target/code-tracer-jar-with-dependencies.jar`:

```sh
java -jar target/code-tracer-jar-with-dependencies.jar --version
java -jar target/code-tracer-jar-with-dependencies.jar --help
```

## Download a release

Download `code-tracer.jar` from the desired
[GitHub release](https://github.com/cs1302uga/cs1302-tracer/releases), then run
`java -jar code-tracer.jar --help`. A full JDK is still required.
Use documentation matching the downloaded version. Source-build examples use the
`target/` JAR path; substitute your downloaded JAR path when following them.

Continue with the [first trace tutorial](../tutorials/first-trace.md).
