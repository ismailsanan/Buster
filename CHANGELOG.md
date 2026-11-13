# Changelog

## 1.0.0

First release for BApp Store submission.

**Scanning**
- Dir: multi-probe calibration, handles soft 404 and catch-all 401/403 pages, aborts when calibration fails.
- Dir: directories confirmed by redirect or a trailing-slash probe, including dot-directories (`.git`, `.well-known`).
- Dir: duplicate collapsing per directory only; directories are never hidden.
- Vhost: custom headers and multi-probe calibration.
- DNS: streamed lookups with bounded concurrency; wildcard IPs detected and filtered.
- DNS: choose the system resolver or Burp's network stack (HTTP probe through upstream proxy / SOCKS).
- Optional requests-per-second limit; automatic backoff on 429 (and 503 with Retry-After).

**Results**
- Right click: Send to Repeater / Intruder, add to site map, copy URLs, hide size or status.
- Filter bar: hide statuses and sizes, regex match; copy and export use the filtered view.
- "Send to Buster" from Burp's context menu pre-fills a mode from any request.

**Robustness**
- No network or parsing work on the Swing EDT; clean Stop and unload.
- Hits stored in Burp temp files; wordlists streamed with bounded line length.
- CSV export escaped against formula injection; HTML rendering disabled in tables.
