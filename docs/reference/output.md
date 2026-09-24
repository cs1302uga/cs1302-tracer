# JSON output reference

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
corresponding source; line numbers are 1-based. Duplicate source paths are rejected.
Discovered dependencies outside the submitted bundle are not included in `sources`.

## Modern format

`steps` contains chronological snapshots, with `callStack`, `statics`, and `heap`.
References use `{"ref": id}`; IDs identify objects within the result and are not
stable across runs. Variables include name, type, value, and finality metadata.
Output/input fields record captured streams and guest input consumption.

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
