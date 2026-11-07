#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""ADR-0352 §2/§8 `product-ir-run-limits`: every committed IR document and golden scenario stays
inside the schema maxima.

WHY A GATE AND NOT ONLY THE PARSER
    The Kotlin parser rejects a document over the limits at load time, so a consumer can never run
    one. But a fixture or a golden scenario is committed as JSON, and nothing loads it until a test
    does — a scenario over the limit that nobody's test opens is a lie in the repository. This gate
    opens every one of them on every PR, without a JVM, and reads the maxima from the schema file
    itself so the two cannot drift: a limit raised in the schema is raised here in the same commit.

WHAT IT CHECKS, per document (an IR file or the `revision` of a scenario):
    * irVersion is a supported version (the schema's `const`);
    * tiers <= maxItems, fees <= maxItems, rules per hook <= maxItems, notify variables <= maxProperties;
    * predicate nesting depth <= MAX_PREDICATE_DEPTH (the one limit the schema cannot express — a
      recursive $ref has no depth bound — so it is read from the schema's description sentence);
    * ruleId present, matching the pattern, unique within the document;
    * for a scenario additionally: every step's `expected` list <= maxInstructionsPerEvaluation.

SCOPE is derived: every `*.json` under the repository whose top level carries `irVersion`, plus
every `*.json` whose top level carries `revision` and `steps` (a golden scenario), excluding build
directories. A JSON Schema file is not a subject. Prints SUBJECTS=<n>.

MODES: advisory (::warning, exit 0) unless --enforce. rules.yaml: product_ir_run_limits.

Usage: check-product-ir-run-limits.py [--root DIR] [--enforce] | --self-test
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import pathlib
import re
import sys
import tempfile

SCHEMA = "openbank-libs-domain/src/main/resources/product-ir/v1.schema.json"
SKIP_DIRS = {"build", "node_modules", ".git", ".gradle", "dist", ".next"}
RULE_ID_KEYS = ("interest", "termDeposit")
HOOKS = ("onOpen", "prePosting", "postPosting", "scheduled", "onParameterChange", "onClose")


def load_limits(schema_path: pathlib.Path) -> dict:
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    defs = schema["$defs"]
    desc = schema.get("description", "")
    m_depth = re.search(r"predicate depth <= (\d+)", desc)
    m_instr = re.search(r"maxInstructionsPerEvaluation = (\d+)", desc)
    if not m_depth or not m_instr:
        raise ValueError(f"{schema_path}: description must state 'predicate depth <= N' and 'maxInstructionsPerEvaluation = N'")
    return {
        "irVersion": schema["properties"]["irVersion"]["const"],
        "maxTiers": defs["interest"]["properties"]["tiers"]["maxItems"],
        "maxFees": schema["properties"]["fees"]["maxItems"],
        "maxRulesPerHook": defs["hookRules"]["maxItems"],
        "maxNotifyVariables": next(a for a in defs["action"]["oneOf"] if a["properties"]["type"].get("const") == "NOTIFY")["properties"]["variables"]["maxProperties"],
        "maxPredicateDepth": int(m_depth.group(1)),
        "maxInstructions": int(m_instr.group(1)),
        "ruleIdPattern": re.compile(defs["ruleId"]["pattern"]),
    }


def predicate_depth(node) -> int:
    if not isinstance(node, dict):
        return 0
    t = node.get("type")
    if t in ("and", "or"):
        return 1 + max((predicate_depth(o) for o in node.get("operands") or []), default=0)
    if t == "not":
        return 1 + predicate_depth(node.get("operand"))
    return 1


def check_document(doc: dict, limits: dict, where: str) -> list[str]:
    f: list[str] = []
    if doc.get("irVersion") != limits["irVersion"]:
        f.append(f"{where}: irVersion {doc.get('irVersion')!r} is not the supported {limits['irVersion']}")
    interest = doc.get("interest") or {}
    tiers = interest.get("tiers") or []
    if len(tiers) > limits["maxTiers"]:
        f.append(f"{where}: {len(tiers)} tiers exceeds the limit {limits['maxTiers']}")
    fees = doc.get("fees") or []
    if len(fees) > limits["maxFees"]:
        f.append(f"{where}: {len(fees)} fees exceeds the limit {limits['maxFees']}")
    rule_ids: list[tuple[str, str]] = []
    predicates: list[tuple[str, object]] = []
    if interest:
        rule_ids.append((f"{where}/interest", interest.get("ruleId")))
        if "bonus" in interest:
            rule_ids.append((f"{where}/interest/bonus", (interest["bonus"] or {}).get("ruleId")))
            predicates.append((f"{where}/interest/bonus/predicate", (interest["bonus"] or {}).get("predicate")))
    for i, fee in enumerate(fees):
        rule_ids.append((f"{where}/fees/{i}", (fee or {}).get("ruleId")))
        if "waiver" in (fee or {}):
            predicates.append((f"{where}/fees/{i}/waiver", fee["waiver"]))
    term = doc.get("termDeposit")
    if term:
        rule_ids.append((f"{where}/termDeposit", term.get("ruleId")))
        if "earlyWithdrawal" in term:
            rule_ids.append((f"{where}/termDeposit/earlyWithdrawal", (term["earlyWithdrawal"] or {}).get("ruleId")))
    hooks = doc.get("hooks") or {}
    for hook in HOOKS:
        rules = hooks.get(hook) or []
        if len(rules) > limits["maxRulesPerHook"]:
            f.append(f"{where}/hooks/{hook}: {len(rules)} rules exceeds the limit {limits['maxRulesPerHook']}")
        for i, rule in enumerate(rules):
            rule_ids.append((f"{where}/hooks/{hook}/{i}", (rule or {}).get("ruleId")))
            if "when" in (rule or {}):
                predicates.append((f"{where}/hooks/{hook}/{i}/when", rule["when"]))
            action = (rule or {}).get("action") or {}
            if action.get("type") == "NOTIFY" and len(action.get("variables") or {}) > limits["maxNotifyVariables"]:
                f.append(f"{where}/hooks/{hook}/{i}/action: {len(action['variables'])} notify variables exceeds the limit {limits['maxNotifyVariables']}")
    for path, rid in rule_ids:
        if not isinstance(rid, str) or not rid:
            f.append(f"{path}: missing ruleId")
        elif not limits["ruleIdPattern"].match(rid):
            f.append(f"{path}: ruleId {rid!r} does not match the pattern")
    seen: dict[str, str] = {}
    for path, rid in rule_ids:
        if isinstance(rid, str) and rid:
            if rid in seen:
                f.append(f"{path}: duplicate ruleId {rid!r} (also at {seen[rid]})")
            seen.setdefault(rid, path)
    for path, pred in predicates:
        d = predicate_depth(pred)
        if d > limits["maxPredicateDepth"]:
            f.append(f"{path}: predicate depth {d} exceeds the limit {limits['maxPredicateDepth']}")
    return f


def check_scenario(doc: dict, limits: dict, where: str) -> list[str]:
    f = check_document(doc.get("revision") or {}, limits, f"{where}#revision")
    for i, step in enumerate(doc.get("steps") or []):
        n = len((step or {}).get("expected") or [])
        if n > limits["maxInstructions"]:
            f.append(f"{where}/steps/{i}: {n} expected instructions exceeds maxInstructionsPerEvaluation {limits['maxInstructions']}")
    return f


def subjects(root: pathlib.Path) -> list[tuple[pathlib.Path, str, dict]]:
    out = []
    for path in sorted(root.rglob("*.json")):
        if any(part in SKIP_DIRS for part in path.relative_to(root).parts):
            continue
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except (ValueError, UnicodeDecodeError):
            continue
        if not isinstance(doc, dict) or "$schema" in doc:
            continue
        if "irVersion" in doc and "hooks" in doc:
            out.append((path, "ir", doc))
        elif "revision" in doc and "steps" in doc and isinstance(doc.get("revision"), dict):
            out.append((path, "scenario", doc))
    return out


def run(root: pathlib.Path, enforce: bool) -> int:
    schema_path = root / SCHEMA
    if not schema_path.is_file():
        print(f"::error::{SCHEMA} is missing — the limits have no source; refusing to report a pass")
        return 1
    limits = load_limits(schema_path)
    subs = subjects(root)
    print(f"SUBJECTS={len(subs)}")
    if not subs:
        print("::error::no IR document or golden scenario found — refusing to report a pass")
        return 1
    findings: list[str] = []
    for path, kind, doc in subs:
        rel = str(path.relative_to(root))
        findings += check_scenario(doc, limits, rel) if kind == "scenario" else check_document(doc, limits, rel)
    for line in findings:
        print(f"::{'error' if enforce else 'warning'}::{line}")
    if findings:
        print(f"product-ir-run-limits: {len(findings)} finding(s) over {len(subs)} document(s)" + ("" if enforce else " — ADVISORY mode, not failing the build"))
        return 1 if enforce else 0
    print(f"product-ir-run-limits: OK — {len(subs)} document(s) within the schema maxima")
    return 0


# --------------------------------------------------------------------------------------------
# Self-test: negatives first.
# --------------------------------------------------------------------------------------------

def _schema_text() -> str:
    here = pathlib.Path(__file__).resolve().parents[2] / SCHEMA
    return here.read_text(encoding="utf-8")


def _valid_ir() -> dict:
    return {
        "irVersion": 1, "productCode": "X", "currency": "EUR",
        "interest": {"ruleId": "interest.base", "rateType": "FIXED", "rate": "0.01", "dayCount": "ACT_360",
                     "accrualCadence": "DAILY", "capitalisationCadence": "MONTHLY",
                     "accrualRounding": "INTEREST_ACCRUAL", "postingRounding": "LEDGER_POSTING"},
        "fees": [],
        "hooks": {"onOpen": [{"ruleId": "open.a", "action": {"type": "SCHEDULE", "hook": "scheduled", "tag": "A", "cadence": "DAILY"}}],
                  "scheduled": [{"ruleId": "s.a", "trigger": "A", "action": {"type": "ACCRUE_INTEREST"}}]},
    }


def self_test() -> int:
    ok = True

    def case(label: str, docs: dict[str, dict], want_exit: int, text: str) -> None:
        nonlocal ok
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / SCHEMA).parent.mkdir(parents=True)
            (root / SCHEMA).write_text(_schema_text(), encoding="utf-8")
            for name, doc in docs.items():
                p = root / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(json.dumps(doc), encoding="utf-8")
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                got = run(root, enforce=True)
            good = got == want_exit and text in buf.getvalue()
            ok &= good
            print(f"  [{'ok' if good else 'WRONG'}] {label}: exit {got} (want {want_exit})")
            if not good:
                print(buf.getvalue())

    over_tiers = _valid_ir()
    over_tiers["interest"] = {**over_tiers["interest"], "rateType": "TIERED", "tiers": [{"from": "0", "rate": "0.01"}] * 17}
    over_tiers["interest"].pop("rate")
    case("17 tiers is red", {"a/ir.json": over_tiers}, 1, "17 tiers exceeds the limit 16")

    deep = {"type": "noEvents", "kind": "FEE", "window": {"days": 1}}
    for _ in range(9):
        deep = {"type": "not", "operand": deep}
    deep_ir = _valid_ir()
    deep_ir["fees"] = [{"ruleId": "fee.x", "feeCode": "X", "amount": "1", "cadence": "MONTHLY", "accountingClass": "FEE_INCOME", "waiver": deep}]
    case("predicate depth 10 is red", {"a/ir.json": deep_ir}, 1, "predicate depth 10 exceeds the limit 8")

    dup = _valid_ir()
    dup["hooks"]["scheduled"][0]["ruleId"] = "interest.base"
    case("a duplicate ruleId is red", {"a/ir.json": dup}, 1, "duplicate ruleId 'interest.base'")

    missing = _valid_ir()
    del missing["interest"]["ruleId"]
    case("a missing ruleId is red", {"a/ir.json": missing}, 1, "missing ruleId")

    idx = _valid_ir()
    idx["hooks"]["onOpen"][0]["ruleId"] = "0"
    case("an index-like ruleId is red", {"a/ir.json": idx}, 1, "ruleId '0' does not match the pattern")

    many_rules = _valid_ir()
    many_rules["hooks"]["onClose"] = [{"ruleId": f"c.{i}", "action": {"type": "NOTIFY", "templateId": "t"}} for i in range(33)]
    case("33 rules on one hook is red", {"a/ir.json": many_rules}, 1, "33 rules exceeds the limit 32")

    v2 = _valid_ir()
    v2["irVersion"] = 2
    case("an unsupported irVersion is red", {"a/ir.json": v2}, 1, "irVersion 2 is not the supported 1")

    scenario = {"name": "s", "revision": _valid_ir(), "state": {}, "steps": [{"at": "x", "hook": "onOpen", "expected": [{"type": "NOTIFY"}] * 65}]}
    case("65 expected instructions in a scenario step is red", {"g/s.json": scenario}, 1, "65 expected instructions exceeds maxInstructionsPerEvaluation 64")

    scenario_bad_rev = {"name": "s", "revision": over_tiers, "state": {}, "steps": []}
    case("a scenario whose revision is over the limit is red", {"g/s.json": scenario_bad_rev}, 1, "s.json#revision: 17 tiers")

    case("no subject at all is red", {"a/other.json": {"hello": 1}}, 1, "no IR document or golden scenario found")

    fine_scenario = {"name": "s", "revision": _valid_ir(), "state": {}, "steps": [{"at": "x", "hook": "onOpen", "expected": [{"type": "NOTIFY"}] * 64}]}
    case("a valid IR and a valid scenario are green, SUBJECTS=2", {"a/ir.json": _valid_ir(), "g/s.json": fine_scenario}, 0, "SUBJECTS=2")

    # advisory: finding printed as a warning, exit 0
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        (root / SCHEMA).parent.mkdir(parents=True)
        (root / SCHEMA).write_text(_schema_text(), encoding="utf-8")
        (root / "ir.json").write_text(json.dumps(over_tiers), encoding="utf-8")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            got = run(root, enforce=False)
        good = got == 0 and "::warning::" in buf.getvalue() and "17 tiers" in buf.getvalue()
        ok &= good
        print(f"  [{'ok' if good else 'WRONG'}] advisory mode prints the finding and exits 0")
        (root / SCHEMA).unlink()
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            got = run(root, enforce=False)
        good = got == 1 and "is missing" in buf.getvalue()
        ok &= good
        print(f"  [{'ok' if good else 'WRONG'}] a missing schema is red even in advisory mode")

    print("self-test " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run(pathlib.Path(args.root).resolve(), args.enforce)


if __name__ == "__main__":
    sys.exit(main())
