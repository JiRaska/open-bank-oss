#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Assert no Loki rule can match Loki's own record of that rule running.

WHY THIS EXISTS.

Loki logs every query it serves, and the log line CONTAINS the query text. So a rule shaped

    {namespace=~".+"} |= "SOME_LITERAL"

matches Loki's own log of itself running, and the loop never clears: the rule evaluates, the
evaluation is logged, the log satisfies the rule, so the rule fires forever on nothing.

Measured 2026-08-21, within minutes of the Loki ruler evaluating a rule for the first time in this
estate (#6032). All three unscoped rules in loki-rules-silent-failures.yaml were `firing`, and all
three were false positives. Over 6h with `{namespace=~".+"}`:

    HR000068                       102 lines -> 0 once pod=loki-* is excluded
    SRCFG00040                      94 lines -> 0
    duplicate key value violates   289 lines -> 1   (and that one a benign Temporal constraint)

Every line came from `pod=loki-0`. These are the alerts for a dead scheduler, a service that failed
to boot, and the Panache assigned-@Id defect — three of the sharpest detectors in the estate, all
guaranteed to cry wolf from the moment delivery worked.

Excluding the ruler component alone is NOT sufficient and was tried: the query FRONTEND logs the
same text for any Grafana panel or ad-hoc query mentioning the literal, so 66 of 78 lines survived
`!= "component=ruler"`. The POD is the boundary that holds.

The control that shows this is the mechanism rather than a coincidence: the Falco rules grep for
literals identically and never fired, because their selector is `{namespace="falco"}`, which cannot
match the Loki pod.

The Loki namespace and workload name are DERIVED from apps/loki.yaml, not hard-coded, so moving
Loki cannot leave this gate quietly checking the wrong pod.

Usage:
    check-loki-rule-self-match.py             # scan
    check-loki-rule-self-match.py --selftest  # prove the gate can fail
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"
LOKI_APP = GITOPS / "apps" / "loki.yaml"
RULES_GLOB = "components/observability/loki-rules-*.yaml"

SELECTOR_RE = re.compile(r"\{(?P<body>[^{}]*)\}")
LABEL_RE = re.compile(r'(?P<label>\w+)\s*(?P<op>=~|!~|!=|=)\s*"(?P<value>(?:[^"\\]|\\.)*)"')
# A line filter is what makes a rule capable of matching the query log at all.
LINE_FILTER_RE = re.compile(r"\|=")


def loki_identity() -> tuple[str, str]:
    """(namespace, workload name) for Loki, read from its Argo Application."""
    doc = yaml.safe_load(LOKI_APP.read_text(encoding="utf-8"))
    ns = doc["spec"]["destination"]["namespace"]
    values = doc["spec"]["source"]["helm"]["valuesObject"]
    name = values.get("fullnameOverride") or "loki"
    return ns, name


def selector_excludes_loki(selector: str, ns: str, name: str) -> bool:
    """True when this stream selector provably cannot match Loki's own pod."""
    labels = [(m.group("label"), m.group("op"), m.group("value")) for m in LABEL_RE.finditer(selector)]

    for label, op, value in labels:
        # An explicit pod exclusion is the sanctioned fix.
        if label == "pod" and op in ("!~", "!="):
            try:
                if op == "!~" and re.fullmatch(value, f"{name}-0"):
                    return True
            except re.error:
                pass
            if op == "!=" and value == f"{name}-0":
                return True
        # A namespace pinned somewhere Loki is not.
        if label == "namespace":
            if op == "=" and value != ns:
                return True
            if op == "!=" and value == ns:
                return True
            if op == "=~":
                try:
                    if not re.fullmatch(value, ns):
                        return True
                except re.error:
                    pass
            if op == "!~":
                try:
                    if re.fullmatch(value, ns):
                        return True
                except re.error:
                    pass
    return False


def rules_from(path: Path):
    """Yields (alert_name, expr) for every rule in a Loki rule ConfigMap."""
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    for payload in (doc.get("data") or {}).values():
        parsed = yaml.safe_load(payload)
        for group in (parsed or {}).get("groups", []):
            for rule in group.get("rules", []):
                name = rule.get("alert") or rule.get("record") or "<unnamed>"
                yield name, rule.get("expr", "")


def scan(ns: str, name: str) -> tuple[list[str], int]:
    violations, subjects = [], 0
    for path in sorted(GITOPS.glob(RULES_GLOB)):
        for alert, expr in rules_from(path):
            if not LINE_FILTER_RE.search(expr):
                continue
            for m in SELECTOR_RE.finditer(expr):
                selector = m.group("body")
                if not LABEL_RE.search(selector):
                    continue
                subjects += 1
                if selector_excludes_loki(selector, ns, name):
                    continue
                violations.append(
                    f"{path.relative_to(REPO)}: {alert}: selector {{{selector.strip()}}} can match "
                    f"Loki's own query log in namespace {ns!r} — the rule matches its own "
                    f'evaluation and fires forever. Add pod!~"{name}-.*".'
                )
    return violations, subjects


def selftest() -> int:
    ns, name = loki_identity()
    good, subjects = scan(ns, name)
    if subjects == 0:
        print("SELFTEST FAIL: scanned zero line-filtered selectors — the gate has no subject")
        return 1
    if good:
        print("SELFTEST FAIL: the committed tree is not clean, so a negative control proves nothing")
        for v in good:
            print("   ", v)
        return 1

    # Negative control. Re-run against a Loki identity the exclusions cannot cover: every subject
    # that relied on `pod!~"loki-.*"` must now be reported, and every subject that is safe because
    # its namespace is pinned elsewhere must STILL be safe. That second half matters — a control
    # that flags everything would also pass a matcher that flags everything.
    bad, bad_subjects = scan(ns, "somethingelse")
    if bad_subjects != subjects:
        print(f"SELFTEST FAIL: subject count moved with the identity ({subjects} -> {bad_subjects})")
        return 1
    if not bad:
        print("SELFTEST FAIL: negative control reported nothing — the matcher cannot fail")
        return 1

    print(
        f"check-loki-rule-self-match --selftest: OK — {subjects} selector(s) clean against "
        f"{name!r} in {ns!r}, {len(bad)} reported when the pod exclusion no longer covers Loki."
    )
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", "--self-test", dest="selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    ns, name = loki_identity()
    violations, subjects = scan(ns, name)
    print(f"SUBJECTS={subjects}  # line-filtered stream selector(s) scanned")
    if violations:
        print(f"check-loki-rule-self-match: {len(violations)} violation(s):")
        for v in violations:
            print("  ", v)
        return 1
    print(
        f"check-loki-rule-self-match: {subjects} line-filtered selector(s), none can match Loki's "
        f"own logs ({name!r} in {ns!r}) — clean."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
