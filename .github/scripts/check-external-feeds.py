#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# External public-feed watch: catch a dead third-party data feed before a money-path job
# silently stops producing (issue #2204).
#
# WHY THIS EXISTS
#   `openbank-fx-service` ingests the statutory ČNB daily fixing from a public text feed. On
#   2026-07-31 that feed's configured URL was a 404 — one path segment short — and had been for
#   the whole life of the service. ČNB serves the 404 as a 58 KB HTML page, `CnbFixingParser`
#   rejects it, and `CnbRateIngestionScheduler`'s catch swallows the exception into a single
#   ERROR line. So:
#
#     * every layer was green. The scheduler fired (after #2187 fixed HR000068), the rest-client
#       resolved, the circuit breaker never opened — a 404 is a perfectly good HTTP response.
#     * the ONLY evidence was an absence: `fx_rates` stopped gaining `source = CNB` rows. Nothing
#       alerts on a table not growing.
#     * downstream, `FxRevaluationService` reads `findLatestBySource(... validTo > now)`, gets
#       `null`, logs "No ČNB rate for EUR/CZK — skipping its revaluation leg", and returns
#       `posted = false`. A successful-looking run of a job that revalued nothing.
#
#   A URL in a config file is not covered by any test in this repo, by construction: a unit test
#   stubs the client, an IT serves a local fixture, and a consumer pact answers whatever path it
#   is asked for (the #2283 lesson — only the real provider can falsify a path). The upstream here
#   is a foreign government feed with no pact and no contract; the only thing that can falsify its
#   URL is fetching it.
#
# WHAT IT CHECKS
#   1. DRIFT, both directions, offline — this half is PR-BLOCKING.
#        Every external `http(s)://` URL in a scanned `application.yaml` must be either
#        (a) declared in FEEDS, so it is probed, or
#        (b) declared in NOT_PROBED with a reason it cannot be.
#        A new external dependency added without either is a feed this watch would silently know
#        nothing about while still passing. This is the repo's derived-scope rule: a gate whose
#        coverage set is maintained separately from the artifacts it covers reads as *passing*
#        when the set is short, never as *unchecked*.
#
#        FEEDS holds NO copy of the URL. It names the YAML path (`quarkus.rest-client.cnb-feed.url`)
#        and the probe reads the value out of the committed file. A second copy would move together
#        with the first and the probe would keep passing against a URL the service does not use —
#        the same vacuous shape as deriving both halves of a pact from one annotation.
#
#   2. LIVENESS, online — this half NEVER blocks a PR; it escalates to an issue.
#        Fetch each declared feed and assert its SHAPE, not merely its status code. A 200 proves
#        a server answered; it does not prove the answer is the feed. ČNB's own 404 page is a
#        200-shaped HTML document to anything that only reads the status line, and several CDNs
#        return 200 + an interstitial. So each feed declares a matcher that the real payload must
#        satisfy, and `--self-test` runs every matcher against a payload it MUST accept and
#        payloads it MUST reject (including that HTML error page). A matcher that has only ever
#        returned "fine" is indistinguishable from one that always does.
#
# EXIT CODES
#   0  no drift, every declared feed live and correctly shaped
#   1  DRIFT — an undeclared external URL, a declared feed whose YAML path no longer resolves, or
#      a stale NOT_PROBED entry. Offline, deterministic, and a PR must not merge over it.
#   2  at least one declared feed is DEAD/misshapen, or could not be reached while others could.
#      Actionable, but a property of the internet at this moment, so it escalates rather than
#      blocking a merge. Every feed is still attempted and reported — one feed's timeout never
#      suppresses another's verdict (see `triage`).
#   3  nothing could be determined at all: PyYAML missing, or NO feed was reachable, which points
#      at this runner's network rather than at any feed. NEVER conflate with 0 — a fetch that
#      fails is not evidence the feed is fine, and this script exists precisely because a broken
#      thing's silence read as health for 46 days.
#
# Run:  python3 .github/scripts/check-external-feeds.py [--root .] [--self-test] [--offline]

import argparse
import pathlib
import re
import sys
import urllib.error
import urllib.request

try:
    import yaml
except ImportError:  # pragma: no cover - reported as exit 3 by main()
    yaml = None

TIMEOUT_SECONDS = 25
USER_AGENT = "openbank-external-feed-watch/1.0 (+https://github.com/JiRaska/open-bank-oss)"

# ---------------------------------------------------------------------------------------------
# Shape matchers. Each returns None when the payload IS the expected feed, or a short reason why
# it is not. They must reject an HTML error page — that is the failure this whole script is about.
# ---------------------------------------------------------------------------------------------


def _looks_like_html(text):
    head = text.lstrip()[:200].lower()
    return head.startswith("<!doctype html") or head.startswith("<html") or "<head>" in head


def shape_cnb_fixing(text):
    """The ČNB daily central-bank fixing text feed.

    Line 1 is `DD.MM.YYYY #seq`; the rest are `country|currency|amount|code|rate`. Asserting the
    header AND a plausible number of rate lines is what separates the feed from a 200-with-nothing
    and from the HTML 404 page ČNB actually serves for a wrong path.
    """
    if _looks_like_html(text):
        return "payload is an HTML page, not the fixing text feed"
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    if not lines:
        return "payload is empty"
    if not re.match(r"^\d{2}\.\d{2}\.\d{4}\s+#\d+$", lines[0]):
        return f"first line is not a `DD.MM.YYYY #seq` header: {lines[0][:60]!r}"
    rates = [ln for ln in lines[1:] if len(ln.split("|")) == 5 and ln.split("|")[2].strip().isdigit()]
    if len(rates) < 5:
        return f"only {len(rates)} parseable rate line(s) — the feed normally carries ~30"
    return None


def shape_xml_document(text):
    """Any XML payload with at least one element — used for registry-style feeds."""
    if _looks_like_html(text):
        return "payload is an HTML page, not XML"
    stripped = text.lstrip()
    if not stripped.startswith("<?xml") and not stripped.startswith("<"):
        return f"payload does not begin as XML: {stripped[:60]!r}"
    if not re.search(r"<[A-Za-z_][\w.:-]*[\s/>]", stripped):
        return "payload contains no XML element"
    return None


SHAPES = {
    "cnb_fixing": shape_cnb_fixing,
    "xml_document": shape_xml_document,
}

# ---------------------------------------------------------------------------------------------
# Declared feeds. `yaml_path` is the dotted key whose VALUE is the URL — never the URL itself.
# ---------------------------------------------------------------------------------------------

FEEDS = [
    {
        "name": "cnb-daily-fixing",
        "file": "openbank-fx-service/src/main/resources/application.yaml",
        "yaml_path": "quarkus.rest-client.cnb-feed.url",
        "shape": "cnb_fixing",
        "why": (
            "The statutory ČNB fixing. `FxRevaluationService` marks every foreign position to it "
            "(ADR-0046); with no rate the revaluation skips the leg and posts nothing."
        ),
    },
]

# External URLs that exist in a scanned YAML but cannot be probed by an unauthenticated CI job.
# Each needs a reason. This list is the thing a human has to justify, not the coverage.
NOT_PROBED = [
    ("https://api.github.com", "authenticated API, not a data feed; failure is loud at call time"),
    ("https://api.groq.com/openai/v1", "authenticated LLM gateway (ADR-0139); needs a key"),
    ("https://api.deepinfra.com/v1/openai", "authenticated LLM gateway; needs a key"),
    ("https://integrate.api.nvidia.com/v1", "authenticated LLM gateway; needs a key"),
    ("https://s3.eu-central-1.amazonaws.com", "AWS endpoint, reached with SigV4 credentials"),
    ("https://kc.open-bank.tech/realms/openbank-customers", "our own Keycloak realm, covered by its own probes"),
    ("https://pid.open-bank.tech", "our own PID issuer, covered by its own probes"),
]

URL_IN_TEXT = re.compile(r"https?://[^\s\"'}\)>,]+")
_LOOPBACK = re.compile(r"^https?://(localhost|127\.0\.0\.1|0\.0\.0\.0)(:\d+)?(/|$)")
_PLAINTEXT = re.compile(r"^http://(?P<host>[^/:]+)(?P<port>:\d+)?")


def is_internal(url):
    """True for a URL that is in-cluster by construction, so not a third-party feed.

    Deliberately narrow, because a false "internal" is a feed this watch never looks at. A
    plaintext `http://` URL qualifies only when something else also marks it as cluster-local:
    a loopback host, a dotless service name, an explicit port (every k8s service URL in this
    tree carries one; a public feed uses the default 443), or an explicit `.svc`/`.cluster.local`
    suffix. Anything over `https://` is treated as external without exception — a banking
    service does not fetch a third-party feed in plaintext, so `https` is the honest tell.
    """
    if _LOOPBACK.match(url):
        return True
    m = _PLAINTEXT.match(url)
    if not m:
        return False  # https:// — external by definition
    host, port = m.group("host"), m.group("port")
    return "." not in host or bool(port) or host.endswith((".svc", ".cluster.local"))


def scanned_yamls(root):
    return sorted(pathlib.Path(root).glob("openbank-*/src/main/resources/application.yaml"))


def external_urls_in(path):
    """Every third-party URL literal in a YAML, ignoring comments and internal hosts."""
    found = []
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.lstrip().startswith("#"):
            continue
        for url in URL_IN_TEXT.findall(line):
            url = url.rstrip("},")
            if is_internal(url):
                continue
            found.append((lineno, url))
    return found


def resolve_yaml_path(doc, dotted):
    node = doc
    for part in dotted.split("."):
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node if isinstance(node, str) else None


def strip_config_default(value):
    """`${CNB_FEED_URL:https://…}` -> the default. A plain URL passes through unchanged."""
    m = re.fullmatch(r"\$\{[A-Za-z_][A-Za-z0-9_]*:(.*)\}", value.strip())
    return (m.group(1) if m else value).strip()


def load_feed_urls(root, problems):
    """Read each declared feed's URL out of its committed YAML. Never from a table."""
    resolved = []
    for feed in FEEDS:
        path = pathlib.Path(root) / feed["file"]
        if not path.exists():
            problems.append(f"DRIFT {feed['name']}: {feed['file']} does not exist")
            continue
        doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        raw = resolve_yaml_path(doc, feed["yaml_path"])
        if raw is None:
            problems.append(
                f"DRIFT {feed['name']}: `{feed['yaml_path']}` no longer resolves in {feed['file']} "
                f"— the probe would test nothing",
            )
            continue
        url = strip_config_default(raw)
        if is_internal(url):
            problems.append(f"DRIFT {feed['name']}: `{feed['yaml_path']}` is not an external URL: {url}")
            continue
        resolved.append({**feed, "url": url})
    return resolved


def check_drift(root, resolved):
    """Every external URL in every scanned YAML is declared, and every declaration is live."""
    problems = []
    probed = {f["url"] for f in resolved}
    excused = {u for u, _ in NOT_PROBED}
    seen_excused = set()

    for path in scanned_yamls(root):
        for lineno, url in external_urls_in(path):
            if url in probed:
                continue
            match = next((e for e in excused if url.startswith(e)), None)
            if match:
                seen_excused.add(match)
                continue
            problems.append(
                f"DRIFT undeclared external URL {path}:{lineno} -> {url}\n"
                f"       Declare it in FEEDS (with a shape matcher) or in NOT_PROBED (with a reason).",
            )

    for url, reason in NOT_PROBED:
        if url not in seen_excused:
            problems.append(
                f"DRIFT stale NOT_PROBED entry: {url} ({reason}) is no longer in any scanned YAML",
            )
    return problems


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as resp:  # noqa: S310 - fixed https feeds
        return resp.status, resp.read().decode("utf-8", errors="replace")


def check_liveness(resolved):
    """Fetch and shape-check every feed. Returns (dead, unreachable, live).

    All three are kept apart on purpose, and every feed is attempted regardless of what the
    previous one did: "dead" is a verdict, "unreachable" is the absence of one, and conflating
    them is how a probe's own failure gets reported as a statement about the thing probed.
    """
    dead, unreachable, live = [], [], []
    for feed in resolved:
        try:
            status, body = fetch(feed["url"])
        except urllib.error.HTTPError as exc:
            dead.append(f"DEAD {feed['name']}: HTTP {exc.code} for {feed['url']}\n       {feed['why']}")
            continue
        except (urllib.error.URLError, OSError, TimeoutError) as exc:
            unreachable.append(f"UNREACHABLE {feed['name']}: {exc} ({feed['url']})")
            continue
        if status != 200:
            dead.append(f"DEAD {feed['name']}: HTTP {status} for {feed['url']}\n       {feed['why']}")
            continue
        reason = SHAPES[feed["shape"]](body)
        if reason:
            dead.append(
                f"DEAD {feed['name']}: HTTP 200 but the payload is not the feed — {reason}\n"
                f"       {feed['url']}\n       {feed['why']}",
            )
        else:
            print(f"OK   {feed['name']}: {feed['url']}")
            live.append(feed["name"])
    return dead, unreachable, live



def triage(dead, unreachable, live):
    """Turn per-feed outcomes into (exit_code, report_lines). Pure, so it is self-testable.

    Per-feed verdicts, not one verdict for the batch. The first version returned 3 the moment
    ANY feed was unreachable, which had two consequences discovered on the very first scheduled
    run (#2917, run 30653484527):

      1. It suppressed the verdict on every other feed. `cnb-daily-fixing` had been fetched and
         shape-checked successfully in that same run, and `cnb-bank-registry` timing out threw
         that away. Had the FIXING been the dead one, its escalation would have been masked by an
         unrelated timeout — the exact "a gate reports about something other than what it checked"
         shape this script exists to prevent.
      2. It made a daily job permanently red. `apl.cnb.cz` does not answer GitHub's runners at all
         (it 404s from the cluster and from a laptop, so this is network-position specific, not an
         outage). A scheduled workflow that is red every single day is a red addressed to nobody
         and gets filtered within a week.

    So unreachable is reported and escalated like any other actionable state, and exit 3 is
    reserved for "nothing at all could be determined" — the probe being broken rather than a feed
    being unhappy.
    """
    lines = list(dead)
    if unreachable:
        lines += unreachable
        lines.append("")
        lines.append("Unreachable is NOT a verdict about the feed — it is the absence of one.")
    if unreachable and not live and not dead:
        lines.append("")
        lines.append("No feed could be reached at all. Suspect this runner's network, not the feeds.")
        return 3, lines
    if dead or unreachable:
        lines.append("")
        lines.append(
            f"{len(dead)} dead/misshapen, {len(unreachable)} undeterminable, {len(live)} live.",
        )
        return 2, lines
    return 0, lines


CNB_GOOD = "31.07.2026 #146\nzemě|měna|množství|kód|kurz\nEMU|euro|1|EUR|24,915\n" + "".join(
    f"Země{i}|měna|1|C{i:02d}|1,0{i}\n" for i in range(9)
)
CNB_404_HTML = '<!DOCTYPE html>\n<html lang="cs">\n<head>\n<title>Stránka nenalezena</title>\n</head>\n'


def self_test():
    """Exercise every matcher against a payload it MUST accept and payloads it MUST reject.

    The rejection cases are the point. A shape matcher is only trustworthy if it has been shown
    to fire — and the single most important input here is the exact HTML error page that started
    this whole issue by being accepted as a successful fetch for 46 days.
    """
    cases = [
        ("cnb_fixing accepts the real feed", shape_cnb_fixing, CNB_GOOD, True),
        ("cnb_fixing rejects the ČNB 404 HTML page", shape_cnb_fixing, CNB_404_HTML, False),
        ("cnb_fixing rejects an empty 200", shape_cnb_fixing, "   \n\n", False),
        ("cnb_fixing rejects a header with no rate lines", shape_cnb_fixing, "31.07.2026 #146\n", False),
        (
            "cnb_fixing rejects a plausible-but-wrong header",
            shape_cnb_fixing,
            "2026-07-31 #146\nEMU|euro|1|EUR|24,915\n",
            False,
        ),
        ("xml_document accepts XML", shape_xml_document, '<?xml version="1.0"?><banks><bank/></banks>', True),
        ("xml_document rejects an HTML error page", shape_xml_document, CNB_404_HTML, False),
        ("xml_document rejects plain text", shape_xml_document, "Not Found", False),
    ]
    failures = 0
    for name, matcher, payload, should_pass in cases:
        reason = matcher(payload)
        ok = (reason is None) == should_pass
        print(f"{'pass' if ok else 'FAIL'}  {name}" + ("" if ok else f"  (got {reason!r})"))
        failures += 0 if ok else 1

    # The config-default unwrapper is load-bearing: get it wrong and every probe fetches the
    # literal string "${CNB_FEED_URL:https://…}" and fails for the wrong reason.
    for raw, want in [
        ("${CNB_FEED_URL:https://x.test/a.txt}", "https://x.test/a.txt"),
        ("https://x.test/a.txt", "https://x.test/a.txt"),
    ]:
        got = strip_config_default(raw)
        ok = got == want
        print(f"{'pass' if ok else 'FAIL'}  strip_config_default({raw!r})" + ("" if ok else f" -> {got!r}"))
        failures += 0 if ok else 1

    # Triage cases. The first two are the regression from run 30653484527: a dead feed must still
    # be reported and escalated when a DIFFERENT feed is merely unreachable, and a lone timeout
    # must not turn a daily job permanently red. Exit 3 survives only for "nothing determinable".
    triage_cases = [
        ("dead alone -> 2", (["DEAD a"], [], ["b"]), 2),
        ("dead + unreachable -> 2, dead still reported", (["DEAD a"], ["UNREACHABLE b"], []), 2),
        ("unreachable but something live -> 2, not 3", ([], ["UNREACHABLE b"], ["a"]), 2),
        ("nothing reachable at all -> 3", ([], ["UNREACHABLE a", "UNREACHABLE b"], []), 3),
        ("all live -> 0", ([], [], ["a", "b"]), 0),
    ]
    for name, args, want in triage_cases:
        code, lines = triage(*args)
        ok = code == want
        # A dead feed that is not printed is the failure mode, not just a wrong exit code.
        if args[0] and not any(ln.startswith("DEAD") for ln in lines):
            ok = False
            name += " [dead feed missing from the report]"
        print(f"{'pass' if ok else 'FAIL'}  {name}" + ("" if ok else f"  (got {code}, want {want})"))
        failures += 0 if ok else 1

    total = len(cases) + 2 + len(triage_cases)
    print(f"\nself-test: {total - failures} passed, {failures} failed")
    return 0 if failures == 0 else 3


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--self-test", action="store_true", help="exercise the shape matchers and exit")
    parser.add_argument("--offline", action="store_true", help="run the drift half only (no network)")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if yaml is None:
        print("::error::PyYAML is not installed — the probe could not run. This is NOT a pass.")
        return 3

    problems = []
    resolved = load_feed_urls(args.root, problems)
    problems += check_drift(args.root, resolved)

    if problems:
        print("\n".join(problems))
        print(f"\n{len(problems)} drift problem(s). This half is offline and deterministic.")
        return 1
    print(f"drift: OK — {len(resolved)} declared feed(s), every external URL accounted for.")

    if args.offline:
        return 0

    dead, unreachable, live = check_liveness(resolved)
    code, lines = triage(dead, unreachable, live)
    if lines:
        print("\n".join(lines))
    return code


if __name__ == "__main__":
    sys.exit(main())
