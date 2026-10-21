# Date/time values and duration arithmetic

From the repository root, after packaging:

```sh
java -jar target/code-tracer.jar trace -i examples/java-time/Main.java --all-breakpoints --format modern
java -jar target/code-tracer.jar trace -i examples/java-time/Main.java --all-breakpoints --format pytutor
```

The guest prints:

```text
2024-02-29T12:30:00.123456789
PT-0.000000001S
PT1.999999999S
```

Trusted inspection shows the stored date, time, and duration components. The
`meeting` object's `date` and `time` fields refer to the same objects as the
corresponding local variables. After `plusSeconds`, `original` still refers to
the duration with `seconds = -1` and `nanos = 999999999`; `elapsed` refers to a
new duration with `seconds = 1` and `nanos = 999999999`. Arithmetic does not
mutate the original duration.

For raw field inspection, add `--result-envelope --inspection FIELDS`.
See the [output reference](../../docs/reference/output.md#date-and-time-objects)
for the complete supported type list and layout fallback. Java integration tests
exercise this source and both formats; it does not add line-sensitive golden
fixtures to the numbered examples.
