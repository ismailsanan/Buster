# Buster

[![CI](https://github.com/ismailsanan/Buster/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ismailsanan/Buster/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/ismailsanan/Buster)](https://github.com/ismailsanan/Buster/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

**Content discovery inside Burp Suite.** Recursive directory brute forcing, virtual host discovery and subdomain enumeration, sent through Burp's own HTTP stack, with your session, your scope and your proxy settings. Results sit next to your traffic instead of in a separate terminal.

Works in **Burp Suite Community and Professional**. Inspired by [gobuster](https://github.com/OJ/gobuster) and [feroxbuster](https://github.com/epi052/feroxbuster).

[POC.webm](https://github.com/user-attachments/assets/3b3ad8f9-61bd-48d0-8a0e-2db61ae4964d)

---

## Features

| Mode | What it does |
|---|---|
| **Dir** | Recursive path brute forcing with extensions and adjustable depth. Calibrates every directory first, so soft 404s, catch-all routes and blanket 401/403 pages don't flood the results. |
| **Vhost** | Varies the `Host` header against one IP to find virtual hosts. A junk-host baseline filters out wildcard responses. |
| **DNS** | Actively brute forces subdomains and lists their A/AAAA records. Wildcard DNS is detected and filtered out. |

Every mode also gives you:

- **Live results**: a sortable table with colour-coded status codes and the full request and response for every HTTP hit.
- **Burp integration**: right-click hits to send them to Repeater or Intruder, or add them to the site map. Right-click any request elsewhere in Burp to *Send to Buster*.
- **Filters**: hide statuses (`404, 500-599`) or sizes, or match a regex. Copy and CSV export follow the filtered view.
- **Authenticated scans**: cookies and auth headers are added to every request.
- **Polite scanning**: an optional request rate limit and automatic backoff on `429`.
- **Large wordlists**: files are streamed rather than loaded into memory, so multi-million-line lists like [SecLists](https://github.com/danielmiessler/SecLists) work.

## Quick start

1. Download `Buster.jar` from the [latest release](https://github.com/ismailsanan/Buster/releases), or [build it](#building-from-source).
2. In Burp go to **Extensions → Installed → Add**, choose type **Java** and select the jar.
3. Open the **Buster** tab, pick a mode, enter a target and a wordlist, and press **Start**.

## Usage

### Dir
1. Enter the target URL, for example `https://target.com/app/`.
2. Paste paths or **Load File**, and optionally set extensions (`php, bak, txt`).
3. Pick the depth and thread count, then press **Start**.

Hits stream in. Directories (a `301` to `/name/`, or a confirmed `/name/`) are scanned recursively up to the chosen depth, including dot-directories like `.git/`.

### Vhost
1. Enter the target IP or host and the base domain (`target.com`).
2. Paste or load a subdomain wordlist, then press **Start**.

Each word is sent as `word.target.com` in the `Host` header to the same address. Only responses that differ from the junk-host baseline are reported.

### DNS
1. Enter the base domain and a subdomain wordlist, then press **Start**.

Names that resolve are listed with their IPs. Names that only resolve because of a wildcard record are filtered out.

### From anywhere in Burp
Right-click a request in Proxy, Target, Repeater or Logger and choose **Send to Buster**. This pre-fills Dir (from that directory), Vhost (that host) or DNS (its domain). Nothing is sent until you press Start.

## Options

| Option | Where | Default | Notes |
|---|---|---|---|
| Threads | all modes | 30 / 10 / 30 | Concurrent requests or lookups. |
| Extensions | Dir | empty | File suffixes tried for every word: with `php, bak`, the word `admin` sends `admin`, `admin.php` and `admin.bak`. Empty = only the bare words. |
| Depth | Dir | 1 | How many directory levels to recurse. `0` = scan only the target directory, `1` = also scan inside each directory found there, and so on. |
| Req/s | Dir, Vhost | 0 (no limit) | A hard cap on requests per second across all threads. |
| Headers / Auth | Dir, Vhost | empty | One `Name: value` per line. A bare `a=b` line becomes a cookie. |
| Extensions on recursion | Dir | off | Recursion happens either way (controlled by Depth). This only decides whether the extensions are also tried inside discovered directories. Off = extensions at the top level only, bare words deeper down (much faster). |
| Collapse duplicates | Dir | on | Hides repeated status+size responses within a directory. Directories are never hidden. |
| Add hits to site map | results bar | off | Pushes every new hit into Target → Site map. |

When a target answers `429` (or `503` with `Retry-After`), Buster retries with growing backoff and slows every thread down, then speeds back up as normal responses return. Rate-limit responses are never shown as hits.

## Good to know

- **DNS mode uses your operating system's resolver**, not Burp's network stack, so upstream proxy and SOCKS settings don't apply to it.
- **Vhost over HTTPS** sends the TLS SNI of the target, not the vhost name, so servers that route purely on SNI won't reveal their vhosts this way.
- **Stop and unload are clean**: in-flight scans are cancelled and every thread exits.

## Building from source

Requires Java 17+ and Maven.

```bash
git clone https://github.com/ismailsanan/Buster
cd Buster
mvn clean verify        # builds and runs the test suite
```

The jar is written to `target/Buster.jar`.

## Contributing

- `dev` is the integration (staging) branch; `main` holds released code.
- Open pull requests against `dev`. CI runs `mvn verify` on every push and pull request.
- Releases are cut by tagging `vX.Y.Z` on `main`; see [CHANGELOG.md](CHANGELOG.md).
- The design, requirements and architecture are described in [docs/DESIGN.md](docs/DESIGN.md).

## Roadmap

- Live progress (requests/sec, ETA) with pause and resume
- Settings that persist across Burp restarts
- Word and line count matching
- Built-in wordlist presets
- Seeding Dir mode from paths already in proxy history

## Credits

Buster reimplements the core ideas of [gobuster](https://github.com/OJ/gobuster) and [feroxbuster](https://github.com/epi052/feroxbuster) as a native Burp extension. All credit for the original tools goes to their authors.

## License

[Apache License 2.0](LICENSE)
