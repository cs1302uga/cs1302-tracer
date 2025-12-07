# Project overview for the interested hacker

This program is divided into four major components:

- the driver
- the compiler
- the tracer
- the value extractor
- the serializer

The driver is `App.java`. It is responsible for:

- parsing command-line arguments
- connecting other components together
- actually outputting things to standard output

The compiler is `CompilationHelper.java`. It:

- locates the guest code's top-level public class/record/enum/interface and main method
- creates a java source file containing the guest code
  - source file is named according to the top-level public declaration
  - parent directories are made to match the guest code's package declaration if necessary
- compiles this source file
  - we keep track of which classes we compile for later usage
  - class files are output into the source tree, so e.g. `Main.class` will be
    in the same directory as `Main.java`. this allows us to use
    the directory structure we made earlier as our classpath when we execute
- returns information about the path we output our classes to, what classes we
  compiled, and the name of the class that contains main. if compilation fails,
  we raise an exception that contains the compilation error message.

The tracer is `DebugTraceHelper.java`. It:

- starts a JVM with a connected JDI
- places breakpoints at the locations we want to take snapshots
- runs the guest program
- snapshots guest program state when breakpoints are reached

The tracer snapshot process involves collecting static fields from loaded
classes, local variables from each frame in the current thread's method stack,
and objects allocated on the heap. To collect objects from the heap, we
start with objects referenced by static variables and frame variables. These objects are then used as the entry points
for a recursive traversal of all accessible values. As an example, say we have
this program:

```java
public class Main {
  public static void main(String[] args) {
    Person alice = new Person("Alice", 42);
  }
}

record Person(String name, int age) { };
```

For an execution snapshot taken at the end of the main method, we'd have no
statics and one frame (main) that contains two local variables (`args` and
`alice`). Let's ignore `args`. The tracer will
extract `alice` as a reference to an object. The tracer then looks inside the
Person object and finds two more values to extract (name and age). Being a
primitive, age is extracted and no recursion happens. The name field, however,
is a String object, so we recurse into the String object to extract all of its
fields. This continues until all accessible values on the heap are extracted.
If you're familiar with garbage collection, this is very similar to the mark
phase of a mark-and-sweep garbage collector.

The value extractor is `TraceValue.java`. This code is responsible for turning
mirrored JDI values (that are owned by the JVM we're debugging) into values
that we own in our JVM. It can handle converting any primitive or object using the `fromJdiValue` method. Special objets like arrays, lambdas, strings, `Map`s, `Collection`s, and `List`s are parsed into specialized wrapper classes that provide more specific/useful representations than the more generic `Object` value.

The serializer is `PyTutorSerializer.java`. It is responsible for transforming
our `ExecutionSnapshot` records into the JSON format that OnlinePythonTutor
accepts. For information on this format, see [this
documentation](https://github.com/pathrise-eng/pathrise-python-tutor/blob/53253554f6fdb9176cb90e54df38b508d9529235/v5-unity/pg_encoder.py#L36-L72)
and/or [this
documentation](https://github.com/pathrise-eng/pathrise-python-tutor/blob/53253554f6fdb9176cb90e54df38b508d9529235/v3/docs/opt-trace-format.md).

References:

- https://wayne-adams.blogspot.com/2011/12/examining-variables-in-jdi.html
- https://itsallbinary.com/java-debug-interface-api-jdi-hello-world-example-programmatic-debugging-for-beginners/
