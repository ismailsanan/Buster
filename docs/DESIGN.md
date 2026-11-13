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

## Non-functional requirements

| Area | Requirement |
|---|---|
| Performance | Configurable thread pools; work is processed in bounded batches. |
| Scalability | Memory stays flat with wordlist size: files are streamed, in-flight work is bounded, and HTTP exchanges are moved to Burp temp files. |
| Responsiveness | Network work never runs on the Swing EDT; UI values are read on the EDT and passed to workers as immutable configs. |
| Reliability | Per-request timeouts enforced by Burp; failed calibration aborts instead of flooding results; errors are logged, not swallowed. |
| Lifecycle | Scans stop cleanly on Stop and on extension unload (cancel, interrupt, bounded join); all threads are named daemons. |
| Security | Server data is treated as untrusted; CSV export is escaped against formula injection; no data leaves Burp other than the scan traffic itself. |
| Integration | HTTP goes through Burp's networking (`api.http()`), so it respects project proxy/TLS settings. DNS uses the OS resolver (documented limitation). |
| Compatibility | Burp Community and Professional; Java 17+; Montoya API 2026.2. |
| Maintainability | Pure logic (`Baseline`, `UrlUtil`, CSV escaping, parsing) is free of Burp types and covered by JUnit 5 tests (`mvn verify`). |

## Architecture

```
Buster (entry point, registers tab and unload handler)
 └─ ui/      DirPanel, VhostPanel, DnsPanel   → read input, start/stop through ScanRunner
             ResultsTable, DnsResultsTable    → live results, viewers, CsvExporter
             WordlistPicker, HeadersPanel, Layouts
 └─ modes/   DirEnumerator, VhostEnumerator, DnsEnumerator  → scan logic per mode
 └─ core/    HttpEngine (thread pool + Burp HTTP), BaselineDetector/Baseline (calibration),
             DnsResolver, WordlistSource (streaming), UrlUtil, Threads
```

**Flow:** panel (EDT) builds a `Config` → `ScanRunner` starts a daemon thread → the enumerator calibrates, streams the wordlist and submits requests to `HttpEngine` → hits are compared to the `Baseline` → results go to the table via `invokeLater`.

## Branching

- `main`: production; released builds only.
- `dev`: staging; integration and testing before merging to `main`.

## Build and test

```
mvn clean verify      # compile and run unit tests
# output: target/Buster.jar → Burp: Extensions → Add → Java
```
