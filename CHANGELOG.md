# Changelog

## Unreleased

**Fixes**
- Scans no longer appear to run forever on a slow or dead target. A request's timeout now starts when it is handed to Burp, not when the result is read, so paced or queued requests are not dropped. A target that stops answering (nothing back for 3x the request timeout + 5s) aborts the scan with a clear status instead of waiting out every queued request.
- Dir: the trailing-slash directory confirmation now runs in the thread pool instead of one request at a time on the result thread.
- Send to DNS / Vhost: the base domain is the registered domain (`a.b.target.com` -> `target.com`, `x.shop.co.uk` -> `shop.co.uk`), not the host minus its first label. The other levels are in the drop-down.
- Vhost: the target is an IP or host[:port] with an HTTPS checkbox; no `https://` prefix needed (a typed scheme still wins).

**Features**
- Progress bar for every mode: done / total, percentage, requests per second and ETA; the current directory ("dir 2 of 5") for Dir.
- Auto-filter line above the results shows what calibration decided to hide (e.g. `status 404 (any size)`, or `status 403 with size 1200-1260`), and a Scan log tab records it per directory, like feroxbuster's auto-filter messages.
- Dir and Vhost: **Show all responses** turns the auto-filter off; "not found" responses are listed with Type = `baseline`.
- Clearer panels: a description per mode, grouped options, tooltips on every field, Stop disabled when idle.
- Vhost asks for an "IP address" and a "Domain"; sending a request to Vhost looks up the host's IP. Vhost and DNS show a live example of what will be sent / tried.
- Panel descriptions are plain text (Burp could show raw `<html>` markup).
- Context menu entries no longer repeat "Send to Buster" (Burp already shows them under Extensions > Buster).

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
