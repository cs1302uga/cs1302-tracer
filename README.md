# Tracer

Tracer is a tool that takes snapshots of a Java program's state. These snapshots
are then output in a [OnlinePythonTutor](https://pythontutor.com/)-compatible
JSON format for subsequent visualization with [other tools](https://github.com/cs1302uga/cs1302-code-visualizer/).

<table>
<tr>
<td> Code </td> <td> Output </td>
</tr>
<tr>
<td>

```java
public class Main {
  public static void main(String[] args) {
    Person alice = new Person("Alice", 42);
  }
}

record Person(String name, int age) { };
```

</td>
<td>

```json
{
  "stdin": "",
  "trace": [
    {
      "globals_attrs": {},
      "ordered_globals": [],
      "stack_to_render": [
        {
          "unique_hash": "0",
          "parent_frame_id_list": [],
          "is_highlighted": true,
          "encoded_locals": {
            "alice": ["REF", 65]
          },
          "ordered_varnames": ["alice"],
          "is_zombie": false,
          "is_parent": false,
          "locals_attrs": {
            "alice": {
              "final": false,
              "type": "Person"
            }
          },
          "frame_id": 0,
          "func_name": "main:4"
        }
      ],
      "heap_attrs": {
        "65": {
          "final": [true, true],
          "type": ["java.lang.String", "int"]
        }
      },
      "line": 4,
      "globals": {},
      "event": "step_line",
      "heap": {
        "65": ["INSTANCE", "Person", ["name", "Alice"], ["age", 42]]
      },
      "stdout": "",
      "stderr": "",
      "func_name": "main"
    }
  ],
  "userlog": "",
  "code": "public class Main {\n  public static void main(String[] args) {\n    Person alice = new Person(\"Alice\", 42);\n  }\n}\n\nrecord Person(String name, int age) { };\n"
}
```

</td>
</tr>
</table>

Tracer can detect and tag final variables and object fields. It is also able to
create implementations of a functional interface's single abstract method from
lambda expressions.

## Compiling and Running

To build this project, you'll need to install [Apache
Maven](https://maven.apache.org/install.html) and a [Java Development
Kit](https://adoptium.net/temurin/releases) (version 21 or greater).

To compile the program, run

```console
$ mvn clean compile
```

Then, to execute the build you just made with the input file `./Main.java`, run

```console
$ mvn exec:java -e -Dexec.mainClass="cs1302.tracer.App" -Dexec.args="trace -i ./Main.java"
```

If you instead want to create a self-contained Java Archive (JAR) file, you can
run the following:

```console
$ mvn clean compile assembly:single
```

This will create a new file in `target/` named something like
`code-tracer-1.0.0-jar-with-dependencies.jar`, which you can then execute with
Java.

```console
$ java -jar target/code-tracer-1.0.0-jar-with-dependencies.jar tracer -i ./Main.java
```

## Usage

Tracer is split into multiple subcommands. For a list of subcommands, run the
program without any arguments. For usage information, run a subcommand with the
`--help` option passed. A sample of what the `trace` subcommand's help message
looks like is provided below.

```console
$ java -jar target/code-tracer-1.0.0-jar-with-dependencies.jar trace --help
Usage: code-tracer trace [-hsvV] [--accumulate-breakpoints]
                         [--remove-main-args] [--remove-method-this]
                         [-i=<input>] [-b=<breakpoints>]...
Generate an execution trace for a Java program.
      --accumulate-breakpoints
                             Output an array of snapshots containing each time
                               a breakpoint was reached instead of just the
                               last time.
  -b, --breakpoints=<breakpoints>
                             Breakpoints at which to take snapshots. The
                               snapshots taken will represent the state of
                               memory immediately before each line is executed.
                               If no breakpoints are provided, the default
                               behavior is to takeone snapshot at the end of
                               the program's main method.
  -h, --help                 Show this help message and exit.
  -i, --input=<input>        Input path to Java source file (defaults to stdin
                               if omitted).
      --remove-main-args     Don't include the main method's `args` parameter
                               in the output.
      --remove-method-this   Don't include the value of `this` for methods in
                               the output.
  -s, --inline-strings       If provided, strings are inlined into fields
                               instead of going through a reference.
  -v, --verbose              Output messages about what the tracer is doing.
  -V, --version              Print version information and exit.
```

## Limitations

Some features have limitations that must be taken into account when using this tool. They are noted below.

- Lambda method reconstruction is limited to only variables inside of methods
  (not fields on objects)
- All lambdas that are to be reconstructed must be final or effectively final
  (i.e. only assigned once). If they are reassigned, the wrong lambda body may
  be displayed in the implementation.

## TODO

- See `TODO` comments left throughout the source tree.
- Exceptions thrown by the guest code cause tracing to fail transparently. It's
  probably better to have special output for exceptions so that downstream
  consumers can do better error handling/provide better messages.

## Working on this tool

For a quick introduction to this project's structure, see `HACKING.md` in the repository.

I've placed some useful git hooks (for formatting before commit and compiling/testing before push) in the `.githooks` directory.
