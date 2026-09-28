#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""External Secrets Operator (ESO) 0.17 stops serving `external-secrets.io/v1beta1` (and the
older `v1alpha1`) — a manifest still on either group/version fails to sync the moment the chart
crosses that line, and ArgoCD reports it as a plain sync error with no hint that the CAUSE is the
chart bump three files away in `openbank-infra/gitops/apps/external-secrets.yaml`.

WHY THIS EXISTS
---------------
ESO removed `v1beta1` (and the already-deprecated `v1alpha1`) CRD storage in the 0.17 line; the
API server no longer has anything to convert an old-apiVersion manifest INTO. Every `ExternalSecret`
/ `SecretStore` / `ClusterSecretStore` manifest under `openbank-infra/gitops/**` in this repo is
still written as `external-secrets.io/v1beta1` (measured 2026-09-27: ~90 files), and the chart
Application (`openbank-infra/gitops/apps/external-secrets.yaml`) pins `targetRevision: 0.10.4` —
nowhere near 0.17. So today the fleet is fine and this gate finds nothing to fail on. The trap is
what happens the day someone bumps that one `targetRevision` line to keep up with upstream: ArgoCD
would try to sync ~90 unrelated manifests that would all start failing at once, and nothing in the
diff of THAT PR would say why — the failing files are not the ones the PR touched.

WHAT THIS CHECKS
----------------
Reads `spec.source.targetRevision` off the `external-secrets` Argo CD `Application` in
`openbank-infra/gitops/apps/external-secrets.yaml`. If that version is >= 0.17.0, every YAML
document anywhere under `openbank-infra/gitops/**` whose `apiVersion` starts with
`external-secrets.io/v1beta1` or `external-secrets.io/v1alpha1` is a finding. Below 0.17.0 the gate
is a no-op by design — the point is to catch the two changes landing OUT OF ORDER, not to force a
migration nobody has decided to do yet. The fix is either: bump every manifest's `apiVersion` to
`external-secrets.io/v1` in the SAME PR as the chart bump, or don't bump the chart yet.

Usage:  check-eso-crd-api-version.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
APP_MANIFEST = "openbank-infra/gitops/apps/external-secrets.yaml"
GITOPS_ROOT = "openbank-infra/gitops"

STALE_GROUP_VERSION_RE = re.compile(r"^external-secrets\.io/(v1beta1|v1alpha1)$")

# The version ESO actually dropped v1beta1/v1alpha1 CRD storage at. Stated, not derived — confirm
# against the real ESO release notes before changing it, the way ADR-0265 measured the Postgres
# untrusted-extension split against the real image rather than a blog post.
DROP_VERSION = (0, 17, 0)


def parse_semver(text: str) -> tuple[int, int, int] | None:
    """Parse a bare `MAJOR.MINOR.PATCH`, tolerating a leading 'v'. None if it isn't one.

    `targetRevision` can also be a branch name, a tag like `HEAD`, or a range — none of those are
    a version this gate can compare, and treating them as "not >= 0.17.0" would be silently wrong
    in the unsafe direction. Only a plain dotted-triple is trusted; anything else is reported and
    skipped rather than guessed at.
    """
    m = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)(?:-.*)?", text.strip())
    if not m:
        return None
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)))


def chart_target_revision(root: pathlib.Path) -> tuple[int, int, int] | None:
    path = root / APP_MANIFEST
    if not path.is_file():
        print(f"::error::{APP_MANIFEST} not found — cannot determine the ESO chart version")
        return None
    try:
        docs = list(yaml.safe_load_all(path.read_text(errors="ignore")))
    except yaml.YAMLError as exc:
        print(f"::error file={APP_MANIFEST}::could not parse YAML: {exc}")
        return None
    for doc in docs:
        if not isinstance(doc, dict):
            continue
        if doc.get("kind") != "Application":
            continue
        if (doc.get("metadata") or {}).get("name") != "external-secrets":
            continue
        source = (doc.get("spec") or {}).get("source") or {}
        rev = source.get("targetRevision")
        if rev is None:
            continue
        parsed = parse_semver(str(rev))
        if parsed is None:
            print(
                f"::warning file={APP_MANIFEST}::targetRevision '{rev}' is not a plain "
                f"MAJOR.MINOR.PATCH — check-eso-crd-api-version cannot compare it and is skipping"
            )
        return parsed
    print(f"::error file={APP_MANIFEST}::no 'external-secrets' Application with spec.source.targetRevision found")
    return None


ESO_GROUP_RE = re.compile(r"^external-secrets\.io/(v1|v1beta1|v1alpha1)$")


def eso_group_manifests(root: pathlib.Path) -> list[tuple[str, str]]:
    """[(relative path, apiVersion)] for every gitops YAML doc in the external-secrets.io group,
    on ANY version — this is the gate's CORPUS, not its findings. Scoping it to v1/v1beta1/v1alpha1
    keeps it nonzero today (all ~135 manifests are 'v1' after #11128) so `min_subjects:` can detect
    the corpus collapsing — e.g. a renamed gitops directory, or nobody's ExternalSecrets being found
    at all — instead of reading "0 subjects" as the legitimately-clean state findings() reports."""
    out: list[tuple[str, str]] = []
    gitops = root / GITOPS_ROOT
    if not gitops.is_dir():
        return out
    for path in sorted(gitops.rglob("*.yaml")):
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        if "external-secrets.io/" not in text:
            continue
        try:
            docs = list(yaml.safe_load_all(text))
        except yaml.YAMLError:
            print(f"::warning file={path}::could not parse YAML — skipped by check-eso-crd-api-version")
            continue
        for doc in docs:
            if not isinstance(doc, dict):
                continue
            api_version = doc.get("apiVersion")
            if isinstance(api_version, str) and ESO_GROUP_RE.match(api_version.strip()):
                out.append((str(path.relative_to(root)), api_version.strip()))
    return out


def findings(root: pathlib.Path = REPO) -> tuple[list[str], int]:
    subjects = eso_group_manifests(root)
    chart_version = chart_target_revision(root)

    messages: list[str] = []
    if chart_version is not None and chart_version >= DROP_VERSION:
        for rel_path, api_version in subjects:
            if not STALE_GROUP_VERSION_RE.match(api_version):
                continue
            messages.append(
                f"::error file={rel_path}::declares apiVersion '{api_version}', but "
                f"{APP_MANIFEST} pins the external-secrets chart to "
                f"{'.'.join(str(p) for p in chart_version)} — ESO {'.'.join(str(p) for p in DROP_VERSION)}+ "
                f"stops serving v1beta1/v1alpha1 CRDs, so this manifest would fail to sync. Bump "
                f"apiVersion to 'external-secrets.io/v1' in the same PR as the chart bump."
            )
    return messages, len(subjects)


def self_test() -> int:
    """Falsify both directions against a temporary tree, per the repo's 'a gate that has only
    ever passed is unfalsified' rule."""
    import tempfile

    ok = True

    def app_manifest(target_revision: str) -> str:
        return (
            "apiVersion: argoproj.io/v1alpha1\n"
            "kind: Application\n"
            "metadata:\n"
            "  name: external-secrets\n"
            "spec:\n"
            "  source:\n"
            "    repoURL: https://charts.external-secrets.io\n"
            "    chart: external-secrets\n"
            f"    targetRevision: {target_revision}\n"
        )

    v1beta1_secret = (
        "apiVersion: external-secrets.io/v1beta1\n"
        "kind: ExternalSecret\n"
        "metadata:\n"
        "  name: x\n"
    )
    v1_secret = v1beta1_secret.replace("v1beta1", "v1")
    v1alpha1_secret = v1beta1_secret.replace("v1beta1", "v1alpha1")

    def case(label: str, target_revision: str, secret_yaml: str | None, expected_findings: int, expected_subjects: int) -> None:
        nonlocal ok
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            apps_dir = root / "openbank-infra" / "gitops" / "apps"
            apps_dir.mkdir(parents=True)
            (apps_dir / "external-secrets.yaml").write_text(app_manifest(target_revision))
            if secret_yaml is not None:
                comp_dir = root / "openbank-infra" / "gitops" / "components" / "x"
                comp_dir.mkdir(parents=True)
                (comp_dir / "es.yaml").write_text(secret_yaml)
            msgs, subjects = findings(root)
            got, got_subjects = len(msgs), subjects
            status = "ok " if (got, got_subjects) == (expected_findings, expected_subjects) else "FAIL"
            if (got, got_subjects) != (expected_findings, expected_subjects):
                ok = False
            print(
                f"  [{status}] {label}: findings={got} (expected {expected_findings}), "
                f"subjects={got_subjects} (expected {expected_subjects})"
            )

    # KNOWN-POSITIVE: chart >= 0.17.0 and a synthetic v1beta1 manifest — MUST fail.
    case("chart 0.17.0 + v1beta1 manifest — MUST flag", "0.17.0", v1beta1_secret, 1, 1)
    # KNOWN-POSITIVE variant: v1alpha1 is equally dead on 0.17+.
    case("chart 0.20.1 + v1alpha1 manifest — MUST flag", "0.20.1", v1alpha1_secret, 1, 1)
    # KNOWN-NEGATIVE: today's real shape — chart below 0.17.0, v1beta1 present, must NOT flag.
    case("chart 0.10.4 (today's pin) + v1beta1 manifest — must not flag", "0.10.4", v1beta1_secret, 0, 1)
    # KNOWN-NEGATIVE: chart at 0.17.0 but manifests already migrated to v1 — must NOT flag. The
    # manifest is still a subject (it IS in the external-secrets.io group) but not a finding.
    case("chart 0.17.0 + v1 manifest only — must not flag", "0.17.0", v1_secret, 0, 1)
    # Boundary: exactly the drop version with the stale group/version still counts as a finding.
    case("chart exactly 0.17.0 boundary — MUST flag", "0.17.0", v1beta1_secret, 1, 1)
    # Just below the boundary must not flag.
    case("chart 0.16.99 just below boundary — must not flag", "0.16.99", v1beta1_secret, 0, 1)
    # No ExternalSecret manifests at all: 0 subjects, 0 findings, regardless of chart version.
    case("no gitops manifests at all — 0 subjects", "0.17.0", None, 0, 0)

    print("self-test: PASS" if ok else "self-test: FAIL")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    messages, subjects = findings()
    for line in messages:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    print(f"SUBJECTS={subjects}")
    verdict = "clean." if not messages else f"{len(messages)} finding(s) above."
    print(f"check-eso-crd-api-version: {subjects} external-secrets.io-group manifest(s) examined — {verdict}")
    return 1 if messages and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
