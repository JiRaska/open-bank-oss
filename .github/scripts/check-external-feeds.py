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
#        A feed that also has an in-cluster liveness registration (#4743/#4943's
#        `FeedFetchRecorder`, registered under a `const val FEED_NAME` beside the feed's
#        `@Scheduled` class) declares that file+const under `kotlin_liveness`, and this DRIFT half
#        asserts the two names agree. ADR-0237 point 2 keeps the CI probe (this file, falsifying
#        the URL from outside GitHub's runners) and the in-cluster gauge (measuring freshness from
#        inside the cluster) deliberately SEPARATE mechanisms — they measure different things and
#        folding them would silently reverse that decision. But they are only talking about the
#        SAME feed if the name string matches, and nothing enforced that before this: #4943 kept
#        `FEED_NAME = "cnb-daily-fixing"` equal to `FEEDS[...]['name']` by hand and said so in a
#        comment. A comment is not a check — this derives the assertion instead of trusting it.
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
import tempfile
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
        # The in-cluster liveness twin (#4743/#4943): `CnbRateIngestionScheduler` registers a
        # `FeedFetchRecorder` under this constant, and `openbank_feed_fetch_total{feed=...}` /
        # `openbank_workflow_last_success_age_seconds{workflow="feed-<name>"}` are only comparable
        # to THIS entry's verdict if the two names actually match.
        "kotlin_liveness": {
            "file": (
                "openbank-fx-service/src/main/kotlin/com/openbank/fx/infrastructure/"
                "schedule/CnbRateIngestionScheduler.kt"
            ),
            "const": "FEED_NAME",
        },
    },
    {
        "name": "eu-fsf-sanctions-list",
        "file": "openbank-sanctions-service/src/main/resources/application.yaml",
        "yaml_path": "openbank.sanctions.eu-fsf.url",
        "shape": "xml_document",
        "why": (
            "The European Commission's consolidated Financial Sanctions Files list "
            "(issue #8362). `EuFsfSaxParser` streams this XML into `SanctionsListImport`; "
            "an HTML error page or empty body in place of the feed would otherwise be "
            "parsed as zero designated entities, silently clearing the screening list "
            "instead of failing loudly."
        ),
        # No kotlin_liveness entry yet: this PR wires the import path (SanctionsImportService /
        # EuFsfSaxParser) but does not add a scheduled FeedFetchRecorder for it — a feed with
        # no kotlin_liveness is skipped by that half of the check, not flagged.
    },
]

# External URLs that exist in a scanned YAML but cannot be probed by an unauthenticated CI job.
# Each needs a reason. This list is the thing a human has to justify, not the coverage.
NOT_PROBED = [
    ("https://api.github.com", "authenticated API, not a data feed; failure is loud at call time"),
    ("https://api.deepinfra.com/v1/openai", "authenticated LLM gateway; needs a key"),
    # Card scheme developer sandboxes (ADR-0283 phase 2, #8810). Both refuse an unauthenticated
    # request, so a probe would report a failure that says nothing about the feed being healthy —
    # and no environment holds a credential today, which is why the adapters answer NOT_BOUND.
    ("https://sandbox.api.visa.com", "Visa developer sandbox; mutual TLS plus an API key, so an unauthenticated probe cannot reach a verdict"),
    ("https://sandbox.api.mastercard.com", "Mastercard developer sandbox; every call is OAuth 1.0a signed, so an unauthenticated probe cannot reach a verdict"),
    ("https://integrate.api.nvidia.com/v1", "authenticated LLM gateway; needs a key"),
    ("https://s3.eu-north-1.amazonaws.com", "AWS endpoint, reached with SigV4 credentials"),
    ("https://kc.open-bank.tech/realms/openbank-customers", "our own Keycloak realm, covered by its own probes"),
    ("https://pid.open-bank.tech", "our own PID issuer, covered by its own probes"),
    # ADR-0284 public business registers. Both are PER-ENTITY lookup APIs, not documents: there is
    # no URL to fetch without an identifier, so a shape probe would have to invent a company to ask
    # about and would then be asserting that company's continued existence rather than the feed's
    # health. Failure is loud where it happens — RegistryUnavailableException answers 503 and the
    # onboarding case lands in MANUAL_REVIEW rather than degrading to self-declaration, which is
    # the property `kyb.registry.lookups{outcome="unavailable"}` counts.
    ("https://ares.gov.cz/ekonomicke-subjekty-v-be/rest", "ARES per-IČO lookup API (kyb-service); no fixed document, and an outage becomes MANUAL_REVIEW, never a silent pass"),
    ("https://api.gleif.org/api/v1", "GLEIF per-LEI lookup API (kyb-service); same shape as ARES above"),
    # --- gitops corpus (#6242). Everything below became visible when CORPUS_GLOBS gained
    # `openbank-infra/gitops/**/*.yaml`. Each entry is stale-checked in BOTH directions by
    # check_drift: an entry whose URL leaves the tree fails just as loudly as an undeclared URL.
    #
    # (1) IDENTIFIERS, not fetch targets. A URL-shaped string nothing dereferences.
    ("https://www.apache.org/licenses/LICENSE-2.0", "SPDX licence identifier in an embedded SQL header; never fetched"),
    ("https://cyclonedx.org/bom", "CycloneDX schema URI in a Kyverno SBOM policy; an identifier the policy matches on"),
    ("https://slsa.dev/provenance/v0.2", "SLSA predicate-type URI in the Kyverno provenance policy and predicate builder; an identifier the policy matches on"),
    ("https://github.com/JiRaska/open-bank-oss", "configSource URI inside the SLSA provenance predicate (as git+https://…); an identifier the admission policy pins, never fetched"),
    ("https://openbank.dev/buildtypes/github-actions-docker-buildx/v1", "SLSA buildType identifier minted by build-slsa-provenance.py and pinned by the Kyverno policy; an identifier, not a host"),
    ("https://git.k8s.io", "upstream source link in a vendored CRD's description text; never fetched"),
    ("https://github.com/thanos-io/thanos/blob", "upstream doc link in a vendored CRD's description text; never fetched"),
    ("https://github.com/kubernetes-sigs/controller-tools/issues", "upstream issue link in a vendored CRD comment; never fetched"),
    ("https://github.com/JiRaska/open-bank-oss/blob", "runbook deep-link in an alert annotation; read by a human, not by a workload"),
    ("https://open-bank.tech/", "OAuth redirect/claimed-HTTPS identifier in a Keycloak client; not a feed"),
    ("https://flagd.dev", "flagd JSON-schema URI in a feature-flag ConfigMap; an identifier"),
    ("https://go.temporal.io", "Go module path in a Temporal chart value; not an HTTP fetch"),
    #
    # (2) DEPLOY-TIME sources. Resolved by Argo CD / the registry cache, not by a running
    # service. A failure blocks the sync or the pull loudly — it cannot go silent the way a
    # 404 on a data feed did (#2204), which is exactly why they are declared and not probed.
    ("https://github.com/JiRaska/open-bank-oss.git", "this repo, cloned by the realm-drift and tier-classifier CronJobs; a clone failure is loud"),
    ("https://gitlab.com", "upstream source repo pinned by the GlitchTip chart; deploy-time"),
    ("https://grafana.github.io", "Helm chart repository; deploy-time, a failure blocks the Argo CD sync"),
    ("https://open-telemetry.github.io", "Helm chart repository; deploy-time"),
    ("https://prometheus-community.github.io", "Helm chart repository; deploy-time"),
    ("https://argoproj.github.io", "Helm chart repository; deploy-time"),
    ("https://charts.external-secrets.io", "Helm chart repository; deploy-time"),
    ("https://charts.fairwinds.com", "Helm chart repository; deploy-time"),
    ("https://falcosecurity.github.io", "Helm chart repository; deploy-time"),
    ("https://kubernetes.github.io", "Helm chart repository; deploy-time"),
    ("https://kubernetes-sigs.github.io", "Helm chart repository; deploy-time"),
    ("https://kyverno.github.io", "Helm chart repository; deploy-time"),
    ("https://openbao.github.io", "Helm chart repository; deploy-time"),
    ("https://strimzi.io", "Helm chart repository; deploy-time"),
    ("https://robusta-charts.storage.googleapis.com", "Helm chart repository; deploy-time"),
    #
    # (3) REGISTRY / BUILD-ARTEFACT upstreams behind our own caches. Real third-party egress,
    # but each has an in-cluster cache whose own liveness is the signal, and none serves a
    # data feed a money-path job reads.
    ("https://registry-1.docker.io", "upstream mirrored by the in-cluster registry cache"),
    ("https://quay.io", "upstream mirrored by the in-cluster registry cache"),
    ("https://ghcr.io", "upstream mirrored by the in-cluster registry cache"),
    ("https://repo1.maven.org", "Maven Central, mirrored by Reposilite"),
    ("https://plugins.gradle.org", "Gradle plugin portal, mirrored by Reposilite"),
    ("https://dl.google.com", "Google Maven, mirrored by Reposilite"),
    #
    # (3b) AUTHENTICATED LLM EGRESS. Real third-party egress from a running workload, but it
    # cannot be probed: the endpoint answers 401 without a key, so a probe would assert the
    # liveness of an error page (the #2204 shape it exists to prevent). HolmesGPT dials this for
    # its meta/llama-3.1-70b-instruct route; a failure surfaces as a failed investigation, not as
    # a silently-empty table, and the LLM-failure alerts (#6041) cover the gateway path.
    # Same category and same reason as api.deepinfra.com above.
    ("https://integrate.api.nvidia.com/v1", "authenticated LLM endpoint for HolmesGPT; needs a key, so a probe would only measure a 401"),
    #
    # (4) ACME. Real egress; a failure surfaces as an un-renewed Certificate, which cert-manager
    # reports and the certificate-expiry alert covers.
    ("https://acme-v02.api.letsencrypt.org", "ACME directory; failure surfaces as a cert-manager Certificate condition"),
    ("https://acme-staging-v02.api.letsencrypt.org", "ACME staging directory; non-production issuer"),
    #
    # (5) OUR OWN public hostnames, each covered by its own probe or journey CronJob.
    ("https://admin.open-bank.tech", "our own admin-ui ingress; covered by the public-edge journey probe"),
    ("https://api.open-bank.tech", "our own API ingress; covered by the public-edge journey probe"),
    ("https://customer.open-bank.tech", "our own customer ingress; covered by the public-edge journey probe"),
    ("https://kc.open-bank.tech", "our own Keycloak ingress; covered by its own probes"),
    ("https://glitchtip.open-bank.tech", "our own GlitchTip ingress"),
    ("https://langfuse.open-bank.tech", "our own Langfuse ingress"),
    ("https://pact.open-bank.tech", "our own Pact Broker ingress"),
]

URL_IN_TEXT = re.compile(r"https?://[^\s\"'}\)>,]+")
_LOOPBACK = re.compile(r"^https?://(localhost|127\.0\.0\.1|0\.0\.0\.0)(:\d+)?(/|$)")
_PLAINTEXT = re.compile(r"^http://(?P<host>[^/:]+)(?P<port>:\d+)?")
_ANY_SCHEME = re.compile(r"^https?://(?P<host>[^/:]+)")


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
    m = _ANY_SCHEME.match(url)
    host = m.group("host") if m else ""
    # A Kubernetes DNS suffix is in-cluster under ANY scheme. This used to be tested only on
    # the `http://` branch, so `https://campaign-service.campaign.svc:8443` fell through to
    # "external by definition" and needed a NOT_PROBED entry to excuse an in-cluster mTLS call.
    # Widening the corpus to gitops (#6242) makes that shape the norm, not the exception.
    if host.endswith((".svc", ".cluster.local")) or ".svc." in host:
        return True
    m = _PLAINTEXT.match(url)
    if not m:
        return False  # https:// — external by definition
    host, port = m.group("host"), m.group("port")
    return "." not in host or bool(port)


# The corpus. DERIVED from the artifacts, not hand-kept: every service config plus every
# deployed manifest. It used to be the first glob alone, which is why a live LLM egress
# declared only in `openbank-infra/gitops/apps/holmesgpt.yaml` was outside this gate's reach
# and declared nowhere — the gate reported "every external URL accounted for" and exited 0
# while a workload that receives cluster diagnostics called a third party (#6242). A gate
# whose SCOPE is narrower than the subject it claims to govern reads as PASSING, never as
# UNCHECKED; adding a source here is the only way to change that, so it is one list, in code,
# next to the check that consumes it.
CORPUS_GLOBS = (
    "openbank-*/src/main/resources/application.yaml",
    "openbank-infra/gitops/**/*.yaml",
)


def scanned_yamls(root):
    base = pathlib.Path(root)
    out = set()
    for pattern in CORPUS_GLOBS:
        out.update(base.glob(pattern))
    return sorted(out)


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
            # LONGEST matching prefix, not any. `excused` is a set, so `next(...)` picked an
            # arbitrary one; with overlapping entries (a host and a path under it) that made
            # which entry got marked seen — and therefore which one was reported stale —
            # depend on hash order. Longest-prefix is deterministic and keeps a specific
            # entry meaningful next to a broader one covering the same host.
            candidates = [e for e in excused if url.startswith(e)]
            match = max(candidates, key=len) if candidates else None
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


_KOTLIN_CONST = re.compile(r'const\s+val\s+(\w+)\s*=\s*"([^"]*)"')


def extract_kotlin_const(text, const_name):
    """Pull `const val <const_name> = "value"` out of Kotlin source text.

    Returns None when the constant is absent — never an empty string, so a genuinely blank
    declaration (`const val FEED_NAME = ""`) is still distinguishable from "not found here".
    Deliberately a plain regex over the source text, not a real Kotlin parse: the one thing this
    needs to survive is a companion object's formatting, not arbitrary Kotlin.
    """
    for name, value in _KOTLIN_CONST.findall(text):
        if name == const_name:
            return value
    return None


def check_kotlin_feed_names(root, feeds=None):
    """A declared feed's CI name and its in-cluster liveness name must be the same string.

    Narrower than folding the two mechanisms together (ADR-0237 point 2 keeps them separate on
    purpose — see the module docstring). This only asserts the two lanes are talking about the
    SAME feed: `FEEDS[...]['name']` here vs the `const val FEED_NAME` the Kotlin scheduler
    registers its `FeedFetchRecorder` under. A feed with no `kotlin_liveness` entry is skipped —
    not every declared feed has an in-cluster liveness registration (yet), and this check has
    nothing to compare for those.
    """
    problems = []
    for feed in feeds if feeds is not None else FEEDS:
        kl = feed.get("kotlin_liveness")
        if not kl:
            continue
        path = pathlib.Path(root) / kl["file"]
        if not path.exists():
            problems.append(
                f"DRIFT {feed['name']}: kotlin_liveness file does not exist: {kl['file']}",
            )
            continue
        value = extract_kotlin_const(path.read_text(encoding="utf-8"), kl["const"])
        if value is None:
            problems.append(
                f"DRIFT {feed['name']}: no `const val {kl['const']}` found in {kl['file']} — the "
                f"CI probe and the in-cluster feed liveness gauge can no longer be checked for "
                f"agreement",
            )
        elif value != feed["name"]:
            problems.append(
                f"DRIFT {feed['name']}: FEEDS declares {feed['name']!r} but {kl['file']}'s "
                f"`{kl['const']}` is {value!r} — the CI probe and the in-cluster feed liveness "
                f"gauge would be naming two different feeds",
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

    # kotlin_liveness name-consistency: the two lanes (this file's FEEDS, the in-cluster
    # `FeedFetchRecorder`'s `const val FEED_NAME`) must be checked for agreement, not just
    # assumed by hand as #4943's own comment did. Falsify both ways — the agreeing case must
    # pass, and each disagreement shape (renamed const, missing const, missing file) must be
    # reported as DRIFT.
    kotlin_cases = [
        ("agreeing const passes clean", 'const val FEED_NAME = "cnb-daily-fixing"', True, True),
        ("renamed const is reported as DRIFT", 'const val FEED_NAME = "cnb-fixing-renamed"', True, False),
        ("const absent from the file is reported as DRIFT", 'const val OTHER_NAME = "cnb-daily-fixing"', True, False),
        ("missing kotlin file is reported as DRIFT", None, False, False),
    ]
    for name, kotlin_source, write_file, should_pass in kotlin_cases:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            if write_file:
                (root / "Scheduler.kt").write_text(kotlin_source, encoding="utf-8")
            fake_feeds = [
                {
                    "name": "cnb-daily-fixing",
                    "kotlin_liveness": {"file": "Scheduler.kt", "const": "FEED_NAME"},
                },
            ]
            problems = check_kotlin_feed_names(str(root), feeds=fake_feeds)
            ok = (not problems) == should_pass
            print(f"{'pass' if ok else 'FAIL'}  {name}" + ("" if ok else f"  (got {problems!r})"))
            failures += 0 if ok else 1

    # A feed with no kotlin_liveness entry must be skipped, not flagged — not every declared
    # feed has an in-cluster registration.
    skip_problems = check_kotlin_feed_names(".", feeds=[{"name": "no-liveness-yet"}])
    ok = skip_problems == []
    print(f"{'pass' if ok else 'FAIL'}  a feed with no kotlin_liveness entry is skipped" + ("" if ok else f"  (got {skip_problems!r})"))
    failures += 0 if ok else 1

    total = len(cases) + 2 + len(triage_cases) + len(kotlin_cases) + 1
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
    problems += check_kotlin_feed_names(args.root)

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
