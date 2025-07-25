To compile:
```console
$ mvn clean compile assembly:single
```

To run:
```console
$ java -jar target/code-tracer-1.0-SNAPSHOT-jar-with-dependencies.jar -h
usage: code-tracer
 -b,--breakpoint <arg>             breakpoint at which to take a snapshot.
                                   the snapshot taken will represent the
                                   state of memory immediately before this
                                   line is executed. multiple instances of
                                   this option can be provided. if none
                                   are provided, the default behavior is
                                   to
                                   take one snapshot at the end of the
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
```

References:
- https://wayne-adams.blogspot.com/2011/12/examining-variables-in-jdi.html
- https://itsallbinary.com/java-debug-interface-api-jdi-hello-world-example-programmatic-debugging-for-beginners/
- https://github.com/pathrise-eng/pathrise-python-tutor/blob/53253554f6fdb9176cb90e54df38b508d9529235/v5-unity/pg_encoder.py#L36-L72
