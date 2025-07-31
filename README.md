# Tracer

Tracer is a tool that takes snapshots of a Java program's state. These snapshots
are then output in [OnlinePythonTutor](https://pythontutor.com/)'s JSON format
for subsequent visualization with other tools.

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
      "ordered_globals": [],
      "stack_to_render": [
        {
          "unique_hash": "0",
          "parent_frame_id_list": [],
          "is_highlighted": true,
          "encoded_locals": {
            "alice": ["REF", 56]
          },
          "ordered_varnames": ["alice"],
          "is_zombie": false,
          "is_parent": false,
          "frame_id": 0,
          "func_name": "main:4"
        }
      ],
      "stdout": "",
      "line": 4,
      "globals": {},
      "event": "step_line",
      "heap": {
        "55": ["LIST"],
        "56": ["INSTANCE", "Person", ["name", ["REF", 61]], ["age", 42]],
        "61": ["INSTANCE", "String", ["", "Alice"]]
      },
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
$ mvn exec:java -e -Dexec.mainClass="cs1302.tracer.App" -Dexec.args="-i ./Main.java"
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
$ java -jar target/code-tracer-1.0.0-jar-with-dependencies.jar -i ./Main.java
```

## Usage

For usage information, run the program with the `--help` option passed. A sample
of what this message looks like is provided below.

```console
$ java -jar target/code-tracer-1.0.0-jar-with-dependencies.jar -h
usage: code-tracer
 -b,--breakpoint <arg>             breakpoint at which to take a snapshot.
                                   the snapshot taken will represent the
                                   state of memory immediately before this
                                   line is executed. multiple instances of
                                   this option can be provided. if none
                                   are provided, the default behavior is
                                   to take one snapshot at the end of the
                                   program's main method.
 -h,--help                         print this help message and then exit
 -i,--input <arg>                  input path to Java source file
                                   (defaults to stdin if omitted)
 -l,--list-available-breakpoints   instead of running a trace, list the
                                   breakpoints available in provided
                                   source file
 -o,--output <arg>                 output path (defaults to stdout if
                                   omitted)
 -s,--inline-strings               if provided, strings are inlined into
                                   fields instead of going through a
                                   reference
    --show-licenses                show the licenses for projects used in
                                   this program and then exit
```

## Known issues/TODOs

- See `TODO` comments left throughout the source tree.
- Exceptions thrown by the guest code cause tracing to fail transparently. It's
  probably better to have special output for exceptions so that downstream
  consumers can do better error handling/provide better messages.

## Working on this tool

For a quick introduction to this project's structure, see `HACKING.md` in the repository.
