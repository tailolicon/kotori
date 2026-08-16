#!/usr/bin/env python3
"""Refresh the domain feed the built-in Vietnamese anime sources resolve against.

These sites move host constantly, and until now the only list of where they live was
hardcoded in each extension — so following a move meant rebuilding and republishing an
apk that every user then had to update by hand. This writes the same knowledge into
`extensions/repo-anime/domains.json`, which the app fetches at runtime, so a move costs
a commit instead of a release.

Discovery is per-site because the two sites move differently:

* AnimeHay increments a counter in the host (`animehay11.site`, `animehay15.site`) and
  registers several ahead of time. Walking that counter, following redirects and keeping
  whatever answers is the only thing that works — it does not announce moves anywhere.
* AnimeVietsub publishes its current domain behind a permanent short link, which is the
  authoritative answer and needs no guessing at all.

Every host found is verified against a marker from the real page. A domain these sites
have abandoned gets picked up by squatters within days, and a parked page answers `200`
just as readily as the site, so "something responded" is not evidence of anything.

Usage:
    python .github/scripts/update_domains.py            # rewrite the feed if it changed
    python .github/scripts/update_domains.py --dry-run  # print findings, touch nothing
"""

from __future__ import annotations

import argparse
import gzip
import http.cookiejar
import json
import os
import re
import sys
import urllib.error
import urllib.request
import zlib
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

FEED_PATH = Path(__file__).resolve().parents[2] / "extensions" / "repo-anime" / "domains.json"

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)
TIMEOUT = 15
# The app probes serially and stops at the first host that answers, so its list only needs to be
# ordered; this has to actually walk every candidate, hence the pool.
WORKERS = 12
# Live hosts always come first, so the tail is history — a dead domain is kept because it may
# still redirect at the live one later, which is how a move gets noticed at all for AnimeVietsub.
MAX_DOMAINS = 16


# ============================== HTTP ==============================


def _decode(response, raw: bytes) -> str:
    encoding = response.headers.get("Content-Encoding", "")
    if encoding == "gzip":
        raw = gzip.decompress(raw)
    elif encoding == "deflate":
        raw = zlib.decompress(raw, -zlib.MAX_WBITS)
    return raw.decode("utf-8", errors="replace")


def get(url: str) -> tuple[str, str] | None:
    """Where `url` lands and what it served, or None if nothing answered.

    A fresh cookie jar per call, and one replay on `403`: AnimeVietsub answers the first
    request of a session with `403` plus a `Set-Cookie`, and the same request carrying that
    cookie gets a normal `200`. That is a handshake, not a block — the app's extension does
    exactly this, and without it every AnimeVietsub host here would look dead.
    """
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "vi,en-US;q=0.9,en;q=0.8",
            "Accept-Encoding": "gzip, deflate",
        },
    )
    for attempt in range(2):
        try:
            with opener.open(request, timeout=TIMEOUT) as response:
                return response.geturl(), _decode(response, response.read())
        except urllib.error.HTTPError as e:
            if e.code == 403 and attempt == 0 and len(jar) > 0:
                continue
            # A 404 or a Cloudflare challenge still proves a live server; read what we can.
            try:
                body = _decode(e, e.read())
            except Exception:
                body = ""
            return e.geturl(), body
        except Exception:
            return None
    return None


def host_of(url: str) -> str:
    return re.sub(r"^https?://", "", url).split("/")[0].lower()


class Probe:
    """What one host had to say.

    Answering and *being the site* are tracked apart because a runner is not a phone: these
    sites see a datacentre IP and can serve a challenge instead of a page, which reads as
    "the marker is missing" and is indistinguishable from a squatter unless the two are
    recorded separately. Collapsing them into a bool made a reachable site look dead.
    """

    def __init__(self, host: str | None, verified: bool, note: str):
        self.host = host
        self.verified = verified
        self.note = note


def resolve(url: str, marker: str) -> Probe:
    result = get(url)
    if result is None:
        return Probe(None, False, "no answer")
    landed, body = result
    host = host_of(landed)
    if marker.lower() in body.lower():
        return Probe(host, True, "live")
    return Probe(host, False, f"answered at {host}, but the page is not the site (no marker)")


# ============================== Discovery ==============================


def counter_candidates(known: list[str], template: str, ahead: int, span: int) -> list[str]:
    """Hosts to try for a site that numbers its domains.

    Both directions matter. Forward, because the site registers the next few in advance and
    moves onto them without warning. Backward, because the counter is not monotonic in
    practice — `animehay14` and `animehay15` both currently redirect down to `animehay11`,
    so a walk that only ever counts up would have declared the site dead.
    """
    numbers = {int(n) for host in known for n in re.findall(r"\d+", host)}
    highest = max(numbers, default=0)
    lowest = min(numbers, default=1)
    wanted = range(max(1, lowest - span), highest + ahead + 1)
    return [template % n for n in wanted]


def probe_all(hosts: list[str], marker: str) -> dict[str, Probe]:
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        return dict(zip(hosts, pool.map(lambda h: resolve(f"https://{h}/", marker), hosts)))


def rank(probes: dict[str, Probe], announced: list[str]) -> list[str]:
    """Verified hosts, best first.

    A host several others redirect at is where the site currently *is*, rather than merely
    another name it answers to, so it wins — and an announced host wins outright, because the
    site said so itself.
    """
    votes: dict[str, int] = {}
    for source, probe in probes.items():
        if not probe.verified:
            continue
        votes[probe.host] = votes.get(probe.host, 0) + 1
        # A host that redirects elsewhere is worth keeping as a name the site still answers to,
        # but it is not where the site is, so it earns no vote of its own.
        votes.setdefault(source, 0)

    def key(host: str) -> tuple:
        announced_rank = announced.index(host) if host in announced else len(announced)
        number = max((int(n) for n in re.findall(r"\d+", host)), default=0)
        return (announced_rank, -votes.get(host, 0), -number, host)

    return sorted(votes, key=key)


def discover(entry: dict) -> tuple[list[str], list[str], list[str]]:
    """What one source's hosts look like from here.

    Returns the verified hosts best-first, the hosts an announcement points at that could not
    be verified, and a log. The middle one exists because a CI runner is the one place these
    sites are most likely to refuse: AnimeVietsub serves a datacentre IP a challenge rather
    than a page, so from here it looks dead however healthy it is. Its own short link still
    redirects correctly, and that redirect is the only signal left worth reading.
    """
    marker = entry["marker"]
    previous = list(entry.get("domains", []))
    log: list[str] = []

    announced: list[str] = []
    unverified: list[str] = []
    for url in entry.get("announce", []):
        probe = resolve(url, marker)
        log.append(f"  announce {url}: {probe.note}")
        target = announced if probe.verified else unverified
        if probe.host and probe.host not in target:
            target.append(probe.host)

    candidates = list(dict.fromkeys(announced + unverified + previous + counter_hosts(entry)))
    probes = probe_all(candidates, marker)
    for host in candidates:
        probe = probes[host]
        if probe.verified:
            log.append(f"  {host} live" if probe.host == host else f"  {host} -> {probe.host}")
        elif probe.host:
            log.append(f"  {host}: {probe.note}")

    return rank(probes, announced), unverified, log


def counter_hosts(entry: dict) -> list[str]:
    template = entry.get("template")
    if not template:
        return []
    return counter_candidates(
        entry.get("domains", []),
        template,
        ahead=entry.get("ahead", 6),
        span=entry.get("behind", 6),
    )


# ============================== Feed ==============================


def load_feed() -> dict:
    return json.loads(FEED_PATH.read_text(encoding="utf-8"))


def write_feed(feed: dict) -> None:
    FEED_PATH.write_text(json.dumps(feed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def summarise(lines: list[str]) -> None:
    text = "\n".join(lines)
    print(text)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(text + "\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="report findings without writing")
    parser.add_argument("--only", help="refresh a single source key")
    args = parser.parse_args()

    feed = load_feed()
    report: list[str] = []
    changed = False
    all_dead: list[str] = []

    for entry in feed["sources"]:
        if args.only and entry["key"] != args.only:
            continue
        live, unverified, log = discover(entry)
        report.append(f"### {entry['key']}")
        report.extend(log or ["  nothing answered"])

        if not live and unverified:
            # The site is unreachable from a runner but still announcing where it is. Trust the
            # announcement: it comes from the site, and the app re-checks the marker anyway from
            # a connection that can actually reach it. Not doing this is how a source that CI
            # can never verify ends up never tracked at all.
            report.append(f"  unreachable from CI; trusting the announcement: {unverified[0]}")
            live = unverified
        elif not live:
            # Never blank a list on a bad probe: a runner-side network blip would otherwise
            # leave the app with no candidates at all until the next run six hours later.
            all_dead.append(entry["key"])
            report.append(f"  **no live host found — keeping {len(entry['domains'])} known**")
            continue

        merged = live + [d for d in entry["domains"] if d not in live]
        merged = merged[:MAX_DOMAINS]
        if merged != entry["domains"]:
            report.append(f"  changed: {entry['domains'][:3]} -> {merged[:3]}")
            entry["domains"] = merged
            changed = True
        else:
            report.append("  unchanged")

    if changed and not args.dry_run:
        feed["updated"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        write_feed(feed)
        report.append("\nfeed rewritten")
    elif not changed:
        report.append("\nno change")

    summarise(report)

    # A source whose every known host went dark at once is the interesting failure: either the
    # site moved somewhere this cannot find, or the runner cannot reach it. Both want a look.
    if all_dead:
        print(f"::warning::no live domain found for: {', '.join(all_dead)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
