# Sets and unmodifiable views

After packaging, run from the repository root:

```sh
java -jar target/code-tracer.jar trace -i examples/sets/Main.java --all-breakpoints --format modern
java -jar target/code-tracer.jar trace -i examples/sets/Main.java --all-breakpoints --format pytutor
```

The program prints `[first, null]`, then `[first, null, last]`. The unmodifiable
view reflects changes made through `backing`. `alias` and `view` refer to the
same wrapper; `backing` refers to a different object. Earlier captured snapshots
retain their earlier elements. LinkedHashSet supplies the displayed order.

Trusted inspection displays elements using the existing modern array and Python
Tutor SET representations. Add `--result-envelope --inspection FIELDS` to see
raw storage, including the wrapper's backing reference. See the
[set output contract](../../docs/reference/output.md#set-objects).
Java integration tests exercise this example without line-sensitive golden files.
