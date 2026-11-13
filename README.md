


# Buster

A Burp extension for content discovery: recursive directory brute forcing, virtual host discovery, and subdomain enumeration, without leaving Burp.

The idea is simple. Take the enumeration you'd normally do with standalone tools and run it through Burp's own HTTP stack instead. Same session, same scope, same proxy, and the results sit next to your traffic rather than in a separate terminal.

Inspired by [gobuster](https://github.com/OJ/gobuster) and [feroxbuster](https://github.com/epi052/feroxbuster).

**Why Buster:** it works in Burp Community as well as Professional, combines recursive directory, virtual host and DNS discovery in one tab, applies your session cookies / auth headers to every request, and every hit keeps its full request and response for inspection inside Burp.


[POC.webm](https://github.com/user-attachments/assets/3b3ad8f9-61bd-48d0-8a0e-2db61ae4964d)



## Modes

**Dir** brute forces paths and recurses into directories it finds, rather than doing a single flat pass. It calibrates against each directory first, sending several random paths to learn what "not found" looks like, so catch all routing, custom 404 pages and blanket 403 responses don't drown the results. If calibration gets no response the scan stops (or skips that directory) instead of guessing. A `301` to `/admin/` is treated as a real directory and queued for recursion; other extensionless hits are only recursed into after `/name/` itself is confirmed to exist. Supports an extension list, adjustable depth, custom headers, and duplicate response collapsing (per directory, never hiding directories).

**Vhost** sends requests to one target IP while varying the Host header, revealing virtual hosts served from the same address. It baselines with a junk Host value first, so on a wildcard setup where everything responds, only genuinely different responses get reported. Because Burp separates the connection target from the Host header, you can point at an IP and ask it for `dev.target.com`, `staging.target.com`, and so on over the same connection. Custom headers are supported here too. Note: over HTTPS the TLS SNI is the target host, so servers that route purely on SNI will not reveal their vhosts this way.

**DNS** resolves candidate subdomains against a base domain and reports the ones that exist, along with their IPs. Existing Burp subdomain tools only pull names already seen in traffic; this one actively brute forces them. Wildcard DNS is detected up front and names that only resolve to the wildcard IPs are filtered out.

> DNS lookups use the operating system resolver, not Burp's network stack, so upstream proxy / SOCKS settings do not apply to DNS mode.

## Wordlists

An Intruder style box in each mode. Paste words straight in, or hit Load File to pull in a local list such as [SecLists](https://github.com/danielmiessler/SecLists).


## Results

Dir and Vhost share one sortable table, styled after gobuster's output; selecting a row shows the full request and response:

```
Result                        Status  Size  Type  Redirect
https://target/admin          301     178   dir   https://target/admin/
https://target/config.php     200     1204  file
https://target/.git/          200     452   dir
```

DNS mode lists `Subdomain / Type / Value`.


Status codes are colour coded (2xx green, 3xx cyan, 401/403 yellow, 5xx red), results stream in as they're found, and you can Copy them or Export to CSV (cells are escaped against spreadsheet formula injection).

- **Filter** the view by hiding statuses (`404, 500-599`), hiding sizes, or matching a regex against the URL or redirect. Copy and Export use the filtered view.
- **Right click** one or more hits to Send to Repeater, Send to Intruder, Add to site map, Copy URLs, or hide that size or status.
- Tick **Add hits to site map** to push every new hit into Target > Site map as it arrives.



## Installation

Build from source. Requires Java 17+ and Maven.

```
git clone https://github.com/ismailsanan/Buster
cd Buster
mvn clean package
```


The jar lands at `target/Buster.jar`. Prebuilt jars are attached to each [GitHub release](https://github.com/ismailsanan/Buster/releases). Then in Burp: Extensions, Add, type Java, select the jar, and open the Buster tab.



## Usage

**Dir.** Enter the target, paste or load a path wordlist, set your extensions, depth and thread count, and start. Hits stream in and directories are recursed automatically.

**Vhost.** Enter the target IP or host and the base domain, paste or load a subdomain wordlist, and start. Real virtual hosts are reported and wildcard responses are filtered out.

**DNS.** Enter the base domain, paste or load a subdomain wordlist, and start. Subdomains that resolve are listed with their IPs.

**From anywhere in Burp.** Right click a request in Proxy, Target, Repeater or Logger and pick *Send to Buster* to pre-fill Dir (from that directory), Vhost (that host) or DNS (its domain). Nothing starts until you press Start.

**Rate limiting.** Dir and Vhost take an optional *Req/s* limit (0 = none). Independently, when the target answers `429` (or `503` with `Retry-After`) Buster retries with a growing backoff and slows all threads down, then speeds back up as normal responses return. Rate-limit responses are never reported as hits.


## Roadmap

Live progress (requests/sec, ETA) with pause and resume. Persisted settings. Word/line count matching. Built-in wordlist presets. Seeding Dir mode from paths already in proxy history.


## Documentation

See [docs/DESIGN.md](docs/DESIGN.md) for the functional and non-functional requirements and the architecture.


## Credits

Inspired by [gobuster](https://github.com/OJ/gobuster) and [feroxbuster](https://github.com/epi052/feroxbuster). Buster reimplements their core ideas as a native Burp extension; all credit for the original tools goes to their authors.


