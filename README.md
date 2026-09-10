# Buster

Burp Suite extension, discovering recursive directory, vhost, DNS.

The idea is simple: take some of the enumeration workflows normally done with standalone tools and bring them directly into Burp Suite.

The project is mainly focused on web, with support planned for recursive directories, virtual hosts, DNS.

Inspired by [gobuster](https://github.com/OJ/gobuster) and [feroxbuster](https://github.com/epi052/feroxbuster) 

---

## Modes

### Dir (recursive directory & file discovery)
Brute-forces paths against a target, and **recurses into discovered directories** rather than a single flat pass.

* wildcard path in each directory before scanning it, so catch-all routing and custom error pages 
* redirect aware directory detection  `301 → /admin/` is treated as the canonical
* extension list (`.php`, `.bak`, `.env`, …), configurable recursion depth, and duplicate-response collapsing

### Vhost  (virtual host enumeration)
Sends requests to one target IP while varying the **Host header**, revealing virtual hosts served from the same address.

* built-in wildcard-vhost detection  baselines with a garbage Host value and only reports candidates whose response actually differs

* Burp cleanly separates the connection target from the Host header, so you point at an IP and ask it for `dev.target.com`, `staging.target.com`,  on the same connection

### DNS  (active subdomain brute-forcing)
Resolves candidate subdomains against a base domain and reports the ones that exist, with their resolved IPs.

---

## Wordlists

**Intruder-style wordlist box**: paste words directly, or **Load File** to pull in a local list (e.g. [SecLists](https://github.com/danielmiessler/SecLists))

---

## Results

A gobuster-styled, sortable results table shared across all modes:

```
Result                          Status   Size    Notes
/admin                          301      178     dir  -> /admin/
/config.php                     200      1204    file
/.git/                          200      452     dir
```

* status codes colour-coded (2xx green, 3xx cyan, 401/403 yellow, 5xx red)
* results stream in live as they're found
* **Copy** to clipboard or **Export CSV** for reporting

---

## Installation

### Build from source
Requirements: Java 17+, Maven

```
git clone https://github.com/ismailsanan/Buster
cd Buster
mvn clean package
```

The extension jar will be at `target/Buster.jar`.

### Load into Burp
1. Extensions → Add
2. Extension type: **Java**
3. Select `target/Buster.jar`
4. Open the **Buster** tab

---

## Usage

**Dir**
1. Enter the target (`https://example.com`)
2. Paste or load a path wordlist
3. Set extensions, recursion depth, and thread count
4. Start hits stream in, discovered directories are recursed automatically

**Vhost**
1. Enter the target IP/host and the base domain
2. Paste or load a subdomain wordlist
3. Start —real virtual hosts are reported, wildcard responses filtered out

**DNS**
1. Enter the base domain
2. Paste or load a subdomain wordlist
3. Start  resolving subdomains are listed with their IPs

---

## Roadmap

* DNS wildcard detection (discard names matching a random-probe IP)
* Optional push of dir/vhost hits into Burp's site map for one-click Repeater pivots
* Response-word filtering (include/exclude by status, size, or regex)

---

## Credits

Inspired by [gobuster](https://github.com/OJ/gobuster) and [feroxbuster](https://github.com/epi052/feroxbuster). Buster reimplements their core ideas as a native Burp extension; all credit for the original tools and their design goes to their authors.

## License

MIT
