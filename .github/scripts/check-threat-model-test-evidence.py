#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Did the test a threat model cites actually RUN GREEN? (issue #13171, ratchet)

`check-threat-model-claims.py` (threat-model-claims-resolve) accepts a cited test class's EXISTENCE
as proof. A class can exist while excluded from its CI task, skipped for a month, or red on main.
This reads the newest Test Intelligence run envelope per component and reports UNVERIFIED when a
cited test class (or the tests declaring `verifies:<scope>/<row-id>`) is absent from it or not
passed. The logic lives beside the claims parser (`load_evidence`, `evidence_findings`); this
entrypoint only decides where evidence comes from and whether findings fail.

  --evidence PATH     envelope file or directory (repeatable; requires --run-metadata)
  --fetch-evidence    newest scheduled main Services CI envelopes via `gh` (best effort: ANY
                      failure is UNRESOLVED — not a pass, not a failure)
  --pr-base SHA       with --fetch-evidence: skip the fetch if this diff touches nothing the audit
                      reads differently. The envelopes come from the newest scheduled main run, so
                      an unrelated PR would get main's answer at about 90 gh calls per CI run. A
                      failed diff or an empty base fetches as before.
  --enforce           make every UNVERIFIED fail, baseline or not.
  --ratchet FILE      make UNVERIFIED fail only if it is absent from FILE AND --pr-base shows this
                      diff adding or editing the row that cites it. Scheduled evidence describes an
                      earlier commit, so a test going red on main is not the fault of an unrelated
                      PR, a red citation in a row the PR left alone is not either, and a push to main
                      has no author to fail: all stay warnings. FILE may only shrink against
                      --pr-base; that is checked before any evidence is read, so an unreadable
                      envelope cannot hide a line added to pass a PR.

Evidence comes from Services CI plus, for openbank-admin-ui, the deployed session lane. If that lane
cannot be read, admin-ui is dropped from the audit (unknown, not a verdict).
"""
from __future__ import annotations

import argparse
import importlib.util
import pathlib
import re
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent


# What can change this audit's answer on a PR. Test sources are absent on purpose: the envelopes predate
# the PR, so editing a test cannot change what they say about it.
RELEVANT = ("docs/threat-models/", ".github/scripts/check-threat-model-",
            ".github/gates/baselines/threat-model-test-evidence.txt")
DEPLOYED_WORKFLOW = "admin-ui-deployed-auth-gate.yml"
# Only the session lane: the same run's auth-gate envelope has the same component and declares no
# ids, so letting it in would make "newest per component" silently decide which one is read.
DEPLOYED_ARTIFACT = "test-intelligence-run-openbank-admin-ui-deployed-session-"
DEPLOYED_COMPONENT = "openbank-admin-ui"


def finding_key(svc: str, rid: str, detail: str) -> str:
    cited = re.search(r"`([^`]+)`", detail)
    return f"{svc}|{rid}|{cited.group(1) if cited else detail}"


def _baseline_lines(text: str) -> list[str]:
    return [s for s in (line.split("#", 1)[0].strip() for line in text.splitlines()) if s]


def load_baseline(path: pathlib.Path) -> set[str]:
    return set(_baseline_lines(path.read_text()))


def show_at(base: str, root: pathlib.Path, rel: str, run=subprocess.run) -> str | None:
    """A file's text at the PR's merge-base, or None when it did not exist there. Raises if unreadable."""
    point = run(["git", "-C", str(root), "merge-base", base, "HEAD"],
                capture_output=True, text=True, timeout=60, check=True).stdout.strip()
    tree = run(["git", "-C", str(root), "ls-tree", "--name-only", point, "--", f":/{rel}"],
               capture_output=True, text=True, timeout=60, check=True).stdout
    if not tree.strip():
        return None
    return run(["git", "-C", str(root), "show", f"{point}:{rel}"],
               capture_output=True, text=True, timeout=60, check=True).stdout


def touched_rows(base: str, root: pathlib.Path, parse, run=subprocess.run) -> dict[str, set[tuple[str, str]]] | None:
    """(row id, mitigation) this diff adds or edits, per model; None when nothing can be attributed."""
    if not base:
        return None
    try:
        diff = run(["git", "-C", str(root), "diff", "--name-only", f"{base}...HEAD"],
                   capture_output=True, text=True, timeout=60, check=True).stdout
        rows: dict[str, set[tuple[str, str]]] = {}
        for line in diff.splitlines():
            path = root / line
            if not (line.startswith("docs/threat-models/") and line.endswith(".md")) or not path.exists():
                continue
            before = show_at(base, root, line, run)
            old = {tuple(c[:3]) for c in parse(before)} if before is not None else set()
            # Prose units carry no row id; the audit does not judge them either.
            rows[line[len("docs/threat-models/"):-len(".md")]] = {
                (c[0], c[2]) for c in parse(path.read_text(encoding="utf-8"))
                if c[3] != "prose" and tuple(c[:3]) not in old}
    except (OSError, subprocess.SubprocessError):
        return None
    return rows


def blames_row(finding: tuple, rows: set[tuple[str, str]]) -> bool:
    """Is this finding about a citation in a row the diff touched? Row ids repeat across tables."""
    _kind, _svc, rid, detail = finding
    cited = re.search(r"`([^`]+)`", detail)
    return any(row == rid and (not cited or cited.group(1) in mitigation
                               or cited.group(1).startswith("verifies:") and cited.group(1).endswith(f"/{rid}"))
               for row, mitigation in rows)


def _declaring_files(ref: str, root: pathlib.Path, comp: str, cls: str, run=subprocess.run) -> set[str]:
    """Files under `<comp>/src/test` at `ref` that DECLARE class/object `cls` (a mention is not a declaration)."""
    decl = re.compile(rf"^[ \t]*(?:(?:public|internal|private|protected|abstract|open|final|data|sealed|static)\s+)*"
                      rf"(?:class|object)\s+{re.escape(cls)}\b", re.M)
    listed = run(["git", "-C", str(root), "grep", "-l", "-w", "-F", "-e", cls, ref, "--", f"{comp}/src/test"],
                 capture_output=True, text=True, timeout=60)
    if listed.returncode not in (0, 1):
        raise subprocess.SubprocessError(listed.stderr)
    out = set()
    for line in listed.stdout.splitlines():
        path = line.split(":", 1)[1] if ":" in line else line
        text = run(["git", "-C", str(root), "show", f"{ref}:{path}"], capture_output=True, text=True,
                   timeout=60, check=True).stdout
        if decl.search(text):
            out.add(path)
    return out


def added_test_classes(base: str, root: pathlib.Path, run=subprocess.run) -> set[tuple[str, str]]:
    """(component, class) for classes that are truly NEW in this diff: a file under `<component>/src/test/`
    is added, no file in that component declared the class at the merge-base, and at HEAD exactly one file
    declares it (an added one). A second file reusing an existing class's name must not launder its result."""
    if not base:
        return set()
    try:
        diff = run(["git", "-C", str(root), "diff", "--name-only", "--diff-filter=A", f"{base}...HEAD"],
                   capture_output=True, text=True, timeout=60, check=True).stdout
        point = run(["git", "-C", str(root), "merge-base", base, "HEAD"],
                    capture_output=True, text=True, timeout=60, check=True).stdout.strip()
        added: dict[tuple[str, str], set[str]] = {}
        for line in diff.splitlines():
            m = re.fullmatch(r"([^/]+)/src/test/(?:.+/)?([^/]+)\.(?:kt|java)", line.strip())
            if m:
                added.setdefault((m.group(1), m.group(2)), set()).add(line.strip())
        out = set()
        for (comp, cls), files in added.items():
            if _declaring_files(point, root, comp, cls, run):
                continue
            if len(heads := _declaring_files("HEAD", root, comp, cls, run)) == 1 and heads <= files:
                out.add((comp, cls))
        return out
    except (OSError, subprocess.SubprocessError):
        return set()


def is_pending_new_test(finding: tuple, added: set[tuple[str, str]]) -> bool:
    """A cited test this very diff adds cannot appear in an earlier run: the PR's own CI is its evidence."""
    _kind, svc, _rid, detail = finding
    cited = re.search(r"`([^`]+)`", detail)
    return bool(cited and "has no result in the latest" in detail and (svc, cited.group(1)) in added)


def ratchet_failures(findings: list, baseline: set[str], touched: dict[str, set[tuple[str, str]]] | None,
                     added: set[tuple[str, str]] | None = None) -> list:
    if touched is None:
        return []
    return [f for f in findings
            if blames_row(f, touched.get(f[1], set())) and finding_key(f[1], f[2], f[3]) not in baseline
            and not is_pending_new_test(f, added or set())]


def baseline_growth(base: str, root: pathlib.Path, ratchet: pathlib.Path, run=subprocess.run) -> list[str] | None:
    """Lines the diff adds to the baseline; [] when it introduces the file, None when it cannot tell."""
    if not base:
        return None
    current = set(_baseline_lines(ratchet.read_text()))
    try:
        before = show_at(base, root, ratchet.resolve().relative_to(root).as_posix(), run)
    except (OSError, ValueError, subprocess.SubprocessError):
        return None
    return [] if before is None else sorted(current - set(_baseline_lines(before)))


def merge_deployed(evidence: dict, deployed: dict) -> None:
    """Add the session lane's results; without them this component's audit would be partial."""
    lane = deployed.get(DEPLOYED_COMPONENT)
    if lane is None:
        evidence.pop(DEPLOYED_COMPONENT, None)
        return
    mine = evidence.setdefault(DEPLOYED_COMPONENT, {"classes": {}, "verifies": {}})
    for field in ("classes", "verifies"):
        for name, states in lane[field].items():
            mine[field].setdefault(name, set()).update(states)


def fetch_needed(base: str, root: pathlib.Path, run=subprocess.run) -> bool:
    if not base:
        return True
    try:
        diff = run(["git", "-C", str(root), "diff", "--name-only", f"{base}...HEAD"],
                   capture_output=True, text=True, timeout=60, check=True).stdout
    except (OSError, subprocess.SubprocessError):
        return True
    return any(line.startswith(RELEVANT) for line in diff.splitlines())


def fetch_needed_self_test() -> int:
    class Out:
        def __init__(self, stdout):
            self.stdout = stdout

    def diff(text):
        return lambda *a, **k: Out(text)

    def broken(*a, **k):
        raise subprocess.CalledProcessError(128, "git")

    root = pathlib.Path(".")
    cases = [
        ("no base fetches", fetch_needed("", root, diff("")), True),
        ("unrelated diff skips", fetch_needed("b", root, diff("openbank-x/src/main/A.kt\n")), False),
        ("empty diff skips", fetch_needed("b", root, diff("")), False),
        ("test source skips", fetch_needed("b", root, diff("openbank-x/src/test/ATest.kt\n")), False),
        ("threat model fetches", fetch_needed("b", root, diff("a.md\ndocs/threat-models/x.md\n")), True),
        ("this gate fetches", fetch_needed("b", root, diff(".github/scripts/check-threat-model-claims.py\n")), True),
        ("failed diff fetches", fetch_needed("b", root, broken), True),
    ]
    bad = [name for name, got, want in cases if got != want]
    print(f"fetch_needed self-test: {len(cases)} cases, failed={bad}")
    return 1 if bad else 0


def ratchet_self_test() -> int:
    new = ("UNVERIFIED", "m", "T1", "cites test `SkippedGuardTest`, whose latest result is skipped")
    old = ("UNVERIFIED", "m", "T2", "cites test `OldTest`, which exists but has no result")
    other = ("UNVERIFIED", "n", "T1", "cites test `RedOnMainTest`, whose latest result is failed")
    base = {"m|T2|OldTest"}
    ev = {DEPLOYED_COMPONENT: {"classes": {}, "verifies": {"a/b": {"passed"}}}}
    gone: dict = {}
    merge_deployed(gone, {})
    kept = {DEPLOYED_COMPONENT: {"classes": {}, "verifies": {"a/b": {"skipped"}}}}
    merge_deployed(kept, ev)
    popped = dict(ev)
    merge_deployed(popped, {})
    edited = {"m": {("T1", "`SkippedGuardTest` asserts it")}}
    unrelated_edit = {"m": {("T9", "`AnotherTest` asserts it")}}
    same_id_other_table = {"m": {("T1", "`AnotherTest` asserts it")}}
    declared = ("UNVERIFIED", "m", "T1", "`verifies:m/T1` ran but not green (failed)")
    nores = ("UNVERIFIED", "m", "T1", "cites test `SkippedGuardTest`, which exists but has no result in the latest m run (not executed)")
    redres = ("UNVERIFIED", "m", "T1", "cites test `SkippedGuardTest`, whose latest result is failed")
    cases = [
        ("new finding in a touched row fails", ratchet_failures([new], base, edited), [new]),
        ("baselined finding passes", ratchet_failures([old], base, {"m": {("T2", "`OldTest`")}}), []),
        ("a model this PR did not touch cannot fail it", ratchet_failures([other], base, edited), []),
        ("another row of the same model cannot fail it", ratchet_failures([new], base, unrelated_edit), []),
        ("a same-id row citing something else cannot fail it", ratchet_failures([new], base, same_id_other_table), []),
        ("a touched row's declared verifies id fails", ratchet_failures([declared], base, same_id_other_table), [declared]),
        ("no diff (a push to main) cannot fail", ratchet_failures([new], base, None), []),
        ("a touched row citing a test added by the diff is pending, not failing",
         ratchet_failures([nores], base, edited, {("m", "SkippedGuardTest")}), []),
        ("a touched row citing an existing test with no result still fails", ratchet_failures([nores], base, edited, set()), [nores]),
        ("a test added in a different component still fails",
         ratchet_failures([nores], base, edited, {("n", "SkippedGuardTest")}), [nores]),
        ("an added test with a red result still fails",
         ratchet_failures([redres], base, edited, {("m", "SkippedGuardTest")}), [redres]),
        ("key ignores the wording of the verdict", finding_key("m", "T2", old[3]), "m|T2|OldTest"),
        ("skipped in one lane survives a pass in the other", kept[DEPLOYED_COMPONENT]["verifies"]["a/b"], {"passed", "skipped"}),
        ("an unreadable lane makes the component unknown", popped, {}),
    ]
    bad = [name for name, got, want in cases if got != want]
    print(f"ratchet self-test: {len(cases)} cases, failed={bad}")
    return 1 if bad else 0


def git_ratchet_self_test(parse) -> int:
    """Real git, so the merge-base reads, the renamed-away file and the growth check are all exercised."""
    def git(*args):
        subprocess.run(["git", "-C", str(root), "-c", "user.name=t", "-c", "user.email=t@t",
                        "-c", "commit.gpgsign=false", *args], check=True, capture_output=True, text=True)

    table = "| ID | Threat | Mitigation |\n|---|---|---|\n"
    with tempfile.TemporaryDirectory() as scratch:
        root = pathlib.Path(scratch).resolve()
        git("init", "-q")
        model = root / "docs/threat-models/m.md"
        model.parent.mkdir(parents=True)
        model.write_text(table + "| T1 | Replay | `RedTest` asserts it |\n| T2 | Spoof | `OtherTest` asserts it |\n")
        ratchet = root / "baseline.txt"
        ratchet.write_text("# header\nm|T1|RedTest\n")
        git("add", "."); git("commit", "-qm", "base")
        git("branch", "base")
        cases = [("unchanged baseline does not grow", baseline_growth("base", root, ratchet), []),
                 ("no base cannot tell", baseline_growth("", root, ratchet), None),
                 ("an unknown base cannot tell", baseline_growth("no-such-ref", root, ratchet), None)]

        model.write_text(table + "| T1 | Replay | `RedTest` asserts it |\n| T2 | Spoof | `OtherTest` and `NewTest` |\n")
        ratchet.write_text("# header reworded\nm|T1|RedTest\nm|T3|PassMePleaseTest\n")
        git("add", "."); git("commit", "-qm", "edit")
        cases += [
            ("an added baseline line is growth", baseline_growth("base", root, ratchet), ["m|T3|PassMePleaseTest"]),
            ("only the edited row is touched", touched_rows("base", root, parse), {"m": {("T2", "`OtherTest` and `NewTest`")}}),
        ]
        ratchet.write_text("")
        git("add", "."); git("commit", "-qm", "shrink")
        cases.append(("an emptied baseline does not grow", baseline_growth("base", root, ratchet), []))
        fresh = root / "introduced.txt"
        fresh.write_text("m|T1|RedTest\n")
        git("add", "."); git("commit", "-qm", "new")
        cases.append(("a baseline this PR introduces is not growth", baseline_growth("base", root, fresh), []))
        new_model = root / "docs/threat-models/n.md"
        new_model.write_text(table + "| T1 | Replay | `RedTest` asserts it |\n")
        git("add", "."); git("commit", "-qm", "model")
        cases.append(("every row of a new model is touched", touched_rows("base", root, parse)["n"], {("T1", "`RedTest` asserts it")}))
        git("branch", "pre")
        files = {"m/src/test/kotlin/x/AddedIT.kt": "class AddedIT {}\n",
                 "m/src/main/kotlin/x/NotATest.kt": "class NotATest {}\n",
                 "m/src/test/kotlin/x/Sub/Dup.kt": "class Dup {}\n",
                 "m/src/test/kotlin/y/Dup.kt": "internal class Dup {}\n",
                 "m/src/test/kotlin/x/Mention.kt": "class Other { val a = ReusedIT() }\n"}
        for rel, body in files.items():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            (root / rel).write_text(body)
        git("add", "."); git("commit", "-qm", "tests")
        cases.append(("single new class passes; main source, duplicate declarations excluded",
                      added_test_classes("pre", root), {("m", "AddedIT")}))
        (root / "m/src/test/kotlin/z").mkdir()
        (root / "m/src/test/kotlin/z/Existing.kt").write_text("class Existing {}\n")
        git("add", "."); git("commit", "-qm", "existing")
        git("branch", "pre2")
        (root / "m/src/test/kotlin/w").mkdir()
        (root / "m/src/test/kotlin/w/Existing.kt").write_text("class Existing {}\n")
        git("add", "."); git("commit", "-qm", "reuse name")
        cases.append(("an added file reusing an existing class name is not new",
                      added_test_classes("pre2", root), set()))
        cases.append(("no base knows no added tests", added_test_classes("", root), set()))
    bad = [name for name, got, want in cases if got != want]
    print(f"git ratchet self-test: {len(cases)} cases, failed={bad}")
    return 1 if bad else 0


def _claims_module():
    spec = importlib.util.spec_from_file_location("tmclaims", HERE / "check-threat-model-claims.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(REPO))
    ap.add_argument("--evidence", action="append", default=[], metavar="PATH")
    ap.add_argument("--fetch-evidence", action="store_true")
    ap.add_argument("--pr-base", default="")
    ap.add_argument("--run-metadata", type=pathlib.Path, help="independently obtained trusted run API JSON for offline evidence")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--ratchet", type=pathlib.Path)
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    m = _claims_module()
    if a.self_test:
        # the evidence cases live in the claims self-test, beside the parser
        return m.self_test() or fetch_needed_self_test() or ratchet_self_test() or git_ratchet_self_test(m.claims)
    root = pathlib.Path(a.root).resolve()
    services = m.subjects_all(root)
    print(f"SUBJECTS={len(services)}")
    if a.ratchet:
        grown = baseline_growth(a.pr_base, root, a.ratchet)
        if grown:
            print(f"::error file={a.ratchet},title=test evidence baseline grew::this PR adds {grown}. "
                  "The baseline only shrinks: make the cited test run green or remove the citation.")
            return 1
    if a.fetch_evidence and not fetch_needed(a.pr_base, root):
        print("::notice title=test evidence::NOT CHECKED on this PR — it touches no threat model and the "
              "evidence is main's, so main's own run reports the same answer. Not a pass.")
        return 0
    paths = [pathlib.Path(x) for x in a.evidence]
    trusted_run = None
    if a.run_metadata:
        import json
        try:
            trusted_run = json.loads(a.run_metadata.read_text())
        except (OSError, ValueError):
            pass
    with tempfile.TemporaryDirectory() as scratch:
        if a.fetch_evidence:
            fetched, trusted_run = m.fetch_latest_evidence(pathlib.Path(scratch) / "services")
            paths += fetched
        evidence = m.load_evidence(paths, trusted_run)
        if a.fetch_evidence and evidence:
            lane, lane_run = m.fetch_latest_evidence(pathlib.Path(scratch) / "deployed", DEPLOYED_WORKFLOW, DEPLOYED_ARTIFACT)
            merge_deployed(evidence, m.load_evidence(lane, lane_run, DEPLOYED_WORKFLOW))
        if not evidence:
            print("::notice title=test evidence::UNRESOLVED — no Test Intelligence envelope could be read; "
                  "cited tests were not checked for a green run. Not a pass and not a failure.")
            return 0
        findings, judged = m.evidence_findings(root, services, m.Corpus(root), evidence)
    print(f"test-evidence audit: {len(evidence)} component envelope(s), {judged} cited test claim(s) "
          f"judged, {len(findings)} UNVERIFIED")
    baseline = load_baseline(a.ratchet) if a.ratchet else set()
    failing = findings if a.enforce else []
    if a.ratchet:
        added = added_test_classes(a.pr_base, root)
        failing = ratchet_failures(findings, baseline, touched_rows(a.pr_base, root, m.claims), added)
        stale = sorted(baseline - {finding_key(s, r, d) for _k, s, r, d in findings})
        if stale:
            print(f"::notice title=test evidence::{len(stale)} baseline entr(ies) no longer reported; remove: {stale}")
    for finding in findings:
        kind, svc, rid, detail = finding
        level = "error" if finding in failing else "warning"
        note = ""
        if a.ratchet and is_pending_new_test(finding, added):
            note = " (test is added by this PR; its own Services CI runs it, the first scheduled main run after merge judges it)"
        print(f"::{level} file=docs/threat-models/{svc}.md,title=threat-model claim ({kind})::{svc} {rid}: {detail}{note}")
    return 1 if failing else 0


if __name__ == "__main__":
    sys.exit(main())
