#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Journey catalog integrity gate (ADR-0252 phase 2, issue #4348).
#
# WHY THIS EXISTS
#   A synthetic monitor is worth exactly what its coverage claim is worth, and the repo has
#   paid twice for a coverage claim nobody could check: pact-drift-check regenerated only the
#   modules on a hand-kept list, so a module missing from that list left the gate green about
#   work it never did (#2284). A journey catalog has the same shape and worse stakes — a
#   short list reads as "we watch the platform" when it means "we watch three things".
#
#   This gate cannot make the catalog complete; deriving the customer-visible capability set
#   is the harder half and is not built (#4348). What it CAN do is make every claim in the
#   file checkable, and make a silent shrink impossible:
#
#     1. an `active` journey must actually be able to run — the manifest it names exists, the
#        CronJob in it is named `journey-<id>`, and the script the CronJob mounts is present
#        in the same file. A catalog entry pointing at nothing is the purest form of the
#        defect: a coverage claim with no coverage.
#     2. the schedule in the catalog must equal the schedule in the manifest. Two copies of a
#        fact drift; this is the same rule the external-feed watch follows by reading the URL
#        out of the committed config rather than keeping its own.
#     3. BOTH DIRECTIONS. A `journey-*` CronJob on disk that no catalog entry claims is just
#        as wrong as a catalog entry with no CronJob — that is how a journey gets added,
#        forgotten, and then silently deleted with nothing noticing it is gone.
#     4. every `active` journey needs its own JourneyMissing alert instance. `absent()` cannot
#        be written with a regex: a rule matching `journey-.*` fires when SOME series is
#        missing only if it never matched anything, so a journey whose CronJob is deleted
#        while others still run produces no alert at all. Per-journey or nothing.
#     5. every journey — active or planned — declares its falsification. A journey that has
#        only ever been green is unfalsified, the same rule gates.yaml applies to CI gates.
#     6. a `planned` journey names what blocks it. Without this the file becomes a wish list,
#        and a gap that is written down but unexplained decays into background noise the same
#        way an untriaged advisory finding does.
#
# WHAT IT DELIBERATELY DOES NOT CHECK
#   Whether the journey's assertions are any good, and whether the capability text is true.
#   Both are review questions. This gate is about the catalog not lying about itself.
#
# EXIT CODES
#   0 — the catalog is internally consistent
#   1 — the gate could not answer (missing/unparseable catalog, zero journeys). A scan that
#       read nothing must never report green.
#   2 — findings (with --enforce; without it they are ::warning and the exit is 0)
#
# Run:  python3 .github/scripts/check-journey-catalog.py --root . [--enforce]
#       python3 .github/scripts/check-journey-catalog.py --root . --extract public-edge --out /tmp/journey.js
#       python3 .github/scripts/check-journey-catalog.py --self-test

import argparse
import pathlib
import re
import sys
import tempfile

# The checkers run as scripts from the repo root, so this directory is not on sys.path.
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import gatelib  # noqa: E402  (path insert must precede the import)

try:
    import yaml
except ImportError:  # pragma: no cover - environment guard
    print("::error::check-journey-catalog: PyYAML is required")
    sys.exit(1)

CATALOG = "openbank-libs/governance/journeys.yaml"
RULES = "openbank-infra/gitops/components/observability/prometheus-rules-journey.yaml"
COMPONENTS = "openbank-infra/gitops/components"

CRONJOB_PREFIX = "journey-"
# A journey id becomes both the CronJob name and the Prometheus selector used by the
# operator UI. Validate the *derived* DNS label at catalog time: otherwise an id can look
# harmless in YAML but be unqueryable at runtime once the prefix is added.
JOURNEY_ID_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,53}[a-z0-9])?$")

# PromQL string literals accept double quotes, single quotes AND backticks, and whitespace
# around `=` is legal. Matching only `cronjob="journey-x"` would report a MISSING alert for a
# journey that has one — a false positive, so it fails closed rather than silently, but it
# would still be a gate wrong about the tree. `=` only, deliberately: `=~` is the regex form
# this gate exists to reject, since absent() over a regex cannot detect one journey going away
# while its siblings still report.
LABEL_MATCH_RE = re.compile(
    rf"""cronjob\s*=\s*(?P<q>["'`]){CRONJOB_PREFIX}(?P<id>[a-z0-9-]+)(?P=q)"""
)
REQUIRED_ALWAYS = ("id", "title", "capability", "status", "severity", "money_moving", "falsification")
REQUIRED_ACTIVE = ("cronjob", "schedule")
REQUIRED_PLANNED = ("target_schedule",)
VALID_STATUS = ("active", "planned")
VALID_SEVERITY = ("page", "ticket")
CRON_EXPRESSION = re.compile(r"^\S+\s+\S+\s+\S+\s+\S+\s+\S+$")


def load_docs(path: pathlib.Path):
    """Every YAML document in a multi-document manifest, skipping empty ones."""
    return [d for d in yaml.safe_load_all(path.read_text(encoding="utf-8")) if d]


def find_journey_cronjobs(root: pathlib.Path):
    """Every CronJob on disk whose name starts with `journey-`, as {name: file}."""
    found = {}
    for path in sorted((root / COMPONENTS).rglob("*.yaml")):
        try:
            docs = load_docs(path)
        except yaml.YAMLError:
            continue
        for doc in docs:
            if not isinstance(doc, dict):
                continue
            if doc.get("kind") != "CronJob":
                continue
            name = (doc.get("metadata") or {}).get("name", "")
            if name.startswith(CRONJOB_PREFIX):
                found[name] = path.relative_to(root).as_posix()
    return found


def cronjob_facts(root: pathlib.Path, rel_path: str, journey_id: str):
    """
    (schedule, configmap_names, error) for the `journey-<id>` CronJob in `rel_path`, plus the
    names of ConfigMaps DEFINED in the same file. Returning both lets the caller check the
    mounted script is actually present rather than merely referenced — a CronJob mounting a
    ConfigMap nobody created starts and fails, which is a red journey for the wrong reason.
    """
    path = root / rel_path
    if not path.is_file():
        return None, set(), f"manifest not found: {rel_path}"
    try:
        docs = load_docs(path)
    except yaml.YAMLError as exc:
        return None, set(), f"manifest is not valid YAML: {rel_path} ({exc})"

    defined_configmaps = {
        (d.get("metadata") or {}).get("name")
        for d in docs
        if isinstance(d, dict) and d.get("kind") == "ConfigMap"
    }
    for doc in docs:
        if not isinstance(doc, dict) or doc.get("kind") != "CronJob":
            continue
        if (doc.get("metadata") or {}).get("name") != f"{CRONJOB_PREFIX}{journey_id}":
            continue
        spec = doc.get("spec") or {}
        pod = (((spec.get("jobTemplate") or {}).get("spec") or {}).get("template") or {}).get("spec") or {}
        mounted = {
            (v.get("configMap") or {}).get("name")
            for v in (pod.get("volumes") or [])
            if isinstance(v, dict) and v.get("configMap")
        }
        missing = {m for m in mounted if m and m not in defined_configmaps}
        if missing:
            return spec.get("schedule"), defined_configmaps, (
                f"{rel_path} mounts ConfigMap(s) {sorted(missing)} that the file does not define"
            )
        if not mounted:
            return spec.get("schedule"), defined_configmaps, (
                f"{rel_path}: CronJob {CRONJOB_PREFIX}{journey_id} mounts no script ConfigMap"
            )
        return spec.get("schedule"), defined_configmaps, None
    return None, defined_configmaps, f"{rel_path} defines no CronJob named {CRONJOB_PREFIX}{journey_id}"


def extract_script(root: pathlib.Path, journey_id: str) -> str:
    """Return the exact ConfigMap-mounted k6 program for one active catalog journey.

    The manifest is the runtime artifact applied by Argo CD. CI extracts that same scalar
    instead of keeping a sibling .js file which can drift from what the CronJob executes.
    """
    catalog = yaml.safe_load((root / CATALOG).read_text(encoding="utf-8")) or {}
    matches = [item for item in catalog.get("journeys", []) if item.get("id") == journey_id]
    if len(matches) != 1 or matches[0].get("status") != "active":
        raise ValueError(f"{journey_id}: expected exactly one active catalog entry")
    rel_path = matches[0].get("cronjob")
    docs = load_docs(root / rel_path)
    cronjob_name = f"{CRONJOB_PREFIX}{journey_id}"
    cronjob = next((d for d in docs if d.get("kind") == "CronJob" and
                    (d.get("metadata") or {}).get("name") == cronjob_name), None)
    if cronjob is None:
        raise ValueError(f"{journey_id}: manifest defines no {cronjob_name} CronJob")
    pod = (((cronjob.get("spec") or {}).get("jobTemplate") or {}).get("spec") or {}).get("template") or {}
    pod_spec = pod.get("spec") or {}
    mounted = {(volume.get("configMap") or {}).get("name") for volume in pod_spec.get("volumes", [])
               if isinstance(volume, dict) and volume.get("configMap")}
    scripts = [((doc.get("data") or {}).get("journey.js")) for doc in docs
               if doc.get("kind") == "ConfigMap" and (doc.get("metadata") or {}).get("name") in mounted]
    scripts = [script for script in scripts if isinstance(script, str) and script.strip()]
    if len(scripts) != 1:
        raise ValueError(f"{journey_id}: expected one mounted non-empty journey.js, found {len(scripts)}")
    return scripts[0]


def active_ids(root: pathlib.Path) -> list[str]:
    """Return active journey ids only after the catalog has passed its structural checks."""
    findings, fatal, _ = check(root)
    if fatal:
        raise ValueError(fatal)
    if findings:
        raise ValueError("catalog is not consistent: " + "; ".join(findings))
    catalog = yaml.safe_load((root / CATALOG).read_text(encoding="utf-8")) or {}
    return sorted(str(item["id"]) for item in (catalog.get("journeys") or [])
                  if isinstance(item, dict) and item.get("status") == "active")


def alerted_journeys(root: pathlib.Path):
    """
    Journey ids covered by a per-journey `absent(...)` alert in the rules file.

    Matched against the rule EXPRESSIONS, never the whole file: a whole-file grep matches the
    prose explaining why a journey is listed, which is how a check comes to report a pass on
    its own comment (#3072).
    """
    path = root / RULES
    if not path.is_file():
        return set(), f"alert rules not found: {RULES}"
    try:
        docs = load_docs(path)
    except yaml.YAMLError as exc:
        return set(), f"alert rules are not valid YAML ({exc})"
    covered = set()
    for doc in docs:
        if not isinstance(doc, dict) or doc.get("kind") != "PrometheusRule":
            continue
        for group in ((doc.get("spec") or {}).get("groups") or []):
            for rule in (group.get("rules") or []):
                expr = str(rule.get("expr", ""))
                if "absent(" not in expr:
                    continue
                for match in LABEL_MATCH_RE.finditer(expr):
                    covered.add(match.group("id"))
    return covered, None


def check(root: pathlib.Path):
    """(findings, fatal, subjects) — fatal means the gate could not answer and must exit 1."""
    catalog_path = root / CATALOG
    if not catalog_path.is_file():
        return [], f"catalog not found: {CATALOG}", 0
    try:
        catalog = yaml.safe_load(catalog_path.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as exc:
        return [], f"catalog is not valid YAML ({exc})", 0

    journeys = catalog.get("journeys") or []
    if not journeys:
        return [], "catalog declares no journeys — a scan that read nothing is not a pass", 0

    findings = []
    seen_ids = set()
    claimed_cronjobs = {}

    for entry in journeys:
        if not isinstance(entry, dict):
            findings.append("a journeys[] entry is not a mapping")
            continue
        jid = entry.get("id", "<no id>")
        if jid in seen_ids:
            findings.append(f"{jid}: duplicate id")
        seen_ids.add(jid)

        if not isinstance(jid, str) or not JOURNEY_ID_RE.fullmatch(jid):
            findings.append(
                f"{jid!r}: id must be a lowercase DNS-label suffix of at most 55 characters "
                f"so `{CRONJOB_PREFIX}<id>` remains a valid Kubernetes CronJob name"
            )

        for field in REQUIRED_ALWAYS:
            if entry.get(field) in (None, ""):
                findings.append(f"{jid}: missing required field `{field}`")

        status = entry.get("status")
        if status not in VALID_STATUS:
            findings.append(f"{jid}: status must be one of {VALID_STATUS}, got {status!r}")
        if entry.get("severity") not in VALID_SEVERITY:
            findings.append(f"{jid}: severity must be one of {VALID_SEVERITY}")

        if status == "planned":
            if entry.get("runtime_note"):
                findings.append(
                    f"{jid}: planned journeys use `blocked_by`, not `runtime_note` — "
                    "a runtime prerequisite belongs to a scheduled journey with a live verdict"
                )
            if not entry.get("blocked_by"):
                findings.append(
                    f"{jid}: planned journeys must name what blocks them (`blocked_by`) — "
                    "an unexplained gap decays into background noise"
                )
            for field in REQUIRED_PLANNED:
                if entry.get(field) in (None, ""):
                    findings.append(
                        f"{jid}: planned journeys need `{field}` so the intended monitoring "
                        "cadence is reviewable before activation"
                    )
            target_schedule = str(entry.get("target_schedule") or "")
            if target_schedule and not CRON_EXPRESSION.match(target_schedule):
                findings.append(f"{jid}: target_schedule is not a five-field cron expression")
            continue
        if status != "active":
            continue

        runtime_note = entry.get("runtime_note")
        if runtime_note is not None and (not isinstance(runtime_note, str) or not runtime_note.strip()):
            findings.append(f"{jid}: runtime_note must be a non-empty string when declared")

        for field in REQUIRED_ACTIVE:
            if entry.get(field) in (None, ""):
                findings.append(f"{jid}: active journeys need `{field}`")
        rel = entry.get("cronjob")
        if not rel:
            continue
        claimed_cronjobs[f"{CRONJOB_PREFIX}{jid}"] = rel
        schedule, _, error = cronjob_facts(root, rel, jid)
        if error:
            findings.append(f"{jid}: {error}")
            continue
        if schedule != entry.get("schedule"):
            findings.append(
                f"{jid}: catalog schedule {entry.get('schedule')!r} != manifest schedule "
                f"{schedule!r} — two copies of one fact, and they have already drifted"
            )

    on_disk = find_journey_cronjobs(root)
    for name, path in sorted(on_disk.items()):
        if name not in claimed_cronjobs:
            findings.append(
                f"{name} ({path}) is a journey CronJob that no catalog entry claims — "
                "a journey outside the catalog can be deleted with nothing noticing"
            )

    covered, rules_error = alerted_journeys(root)
    if rules_error:
        findings.append(rules_error)
    else:
        for name in sorted(claimed_cronjobs):
            jid = name[len(CRONJOB_PREFIX):]
            if jid not in covered:
                findings.append(
                    f"{jid}: no per-journey absent() alert in {RULES} — a regex alert cannot "
                    "detect the absence of one journey while others still report"
                )
    # Subjects are the catalog entries plus any journey CronJob the catalog does not claim —
    # i.e. everything this gate had an opinion about. Counting only the entries would let a
    # catalog emptied to one line still clear a floor set for the corpus it used to have.
    return findings, None, len(journeys) + len(set(on_disk) - set(claimed_cronjobs))


SELF_TEST_CATALOG_OK = """
version: 1
journeys:
  - id: demo
    title: Demo
    capability: proves the demo works
    status: active
    severity: page
    money_moving: false
    cronjob: openbank-infra/gitops/components/observability/cronjob-journey-demo.yaml
    schedule: "*/5 * * * *"
    falsification: point it at a dead host
  - id: later
    title: Later
    capability: proves something not yet checked
    status: planned
    severity: ticket
    money_moving: true
    target_schedule: "0 * * * *"
    falsification: blackhole the topic
    blocked_by: "#4348 — needs synthetic parties"
"""

SELF_TEST_CRONJOB = """
apiVersion: v1
kind: ConfigMap
metadata:
  name: journey-demo-script
data:
  journey.js: "export default function () {}"
---
apiVersion: batch/v1
kind: CronJob
metadata:
  name: journey-demo
spec:
  schedule: "*/5 * * * *"
  jobTemplate:
    spec:
      template:
        spec:
          volumes:
            - name: script
              configMap:
                name: journey-demo-script
"""

SELF_TEST_RULES = """
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: openbank-journey-alerts
spec:
  groups:
    - name: openbank.journey
      rules:
        - alert: JourneyMissing
          expr: absent(kube_cronjob_status_last_successful_time{cronjob="journey-demo"})
"""


def write_tree(root: pathlib.Path, catalog: str, cronjob: str, rules: str, cronjob_name="cronjob-journey-demo.yaml"):
    (root / "openbank-libs/governance").mkdir(parents=True, exist_ok=True)
    (root / COMPONENTS / "observability").mkdir(parents=True, exist_ok=True)
    (root / CATALOG).write_text(catalog, encoding="utf-8")
    if cronjob is not None:
        (root / COMPONENTS / "observability" / cronjob_name).write_text(cronjob, encoding="utf-8")
    (root / RULES).write_text(rules, encoding="utf-8")


def self_test():
    """
    Every case is a tree this gate MUST or MUST NOT flag. The control comes first: without a
    case that stays clean, a checker that flags everything would pass its own self-test.
    """
    cases = []

    def run(label, catalog, cronjob, rules, expect_finding, cronjob_name="cronjob-journey-demo.yaml"):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_tree(root, catalog, cronjob, rules, cronjob_name)
            findings, fatal, _ = check(root)
            got = bool(findings) or bool(fatal)
            ok = got == expect_finding
            cases.append((label, ok, findings or ([fatal] if fatal else [])))

    run("control: a consistent catalog is clean",
        SELF_TEST_CATALOG_OK, SELF_TEST_CRONJOB, SELF_TEST_RULES, expect_finding=False)

    run("catalog entry whose manifest does not exist",
        SELF_TEST_CATALOG_OK.replace("cronjob-journey-demo.yaml", "cronjob-journey-ghost.yaml"),
        SELF_TEST_CRONJOB, SELF_TEST_RULES, expect_finding=True)

    run("journey id whose prefixed CronJob name exceeds the Kubernetes DNS-label limit",
        SELF_TEST_CATALOG_OK.replace("id: demo", f"id: {'a' * 56}"),
        SELF_TEST_CRONJOB, SELF_TEST_RULES, expect_finding=True)

    run("schedule drifted between catalog and manifest",
        SELF_TEST_CATALOG_OK, SELF_TEST_CRONJOB.replace('"*/5 * * * *"', '"*/30 * * * *"'),
        SELF_TEST_RULES, expect_finding=True)

    run("CronJob on disk that the catalog does not claim",
        SELF_TEST_CATALOG_OK,
        SELF_TEST_CRONJOB + "\n---\n" + SELF_TEST_CRONJOB.replace("journey-demo", "journey-orphan"),
        SELF_TEST_RULES, expect_finding=True)

    run("active journey with no per-journey absent() alert",
        SELF_TEST_CATALOG_OK, SELF_TEST_CRONJOB,
        SELF_TEST_RULES.replace('cronjob="journey-demo"', 'cronjob=~"journey-.*"'),
        expect_finding=True)

    run("planned journey with no blocker",
        SELF_TEST_CATALOG_OK.replace('    blocked_by: "#4348 — needs synthetic parties"\n', ""),
        SELF_TEST_CRONJOB, SELF_TEST_RULES, expect_finding=True)

    run("planned journey with no target schedule",
        SELF_TEST_CATALOG_OK.replace('    target_schedule: "0 * * * *"\n', ""),
        SELF_TEST_CRONJOB, SELF_TEST_RULES, expect_finding=True)

    run("planned journey cannot mislabel its blocker as a runtime prerequisite",
        SELF_TEST_CATALOG_OK.replace('    blocked_by: "#4348 — needs synthetic parties"\n', '    runtime_note: needs synthetic parties\n    blocked_by: "#4348 — needs synthetic parties"\n'),
        SELF_TEST_CRONJOB, SELF_TEST_RULES, expect_finding=True)

    run("journey with no falsification",
        SELF_TEST_CATALOG_OK.replace("    falsification: point it at a dead host\n", ""),
        SELF_TEST_CRONJOB, SELF_TEST_RULES, expect_finding=True)

    run("CronJob mounting a ConfigMap the file never defines",
        SELF_TEST_CATALOG_OK,
        SELF_TEST_CRONJOB.replace("name: journey-demo-script\ndata:", "name: some-other-name\ndata:"),
        SELF_TEST_RULES, expect_finding=True)

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        write_tree(root, SELF_TEST_CATALOG_OK, SELF_TEST_CRONJOB, SELF_TEST_RULES)
        extracted = extract_script(root, "demo")
        cases.append(("control: CI extracts the exact mounted runtime script",
                      extracted == "export default function () {}", [repr(extracted)]))
        try:
            extract_script(root, "later")
            cases.append(("planned journey cannot be executed", False, []))
        except ValueError:
            cases.append(("planned journey cannot be executed", True, []))

    run("empty catalog is fatal, not a pass",
        "version: 1\njourneys: []\n", SELF_TEST_CRONJOB, SELF_TEST_RULES, expect_finding=True)

    # The alert-coverage check must read EXPRESSIONS, not the file: a rules file whose prose
    # names the journey but whose expression does not still has no alert for it.
    for quote, label in (("'", "single quotes"), ("`", "backticks")):
        run(f"control: an absent() matcher written with {label} still counts",
            SELF_TEST_CATALOG_OK, SELF_TEST_CRONJOB,
            SELF_TEST_RULES.replace('cronjob="journey-demo"', f"cronjob = {quote}journey-demo{quote}"),
            expect_finding=False)

    run("journey named only in an alert's prose, not its expression",
        SELF_TEST_CATALOG_OK, SELF_TEST_CRONJOB,
        SELF_TEST_RULES.replace(
            'expr: absent(kube_cronjob_status_last_successful_time{cronjob="journey-demo"})',
            'expr: absent(kube_cronjob_status_last_successful_time{cronjob=~"journey-.*"})\n'
            '          annotations:\n'
            '            summary: covers journey-demo among others',
        ),
        expect_finding=True)

    failed = [c for c in cases if not c[1]]
    for label, ok, detail in cases:
        print(f"  {'ok  ' if ok else 'FAIL'} {label}")
        if not ok and detail:
            for line in detail:
                print(f"         {line}")
    print(f"check-journey-catalog self-test: {len(cases) - len(failed)}/{len(cases)} passed")
    return 0 if not failed else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true", dest="selftest")
    parser.add_argument("--extract", metavar="JOURNEY_ID")
    parser.add_argument("--out")
    parser.add_argument("--active-ids", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        return self_test()

    root = pathlib.Path(args.root).resolve()
    if args.active_ids:
        try:
            print("\n".join(active_ids(root)))
        except (OSError, yaml.YAMLError, ValueError) as exc:
            print(f"::error::check-journey-catalog: cannot enumerate active journeys: {exc}")
            return 1
        return 0
    if args.extract:
        if not args.out:
            parser.error("--extract requires --out")
        try:
            script = extract_script(root, args.extract)
        except (OSError, yaml.YAMLError, ValueError) as exc:
            print(f"::error::check-journey-catalog: cannot extract {args.extract}: {exc}")
            return 1
        pathlib.Path(args.out).write_text(script, encoding="utf-8")
        print(f"check-journey-catalog: extracted {args.extract} runtime script to {args.out}")
        return 0
    findings, fatal, subjects = check(root)
    # Printed unconditionally, including on the failure path: a gate that found its corpus and
    # then failed on it must not also be reported as having lost the corpus.
    gatelib.subjects(subjects, "journeys examined")
    if fatal:
        print(f"::error::check-journey-catalog: {fatal}")
        return 1
    if not findings:
        print("check-journey-catalog: OK — every journey claim is checkable and consistent.")
        return 0
    level = "error" if args.enforce else "warning"
    for finding in findings:
        print(f"::{level}::check-journey-catalog: {finding}")
    print(f"check-journey-catalog: {len(findings)} finding(s)")
    return 2 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
