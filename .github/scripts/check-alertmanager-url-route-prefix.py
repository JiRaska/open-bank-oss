#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Assert every hand-written Alertmanager API URL carries the route prefix Alertmanager actually serves.

WHY THIS EXISTS.

`alertmanagerSpec.routePrefix: /tools/alertmanager` (apps/kube-prometheus-stack.yaml) moves the
WHOLE HTTP surface, `/api/v2/alerts` included, so the bare service URL answers 404 on every API
path. A component configured with the bare URL therefore posts alerts into a 404 and drops them.

That is not hypothetical. Measured live 2026-08-21, the first time a Loki rule ever fired in this
estate (the ruler itself had never loaded a rule until that day, #6032):

    loki_prometheus_notifications_sent_total    = 10
    loki_prometheus_notifications_errors_total  = 10     <- every single one
    loki_prometheus_notifications_dropped_total = 10

with the ruler reporting `state: firing` and Alertmanager holding zero such alerts. From inside the
cluster, POST to `…:9093/api/v2/alerts` -> 404, and to `…:9093/tools/alertmanager/api/v2/alerts`
-> 200.

The reason a gate is warranted rather than a comment: the comment ALREADY EXISTED. Sitting eleven
lines above the `routePrefix` value, apps/kube-prometheus-stack.yaml says "routePrefix moves the
whole HTTP surface, INCLUDING /api/v2/alerts, which is how Prometheus delivers alerts" — and the
change that added the ruler's `alertmanager_url` still wrote the bare URL. Prose in a neighbouring
file does not survive being read by someone editing a different one.

Prometheus itself is unaffected: prometheus-operator configures its Alertmanager target through
`prometheusSpec.alertingEndpoints` with an explicit `pathPrefix`. Only hand-configured clients —
the Loki ruler today — are exposed, which is exactly why this is small and worth keeping small.

Failure shape it catches: an alert that fires, is delivered nowhere, and is indistinguishable from
an alert that never fired on every dashboard in this estate.

Usage:
    check-alertmanager-url-route-prefix.py             # scan
    check-alertmanager-url-route-prefix.py --selftest  # prove the gate can fail
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"
STACK = GITOPS / "apps" / "kube-prometheus-stack.yaml"

# `alertmanager_url: http://host:9093[/prefix]` — the Loki ruler's key. Matched textually rather
# than by parsing YAML because these files are Argo Application manifests carrying a chart's
# valuesObject, and the key lives at a different depth in each.
URL_RE = re.compile(r"^\s*alertmanager_url:\s*(?P<url>\S+)\s*$", re.MULTILINE)
ROUTE_PREFIX_RE = re.compile(r"^\s*routePrefix:\s*(?P<prefix>\S+)\s*$", re.MULTILINE)

# A blank value is a DIFFERENT defect (the ruler then notifies nothing at all) and is already
# covered by the Loki manifest's own comment; this gate is about a URL that looks configured.
IGNORED_VALUES = {"", '""', "''"}


def route_prefix(text: str) -> str | None:
    """The prefix Alertmanager serves under, read from the chart values that set it."""
    m = ROUTE_PREFIX_RE.search(text)
    if not m:
        return None
    return m.group("prefix").strip().strip("\"'").rstrip("/")


def scan(gitops: Path, prefix: str) -> tuple[list[str], int]:
    """Returns (violations, number of URLs examined)."""
    violations, subjects = [], 0
    for path in sorted(gitops.rglob("*.yaml")):
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for m in URL_RE.finditer(text):
            url = m.group("url").strip().strip("\"'")
            if url in IGNORED_VALUES:
                continue
            subjects += 1
            if url.rstrip("/").endswith(prefix):
                continue
            line = text[: m.start()].count("\n") + 1
            rel = path.relative_to(REPO)
            violations.append(
                f"{rel}:{line}: alertmanager_url {url!r} does not end with the route prefix "
                f"{prefix!r} that Alertmanager serves under — every notification posted to it "
                f"404s and is dropped"
            )
    return violations, subjects


def selftest() -> int:
    """Prove the gate can fail: the real corpus with the prefix stripped must be reported."""
    text = STACK.read_text(encoding="utf-8")
    prefix = route_prefix(text)
    if not prefix:
        print("SELFTEST FAIL: no routePrefix found in", STACK.relative_to(REPO))
        return 1

    good, subjects = scan(GITOPS, prefix)
    if subjects == 0:
        print("SELFTEST FAIL: scanned zero alertmanager_url values — the gate has no subject")
        return 1
    if good:
        print("SELFTEST FAIL: the committed tree is not clean, so a negative control proves nothing")
        for v in good:
            print("   ", v)
        return 1

    # Negative control: ask the same corpus for a prefix it demonstrably does not carry. Every
    # subject must then be reported. This is the half that fails if the matcher silently matches
    # nothing — a gate that examines no files reports clean exactly like a gate that passes.
    bad, bad_subjects = scan(GITOPS, "/definitely-not-the-prefix")
    if bad_subjects != subjects:
        print(f"SELFTEST FAIL: subject count moved with the prefix ({subjects} -> {bad_subjects})")
        return 1
    if len(bad) != subjects:
        print(f"SELFTEST FAIL: negative control flagged {len(bad)} of {subjects} subject(s)")
        return 1

    print(
        f"check-alertmanager-url-route-prefix --selftest: OK — {subjects} subject(s) clean against "
        f"{prefix!r}, all {len(bad)} reported against a wrong prefix."
    )
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", "--self-test", dest="selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if not STACK.exists():
        print(f"check-alertmanager-url-route-prefix: {STACK.relative_to(REPO)} is missing")
        return 1
    prefix = route_prefix(STACK.read_text(encoding="utf-8"))
    if not prefix:
        # No prefix configured means the bare URL is correct and there is nothing to assert.
        print("check-alertmanager-url-route-prefix: Alertmanager sets no routePrefix — nothing to check.")
        return 0

    violations, subjects = scan(GITOPS, prefix)
    print(f"SUBJECTS={subjects}  # alertmanager_url value(s) scanned")
    if violations:
        print(f"check-alertmanager-url-route-prefix: {len(violations)} violation(s):")
        for v in violations:
            print("  ", v)
        return 1
    print(
        f"check-alertmanager-url-route-prefix: {subjects} alertmanager_url value(s) all carry "
        f"{prefix!r} — clean."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
