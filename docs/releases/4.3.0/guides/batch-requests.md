# Build batch requests from source files

Use this recipe when submissions already exist as `.java` files and you need one
[batch request](../reference/batch.md) per submission. It assumes you have run the
[batch tutorial](../tutorials/batch-jobs.md), work in the directory containing
`code-tracer.jar`, and have **Python 3** available. The script uses only Python's
standard library.

## Arrange the submissions

Put each submission in its own folder under `submissions`; the folder name becomes
the request `id`. A submission can have several files. Save these three files as
UTF-8:

`submissions/ada/Main.java`:

<!-- batch-requests-ada-main:start -->
```java
public class Main {
    public static void main(String[] args) {
        System.out.println(6 * 7);
    }
}
```
<!-- batch-requests-ada-main:end -->

`submissions/alan/Main.java`:

<!-- batch-requests-alan-main:start -->
```java
public class Main {
    public static void main(String[] args) {
        System.out.println(Helper.twice(21));
    }
}
```
<!-- batch-requests-alan-main:end -->

`submissions/alan/Helper.java`:

<!-- batch-requests-alan-helper:start -->
```java
public class Helper {
    static int twice(int value) {
        return 2 * value;
    }
}
```
<!-- batch-requests-alan-helper:end -->

## Write the request file

Save this as `make_requests.py`. It joins each submission's files into one
[delimited source bundle](../reference/source-format.md), adds the budgets and
inspection policy you choose, and writes one JSON object per line:

<!-- batch-requests-script:start -->
```python
import json
import pathlib

LIMITS = {
    "timeoutMillis": 5000, "snapshots": 1000, "outputBytes": 65536,
    "heapObjects": 1000, "elements": 10000, "traceBytes": 33554432,
    "sourceBytes": 262144, "sourceFiles": 32, "threads": 64,
    "frames": 4096, "snapshotBytes": 8388608,
}

folders = sorted(p for p in pathlib.Path("submissions").iterdir() if p.is_dir())
with open("requests.ndjson", "w", encoding="utf-8", newline="\n") as out:
    for folder in folders:
        parts = []
        for file in sorted(folder.rglob("*.java")):
            text = file.read_text(encoding="utf-8")
            if not text.endswith("\n"):
                text += "\n"
            parts.append(f"// --- {file.relative_to(folder).as_posix()} ---\n{text}")
        request = {
            "id": folder.name,
            "source": "".join(parts),
            "format": "modern",
            "allBreakpoints": True,
            "inspection": "FIELDS",
            "limits": LIMITS,
        }
        out.write(json.dumps(request, ensure_ascii=False) + "\n")
```
<!-- batch-requests-script:end -->

Run it in **Bash**:

<!-- batch-requests-build:start -->
```sh
python3 make_requests.py
```
<!-- batch-requests-build:end -->

On Windows, run `py make_requests.py` instead. `requests.ndjson` now has one line
per folder, with IDs `ada` and `alan`.

The script always adds a trailing newline, so the next delimiter starts on its own
line, and it always sets every budget, because
[omitted batch limits are unlimited](../reference/batch.md#request-limits). Folder
names must be unique, since responses are matched by ID. Bare file names are
placed under each file's package directory; files in the default package stay at
the bundle root.

## Process the requests

<!-- batch-requests-command:start -->
```sh
java -jar code-tracer.jar batch-trace --workers 2 --max-in-flight 4 --max-jobs-per-worker 100 --input requests.ndjson > results.ndjson
```
<!-- batch-requests-command:end -->

`--input` reads the request file directly, so this command also works in
PowerShell. Both responses in `results.ndjson` report `status` `completed`, and
each job's final captured stdout is `42` followed by a newline. Inspect every
response's `result.status` and `result.complete` as the
[batch protocol](../reference/batch.md#response-ids-and-request-failures)
describes; a successful process exit does not mean every job succeeded.

To trace a submission whose files declare a package, keep the package folders
inside the submission folder, such as `submissions/grace/demo/Main.java`; the
delimiter then names `demo/Main.java`. For untrusted submissions, run each job in
its own disposable process under the [runner contract](../RUNNER_CONTRACT.md).
