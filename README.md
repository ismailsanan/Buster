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
| **Dir** | Finds hidden files and folders on a website (`/admin/`, `/backup.zip`). Recursive, with extensions and adjustable depth. Calibrates every directory first, so soft 404s, catch-all routes and blanket 401/403 pages don't flood the results. |
| **Vhost** | Finds websites hosted on **one server** (IP) that may not be in public DNS: asks the server for `admin.target.com`, `dev.target.com` ... through the `Host` header. |
| **DNS** | Finds **subdomains** of a domain (`admin.target.com`, `vpn.target.com` ...) by asking DNS whether each guessed name exists, and shows the IP each one points to. Needs only the domain, no IP. |

Every mode also gives you:

- **Live results**: a sortable table with colour-coded status codes and the full request and response for every HTTP hit.
- **Burp integration**: right-click hits to send them to Repeater or Intruder, or add them to the site map. Right-click any request elsewhere in Burp and pick a mode under **Extensions → Buster**.
- **Filters**: hide statuses (`404, 500-599`) or sizes, or match a regex. Copy and CSV export follow the filtered view.
- **Authenticated scans**: cookies and auth headers are added to every request.
- **Polite scanning**: an optional request rate limit and automatic backoff on `429`.
- **Progress**: a progress bar with percentage, requests per second and ETA; Dir shows which directory it is on (`dir 2 of 5`).
- **Auto-filter you can see**: the line above the results says what is hidden (for example `status 404 (any size)`), and the **Scan log** tab lists it per directory. Tick **Show all responses** to list everything, with "not found" responses marked as Type `baseline`.
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
1. Enter the server's **IP address** (`10.0.0.5`, or `10.0.0.5:8443` for another port), tick **HTTPS** or not, and the **Domain** (`target.com`). The blue line below shows an example Host header that will be sent.
2. Paste or load a subdomain wordlist, then press **Start**.

Each word is sent as `word.target.com` in the `Host` header to the same address. Only responses that differ from the junk-host baseline are reported.

### DNS

**What it does:** finds the subdomains of a domain. You give it `target.com`; it tells you that `admin.target.com`, `mail.target.com` and `vpn.target.com` exist, and which IP each one points to.

**Why it needs a wordlist:** DNS only answers yes/no questions about one exact name ("does `admin.target.com` exist?"). It won't hand out the full list of a domain's subdomains (a zone transfer, which almost every server refuses). So Buster guesses: it takes each word from the wordlist, puts it in front of the domain and asks DNS about that name.

```
wordlist      name asked                answer
admin    ->   admin.target.com     ->   10.0.0.7        found
dev      ->   dev.target.com       ->   (no such name)  skipped
vpn      ->   vpn.target.com       ->   203.0.113.20    found
```

A good wordlist is a list of common subdomain names, for example SecLists `Discovery/DNS/subdomains-top1million-5000.txt`.

**Wildcard DNS:** some domains answer for *every* name, including made-up ones (`*.target.com`). Before the scan Buster asks about a few random names. If they get an answer, the domain has a wildcard, and names that only point to the wildcard's IPs are hidden. The line above the results and the Scan log say whether that happened.

**Steps**
1. Enter the **Domain** (`target.com`). The blue line below it shows example names that will be tried.
2. Paste or **Load File** a subdomain wordlist.
3. Choose **Resolve via**, then press **Start**:
   - **System DNS resolver** (default): normal DNS lookups from your machine. Results show each name with its IP addresses (IPv4 `A` and IPv6 `AAAA`).
   - **Burp (HTTP probe)**: for targets you can only reach through Burp's upstream proxy or SOCKS (for example an internal network). Each name is opened as `https://name/` (then `http://name/`) through Burp; names that answer are listed with their HTTP status and size instead of an IP.

**DNS or Vhost?** DNS finds names that exist in DNS: you only need the domain. Vhost finds sites that a **specific server** hosts, even ones with no DNS record: you need that server's IP and the domain. A common flow is DNS first, then Vhost against the IPs it found.

### From anywhere in Burp
Right-click a request in Proxy, Target, Repeater or Logger and open **Extensions → Buster**: **Dir scan this directory**, **Vhost scan this host** or **DNS scan target.com**. This pre-fills the mode. The base domain is the registered domain (`a.b.target.com` → `target.com`); the drop-down also offers `a.b.target.com` and `b.target.com`. Nothing is sent until you press Start.

## Options

| Option | Where | Default | Notes |
|---|---|---|---|
| Threads | all modes | 30 / 10 / 30 | Concurrent requests or lookups. |
| Resolve via | DNS | System DNS resolver | `System DNS resolver` or `Burp (HTTP probe)`, see [DNS](#dns). |
| Extensions | Dir | empty | File suffixes tried for every word: with `php, bak`, the word `admin` sends `admin`, `admin.php` and `admin.bak`. Empty = only the bare words. |
| Depth | Dir | 1 | How many directory levels to recurse. `0` = scan only the target directory, `1` = also scan inside each directory found there, and so on. |
| Req/s | Dir, Vhost | 0 (no limit) | A hard cap on requests per second across all threads. |
| Headers / Auth | Dir, Vhost | empty | One `Name: value` per line. A bare `a=b` line becomes a cookie. |
| Extensions on recursion | Dir | off | Recursion happens either way (controlled by Depth). This only decides whether the extensions are also tried inside discovered directories. Off = extensions at the top level only, bare words deeper down (much faster). |
| Collapse duplicates | Dir | on | Hides repeated status+size responses within a directory. Directories are never hidden. |
| Show all responses | Dir, Vhost | off | Turns the auto-filter off: every response is listed, the ones that match the calibrated "not found" answer with Type `baseline`. Use Hide status / Hide size to trim. |
| Add hits to site map | results bar | off | Pushes every new hit into Target → Site map. |

When a target answers `429` (or `503` with `Retry-After`), Buster retries with growing backoff and slows every thread down, then speeds back up as normal responses return. Rate-limit responses are never shown as hits.

## Good to know

- **DNS with the system resolver** doesn't go through Burp, so upstream proxy and SOCKS settings don't apply. Switch **Resolve via** to **Burp** when they should.
- **Vhost over HTTPS** sends the TLS SNI of the target, not the vhost name, so servers that route purely on SNI won't reveal their vhosts this way.
- **Stop and unload are clean**: in-flight scans are cancelled and every thread exits.
- **A dead target ends the scan**: when nothing comes back for 3× the request timeout + 5 s (29 s in Dir), the scan stops with "target stopped answering" instead of waiting out every queued request.
- **Running next to Burp Scanner**: Buster sends through `api.http()` and registers no HTTP handler or scan check, so the two don't interfere directly. They do share Burp's network and the target, so expect both to slow down. If a live audit or live passive task (Dashboard) includes the **Extensions** tool, Burp will scan every Buster request too; leave Extensions out of that task's scope unless you want that.

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

- Pause and resume
- Settings that persist across Burp restarts
- Word and line count matching
- Built-in wordlist presets
- Seeding Dir mode from paths already in proxy history

## Credits

Buster reimplements the core ideas of [gobuster](https://github.com/OJ/gobuster) and [feroxbuster](https://github.com/epi052/feroxbuster) as a native Burp extension. All credit for the original tools goes to their authors.

## License

[Apache License 2.0](LICENSE)
