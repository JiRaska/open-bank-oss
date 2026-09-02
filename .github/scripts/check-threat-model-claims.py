#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A threat model is a set of CLAIMS about running code. Nothing checked whether any is true.

WHY THIS EXISTS
---------------
Proven live, not hypothetical (#6037 / PR #6048, `openbank-settlement-service`):

  * Risk **R2** was "Compensation claimed to have run when it did not", and its mitigation
    credited the status transitions written by the compensation activities themselves. Those
    activities logged a stub line and moved no money. The document asserted the risk was
    mitigated by the very code that embodied it — a claim structurally unable to be false.
  * Risk **T1** named an idempotency key built from identifiers that appear in no source file
    in this repo, and which would change on every workflow run even if they did.

Two earlier instances of the same class are recorded in the documents themselves: settlement S2
credited "service mesh mTLS" for the life of the service while no mesh has ever been deployed
here (#3921), and `openbank-vop-service` documented lookup controls whose two hops dialled ports
that do not exist (#3966).

`check-threat-models.py` proves a model EXISTS. `check-threat-model-diff.py` proves it is
TOUCHED when a trust boundary moves. Neither reads a single claim, so a model can be present,
current, freshly edited — and false. This gate reads the claims.

WHAT IT CHECKS (the mechanically detectable subset — deliberately narrow)
------------------------------------------------------------------------
  PHANTOM    a mitigation cites a code symbol (CamelCase type, source path, `data.x.y` policy
             rule, or a lowerCamelCase identifier) that appears in NO tracked backend source.
             The control named does not exist. (settlement S1 cites `OpaActivityInterceptor`;
             T1 cites `workflowRunId` / `activityId`.)
  STUB       a mitigation cites a symbol whose own DECLARATION opens with a stub marker
             (`stub:`, `TODO`, `FIXME`, `not implemented`) — the control exists as a name only.
  SELF-REF   the threat is precisely "the record says it happened / it reported success" and
             the mitigation's only named mechanism IS record-keeping (a status transition, an
             audit entry, an execution history). The mitigation cannot distinguish the good
             case from the threat. (settlement R2.)

WHAT IT DOES NOT CHECK, AND WHY THAT IS SAID OUT LOUD
-----------------------------------------------------
It cannot decide whether a control that exists does what the sentence says. Settlement T1's key
is inoperative for a reason no parser can see: it is not stable across retries. And it is blind
to the largest class by far — a mitigation naming no mechanism at all is unfalsifiable by
construction, so this gate COUNTS those and prints the count, but does not fail on them: failing
would make "delete the sentence" the cheapest fix, which is the wrong incentive. The 2026-08-20
census over the 23 `money_path_services` models found roughly two thirds of all mitigation
claims in that state. Reducing it is editorial work, not a gate.

SCOPE IS DERIVED, NEVER HAND-KEPT
---------------------------------
Subjects = `rules.yaml: money_path_services` x `docs/threat-models/<service>.md`, and the claims
inside are enumerated by TWO independent parsers whose UNION is checked — a table parser keyed
on a Mitigation/Control column header, and a section parser that reads STRIDE-titled sections
with no header knowledge at all. They disagree by design: the fleet's models are not one format
(2026-08-20: 296 claims by the table parser, 315 by the section parser, 390 in the union, and
two services are invisible to the table parser entirely). A single parser here would be a probe,
not a census — this repo has been burned by exactly that (grepping `src/test` for the word
"contract" scored comment lines as contract tests, #2291).

Exclusions live in ALLOWED_UNRESOLVED and must carry a reason. A stale entry fails in BOTH
directions: an exclusion for a claim that no longer exists is as much a defect as a missing one,
because a gate whose scope is a hand-kept list reads as PASSING when the list is short.

Usage:  check-threat-model-claims.py [--enforce] [--report] [--root <dir>]
        check-threat-model-claims.py --self-test
"""
from __future__ import annotations

import argparse
import importlib.util
import pathlib
import re
import subprocess

REPO = pathlib.Path(__file__).resolve().parents[2]

# ---------------------------------------------------------------- exclusions
# key: "<service>|<risk id>|<symbol or SELF-REF>"  ->  reason a reviewer accepted.
# Empty is the honest state: every finding this gate reports today is a real defect being
# fixed in the same PR, not baselined debt.
ALLOWED_UNRESOLVED: dict[str, str] = {
    # openbank-settlement-service's threat model is being rewritten by PR #6048, which is the
    # worked example this gate was built from. Editing the same rows here would create a
    # semantic conflict with an open money-path PR, so they are excused by NAME and by ROW —
    # never by service, which would blind the gate to the file entirely.
    #
    # Each entry dies on its own: once #6048 lands, the claim it excuses is gone and the
    # stale-exclusion check fails until the line is deleted. That is the point — an exclusion
    # that outlives its subject is the failure mode this list is otherwise prone to.
    "openbank-settlement-service|T1|workflowRunId":
        "idempotency-key defect from #6037; the key is rewritten by PR #6048",
    "openbank-settlement-service|T1|activityId":
        "idempotency-key defect from #6037; the key is rewritten by PR #6048",
    # (An E1 entry for the compensation stub was here and was DELETED, by this gate's own
    #  stale-exclusion rule, the moment the E1 row was corrected: correcting the row disclaimed
    #  the citation, the exclusion stopped matching anything, and the run went red until the
    #  line went away. That is the check failing in the direction people forget to build.)
    # NOT excused, and deliberately left failing until it is answered: S1's
    # `OpaActivityInterceptor`. It is a different defect from #6037, outside #6048's scope, and
    # filed as #6055 — a control that was never built, documented as present and signed off as
    # Closed. It is corrected in the same PR that adds this gate.
}

# ---------------------------------------------------------------- parsers
DASH = re.compile(r"^:?-{2,}:?$")
STRIDE_H = re.compile(
    r"^#{1,4}\s*.*(STRIDE|Spoofing|Tampering|Repudiation|Information disclosure|"
    r"Denial of service|Elevation of privilege|Money-path specific)",
    re.IGNORECASE,
)
HEADERISH = ("mitigation", "control", "threat", "stride", "id", "#", "vector", "risk", "scenario")


def _cells(line: str) -> list[str] | None:
    s = line.strip()
    if not s.startswith("|"):
        return None
    c = [x.strip() for x in s.strip("|").split("|")]
    if c and all(DASH.fullmatch(x) for x in c if x):
        return None  # separator row
    return c


def parse_table(text: str) -> list[tuple[str, str, str]]:
    """METHOD 1 — locate the Mitigation/Control COLUMN from a header row."""
    rows: list[tuple[str, str, str]] = []
    mit = thr = None
    for line in text.splitlines():
        c = _cells(line)
        if c is None:
            if not line.strip().startswith("|"):
                mit = thr = None
            continue
        low = [x.lower() for x in c]
        if any(x.startswith(("mitigation", "control")) for x in low):
            mit = next(i for i, x in enumerate(low) if x.startswith(("mitigation", "control")))
            thr = next((i for i, x in enumerate(low) if x.startswith(("threat", "risk", "scenario"))), None)
            continue
        if mit is not None and len(c) > mit and c[mit]:
            rows.append((c[0], c[thr] if thr is not None and len(c) > thr else "", c[mit]))
    return rows


def parse_sections(text: str) -> list[tuple[str, str, str]]:
    """METHOD 2 — inside STRIDE-titled sections, every table row and bullet is a claim unit.

    Knows nothing about column headers, so it sees the models the table parser cannot (billing
    and vop carry no Mitigation column at all) and misses ones it can.
    """
    rows: list[tuple[str, str, str]] = []
    inside = False
    for line in text.splitlines():
        if line.startswith("#"):
            inside = bool(STRIDE_H.match(line))
            continue
        if not inside:
            continue
        c = _cells(line)
        if c is not None:
            low = [x.lower() for x in c]
            if any(x.startswith(HEADERISH) for x in low) and all(len(x) < 30 for x in c):
                continue
            if len(c) >= 2:
                rows.append((c[0], c[1] if len(c) > 2 else "", c[-1]))
            continue
        s = line.strip()
        if re.match(r"^(?:[-*]|\d+\.)\s+\S", s):
            rows.append(("bullet", s, s))
    return rows


def claims(text: str) -> list[tuple[str, str, str]]:
    """UNION of both parsers, deduplicated on (id, mitigation)."""
    seen: set[tuple[str, str]] = set()
    out: list[tuple[str, str, str]] = []
    for rid, thr, mitig in parse_table(text) + parse_sections(text):
        k = (rid, mitig)
        if k in seen:
            continue
        seen.add(k)
        out.append((rid, thr, mitig))
    return out


# ---------------------------------------------------------------- citations
BACKTICK = re.compile(r"`([^`\n]{2,160})`")
# CamelCase type name: at least two humps AND at least one lowercase letter, so a SCREAMING_CASE
# status constant (`REVERSED`) is prose, not a citation.
CAMEL = re.compile(r"^(?=.*[a-z])[A-Z][A-Za-z0-9]*(?:[A-Z][A-Za-z0-9]*)+$")
SRCPATH = re.compile(r"^[\w./-]+\.(?:kt|java|py|rego|sql|ts|tsx|sh)$")
POLICYRULE = re.compile(r"^data\.[a-z][\w.]+$")
LOWERCAMEL = re.compile(r"^[a-z][a-z0-9]*(?:[A-Z][A-Za-z0-9]*)+$")

# Words that look like lowerCamelCase but are English or product prose, not identifiers.
PROSE_LOWERCAMEL = {"noData", "closeMatch", "openBank", "javaScript", "iBan"}


def citations(text: str) -> list[str]:
    out: list[str] = []
    for raw in BACKTICK.findall(text):
        for tok in re.split(r"[\s,;/+]+", raw.strip()):
            t = tok.split("(")[0].split("#")[0].split("::")[0].strip(" .`'\"")
            if not t or t in PROSE_LOWERCAMEL:
                continue
            structural = CAMEL.match(t) or SRCPATH.match(t) or POLICYRULE.match(t)
            # A lowerCamelCase identifier needs a length floor: short ones are ordinary English
            # in backticks far more often than they are symbols.
            identifier = LOWERCAMEL.match(t) and len(t) >= 8
            if structural or identifier:
                out.append(t)
    return sorted(set(out))


# ---------------------------------------------------------------- corpus
DOCISH = (".md", ".txt", ".adoc")
CODEISH = (".kt", ".java", ".py", ".rego", ".sql", ".ts", ".tsx", ".sh", ".yaml", ".yml",
           ".json", ".gradle", ".kts", ".xml", ".properties", ".tf", ".conf")

STUB_WINDOW = 8  # lines of a declaration's body inspected for a stub marker
STUB_MARK = re.compile(r"\bstub\b\s*:|\bTODO\b|\bFIXME\b|not implemented|NotImplemented",
                       re.IGNORECASE)


def is_deployed(rel: str) -> bool:
    """Is this file a DEPLOYED artifact — the thing a runtime mitigation must live in?

    Backend `src/main`, a policy, or an infra manifest. Used to ANCHOR the stub check; it does
    not decide existence.
    """
    if rel.startswith("openbank-admin-ui/"):
        return False
    return ("/src/main/" in rel) or rel.endswith(".rego") or rel.startswith("openbank-infra/")


def is_backend(rel: str) -> bool:
    """Corpus for EXISTENCE. Deployed source plus `src/test`, minus the admin UI.

    Two boundaries, each measured against a real claim on the 2026-08-20 corpus:

      * `src/test` counts. Several mitigations legitimately cite the test that proves them
        (sanctions D2/C5, transaction Tampering). Excluding tests reported three existing test
        classes as phantom controls — a gate that cries wolf on correct documentation is one
        people learn to silence.
      * the admin UI does not. Settlement S1 credits `OpaActivityInterceptor`; its only
        occurrence outside its own threat model is a label rendered on an admin-UI page. A name
        in a UI string is not a control, and a corpus that counts it reports the control as
        present — the exact failure this gate exists to catch.
    """
    if rel.startswith("openbank-admin-ui/"):
        return False
    return is_deployed(rel) or "/src/test/" in rel


class Corpus:
    """Every tracked backend file read ONCE.

    Deliberately not `git grep` per citation: that is one subprocess per symbol and the gate
    stops finishing inside its CI budget — which is the failure mode where somebody quietly
    narrows the scope until it is fast, and a narrowed scope reads as PASSING.
    """

    def __init__(self, root: pathlib.Path):
        self.root = root
        r = subprocess.run(["git", "-C", str(root), "ls-files"],
                           capture_output=True, text=True, check=False)
        self.files = r.stdout.split()
        self.names = {pathlib.Path(f).name for f in self.files}
        self.paths = set(self.files)
        self.blobs: dict[str, str] = {}
        for f in self.files:
            if f.endswith(DOCISH) or not f.endswith(CODEISH) or not is_backend(f):
                continue
            try:
                self.blobs[f] = (root / f).read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
        # Stub detection anchors on DEPLOYED source only: a stubbed test helper is not the
        # defect this gate is about.
        self.main = {f: b for f, b in self.blobs.items() if is_deployed(f) and "/src/main/" in f}

    def resolve(self, sym: str) -> bool:
        """True iff the symbol appears in at least one tracked backend file."""
        if SRCPATH.match(sym):
            if sym in self.paths or any(f.endswith("/" + sym) for f in self.files):
                return True
            return pathlib.Path(sym).name in self.names
        needle = sym.rsplit(".", 1)[-1] if POLICYRULE.match(sym) else sym
        return any(needle in b for b in self.blobs.values())

    def stub_site(self, sym: str) -> str | None:
        """A cited symbol whose own DECLARATION opens with a stub marker.

        Anchored on the declaration, not on any mention: `paymentId` appears in hundreds of
        lines fleet-wide and one of them, in an unrelated service, carries a TODO — matching
        that reports a phantom defect and teaches people the gate is noise.
        """
        if not (CAMEL.match(sym) or LOWERCAMEL.match(sym)):
            return None
        decl = re.compile(r"\b(?:class|object|interface|fun|val|var)\s+" + re.escape(sym) + r"\b")
        for f, b in self.main.items():
            if sym not in b:
                continue
            lines = b.splitlines()
            for n, line in enumerate(lines):
                if not decl.search(line):
                    continue
                if STUB_MARK.search("\n".join(lines[n:n + STUB_WINDOW])):
                    return f"{f}:{n + 1}"
        return None


# ---------------------------------------------------------------- self-reference
# The threat is "the record says it happened".
RECORD_THREAT = re.compile(
    r"claimed to have (?:run|happened|completed)|reports? success|reported success|"
    r"(?:did|does) not (?:actually|really)|without (?:actually|really)|"
    r"appears? (?:to have )?(?:completed|succeeded)|believed to have",
    re.IGNORECASE,
)
# Mechanisms that are themselves record-keeping — they cannot witness their own truthfulness.
RECORD_MECH = re.compile(
    r"\b(?:status|state)\b[^.;]{0,40}\b(?:update[sd]?|transition[sd]?|set to|records?|column)|"
    r"update[sd]? (?:the )?status|audit (?:trail|log|entr)|activity history|"
    r"workflow history|execution (?:trace|history)|records? every attempt",
    re.IGNORECASE,
)
# An independent mechanism rescues the row — something that could disagree with the record.
REAL_MECH = re.compile(
    r"reconcil|counter-?entr|opposite (?:movement|entry)|compare[sd]? against|"
    r"asserts? the|integration test|queries|verif|independent",
    re.IGNORECASE,
)


# ---------------------------------------------------------------- disclaimers
# A cell that explicitly says the named thing is NOT there is narrating a correction, not
# claiming a control. Its citations are quoted evidence and must not re-trigger the gate.
#
# This is deliberately an escape hatch, and deliberately a costly one: the only way to silence
# a finding without writing the code is to state in the threat model that the control does not
# exist — which is precisely the outcome this gate wants. Contrast a hand-kept exclusion list,
# where silence costs nothing and is invisible from the document.
DISCLAIMED = re.compile(
    r"does not exist|do(?:es)? not exist|exists? in no |present in no |is not implemented|"
    r"never (?:committed|existed|has)|no such |was never (?:built|wired|written)|"
    r"this control does not",
    re.IGNORECASE,
)


def is_disclaimed(mitigation: str) -> bool:
    return bool(DISCLAIMED.search(mitigation))


def self_referential(threat: str, mitigation: str) -> bool:
    if not RECORD_THREAT.search(threat):
        return False
    if REAL_MECH.search(mitigation):
        return False
    return bool(RECORD_MECH.search(mitigation))


# ---------------------------------------------------------------- driver
def money_path(root: pathlib.Path) -> list[str]:
    """Derived from rules.yaml via the coverage gate's own parser — one definition, not two."""
    p = root / "openbank-infra" / "scripts" / "check-threat-models.py"
    spec = importlib.util.spec_from_file_location("cm", p)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.money_path_services(root / "openbank-libs" / "governance" / "rules.yaml")


def audit(root: pathlib.Path):
    services = money_path(root)
    corpus = Corpus(root)
    findings: list[tuple[str, str, str, str]] = []
    used: set[str] = set()
    subjects = n_claims = n_uncited = n_disclaimed = 0
    for svc in services:
        path = root / "docs" / "threat-models" / f"{svc}.md"
        if not path.exists():
            continue  # the coverage gate's job, not this one
        subjects += 1
        for rid, threat, mitig in claims(path.read_text(encoding="utf-8")):
            n_claims += 1
            if is_disclaimed(mitig):
                n_disclaimed += 1
                continue
            cites = citations(mitig)
            if not cites:
                n_uncited += 1
            for sym in cites:
                key = f"{svc}|{rid}|{sym}"
                if not corpus.resolve(sym):
                    if key in ALLOWED_UNRESOLVED:
                        used.add(key)
                        continue
                    findings.append(("PHANTOM", svc, rid,
                                     f"cites `{sym}` — present in no tracked backend source"))
                    continue
                site = corpus.stub_site(sym)
                if site:
                    if key in ALLOWED_UNRESOLVED:
                        used.add(key)
                        continue
                    findings.append(("STUB", svc, rid,
                                     f"cites `{sym}`, whose implementation is a stub ({site})"))
            if self_referential(threat, mitig):
                key = f"{svc}|{rid}|SELF-REF"
                if key in ALLOWED_UNRESOLVED:
                    used.add(key)
                    continue
                findings.append((
                    "SELF-REF", svc, rid,
                    ("threat is 'the record claims it happened'; "
                     "mitigation names only record-keeping"),
                ))
    stale = sorted(set(ALLOWED_UNRESOLVED) - used)
    return services, subjects, n_claims, n_uncited, n_disclaimed, findings, stale


def self_test() -> int:
    """Falsify the gate: it must FAIL on the pre-#6048 settlement text and PASS once corrected.

    A gate is proven by what it PREVENTS. Each fixture is the real defect, verbatim in shape,
    paired with the corrected text — so a rule that stops discriminating fails here rather than
    silently going green on the fleet.
    """
    fails: list[str] = []

    def case(label, got, want):
        if got != want:
            fails.append(f"{label}: expected {want!r}, got {got!r}")

    # --- SELF-REF: settlement R2 as it stood before #6048, then as corrected.
    r2_threat = "Compensation claimed to have run when it did not"
    r2_bad = ("Temporal activity history records every attempt; compensation activities update "
              "status to `REVERSED`/`CREDITED_REVERSED`/`LEDGER_REVERSED` atomically")
    r2_good = ("Compensation activities issue the opposite movement to balance-service and are "
               "asserted by a real-DB integration test that queries for the counter-entry")
    case("pre-#6048 R2 is self-referential", self_referential(r2_threat, r2_bad), True)
    case("corrected R2 is not", self_referential(r2_threat, r2_good), False)
    case("record-keeping for an ORDINARY threat is fine",
         self_referential("Settlement DB record modified out-of-band", r2_bad), False)
    case("a non-record mitigation for a record threat is fine",
         self_referential(r2_threat, "mTLS between the worker and the server"), False)

    # --- PHANTOM: the extractor must SEE the symbols the real defects named.
    t1 = "`referenceId = workflowRunId + activityId` stored before side-effect"
    got = citations(t1)
    for want in ("referenceId", "workflowRunId", "activityId"):
        if want not in got:
            fails.append(f"T1 citation extraction missed {want}: got {got}")
    s1 = "OPA activity interceptor (`OpaActivityInterceptor`) rejects tasks not matching policy"
    case("S1's phantom class is extracted", "OpaActivityInterceptor" in citations(s1), True)
    case("a rego rule reference is extracted",
         "data.openbank.settlement.activity.allow"
         in citations("`data.openbank.settlement.activity.allow`"), True)
    case("a SCREAMING_CASE status constant is prose, not a citation",
         citations("`REVERSED`/`LEDGER_REVERSED`"), [])
    case("short lowercase words are not citations", citations("`the`, `and`"), [])

    # --- corpus scope: the rule that decides settlement S1.
    case("admin-ui source is not a deployed control",
         is_deployed("openbank-admin-ui/src/app/temporal/page.tsx"), False)
    case("a test is not a deployed control", is_deployed("openbank-x/src/test/kotlin/Foo.kt"), False)
    # ...but a test DOES prove the cited name exists. Without this, three real test classes were
    # reported as phantom controls on the 2026-08-20 corpus run.
    case("a test still counts for existence", is_backend("openbank-x/src/test/kotlin/Foo.kt"), True)
    case("admin-ui counts for neither",
         is_backend("openbank-admin-ui/src/app/temporal/page.tsx"), False)
    case("service src/main is deployed", is_deployed("openbank-x/src/main/kotlin/Foo.kt"), True)
    case("a policy is deployed",
         is_deployed("openbank-infra/gitops/components/x/x_rest_ext.rego"), True)

    # --- disclaimers. A correction has to NAME the phantom it is correcting, so without this
    # rule the fix for a finding re-triggers the same finding forever.
    case("a cell disclaiming its own control is not a claim",
         is_disclaimed("**Corrected** — this control does not exist; `AuditService` is present "
                       "in no source file in this repository"), True)
    case("an ordinary mitigation is not disclaimed",
         is_disclaimed("`QsealVerifier` verifies the detached signature"), False)
    case("a negation about the THREAT is not a disclaimer of the control",
         is_disclaimed("the attacker does not hold a valid token"), False)

    # --- STUB marker.
    case("a stub log line is a stub marker",
         bool(STUB_MARK.search('"… (compensation — stub: wire reversal)"')), True)
    case("ordinary prose is not", bool(STUB_MARK.search("issues the opposite movement")), False)

    # --- parsers: both must find claims, and the UNION must be at least as large as either.
    doc = ("## Threat enumeration (STRIDE)\n\n### T — Tampering\n\n"
           "| ID | Threat | Mitigation |\n|----|--------|------------|\n"
           f"| T1 | Replayed debit activity credits twice | {t1} |\n"
           f"| R2 | {r2_threat} | {r2_bad} |\n")
    tbl, sec, uni = parse_table(doc), parse_sections(doc), claims(doc)
    case("table parser finds both rows", len(tbl), 2)
    if len(sec) < 2:
        fails.append(f"section parser found {len(sec)} rows, expected >= 2")
    if len(uni) < max(len(tbl), len(sec)):
        fails.append("the union is smaller than a single parser — dedup is eating claims")
    # A model with NO Mitigation column must still be seen: this is the case that makes one
    # parser a probe rather than a census (billing, vop).
    headerless = ("### Spoofing\n\n| # | Item | Status |\n|---|---|---|\n"
                  "| 1 | `SomeFilter` bounds a caller | Shipped |\n")
    case("headerless model is invisible to the table parser", len(parse_table(headerless)), 0)
    if len(parse_sections(headerless)) < 1:
        fails.append("section parser missed a headerless model — the census collapsed to one method")

    for f in fails:
        print(f"SELF-TEST FAIL: {f}")
    print(f"self-test: {'FAILED' if fails else 'ok'} ({len(fails)} failure(s))")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(REPO))
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()

    root = pathlib.Path(a.root).resolve()
    services, subjects, n_claims, n_uncited, n_disclaimed, findings, stale = audit(root)

    print(f"SUBJECTS={subjects}")
    print(f"threat-model claim audit: {subjects}/{len(services)} money-path models, "
          f"{n_claims} mitigation claims (union of two parsers), "
          f"{n_uncited} name no mechanism at all "
          f"(unfalsifiable as written — counted, not failed), "
          f"{n_disclaimed} explicitly disclaimed as absent")

    lvl = "error" if a.enforce else "warning"
    for kind, svc, rid, detail in findings:
        rel = f"docs/threat-models/{svc}.md"
        print(f"::{lvl} file={rel},title=threat-model claim ({kind})::{svc} {rid}: {detail}")
    for key in stale:
        print(f"::{lvl} title=stale exclusion::ALLOWED_UNRESOLVED['{key}'] matched nothing — "
              f"the claim it excused is gone; delete the entry")

    if a.report:
        return 0
    if findings or stale:
        print(f"FAIL: {len(findings)} false/self-referential claim(s), "
              f"{len(stale)} stale exclusion(s)")
        return 1 if a.enforce else 0
    print("OK: every mechanically checkable mitigation claim resolves to real, non-stub code")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
