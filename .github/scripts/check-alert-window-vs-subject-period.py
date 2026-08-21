#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""
Two alert defects that leave a rule LOADED, HEALTHY, EVALUATING — and unable to ever fire.

Both were measured live, not reasoned about, during the #5736 LLM outage (PR #6041).

DEFECT 1 — the rate window is shorter than the SUBJECT'S INVOCATION PERIOD.
    `AiCallErrorRateHigh` used `rate(...[15m])` with `for: 15m`. Its only caller is a
    30-minute cron. So the counter increments once per window: the ratio is 100% for
    about fifteen minutes, then decays to nothing, resetting the `for:` timer just short
    of maturity — forever. Prometheus's own ALERTS series over the 8h40m total outage:
    44 points at `pending`, ZERO at `firing`. Eight pending episodes, lengths
    [6,15,15,15,15,15,15,15] minutes at start-gaps [51,30,30,30,30,30,30,90] — the gap is
    the cron period and the length is the window, which is the fingerprint.
    The window is a property of the CALLER, not of the rule, so no threshold tuning fixes
    it and `promtool check rules`, rule `health=ok` and a green ArgoCD all stay green.
    A rule needs its window comfortably WIDER than its subject's period — this gate wants
    W > 1.5 * P, because at W == P the condition still decays to zero between invocations
    (measured: widening `HighLatencyP99` from [5m] to [30m] against a 30m subject bought
    episodes capped at 29m, while [45m] gave 311 consecutive true minutes).

DEFECT 2 — a ratio is structurally blind in the total-failure case.
    A route that has NEVER succeeded has no `outcome="success"` series at all, so
    `success / total < threshold` matches nothing: the arithmetic yields no result rather
    than 0%, and the alert is silent in exactly the case it was written for. `clamp_min`
    on the DENOMINATOR does not help — the missing side is the numerator. The fix is a
    set difference (`unless`), an `absent()`/`absent_over_time()` arm, or `or vector(0)`.

WHY A DECLARATION AND NOT AN INFERENCE
    Nothing in a rule file says what its subject's period is; the fleet builds periodic
    subjects three different ways (`@Scheduled` in Kotlin, Kubernetes CronJobs, agent
    charter schedules) and the metric is frequently emitted by shared libs code one hop
    from the scheduled caller, so a static call-graph link would be a guess. So every
    windowed rule DECLARES `subject_period` in its annotations — `continuous` for a
    subject driven by real traffic, or a duration for a periodic one — and this gate does
    the arithmetic. The declared duration is validated against the set of periods actually
    DERIVED from the three sources, so a declaration cannot name a cadence that does not
    exist in this repo.

WHY A BASELINE, AND WHY IT IS THE FROZEN SIDE
    Annotating all 121 existing rules in one PR would be a diff nobody can review and
    would collide with every parallel change to the alerting tree. So the undeclared set
    as of today is recorded by NAME in the exclusions file. That is the frozen side, never
    a difference (see openbank-infra/CLAUDE.md on the Keycloak baseline): a rule the
    baseline names is skipped, a rule it does not name must declare, and the check fails in
    BOTH directions — an entry for a rule that has since declared, or that no longer
    exists, is a stale entry and is reported. A new alert rule therefore cannot join the
    undeclared set quietly, and the debt can only shrink.

Usage:
    check-alert-window-vs-subject-period.py              # advisory: warn, exit 0
    check-alert-window-vs-subject-period.py --enforce    # fail on any finding
    check-alert-window-vs-subject-period.py --self-test  # prove the gate can fail
    check-alert-window-vs-subject-period.py --list-subjects   # dump the derived subject set
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    import gatelib
except ImportError:  # pragma: no cover - gatelib always ships beside this script
    gatelib = None

REPO = Path(__file__).resolve().parents[2]
COMPONENTS = REPO / "openbank-infra" / "gitops" / "components"
AGENTS = REPO / "openbank-libs" / "governance" / "agents.yaml"
EXCLUSIONS = REPO / ".github" / "gates" / "alert-subject-period-baseline.yaml"

# THE MATURATION ARITHMETIC, stated exactly rather than as a fudge factor.
#
#   With a subject of period P, `rate/increase(...[W])` is non-zero for W seconds after each
#   invocation and zero after that. So:
#     * W > P  -> the condition never decays between invocations; any `for:` matures. SAFE.
#     * W <= P -> the condition is true in runs of at most W seconds, and `for: F` matures
#                 only if F is comfortably shorter than W. F >= W can NEVER mature.
#   The margin below covers evaluation-interval jitter: a run of W seconds is observed as
#   `W / interval` points, and the first and last are not guaranteed to land inside it.
JITTER_INTERVALS = 2
DEFAULT_EVAL_INTERVAL = 60

RANGE_FN = (
    r"rate|irate|increase|delta|idelta|sum_over_time|count_over_time|avg_over_time|"
    r"max_over_time|min_over_time|stddev_over_time|quantile_over_time|changes|resets|deriv|"
    r"predict_linear"
)
RANGE_CALL = re.compile(rf"\b({RANGE_FN})\s*\(")
DURATION = re.compile(r"(\d+)([smhdw])")
UNIT = {"s": 1, "m": 60, "h": 3600, "d": 86400, "w": 604800}
# A range selector, e.g. `[15m]` or `[7d:5m]` (subquery — the first duration is the range).
BRACKET = re.compile(r"\[(\d+[smhdw])(?::\d*[smhdw]?)?\]")

# Guards that make a ratio total-failure safe: each yields a series when one side is absent.
TOTAL_FAILURE_GUARDS = ("absent(", "absent_over_time(", "or vector(", "unless", "or on(")


def dur_seconds(text: str | int | None) -> int | None:
    if text is None:
        return None
    total = sum(int(n) * UNIT[u] for n, u in DURATION.findall(str(text)))
    return total or None


def human(seconds: int) -> str:
    for unit, size in (("d", 86400), ("h", 3600), ("m", 60)):
        if seconds % size == 0 and seconds >= size:
            return f"{seconds // size}{unit}"
    return f"{seconds}s"


# --------------------------------------------------------------------------------------
# The derived subject set — three independent sources, none of them a hand-kept list.
# --------------------------------------------------------------------------------------

CRON_ATTR = re.compile(r"cron\s*=\s*\"([^\"]+)\"")
EVERY_ATTR = re.compile(r"every\s*=\s*\"([^\"]+)\"")
PLACEHOLDER = re.compile(r"[\\$]?\{([A-Za-z0-9_.\-]+)(?::([^}]*))?\}")


def cron_period_seconds(expr: str) -> int | None:
    """Period of a Quartz/cron expression, for the shapes this fleet actually writes.

    Deliberately conservative: an expression whose cadence is not obvious returns None
    rather than a guess, and an unresolvable subject is reported, never assumed safe.
    """
    fields = expr.split()
    if len(fields) not in (5, 6, 7):
        return None
    # Quartz: sec min hour dom mon dow [year]. Standard cron: min hour dom mon dow.
    if len(fields) >= 6:
        sec, minute, hour = fields[0], fields[1], fields[2]
    else:
        sec, minute, hour = "0", fields[0], fields[1]
    for field, base in ((sec, 1), (minute, 60), (hour, 3600)):
        m = re.fullmatch(r"(?:\*|0)/(\d+)", field)
        if m:
            return int(m.group(1)) * base
        if field == "*":
            return base
    # No step and no wildcard in sec/min/hour => at most once a day.
    return 86400


def resolve_placeholder(raw: str, props: dict[str, str]) -> str | None:
    m = PLACEHOLDER.fullmatch(raw.strip())
    if not m:
        return raw
    key, default = m.group(1), m.group(2)
    return props.get(key, default)


def flatten_yaml(node, prefix: str, out: dict[str, str]) -> None:
    if isinstance(node, dict):
        for k, v in node.items():
            flatten_yaml(v, f"{prefix}.{k}" if prefix else str(k), out)
    elif node is not None and not isinstance(node, list):
        out[prefix] = str(node)


def module_properties(module: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    app = module / "src" / "main" / "resources" / "application.yaml"
    if app.exists():
        try:
            for doc in yaml.safe_load_all(app.read_text()):
                if isinstance(doc, dict):
                    flatten_yaml(doc, "", props)
        except yaml.YAMLError:
            pass
    return props


def derive_subjects() -> tuple[dict[str, int], list[str]]:
    """Return {label: period_seconds} and the list of subjects that could not be resolved."""
    subjects: dict[str, int] = {}
    unresolved: list[str] = []

    # (a) Kotlin @Scheduled — cron= and every=.
    for module in sorted(REPO.glob("openbank-*")):
        src = module / "src" / "main" / "kotlin"
        if not src.is_dir():
            continue
        props = module_properties(module)
        for kt in src.rglob("*.kt"):
            text = kt.read_text(errors="replace")
            if "@Scheduled" not in text:
                continue
            for pattern, kind in ((CRON_ATTR, "cron"), (EVERY_ATTR, "every")):
                for raw in pattern.findall(text):
                    resolved = resolve_placeholder(raw, props)
                    label = f"scheduled:{module.name}:{kt.stem}:{raw}"
                    if resolved is None:
                        unresolved.append(label)
                        continue
                    period = (
                        cron_period_seconds(resolved) if kind == "cron" else dur_seconds(resolved)
                    )
                    if period:
                        subjects[label] = period
                    else:
                        unresolved.append(label)

    # (b) Kubernetes CronJobs.
    for manifest in sorted(COMPONENTS.rglob("*.yaml")):
        text = manifest.read_text(errors="replace")
        if "kind: CronJob" not in text:
            continue
        try:
            docs = list(yaml.safe_load_all(text))
        except yaml.YAMLError:
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "CronJob":
                continue
            schedule = (doc.get("spec") or {}).get("schedule")
            name = (doc.get("metadata") or {}).get("name", manifest.stem)
            label = f"cronjob:{name}:{schedule}"
            period = cron_period_seconds(str(schedule)) if schedule else None
            (subjects.__setitem__(label, period) if period else unresolved.append(label))

    # (c) Agent charter schedules.
    if AGENTS.exists():
        try:
            doc = yaml.safe_load(AGENTS.read_text())
        except yaml.YAMLError:
            doc = None
        for agent in (doc or {}).get("agents", []) or []:
            if not isinstance(agent, dict):
                continue
            schedule = agent.get("schedule")
            if not schedule:
                continue
            label = f"charter:{agent.get('id')}:{schedule}"
            period = cron_period_seconds(str(schedule)) or dur_seconds(schedule)
            (subjects.__setitem__(label, period) if period else unresolved.append(label))

    return subjects, unresolved


# --------------------------------------------------------------------------------------
# The rules under test.
# --------------------------------------------------------------------------------------


def iter_alert_rules(root: Path, unparseable: list[str]):
    """Yield (path, group, rule) for every alert rule under `root`.

    A file that carries `- alert:` and does not parse is REPORTED, never skipped. The first
    draft of this function swallowed the YAMLError and moved on — and then reported "no
    findings" about a file it had never read, which is the same silent-pass shape the gate
    exists to prevent. It was caught only by parsing the tree a second way and comparing the
    rule counts (133 vs 137).
    """
    for path in sorted(root.rglob("*.yaml")):
        text = path.read_text(errors="replace")
        if "- alert:" not in text:
            continue
        try:
            docs = list(yaml.safe_load_all(text))
        except yaml.YAMLError as exc:
            unparseable.append(f"{path}: contains `- alert:` but does not parse as YAML: {exc}")
            continue
        for doc in docs:
            if not isinstance(doc, dict):
                continue
            if doc.get("kind") == "PrometheusRule":
                specs = [doc.get("spec") or {}]
            elif "groups" in doc:
                specs = [doc]
            else:
                continue
            for spec in specs:
                for group in spec.get("groups") or []:
                    for rule in group.get("rules") or []:
                        if isinstance(rule, dict) and "alert" in rule:
                            yield path, group, rule


def min_window(expr: str) -> int | None:
    """Smallest range-selector duration attached to a range function in the expression."""
    if not RANGE_CALL.search(expr):
        return None
    windows = [dur_seconds(w) for w in BRACKET.findall(expr)]
    windows = [w for w in windows if w]
    return min(windows) if windows else None


RATIO_SPLIT = re.compile(r"(?<![/=!<>])/(?![/=])")
LABEL_MATCHER = re.compile(r"\{([^}]*)\}")


def ratio_blind(expr: str) -> str | None:
    """A LOW-ratio alarm whose numerator carries matchers the denominator lacks.

    That is the total-failure-blind shape: the numerator selects the GOOD outcome, so when
    nothing is good its series is absent and the whole expression yields nothing. An
    error-rate ratio (`bad / total > x`) is the opposite direction and is not flagged —
    at total failure its numerator is the side that exists.
    """
    flat = " ".join(expr.split())
    if "/" not in flat:
        return None
    # Two spellings of the same alarm. `A / B < x` says the good share is too low.
    # `1 - ( A / B ) > x` says the drop-off is too high, which is the same statement about
    # the same absent numerator — the second form was missed until it was looked for.
    low_share = re.search(r"<\s*[\d.]", flat)
    inverted = re.search(r"1\s*-\s*\(", flat) and re.search(r">\s*[\d.]", flat)
    if not (low_share or inverted):
        return None
    if any(g in flat for g in TOTAL_FAILURE_GUARDS):
        return None
    parts = RATIO_SPLIT.split(flat)
    if len(parts) < 2:
        return None
    num_labels = set(LABEL_MATCHER.findall(parts[0]))
    den_labels = set(LABEL_MATCHER.findall(parts[1]))
    extra = num_labels - den_labels
    if not extra:
        return None
    return (
        "numerator selects a subset the denominator does not "
        f"({sorted(extra)[0][:70]}) and the alarm is on the ratio being LOW — at 100% "
        "failure the numerator series does not exist and the expression yields nothing "
        "(`clamp_min` on the DENOMINATOR does not help — the missing side is the "
        "numerator). "
        "Guard with `unless`, `absent_over_time(...)` or `or vector(0)`."
    )


def load_baseline() -> tuple[set[str], dict[str, str]]:
    if not EXCLUSIONS.exists():
        return set(), {}
    doc = yaml.safe_load(EXCLUSIONS.read_text()) or {}
    entries = doc.get("undeclared_baseline") or []
    names, reasons = set(), {}
    for entry in entries:
        if isinstance(entry, dict):
            names.add(entry["alert"])
            reasons[entry["alert"]] = entry.get("reason", "")
        else:
            names.add(entry)
    return names, reasons


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--list-subjects", action="store_true")
    ap.add_argument("--root", default=str(COMPONENTS))
    args = ap.parse_args()

    subjects, unresolved = derive_subjects()
    if args.list_subjects:
        for label, period in sorted(subjects.items(), key=lambda kv: kv[1]):
            print(f"{human(period):>6}  {label}")
        print(f"\n{len(subjects)} resolved, {len(unresolved)} unresolved")
        for label in unresolved:
            print(f"  UNRESOLVED {label}")
        return 0

    if args.self_test:
        return self_test()

    known_periods = set(subjects.values())
    # The baseline names rules in the real tree, so it is meaningless against a fixture
    # directory — the self-test passes its own --root and must not inherit it, or every
    # baselined name reads as a stale entry.
    real_tree = Path(args.root).resolve() == COMPONENTS.resolve()
    baseline, _ = load_baseline() if real_tree else (set(), {})
    findings: list[str] = []
    seen: set[str] = set()
    seen_rules: list[str] = []
    windowed = 0

    unparseable: list[str] = []
    # Independent count of the alert rules present, by a different method from the YAML walk.
    # If the two disagree, the walk did not reach everything and no verdict it prints is safe.
    # Scope note: Loki rules live in ConfigMap `data:` blobs and are LogQL, not PromQL — they
    # have no range-vector selector of this shape and are covered by check-loki-rules.py. They
    # are excluded from BOTH sides of this comparison, so the counts stay comparable.
    grep_count = 0
    for candidate in Path(args.root).rglob("*.yaml"):
        body = candidate.read_text(errors="replace")
        if "- alert:" not in body or "kind: ConfigMap" in body:
            continue
        grep_count += body.count("- alert:")
    for path, group, rule in iter_alert_rules(Path(args.root), unparseable):
        name = rule["alert"]
        seen.add(name)
        seen_rules.append(name)
        try:
            rel = path.relative_to(REPO)
        except ValueError:  # --root outside the repo (self-test fixtures)
            rel = path
        expr = str(rule.get("expr", ""))
        annotations = rule.get("annotations") or {}
        declared = annotations.get("subject_period")

        blind = ratio_blind(expr)
        if blind and name not in baseline:
            findings.append(f"{rel}: {name}: RATIO IS TOTAL-FAILURE BLIND — {blind}")

        window = min_window(expr)
        if window is None:
            if declared:
                findings.append(
                    f"{rel}: {name}: declares subject_period but has no range window — "
                    "remove the annotation or the declaration is unverifiable."
                )
            continue
        windowed += 1

        if declared is None:
            if name not in baseline:
                findings.append(
                    f"{rel}: {name}: has a range window [{human(window)}] but no "
                    "`subject_period` annotation. Declare `continuous` (driven by real "
                    "traffic) or the subject's period (e.g. `30m`) so the window can be "
                    "checked against it."
                )
            continue

        if name in baseline:
            findings.append(
                f"{rel}: {name}: STALE BASELINE ENTRY — it now declares "
                f"`subject_period: {declared}`. Remove it from {EXCLUSIONS.relative_to(REPO)}."
            )

        if str(declared).strip() == "continuous":
            continue
        period = dur_seconds(declared)
        if period is None:
            findings.append(
                f"{rel}: {name}: subject_period `{declared}` is not a duration or "
                "`continuous`."
            )
            continue
        if period not in known_periods:
            findings.append(
                f"{rel}: {name}: subject_period `{declared}` ({human(period)}) matches no "
                "cadence derived from this repo's @Scheduled methods, CronJobs or agent "
                "charters. Either the subject moved or the declaration is stale — check "
                "with --list-subjects."
            )
        if window > period:
            continue  # W > P: the condition never decays between invocations.
        hold = rule.get("for")
        wait = dur_seconds(hold) or 0
        interval = dur_seconds(group.get("interval")) or DEFAULT_EVAL_INTERVAL
        if wait == 0:
            continue  # No `for:`, so a single true evaluation fires. Nothing to mature.
        if wait + JITTER_INTERVALS * interval >= window:
            findings.append(
                f"{rel}: {name}: WINDOW CANNOT ACCOMMODATE ITS SUBJECT — window "
                f"[{human(window)}] with `for: {hold}` against a subject period of "
                f"{human(period)}. The counter increments once per window, so the condition "
                f"is true in runs of at most {human(window)} and then decays, resetting the "
                "`for:` timer just short of maturity — the rule can sit `pending` forever "
                "and never `firing`. Either widen the window past the subject period "
                f"(> {human(period)}), or shorten `for:` well below {human(window)}, or use "
                "a rate-free set-difference form (`increase(...)` over several invocations "
                "combined with `unless`) that does not decay at all."
            )

    parsed_count = len(seen_rules)
    if parsed_count != grep_count:
        findings.append(
            f"the YAML walk reached {parsed_count} alert rule(s) but `- alert:` appears "
            f"{grep_count} time(s) under {args.root}. The walk did not see everything, so no "
            "verdict below is trustworthy. Fix the parse before reading the findings."
        )
    findings.extend(unparseable)

    for stale in sorted(baseline - seen) if real_tree else []:
        findings.append(
            f"{EXCLUSIONS.relative_to(REPO)}: STALE BASELINE ENTRY — alert `{stale}` no "
            "longer exists. Remove it."
        )

    # Unconditional, and BEFORE the verdict: a gate that found its corpus and then failed on
    # it must not also read as having lost its corpus.
    if gatelib is not None:
        gatelib.subjects(parsed_count, "alert rules")
    print(
        f"check-alert-window-vs-subject-period: {windowed} windowed alert rule(s); "
        f"{len(subjects)} periodic subject(s) derived from @Scheduled + CronJob + charters "
        f"({len(unresolved)} unresolved); {len(baseline)} baselined."
    )
    if not findings:
        print("check-alert-window-vs-subject-period: no findings.")
        return 0
    for finding in findings:
        prefix = "::error" if args.enforce else "::warning"
        print(f"{prefix} ::{finding}")
    print(f"check-alert-window-vs-subject-period: {len(findings)} finding(s).")
    return 1 if args.enforce else 0


SELF_TEST_BAD_WINDOW = """
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: selftest
spec:
  groups:
    - name: selftest
      rules:
        - alert: AiCallErrorRateHigh
          expr: |
            100 * (
              sum by (model) (rate(openbank_llm_requests_total{outcome=~"http_error"}[15m]))
              /
              sum by (model) (rate(openbank_llm_requests_total{outcome!="not_configured"}[15m]))
            ) > 20
          for: 15m
          annotations:
            subject_period: 30m
"""

# The fix #6041 would have needed: a window wider than the 30m subject period.
SELF_TEST_FIXED = SELF_TEST_BAD_WINDOW.replace("[15m]", "[90m]")

# W <= P but `for:` is far shorter than the window, so the run of true evaluations is long
# enough to mature. This is `StatementCloseFailures`' real shape (increase[1h], for: 5m, on a
# monthly subject) and it must NOT be flagged — a rule that has never fired because nothing
# bad happened is healthy, and confusing the two is the whole failure mode of this gate.
SELF_TEST_SHORT_FOR = """
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: selftest
spec:
  groups:
    - name: selftest
      rules:
        - alert: SelfTestShortFor
          expr: sum(increase(x_total[1h])) > 0
          for: 5m
          annotations:
            subject_period: 24h
"""

# No `for:` at all — a single true evaluation fires, so a decaying window cannot hurt it.
# This is `SubledgerTieOutBreak`'s shape (increase[25h], no for, daily subject).
SELF_TEST_NO_FOR = """
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: selftest
spec:
  groups:
    - name: selftest
      rules:
        - alert: SelfTestNoFor
          expr: sum(increase(x_total[25h])) > 0
          annotations:
            subject_period: 24h
"""

SELF_TEST_BLIND_RATIO = """
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: selftest
spec:
  groups:
    - name: selftest
      rules:
        - alert: SelfTestBlindRatio
          expr: |
            sum(increase(x_total{action="SUCCESS"}[1h]))
            / clamp_min(sum(increase(x_total[1h])), 1) < 0.70
          for: 15m
          annotations:
            subject_period: continuous
"""

SELF_TEST_BLIND_RATIO_INVERTED = """
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: selftest
spec:
  groups:
    - name: selftest
      rules:
        - alert: SelfTestBlindRatioInverted
          expr: |
            ( 1 - ( sum(increase(x_total{action="STEP_VIEWED"}[6h]))
                    / clamp_min(sum(increase(x_total[6h])), 1) ) ) > 0.40
          for: 30m
          annotations:
            subject_period: continuous
"""

SELF_TEST_UNDECLARED = """
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: selftest
spec:
  groups:
    - name: selftest
      rules:
        - alert: SelfTestUndeclared
          expr: sum(rate(x_total[5m])) > 1
          for: 5m
"""


def self_test() -> int:
    """Feed the gate each defect it exists to catch, then the fixed shape.

    A gate is proven by what it PREVENTS, so the negative cases run FIRST and must fail.
    """
    import subprocess
    import tempfile

    script = str(Path(__file__).resolve())
    cases = [
        ("pre-#6041 AiCallErrorRateHigh (window 15m vs 30m subject)", SELF_TEST_BAD_WINDOW, 1,
         "WINDOW CANNOT ACCOMMODATE ITS SUBJECT"),
        ("total-failure-blind success/total ratio", SELF_TEST_BLIND_RATIO, 1,
         "RATIO IS TOTAL-FAILURE BLIND"),
        ("the same blindness spelled `1 - (good/total) > x`", SELF_TEST_BLIND_RATIO_INVERTED,
         1, "RATIO IS TOTAL-FAILURE BLIND"),
        ("windowed rule with no subject_period declaration", SELF_TEST_UNDECLARED, 1,
         "no `subject_period` annotation"),
        ("the same rule with the window widened past the subject period", SELF_TEST_FIXED, 0,
         "no findings"),
        ("healthy: window <= period but `for:` far shorter than the window", SELF_TEST_SHORT_FOR,
         0, "no findings"),
        ("healthy: no `for:` at all, so one true evaluation fires", SELF_TEST_NO_FOR, 0,
         "no findings"),
    ]
    ok = True
    for label, body, want_code, want_text in cases:
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "selftest.yaml").write_text(body)
            proc = subprocess.run(
                [sys.executable, script, "--enforce", "--root", tmp],
                capture_output=True, text=True,
            )
        got = proc.returncode
        hit = want_text in proc.stdout
        good = got == want_code and hit
        ok &= good
        print(f"  [{'ok' if good else 'FAIL'}] {label}: exit {got} (want {want_code}), "
              f"expected text {'found' if hit else 'MISSING'}")
        if not good:
            print(proc.stdout[-1500:])
    print("check-alert-window-vs-subject-period --self-test: "
          + ("the gate fails on every defect it claims to catch and passes on the fix."
             if ok else "THE GATE DID NOT BEHAVE AS DOCUMENTED."))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
