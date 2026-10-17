# JSON output reference

For an annotated walkthrough, start with [Read a trace](../tutorials/read-a-trace.md).
This reference defines field behavior and complete output examples.

The tracer supports Python Tutor (`pytutor`, default) and modern (`modern`) JSON.
These examples are complete output from the source below using `--all-breakpoints`.
CI compares them with actual output after normalizing heap IDs.

<!-- source:start -->
```java
public class Main {
    public static void main(String[] args) {
        int answer = 42;
        System.out.println(answer);
    }
}
```
<!-- source:end -->

## Shared source metadata

Both formats include `code`, `sources`, and `entryFile`. `code` retains the supplied
input; `sources` maps package-relative file paths to submitted source text, excluding
multi-file delimiters. `entryFile` selects the initial file and is a key in `sources`.
Map order does not choose the entry point. Step and frame `file` values select the
corresponding source; line numbers are 1-based. Discovered dependencies outside the
submitted bundle are not included in `sources`.

Logical submitted paths choose compilation destinations. Escaping or duplicate
destinations are rejected during preparation. Rendered source identities instead
combine the declared package with the source basename. Distinct logical destinations
can therefore compile successfully yet produce the same debug source path; rendering
rejects that ambiguity when constructing source metadata. The same basename in
different packages remains distinct. Source text, line endings, ordering, and entry
selection are preserved when prepared tracing renders after compilation resources
close or retries rendering during recovery.

## Input and output observations

Root `stdin` is the input supplied to the guest, separate from source text read
through the tracer's stdin. Snapshot `stdinConsumed` is the observed consumed
prefix of that string, and `stdinOffset` is its zero-based UTF-16 code-unit length,
not a byte offset. Input observation recognizes supported standard-input readers
and avoids counting nested reads twice; it is not a record of every operating-system
read or every custom reader's buffering. Each job starts with a fresh cursor.

Snapshot `stdout` and `stderr` contain cumulative captured output. Chronological
snapshots record progress; after execution, the final snapshot receives trailing
output when budgets permit. Selected-breakpoint presentation also refreshes the
latest retained hit at each location with final output. A result envelope carries
the available guest streams even when no usable snapshot payload exists.

## Floating-point special values

Non-finite Java values remain valid JSON. Modern output represents `NaN`, positive
infinity, and negative infinity as the strings `"NaN"`, `"Infinity"`, and
`"-Infinity"` for both `float` and `double`. Finite values remain JSON numbers.
Python Tutor retains its existing literal conventions: floats use
`["NUMBER-LITERAL", "value"]`, while non-finite doubles use
`["SPECIAL_FLOAT", "value"]`, with the same special-value spellings.

## Generic method variable types

Stack variable labels describe source-declared types for the active invocation.
For example, `max(T[])` called with an `Integer[]` variable displays `Integer[]`
for its parameter and `Integer` for its `T` locals with `--type-style simple`.
An explicit `<Number>` invocation keeps `Number` labels even when the value is
an `Integer` or `null`. Arrays and nested parameterized local declarations use
the same substitutions. Heap objects retain their runtime identities and classes.

Resolution requires source metadata and an unambiguous caller invocation matched
by declaring type and erased method descriptor. Explicit type arguments and
single-argument inference from declared variables, fields, casts, or array
accesses into a `T` or `T[]` parameter are supported. Substitutions require
concrete reference types (including default-package classes, parameterized types,
and arrays with reference or primitive components). Method type parameters shadow
class parameters, and recursive calls and threads have independent binding contexts.

A method with exactly one unbounded type parameter
and one parameter of that type, such as `<T> void helper(T item)`, also supports
string literals, explicit object creation, and non-generic method results when
the invocation is a standalone expression statement (no target type). For
example, `helper("hello")` labels `T` locals as `String`, while `helper(number())`
labels them as `Number` when `number()` declares that return type, even if it
returns an `Integer`. This additional subset excludes boxing, raw types, diamond
or anonymous construction, local classes, generic method results, receiver-dependent return
types, raw receiver ancestry, implicit instance receivers (including the outer
callee's receiver), array-clone expressions,
and types nested in generic owners. Nested arguments and receivers must
also have supported independent expression forms; no guest methods are executed
to infer a type. Bounded type parameters and `T[]` formals retain the existing
variable/field/cast/array-access inference rules.

Missing source, multiple matching calls on one line, target-dependent inference,
varargs, multi-argument inference, and unsupported capture or intersection types
retain unresolved type variables. The tracer does not infer method bindings from
runtime object classes. Both JSON formats use their existing type fields;
consumers need no rendering changes, but must regenerate cached traces and images
with the updated tracer.

## Modern format

`steps` contains chronological snapshots, with `callStack`, `statics`, and `heap`.
References use `{"ref": id}`; IDs identify objects within the result and are not
stable across runs. Variables include name, type, value, and finality metadata.
Output/input fields record captured streams and guest input consumption.

### Opt-in thread capture

`--multithread --format modern` adds `threads` and `triggeringThreadId` to every
step, including steps where only one application thread exists. It implies
chronological capture; `--breakpoints` can still restrict source-line stops.
Without `--multithread`, the existing output shape is preserved. Python Tutor
cannot represent this mode; the CLI, batch validation, and Python Tutor serializer
reject it explicitly.

Each `threads` entry has `id`, `name`, `state`, and `callStack`. IDs are guest
object identities, stable within one job and unrelated to a thread's name.
Stacks contain submitted application frames, bottommost first. The top-level
`callStack` remains the triggering thread's application stack. All stacks share
one `heap` and one set of `statics`; the same reference ID means the same object.
A thread waiting inside **JDK** code can have an empty application stack.

Events are `step_line`, `main_exit`, `exception`, `thread_start`, and
`thread_death`. Death events include the terminating thread with `TERMINATED`
state and an empty stack; later steps omit it. Other JDI states are `NEW`,
`RUNNABLE`, `SLEEPING`, `BLOCKED`, `WAITING`, and `UNKNOWN`. They describe guest
execution state, independently of debugger suspension; `WAITING` does not
separate timed and untimed parking. JVM shutdown need not produce a death event
for every daemon thread.

Capture includes the entry thread's group and descendant groups, plus threads
observed executing submitted code. JVM service threads are excluded. Platform
threads and conventional executor pools are supported. Virtual-thread start
stops capture with `unsupported_virtual_thread`, preserving prior complete
snapshots in envelope output.

All threads pause during capture using
[JDI event-set suspension](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jdi/com/sun/jdi/event/EventSet.html),
and the tracer invokes no guest methods while inspecting them. JDK thread bookkeeping fields are omitted from heap objects;
fields declared by submitted `Thread` subclasses remain visible. Other ordinary objects
use field-only inspection; arrays, strings, and colors keep their dedicated
representations without guest method calls. These observations are one debugger-influenced run,
not deterministic scheduling or a record of every memory operation. Stream
output is process-wide and is not attributed to individual threads.

Tracing continues after `main` returns until non-daemon application threads
finish or a job limit is reached. Daemon threads do not keep the guest alive.
An uncaught worker exception produces an event and is retained as
`guest_exception` in the result envelope while other threads continue.
Use `--result-envelope` and explicit limits to retain partial traces and status.
Examples 34–41 cover these behaviors. Java callers set `multithread` to `true`
and `format` to `TraceFormat.MODERN` in `TraceRequest`, then call `Tracer.trace`.
The execution implementation selects a dedicated guest and field-only inspection;
batch requests use the same effective policy. Internal breakpoint-map tracing and
the reusable single-stack guest do not implement multithread capture.
Lock ownership and deadlock diagnosis are
not included; timeout alone does not prove deadlock.

<!-- modern:start -->
```json
{
  "code": "public class Main {\n    public static void main(String[] args) {\n        int answer = 42;\n        System.out.println(answer);\n    }\n}\n",
  "format": "modern",
  "stdin": "",
  "steps": [
    {
      "step": 1,
      "line": 3,
      "file": "Main.java",
      "event": "step_line",
      "method": "main",
      "callStack": [
        {
          "methodName": "main",
          "line": 3,
          "file": "Main.java",
          "isHighlighted": true,
          "locals": [
            {
              "name": "args",
              "type": "java.lang.String[]",
              "value": {
                "ref": 49
              },
              "final": false
            }
          ]
        }
      ],
      "statics": [],
      "heap": {
        "49": {
          "id": 49,
          "type": "java.lang.String[]",
          "kind": "array",
          "elements": []
        }
      },
      "stdout": "",
      "stderr": "",
      "stdinConsumed": "",
      "stdinOffset": 0
    },
    {
      "step": 2,
      "line": 4,
      "file": "Main.java",
      "event": "step_line",
      "method": "main",
      "callStack": [
        {
          "methodName": "main",
          "line": 4,
          "file": "Main.java",
          "isHighlighted": true,
          "locals": [
            {
              "name": "args",
              "type": "java.lang.String[]",
              "value": {
                "ref": 49
              },
              "final": false
            },
            {
              "name": "answer",
              "type": "int",
              "value": 42,
              "final": false
            }
          ]
        }
      ],
      "statics": [],
      "heap": {
        "49": {
          "id": 49,
          "type": "java.lang.String[]",
          "kind": "array",
          "elements": []
        }
      },
      "stdout": "",
      "stderr": "",
      "stdinConsumed": "",
      "stdinOffset": 0
    },
    {
      "step": 3,
      "line": 5,
      "file": "Main.java",
      "event": "step_line",
      "method": "main",
      "callStack": [
        {
          "methodName": "main",
          "line": 5,
          "file": "Main.java",
          "isHighlighted": true,
          "locals": [
            {
              "name": "args",
              "type": "java.lang.String[]",
              "value": {
                "ref": 49
              },
              "final": false
            },
            {
              "name": "answer",
              "type": "int",
              "value": 42,
              "final": false
            }
          ]
        }
      ],
      "statics": [],
      "heap": {
        "49": {
          "id": 49,
          "type": "java.lang.String[]",
          "kind": "array",
          "elements": []
        }
      },
      "stdout": "42\n",
      "stderr": "",
      "stdinConsumed": "",
      "stdinOffset": 0
    }
  ],
  "sources": {
    "Main.java": "public class Main {\n    public static void main(String[] args) {\n        int answer = 42;\n        System.out.println(answer);\n    }\n}\n"
  },
  "entryFile": "Main.java"
}
```
<!-- modern:end -->

## Python Tutor format

`trace` contains snapshots with `stack_to_render`, `heap`, and `stdout`.
References use `["REF", id]`; heap objects use tagged tuple arrays. Additional
metadata such as `locals_attrs` and `heap_attrs` preserves Java type information.

<!-- pytutor:start -->
```json
{
  "code": "public class Main {\n    public static void main(String[] args) {\n        int answer = 42;\n        System.out.println(answer);\n    }\n}\n",
  "stdin": "",
  "trace": [
    {
      "stdout": "",
      "stderr": "",
      "event": "step_line",
      "func_name": "main",
      "line": 3,
      "stack_to_render": [
        {
          "func_name": "main:3",
          "encoded_locals": {
            "args": [
              "REF",
              49
            ]
          },
          "locals_attrs": {
            "args": {
              "final": false,
              "type": "java.lang.String[]"
            }
          },
          "ordered_varnames": [
            "args"
          ],
          "parent_frame_id_list": [],
          "is_highlighted": true,
          "is_zombie": false,
          "is_parent": false,
          "unique_hash": "0",
          "frame_id": 0,
          "file": "Main.java"
        }
      ],
      "globals": {},
      "globals_attrs": {},
      "ordered_globals": [],
      "heap": {
        "49": [
          "LIST"
        ]
      },
      "heap_attrs": {
        "49": {
          "type": "java.lang.String[]"
        }
      },
      "file": "Main.java",
      "stdinConsumed": "",
      "stdinOffset": 0
    },
    {
      "stdout": "",
      "stderr": "",
      "event": "step_line",
      "func_name": "main",
      "line": 4,
      "stack_to_render": [
        {
          "func_name": "main:4",
          "encoded_locals": {
            "args": [
              "REF",
              49
            ],
            "answer": 42
          },
          "locals_attrs": {
            "args": {
              "final": false,
              "type": "java.lang.String[]"
            },
            "answer": {
              "final": false,
              "type": "int"
            }
          },
          "ordered_varnames": [
            "args",
            "answer"
          ],
          "parent_frame_id_list": [],
          "is_highlighted": true,
          "is_zombie": false,
          "is_parent": false,
          "unique_hash": "0",
          "frame_id": 0,
          "file": "Main.java"
        }
      ],
      "globals": {},
      "globals_attrs": {},
      "ordered_globals": [],
      "heap": {
        "49": [
          "LIST"
        ]
      },
      "heap_attrs": {
        "49": {
          "type": "java.lang.String[]"
        }
      },
      "file": "Main.java",
      "stdinConsumed": "",
      "stdinOffset": 0
    },
    {
      "stdout": "42\n",
      "stderr": "",
      "event": "step_line",
      "func_name": "main",
      "line": 5,
      "stack_to_render": [
        {
          "func_name": "main:5",
          "encoded_locals": {
            "args": [
              "REF",
              49
            ],
            "answer": 42
          },
          "locals_attrs": {
            "args": {
              "final": false,
              "type": "java.lang.String[]"
            },
            "answer": {
              "final": false,
              "type": "int"
            }
          },
          "ordered_varnames": [
            "args",
            "answer"
          ],
          "parent_frame_id_list": [],
          "is_highlighted": true,
          "is_zombie": false,
          "is_parent": false,
          "unique_hash": "0",
          "frame_id": 0,
          "file": "Main.java"
        }
      ],
      "globals": {},
      "globals_attrs": {},
      "ordered_globals": [],
      "heap": {
        "49": [
          "LIST"
        ]
      },
      "heap_attrs": {
        "49": {
          "type": "java.lang.String[]"
        }
      },
      "file": "Main.java",
      "stdinConsumed": "",
      "stdinOffset": 0
    }
  ],
  "userlog": "",
  "sources": {
    "Main.java": "public class Main {\n    public static void main(String[] args) {\n        int answer = 42;\n        System.out.println(answer);\n    }\n}\n"
  },
  "entryFile": "Main.java"
}
```
<!-- pytutor:end -->

## Optional primitive binary metadata

Use `trace --include-primitive-bits`, batch request `"includePrimitiveBits": true`,
or the Java overload `Tracer.trace(request, true)`.
The default is false. When disabled, all binary metadata properties are omitted
and the existing literal values and output shapes are unchanged. The option works
with both formats, final-state, selected-breakpoint, and chronological capture,
ordinary output and envelopes. Multithread capture still requires modern output.

A runnable demonstration is in `examples/primitive-bits/Main.java`.

Each descriptor contains `type`, `encoding`, and `bits`. Bits are a string of
binary digits, most-significant first, with leading zeros, no separators, and no
`0b` prefix. Width and interpretation are:

| Java type | Width | `encoding` |
| --- | --- | --- |
| `byte`, `short`, `int`, `long` | 8, 16, 32, 64 respectively | `twos-complement` |
| `char` | 16 | `utf16-code-unit` |
| `float` | 32 | `ieee754-binary32` |
| `double` | 64 | `ieee754-binary64` |
| `boolean` | 1 logical digit | `logical` |

Boolean false is `"0"`; true is `"1"`. This logical encoding does not specify JVM
storage size. Character bits describe one UTF-16 code unit, including isolated
surrogates. Binary metadata describes the value captured through JDI, not its
original source spelling, address, or physical memory layout; it has no machine
byte order. Integer literals `42`, `0x2a`, and `0b101010` yield the same int bits.

Floating-point encodings distinguish positive and negative zero and represent
subnormal values, infinities, and NaNs. The encoder uses raw-bit conversions on
the received Java value; it does not deliberately canonicalize NaNs. Guest/JDI
transport and signaling-NaN behavior can affect NaN payloads, so this is not a
promise to recover every original guest NaN bit pattern. Existing human-readable
non-finite-value conventions remain unchanged.

### Encoding definitions and references

**Normative references** below define the Java values, standard encoding formats,
and conversion contracts used by this metadata. This output reference defines
the tracer-specific contract: property names, `encoding` identifiers, fixed-width
MSB-first strings, leading zeros, and the boolean mapping. **Informative references**
provide explanations and examples; they do not add requirements to that contract.
Java specification and API links use Java SE 21, the project's minimum supported
JDK. IEEE and Unicode references identify explicit editions.

#### Signed integers: `twos-complement`

**Normative:** [JLS §4.2, Primitive Types and Values](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2)
defines the signed two's-complement representations of `byte`, `short`, `int`,
and `long`; [§4.2.1, Integral Types and Values](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2.1)
gives their ranges. The descriptor retains the type's own width from the table
above, including for negative `byte` and `short` values.

**Informative:** Oracle's [Primitive Data Types tutorial](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
introduces these integer types and ranges. It is a JDK 8-era tutorial; the Java
SE 21 specification above supplies the normative definitions here.

#### Characters: `utf16-code-unit`

**Normative:** [JLS §4.2](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2)
defines `char` as an unsigned 16-bit value representing a UTF-16 code unit;
[§3.1, Unicode](https://docs.oracle.com/javase/specs/jls/se21/html/jls-3.html#jls-3.1)
distinguishes code units from code points. [The Unicode Standard, Version 15.0,
§3.9, Unicode Encoding Forms](https://www.unicode.org/versions/Unicode15.0.0/ch03.pdf)
defines code units (D77) and UTF-16 (D91). A descriptor records one code unit,
so an isolated surrogate is representable; it does not assert that this unit
alone is a Unicode scalar value or a well-formed UTF-16 sequence.

**Informative:** The Unicode Consortium's [UTF-8, UTF-16, UTF-32 & BOM FAQ](https://www.unicode.org/faq/utf_bom.html)
explains code units, surrogate pairs, and byte-order conventions. The descriptor
contains textual binary digits, not a UTF-16 byte stream.

#### Floating point: `ieee754-binary32` and `ieee754-binary64`

**Normative:** [JLS §4.2.3, Floating-Point Types and Values](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2.3)
maps `float` and `double` to binary32 and binary64 and identifies
[IEEE Std 754-2019, Standard for Floating-Point Arithmetic](https://standards.ieee.org/ieee/754/6210/)
as the applicable edition. The Java API contracts for
[`Float.floatToRawIntBits`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Float.html#floatToRawIntBits(float))
and [`Double.doubleToRawLongBits`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html#doubleToRawLongBits(double))
specify the bit layouts and raw conversions used by the encoder. Their inverse
methods, [`Float.intBitsToFloat`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Float.html#intBitsToFloat(int))
and [`Double.longBitsToDouble`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html#longBitsToDouble(long)),
document signaling-NaN limitations. These contracts apply to the received Java
value; the guest/JDI NaN-payload limitation above still applies.

**Informative:** Oracle's [Numerical Computation Guide, Chapter 2: IEEE Arithmetic](https://docs.oracle.com/cd/E19059-01/stud.10/819-0499/ncg_math.html),
especially §§2.2.2–2.2.3, illustrates the sign, exponent, and fraction fields and
special values. This historical guide uses the older names *single* and *double*
and discusses machine storage; use the normative references above for the Java
contract. Its machine byte-order examples do not describe this textual metadata.

#### Booleans: `logical`

**Normative:** [JLS §4.2.5, The `boolean` Type and boolean Values](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2.5)
defines the two Java values, `false` and `true`. The tracer defines their respective
`bits` strings as `"0"` and `"1"`; that mapping is a protocol convention of this
project, not an externally standardized JVM storage format.

**Informative:** The boolean discussion in Oracle's [Primitive Data Types tutorial](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
distinguishes two-valued logical information from a specified storage size.

### Modern placement

Locals, static variables, and instance fields have an optional sibling `binary`
property. For example, a captured byte value of -1 is represented as:

```json
{
  "name": "delta",
  "type": "byte",
  "value": -1,
  "final": false,
  "binary": {"type": "byte", "encoding": "twos-complement", "bits": "11111111"}
}
```

A `kind: "box"` heap object has `binary` alongside its existing `value`.
A heap object rendered with `elements` has an `elementBinary` array of equal
length, including an empty array for no elements. A position containing a direct
primitive has its descriptor; other positions contain JSON null. References
still refer to heap objects, where boxed payload metadata appears. Ordinary
objects carry primitive descriptors on their fields. Under field-only inspection,
wrappers remain ordinary objects and their captured primitive fields carry the
metadata; the option does not change inspection policy or invoke guest methods.

### Python Tutor placement

Existing scalar values and tagged tuples are preserved. Primitive locals and
statics add `binary` inside their `locals_attrs` or `globals_attrs` entry.
For example, `locals_attrs.delta` can be:

```json
{
  "type": "byte",
  "final": false,
  "binary": {"type": "byte", "encoding": "twos-complement", "bits": "11111111"}
}
```

`heap_attrs[id].binary` describes a specialized boxed primitive payload.
`heap_attrs[id].elementBinary` aligns with list/collection payload elements,
excluding the leading tuple tag. `heap_attrs[id].fieldBinary` aligns with ordinary
object field pairs, excluding the `INSTANCE` tag and type label. Both arrays use
null for nonprimitive positions; positional field metadata preserves duplicate
field names. Empty payloads have empty metadata arrays. Reference values themselves
never receive primitive metadata. Map keys and values captured as wrapper references
are described on their referenced heap objects.

Binary strings preserve all integer bits even in consumers whose JSON-number
handling cannot represent a 64-bit long exactly. Consumers can derive hexadecimal
formatting, digit groups, or sign/exponent/fraction highlighting from the descriptor.

### Cost and limits

Metadata is derived from owned typed snapshots during serialization. It is not
stored in retained snapshots, so it does not change `traceBytes` or `snapshotBytes`
accounting or capture-limit admission. It increases serialized payload size and
serialization allocations. Those counters do not measure final response bytes;
`outputBytes` limits guest stdout/stderr, not trace JSON. See
[bounded tracing](../BOUNDED_TRACING.md) for the accounting boundary.
Completed partial snapshots receive metadata just like complete traces.

## Selected breakpoints and envelopes

Non-envelope selected-breakpoint output may be a dictionary of breakpoint results;
do not assume every invocation has the chronological root shown above. Modern
selected output can contain a `breakpoints` mapping. Envelope mode always carries
a format-specific sequence root when a trace is available.

`--result-envelope` adds versioned status, effective limits, counters, diagnostics,
and bounded guest streams around the trace. `trace` may be null on failure.
Envelope mode leaves omitted budgets unlimited. Read the full
[bounded result contract](../BOUNDED_TRACING.md) for limits, exit codes, stop reasons,
partial results, and the difference between empty and unavailable traces.
