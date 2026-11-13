# Buster: Design Overview

Burp Suite extension (Montoya API, Java 17) for content discovery: directories, virtual hosts and subdomains.

## Functional requirements

| ID | Requirement |
|---|---|
| F1 | **Dir mode**: brute force paths from a wordlist against a target URL, with optional file extensions. |
| F2 | Recurse into discovered directories up to a configurable depth (0–6). |
| F3 | Calibrate each directory with random probes and report only responses that differ from "not found" (handles soft 404, catch-all 200/403). |
| F4 | Detect directories by a redirect to `path/`, or by confirming `path/` exists. |
| F5 | Optionally collapse duplicate responses (same status and size) within a directory. |
| F6 | **Vhost mode**: vary the `Host` header against one target and report hosts whose response differs from a junk-host baseline. |
| F7 | **DNS mode**: resolve `word.domain` candidates and report A/AAAA records; detect wildcard DNS and filter its IPs. |
| F8 | Apply user-defined headers (cookies, auth tokens) to every Dir/Vhost request. |
| F9 | Accept pasted wordlists or stream large wordlist files from disk. |
| F10 | Show results live in a sortable table, with request/response viewers for HTTP hits. |
| F11 | Copy results to the clipboard or export them to CSV. |
| F12 | Start and stop scans at any time. |
| F13 | Filter results by hidden statuses, hidden sizes and a regex; copy/export the filtered view. |
| F14 | Right click hits: Send to Repeater / Intruder, add to site map, copy URLs, hide size/status. |
| F15 | Optionally add every hit to Burp's site map as it arrives. |
| F16 | "Send to Buster" from Burp's context menu pre-fills Dir, Vhost or DNS from a selected request. |
| F17 | Optional requests-per-second limit for Dir and Vhost. |
| F18 | "Only in-scope targets" (default on): Dir and Vhost refuse out-of-scope targets and Dir never recurses out of scope. |

## Non-functional requirements

| Area | Requirement |
|---|---|
| Performance | Configurable thread pools; work is processed in bounded batches. |
| Scalability | Memory stays flat with wordlist size: files are streamed, in-flight work is bounded, and HTTP exchanges are moved to Burp temp files. |
| Responsiveness | Network work never runs on the Swing EDT; UI values are read on the EDT and passed to workers as immutable configs. |
| Reliability | Per-request timeouts enforced by Burp; failed calibration aborts instead of flooding results; errors are logged, not swallowed. |
| Politeness | 429 (and 503 with Retry-After) triggers retries with shared exponential backoff (250ms to 5s) that decays on success; rate-limit responses are never reported as hits. |
| Lifecycle | Scans stop cleanly on Stop and on extension unload (cancel, interrupt, bounded join); all threads are named daemons. |
| Security | Server data is treated as untrusted; CSV export is escaped against formula injection; no data leaves Burp other than the scan traffic itself. |
| Integration | HTTP goes through Burp's networking (`api.http()`), so it respects project proxy/TLS settings. DNS uses the OS resolver (documented limitation). |
| Compatibility | Burp Community and Professional; Java 17+; Montoya API 2026.2. |
| Maintainability | Pure logic (`Baseline`, `UrlUtil`, CSV escaping, parsing) is free of Burp types and covered by JUnit 5 tests (`mvn verify`). |

## Architecture

All code lives in `com.ismailsanan.buster` (sources in `src/main/java`, tests mirror it in `src/test/java`).

```
Buster (entry point, registers tab, context menu and unload handler)
 └─ ui/      DirPanel, VhostPanel, DnsPanel   → read input, start/stop through ScanRunner
             ResultsTable, DnsResultsTable    → live results, filters, right click actions, CsvExporter
             BusterContextMenu                → "Send to Buster" from other Burp tools
             WordlistPicker, HeadersPanel, Layouts
 └─ scan/    DirEnumerator, VhostEnumerator, DnsEnumerator  → scan logic per mode
 └─ core/    HttpEngine (thread pool + Burp HTTP), BaselineDetector/Baseline (calibration),
             Throttle (rate limit + backoff), ResultFilter, DnsResolver,
             WordlistSource (streaming), UrlUtil, Threads
```

**Flow:** panel (EDT) builds a `Config` → `ScanRunner` starts a daemon thread → the enumerator calibrates, streams the wordlist and submits requests to `HttpEngine` → hits are compared to the `Baseline` → results go to the table via `invokeLater`.

## Branching, CI and releases

- `dev`: staging; all work lands here first.
- `main`: production; only receives tested changes from `dev`.
- **CI** (`.github/workflows/ci.yml`): every push and PR to `dev` or `main` runs `mvn verify` and uploads the jar.
- **Release** (`.github/workflows/release.yml`): pushing a tag `vX.Y.Z` that matches the pom version builds, tests and publishes a GitHub Release with `Buster.jar`.

Recommended GitHub settings (Settings → Branches → add rule for `main`): require a pull request before merging, require the `CI / build` status check, and block force pushes.

Release steps: bump `<version>` in `pom.xml`, add a `CHANGELOG.md` entry, merge to `main`, then `git tag vX.Y.Z && git push origin vX.Y.Z`.

## Build and test

```
mvn clean verify      # compile and run unit tests
# output: target/Buster.jar → Burp: Extensions → Add → Java
```
