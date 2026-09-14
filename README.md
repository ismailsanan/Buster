


# Buster

A Burp extension for content discovery: recursive directory brute forcing, virtual host discovery, and subdomain enumeration, without leaving Burp.

The idea is simple. Take the enumeration you'd normally do with standalone tools and run it through Burp's own HTTP stack instead. Same session, same scope, same proxy, and the results sit next to your traffic rather than in a separate terminal.

Inspired by [gobuster](https://github.com/OJ/gobuster) and [feroxbuster](https://github.com/epi052/feroxbuster).



## Modes

**Dir** brute forces paths and recurses into directories it finds, rather than doing a single flat pass. It calibrates against each directory first, sending a random path to learn what "not found" looks like, so catch all routing and custom 404 pages don't drown the results. A `301` to `/admin/` is treated as a real directory and queued for recursion. Supports an extension list, adjustable depth, and duplicate response collapsing.

**Vhost** sends requests to one target IP while varying the Host header, revealing virtual hosts served from the same address. It baselines with a junk Host value first, so on a wildcard setup where everything responds, only genuinely different responses get reported. Because Burp separates the connection target from the Host header, you can point at an IP and ask it for `dev.target.com`, `staging.target.com`, and so on over the same connection.

**DNS** resolves candidate subdomains against a base domain and reports the ones that exist, along with their IPs. Existing Burp subdomain tools only pull names already seen in traffic; this one actively brute forces them.

## Wordlists

An Intruder style box in each mode. Paste words straight in, or hit Load File to pull in a local list such as [SecLists](https://github.com/danielmiessler/SecLists).


## Results

One sortable table, shared across modes, styled after gobuster's output:

Result Status Size Notes
/admin 301 178 dir -> /admin/
/config.php 200 1204 file
/.git/ 200 452 dir


Status codes are colour coded (2xx green, 3xx cyan, 401/403 yellow, 5xx red), results stream in as they're found, and you can Copy them or Export to CSV.



## Installation

Build from source. Requires Java 17+ and Maven.

git clone https://github.com/ismailsanan/Buster
cd Buster
mvn clean package


The jar lands at `target/Buster.jar`. Then in Burp: Extensions, Add, type Java, select the jar, and open the Buster tab.



## Usage

**Dir.** Enter the target, paste or load a path wordlist, set your extensions, depth and thread count, and start. Hits stream in and directories are recursed automatically.

**Vhost.** Enter the target IP or host and the base domain, paste or load a subdomain wordlist, and start. Real virtual hosts are reported and wildcard responses are filtered out.

**DNS.** Enter the base domain, paste or load a subdomain wordlist, and start. Subdomains that resolve are listed with their IPs.


## Roadmap

DNS wildcard detection, so names that only resolve because everything does get discarded. Optional pushing of hits into Burp's site map for one click Repeater pivots. Response filtering by status, size, or regex.

