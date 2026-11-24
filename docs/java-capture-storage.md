# Java capture storage

The Java tool continues to read version-1 JSON evidence formats. New paged viewer
captures use a version-2 manifest and compressed event chunks. A legacy saved
viewer snapshot contains the original exports and any source
text explicitly pinned by the developer. A CLI capture is a separate, allowlisted
projection: it excludes source text, payloads, headers, exception messages,
user/context fields, and thread names. Neither is proof of complete execution.

## Large traces and paged details

An upgraded agent exposes a compact overview separately from retained events.
The request diagram uses that overview, so a large line timeline does not remove
an otherwise available service from the graph. If a service cannot supply its
details, available summary evidence remains explicitly incomplete.

- The timeline holds one page at a time: normally **500 events / 256 KiB**, with
  a hard **1 MiB** event-response ceiling. Source enrichment is limited to the
  selected page. Request payloads and full SQL/bindings load on disclosure.
- Pages pin the segment, service instance and captured sequence high-water mark.
  A restart, missing capture or invalid cursor produces an error, not a switch to
  another request. An oversized event is explicitly omitted with cursor progress.
- Code-flow reconstruction carries bounded invocation stacks across pages, so a
  page boundary is not mistaken for a method exit. It scans at most 100,000 events
  and returns a bounded model; gaps and model limits remain visible.
- Federation and paged execution reads share an 8-worker/64-queued admission pool.
  Each operation is bounded to 12 seconds, 512 reads and 32 MiB, with fair service
  shares. A slow/unavailable service produces partial evidence, not missing-service
  success. Source enrichment caches within one operation only: 64 files, 8 MiB,
  2 seconds and 256 class resolutions; later operations see the current checkout.
- The agent has separate count and estimated-byte budgets. Method/line capture
  stops before reserved dependency/error capacity is consumed. This protects
  useful overview evidence, but cannot guarantee unlimited retention or bound
  total JVM heap, temporary encoding allocations or callback CPU cost.

New viewer snapshots are captured on the server, one bounded page at a time, and
published only after their private compressed chunks and manifest have been
written. They do not require one full export or a browser-sized JSON request.
Source lines from the configured checkout are pinned at capture time within each
page's byte budget. Omitted source text is flagged; raw events are kept. Saved
reads never substitute source from a newer checkout.
Version-2 captures live in `chunked-v2` under the existing viewer namespace.
Missing/corrupt saved chunks never trigger live reads. Existing version-1 files
are not rewritten or deleted.

The stores have separate quotas; the legacy 5 MiB limit is not the new paged
snapshot limit:

| Store | Capture limit | Per capture | Total per store |
| --- | --- | --- | --- |
| Version-2 paged viewer snapshots | 20 | 128 MiB raw / 64 MiB compressed | 512 MiB raw / 256 MiB compressed |
| Version-1 viewer JSON snapshots | 20 | 5 MiB JSON | 20 MiB |
| Minimized CLI/MCP captures | 20 | 5 MiB JSON | 20 MiB |

A version-2 snapshot permits at most **64 segments / 4,096 chunks**. Each
decompressed chunk and its manifest are bounded to **1 MiB** separately. Both
compressed and raw sizes are checked, including on reads. Full stores reject
saves instead of silently evicting evidence; interrupted writes remain
quota-accounted. The version-2 quotas apply per viewer namespace, separately
from that namespace's legacy JSON store.

The saved view downloads as one ZIP containing its manifest and compressed
chunks. **Saved captures → Import capture ZIP** imports that archive into a new
saved ID without connecting to the original services. Imports validate paths,
identities, chunk integrity and compressed/decompressed limits; the upload ceiling
is 66 MiB including archive overhead. It can contain raw request data, SQL and bindings; review the evidence
before sharing it. CLI/MCP comparison and search use a bounded, minimized
projection rather than loading all chunks; any details beyond that bounded
prefix are explicitly reported as omitted. Analysis strips payload/source/SQL
values before charging its 2 MiB event budget and shares that budget across saved
segments, with at most 32 MiB of raw chunk reads and 10 seconds. Each segment has
`analysisCoverage` (saved/retained events, bytes read and completeness). This is
still a bounded prefix analysis, not a full streaming index of arbitrary captures.

New captures include optional recording provenance: application version label,
agent-JAR digest and effective-configuration fingerprint. Offline comparison warns
when known configuration/build fingerprints differ. A supplied version label is
not a verified source revision, and missing old metadata remains unknown. Optional
method descriptors and runtime-local thread tokens improve reconstruction without
rewriting older captures; thread tokens are not identities across JVMs.

## Choosing a directory

The defaults remain `~/.tracelens/viewer-snapshots/<viewer-namespace>` and
`~/.tracelens/captures`. Custom directories are operator configuration, not
HTTP/MCP tool arguments. Existing valid evidence can be read without converting,
refetching, or rebuilding it.

Use a trusted local POSIX filesystem and a directory owned by the account running
the tool. The final store directory must be private; newly created directories
use mode `0700` and files use `0600`. Symlinks in any configured path component
are rejected. For example, on macOS `/var/...` and `/tmp/...` may be aliases;
choose the actual `/private/...` path rather than configuring the alias. The
tool does not silently follow aliases or relax ownership and permission checks.
Ancestors must be owned by the current account or root, and must not be writable
by other users/groups. Root-owned sticky temporary directories are the exception:
their sticky bit protects the ownership of entries beneath them. Shared-writable
project directories are deliberately rejected even when the final store is
private.

## Concurrency and publication

The Java tool does not stop another writer, change existing permissions, migrate
files or delete saved evidence at startup. Use one compatible storage protocol
per directory. If an older Python `tracelens` install is still on a machine, give it a separate
private store: its `flock` and Java's `FileChannel` record locks do not provide
portable mutual exclusion, even with the same `.lock` filename. Copy/import is
an explicit action; preserve originals and validate permissions.

Java threads and cooperating Java processes do share the store's lock while
checking quotas and publishing files. Do not use different mount aliases to
address the same storage. Do not use the files as a shared, multi-user service,
and do not assume network filesystem lock or durability semantics are equivalent
to those of a local filesystem.

Chunked capture/import reserves capacity under that lock, then downloads,
compresses and validates in a private staging directory outside it. Publication
rechecks quota and atomically exposes the directory. Each active/interrupted save
reserves the full 128 MiB raw / 64 MiB compressed maximum; this deliberately can
reject a small save when less than one full reservation remains. Slow downloads
and detail/analysis reads pin only their snapshot; deletion/rename of that snapshot
wait, while other captures remain accessible. Failed current writes clean only
their own known staging files; interrupted unknown files are not auto-deleted.
Deletion first atomically retires the published directory before removing its
lock and files, preventing cooperating JVMs from opening a second lock in a
half-deleted snapshot. Interrupted retirement remains quota-accounted. In-process
path locks are released from the registry after the final holder/waiter finishes;
lookups of missing IDs do not accumulate permanent lock entries.

## Legacy version-1 bounds and mutation rules

- Each store permits at most 20 files, 5 MiB per JSON document, and 20 MiB total.
- Interrupted `.pending-<uuid>` files consume both count and byte quota. Reads do
  not clean them up. A committed file and its matching pending hardlink from an
  interrupted publication are recognized; unrelated hardlinks are rejected.
- Capture documents accept only the defined minimized schema. Viewer snapshots
  validate trace/segment identities, graph endpoints, timestamps, row counts,
  warning limits, and finite JSON data.
- Reads reject invalid UTF-8, duplicate JSON keys, corrupt documents, identity/
  filename mismatches, public files, non-regular files, and outside hardlinks.
  A failed read never substitutes fresh service data.
- New files are written privately, flushed, then published with a no-overwrite
  hardlink operation. A UUID collision cannot overwrite a capture. Renaming a
  viewer snapshot changes only its human-readable name through atomic
  replacement; deletion validates that exact saved file first.
- Offline listing, lookup, comparison, projection, and search do not contact an
  application or read a source checkout. Missing-store reads do not create it.

The store fails closed if the filesystem/provider cannot supply the required
Unix ownership, permissions, inode, and link-count checks. Successful publication
also requires file and directory flushing and the applicable atomic operation.
An I/O error after publication can leave a complete file present; inspect the
store before retrying an interrupted write.

## Portable Java limitation

The Java 8 implementation uses standard NIO: it checks every path component
without following links, opens files with
`NOFOLLOW_LINKS`, checks Unix metadata and inode identity, rechecks the directory
before publication, and uses atomic filesystem operations.

These checks **do not fully defend against a hostile process that renames or
replaces parent directories during an operation**. Portable Java NIO
does not expose all of the descriptor-relative operations needed for that
guarantee across macOS and Linux. Ancestor ownership/write checks reduce this
exposure but cannot prevent a malicious process running under the same account
from replacing directories. Keep the hierarchy under trusted ownership; this is
an explicit platform-security limitation, not a shared-user storage guarantee.
