#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every CronJob declares how stale its last success may get before anyone is told.

`CronJobSuccessStale` (openbank-infra/gitops/components/observability/prometheus-rules-jobs.yaml)
compares the age of a CronJob's last success with the budget the CronJob declares in the
annotation `openbank.tech/max-success-age-seconds`. kube-state-metrics exports that annotation as a
LABEL on `kube_cronjob_annotations`. PromQL cannot turn a label into a number, so the recording rule
`openbank:cronjob_max_success_age_seconds` has one arm per allowed value (a "tier"). A CronJob whose
annotation is missing, or names a value with no arm, gets no budget series. The rule then says
nothing about it: the CronJob is unmonitored, and unmonitored looks exactly like healthy. That is how
a dead control ends up looking the same as a working one.

This gate turns a missing annotation into a build failure and checks that the value makes sense. The
value must be:
  * a positive integer number of seconds,
  * one of the tiers the recording rule has an arm for, read from the shipped rule file, never from
    a copy here, and
  * strictly larger than the longest gap between two scheduled runs. A smaller budget fires between
    two healthy runs, and an alert that always fires is no better than none.

Scope: every `kind: CronJob` document under openbank-infra/gitops, plus every `kubernetes_cron_job_v1`
resource under openbank-infra/aws (the ARC reaper lives in Terraform). A static scan cannot see a
CronJob rendered by a third-party chart. None exist today. The runtime alert
`CronJobMaxSuccessAgeUndeclared` covers that case, because it reads every CronJob kube-state-metrics
can see.

    python3 .github/scripts/check-cronjob-max-success-age.py              # the gate
    python3 .github/scripts/check-cronjob-max-success-age.py --self-test  # known-positive/-negative
    python3 .github/scripts/check-cronjob-max-success-age.py --gaps       # longest gap per CronJob
"""
from __future__ import annotations

import datetime as dt
import pathlib
import re
import sys

import yaml

ANNOTATION = "openbank.tech/max-success-age-seconds"
REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"
TF = REPO / "openbank-infra" / "aws"
RULES = GITOPS / "components" / "observability" / "prometheus-rules-jobs.yaml"
MIN_SUBJECTS = 15  # there were 22 CronJobs on 2026-10-03. A scan that finds far fewer is broken, not clean.

_TIER_ARM = re.compile(
    r'(\d+)\s*\*\s*kube_cronjob_annotations\{[^}]*annotation_openbank_tech_max_success_age_seconds="(\d+)"')


def rule_tiers(text: str) -> set[int]:
    """Return the tiers the recording rule understands.

    Each arm has the shape `N * kube_cronjob_annotations{...="N"}`. An arm whose multiplier and label
    disagree would quietly give a CronJob the wrong budget, so that is an error too.
    """
    tiers = set()
    for mult, label in _TIER_ARM.findall(text):
        if mult != label:
            raise ValueError(f"tier arm multiplies by {mult} but matches label {label!r}")
        tiers.add(int(label))
    if not tiers:
        raise ValueError("no tier arms found in the rule file")
    return tiers


_FIELDS = [(0, 59), (0, 23), (1, 31), (1, 12), (0, 6)]


def _field(expr: str, lo: int, hi: int) -> set[int]:
    out: set[int] = set()
    for part in expr.split(","):
        step = 1
        if "/" in part:
            part, s = part.split("/", 1)
            step = int(s)
        if part == "*":
            a, b = lo, hi
        elif "-" in part:
            a, b = (int(x) for x in part.split("-", 1))
        else:
            a = b = int(part)
            if step != 1:
                b = hi
        if a < lo or b > hi or a > b:
            raise ValueError(f"field {expr!r} out of range {lo}-{hi}")
        out.update(range(a, b + 1, step))
    return out


def longest_gap_seconds(schedule: str) -> int:
    """Return the longest interval between consecutive fires over 5 weeks.

    Five weeks is long enough to cover weekly and weekday-only schedules. The parser handles the
    subset of cron this repo uses: numbers, `*`, `*/n`, `a-b`, `a-b/n` and lists. Vixie cron ORs
    day-of-month and day-of-week when both are restricted. No schedule here does that, and the
    parser refuses such a schedule rather than guess.
    """
    f = schedule.split()
    if len(f) != 5:
        raise ValueError(f"expected 5 cron fields, got {schedule!r}")
    mins, hours, dom, mon, dow = (_field(x, lo, hi) for x, (lo, hi) in zip(f, _FIELDS, strict=True))
    if f[2] != "*" and f[4] != "*":
        raise ValueError(f"both day-of-month and day-of-week restricted: {schedule!r}")
    start = dt.datetime(2026, 1, 5)  # a Monday
    times_of_day = sorted(h * 60 + m for h in hours for m in mins)
    fires = []
    for day in range(35):
        d = start + dt.timedelta(days=day)
        if d.day in dom and d.month in mon and (d.weekday() + 1) % 7 in dow:
            fires.extend(d + dt.timedelta(minutes=x) for x in times_of_day)
    if len(fires) < 2:
        raise ValueError(f"schedule {schedule!r} fires fewer than twice in 5 weeks")
    return int(max((b - a).total_seconds() for a, b in zip(fires, fires[1:], strict=False)))


def check_one(where: str, schedule: str | None, value, tiers: set[int]) -> list[str]:
    if value is None:
        return [f"{where}: missing annotation {ANNOTATION} (an undeclared CronJob is unmonitored)"]
    if not re.fullmatch(r"[1-9][0-9]*", str(value)):
        return [f"{where}: {ANNOTATION}={value!r} is not a positive integer number of seconds"]
    if int(value) not in tiers:
        return [f"{where}: {ANNOTATION}={value} is not a tier of openbank:cronjob_max_success_age_seconds "
                f"{sorted(tiers)}, so the rule would not see it (pick a tier or add an arm)"]
    if schedule is None:
        return [f"{where}: no schedule found"]
    try:
        gap = longest_gap_seconds(schedule)
    except ValueError as e:
        return [f"{where}: cannot evaluate schedule: {e}"]
    if int(value) <= gap:
        return [f"{where}: {ANNOTATION}={value} is not larger than the longest gap between runs "
                f"of {schedule!r} ({gap}s), so the alert would fire between healthy runs"]
    return []


_KIND_CRONJOB = re.compile(r"(?m)^kind:\s*CronJob\s*$")


def scan_gitops(root: pathlib.Path) -> list[tuple[str, str | None, object]]:
    found = []
    for f in sorted(root.rglob("*.yaml")):
        text = f.read_text()
        if not _KIND_CRONJOB.search(text):
            continue
        try:
            docs = list(yaml.safe_load_all(text))
        except yaml.YAMLError:
            continue  # templated (Helm) files. yamllint owns their syntax.
        for d in docs:
            if isinstance(d, dict) and d.get("kind") == "CronJob":
                md = d.get("metadata") or {}
                where = f"{f.relative_to(REPO)} {md.get('namespace', '?')}/{md.get('name', '?')}"
                found.append((where, (d.get("spec") or {}).get("schedule"),
                              (md.get("annotations") or {}).get(ANNOTATION)))
    return found


_TF_RES = re.compile(r'resource\s+"kubernetes_cron_job_v1"\s+"([^"]+)"\s*\{')


def scan_tf(root: pathlib.Path) -> list[tuple[str, str | None, object]]:
    found = []
    for f in sorted(root.rglob("*.tf")):
        text = f.read_text()
        for m in _TF_RES.finditer(text):
            # The resource body runs until the next top-level block.
            nxt = re.search(r"^(resource|data|locals|module|output|variable)\b", text[m.end():], re.M)
            body = text[m.end(): m.end() + nxt.start()] if nxt else text[m.end():]
            schedule = None
            lit = re.search(r'^\s*schedule\s*=\s*"([^"]+)"', body, re.M)
            var = re.search(r'^\s*schedule\s*=\s*var\.(\w+)', body, re.M)
            if lit:
                schedule = lit.group(1)
            elif var:  # resolve the variable's default from any .tf in the same root module
                for vf in sorted(f.parent.glob("*.tf")):
                    d = re.search(r'variable\s+"' + re.escape(var.group(1)) + r'"\s*\{[^}]*?default\s*=\s*"([^"]+)"',
                                  vf.read_text(), re.S)
                    if d:
                        schedule = d.group(1)
                        break
            ann = re.search(r'"' + re.escape(ANNOTATION) + r'"\s*=\s*"([^"]*)"', body)
            found.append((f"{f.relative_to(REPO)} {m.group(1)}", schedule, ann.group(1) if ann else None))
    return found


def self_test() -> int:
    tiers = {3600, 86400, 180000}
    cases = [
        (check_one("ok", "*/10 * * * *", "3600", tiers), 0),
        (check_one("missing", "*/10 * * * *", None, tiers), 1),
        (check_one("nonint", "*/10 * * * *", "1h", tiers), 1),
        (check_one("not-a-tier", "*/10 * * * *", "3601", tiers), 1),
        (check_one("too-small", "0 3 * * *", "86400", tiers), 1),   # equals the gap: fires between runs
        (check_one("daily-ok", "0 3 * * *", "180000", tiers), 0),
        (check_one("weekday-too-small", "0 7 * * 1-5", "180000", tiers), 1),  # Fri->Mon is 3 days
    ]
    assert longest_gap_seconds("*/5 * * * *") == 300
    assert longest_gap_seconds("25 */6 * * *") == 6 * 3600
    assert longest_gap_seconds("0 2 * * 0") == 7 * 86400
    assert longest_gap_seconds("0 7 * * 1-5") == 3 * 86400
    bad = [(c, n) for c, n in cases if len(c) != n]
    for c, n in bad:
        print(f"self-test FAILED: expected {n} finding(s), got {c}")
    try:
        rule_tiers('900 * kube_cronjob_annotations{annotation_openbank_tech_max_success_age_seconds="1800"}')
        print("self-test FAILED: a tier arm whose multiplier and label disagree was accepted")
        return 1
    except ValueError:
        pass
    real_tiers = rule_tiers(RULES.read_text())
    if len(real_tiers) < 5:
        print(f"self-test FAILED: only {len(real_tiers)} tiers parsed from the shipped rule")
        return 1
    # The scanners must find the real estate. Otherwise a broken scan reports clean.
    subjects = scan_gitops(GITOPS) + scan_tf(TF)
    if len(subjects) < MIN_SUBJECTS:
        print(f"self-test FAILED: scan found {len(subjects)} CronJobs, expected >= {MIN_SUBJECTS}")
        return 1
    if not any(s[0].endswith(".tf arc_reaper") for s in subjects):
        print("self-test FAILED: the Terraform scan did not find the arc_reaper kubernetes_cron_job_v1")
        return 1
    if any(s[1] is None for s in subjects):
        print("self-test FAILED: a CronJob was found without a schedule (scanner reads the wrong field)")
        return 1
    if bad:
        return 1
    print(f"self-test OK ({len(cases)} cases, {len(real_tiers)} tiers, {len(subjects)} real CronJobs scanned)")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    subjects = scan_gitops(GITOPS) + scan_tf(TF)
    tiers = rule_tiers(RULES.read_text())
    if "--gaps" in argv:
        for where, sched, val in subjects:
            print(f"{longest_gap_seconds(sched) if sched else '?':>8} {val or '-':>8}  {sched}  {where}")
        return 0
    if len(subjects) < MIN_SUBJECTS:
        print(f"::error::found only {len(subjects)} CronJobs (expected >= {MIN_SUBJECTS}); the scan is broken")
        return 1
    print(f"SUBJECTS={len(subjects)}")
    findings = [x for s in subjects for x in check_one(*s, tiers)]
    for x in findings:
        print(f"::error::{x}")
    print(f"cronjob-max-success-age: {len(subjects)} CronJobs, tiers {sorted(tiers)}, {len(findings)} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
