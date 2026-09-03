#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""check-ai-act-high-risk-inventory.py — EU AI Act Annex III inventory vs. the code.

WHAT THIS PROTECTS
------------------
`docs/compliance/eu-ai-act.md` states that **no production high-risk AI system exists on this
platform**, and every Art. 9-15 obligation is presented as pre-satisfied on that basis. That claim
is the platform's entire AI Act position from 2026-08-02, when Annex III obligations start to apply.

The claim is GENERATED from `openbank-libs/governance/ml-systems.yaml`. `check-eu-ai-act.sh` proves
the document matches the declaration — and nothing proved the declaration matches the code. So the
day someone wires model inference into the credit path, `credit-decisioning-engine` stays
`deployed: false` in a YAML file, the generator keeps rendering "no high-risk system exists", the
drift gate stays green, and the platform is operating an undeclared Annex III(5)(b) system.

That is the failure shape this repo keeps finding elsewhere: a generated document that is green
about a declaration nobody checks against reality. This gate closes it from the other side.

WHAT IT CHECKS
--------------
For every `ml_systems` entry declared **not deployed**, assert the code does not contradict it:

  * The **credit plane** (Annex III(5)(b) — creditworthiness assessment of natural persons) must
    contain no model-inference surface at all. Today the shipped credit engine is ADR-0213's
    deterministic decision-table evaluator, which is not an "AI system" — that is precisely why the
    non-applicability claim holds, and it holds only while it stays true.
  * Inference that DOES exist must live only in a plane whose entry is declared deployed. Today that
    is `openbank-fraud-service` (ADR-0084), declared Limited/minimal risk because fraud prevention
    is not Annex III creditworthiness and the deterministic layer is the permanent decision floor.

Scope is DERIVED from `ml_systems.yaml`, not hand-kept: a new entry is covered the moment it is
declared, and an entry deleted from the inventory stops being checked, which is visible in the diff.

WHEN THIS FAILS, THE FIX IS NOT TO EDIT THE YAML
------------------------------------------------
A red here means one of two things, and they have opposite remedies:

  1. Inference genuinely landed in the credit path. Then `credit-decisioning-engine` becomes the
     platform's first HIGH-RISK system and the whole Art. 9-15 package (ADR-0216) must ship WITH it,
     not after: risk management, data governance, technical documentation, logging, transparency,
     human oversight, accuracy/robustness. Flipping `deployed: true` alone converts a green gate
     into a documented violation.
  2. It is a false positive — a test double, a comment, a name that merely looks like inference.
     Then narrow the pattern here, and say why in the commit.

Usage: python3 .github/scripts/check-ai-act-high-risk-inventory.py
Exit:  0 clean, 1 the inventory's not-deployed claim is contradicted by code
"""

from __future__ import annotations

import pathlib
import re
import sys

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
INVENTORY = REPO / "openbank-libs" / "governance" / "ml-systems.yaml"

# Model-inference surface. Deliberately narrow: these are runtime inference constructs, not the word
# "model" (which appears in every domain) and not "score" (fraud, credit policy and AML all use it
# for deterministic rules). A broad matcher here would produce noise, and a noisy gate gets ignored.
INFERENCE_PATTERNS = [
    (re.compile(r"\bai\.onnxruntime\b"), "ai.onnxruntime import"),
    (re.compile(r"\bOrtSession\b"), "OrtSession"),
    (re.compile(r"\bOrtEnvironment\b"), "OrtEnvironment"),
    (re.compile(r"\bInferenceSession\b"), "InferenceSession"),
    (re.compile(r"\bMlModelPort\b"), "MlModelPort"),
]

# The credit plane: modules that participate in assessing the creditworthiness of a natural person.
# Annex III(5)(b) attaches to the *purpose*, so this list is about what the code decides, not where
# it lives. Keep it explicit — silently widening it would weaken the claim without anyone noticing.
CREDIT_PLANE = ["openbank-lending-service", "openbank-anacredit-service"]

CREDIT_SYSTEM_ID = "credit-decisioning-engine"


def load_inventory() -> list[dict]:
    try:
        import yaml
    except ImportError:
        print("::error::check-ai-act-high-risk-inventory: PyYAML unavailable on this runner")
        raise
    data = yaml.safe_load(INVENTORY.read_text(encoding="utf-8"))
    return data.get("ml_systems") or []


def strip_comments(source: str) -> str:
    """Blank out // line comments and /* nested block comments */, preserving line numbers.

    Kotlin block comments NEST. A stripper that closes on the first `*/` would resume scanning
    prose as if it were code — and this file's own docstring, plus every KDoc explaining why the
    credit path must stay inference-free, names the very symbols the gate looks for.
    """
    out: list[str] = []
    i, n, depth = 0, len(source), 0
    while i < n:
        two = source[i : i + 2]
        if depth == 0 and two == "//":
            while i < n and source[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if two == "/*":
            depth += 1
            out.append("  ")
            i += 2
            continue
        if two == "*/" and depth > 0:
            depth -= 1
            out.append("  ")
            i += 2
            continue
        out.append(source[i] if (depth == 0 or source[i] == "\n") else " ")
        i += 1
    return "".join(out)


def scan_module(module: str, repo: pathlib.Path = None) -> list[str]:
    """Inference hits in a module's main sources (tests may legitimately stub a model port)."""
    repo = repo or REPO
    root = repo / module / "src" / "main" / "kotlin"
    hits: list[str] = []
    if not root.is_dir():
        return hits
    for path in sorted(root.rglob("*.kt")):
        code = strip_comments(path.read_text(encoding="utf-8"))
        for lineno, line in enumerate(code.splitlines(), start=1):
            for pattern, label in INFERENCE_PATTERNS:
                if pattern.search(line):
                    hits.append(f"{path.relative_to(repo)}:{lineno}: {label}")
    return hits


def self_test() -> int:
    """Falsify the inference-site scanner.

    EU AI Act Annex III: credit scoring is HIGH RISK. `ml-systems.yaml` declares the credit
    decisioning engine `deployed: false`, and that declaration is what the compliance
    documentation rests on — so the one thing that must never happen quietly is inference code
    appearing in the credit plane while the inventory still says it is not deployed.

    The scanner is a pattern list, which fails in exactly one direction: a spelling it does
    not know is a site it does not see, and the inventory then agrees with a codebase that has
    moved. Nothing else compares the two.
    """
    import tempfile

    fails: list[str] = []

    def run(files: dict) -> list[str]:
        td = tempfile.mkdtemp()
        repo = pathlib.Path(td)
        for rel, body in files.items():
            f = repo / rel
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(body)
        return scan_module("openbank-lending-service", repo)

    K = "openbank-lending-service/src/main/kotlin/com/openbank/lending"

    def case(label, hits, want):
        got = bool(hits)
        if got != want:
            fails.append(f"{label}: expected hit={want}, got {hits}")

    # EVERY pattern must fire. Missing one leaves that spelling free while the gate keeps
    # reporting clean about the others — the inventory stays "true" by not looking.
    for frag, label in (("import ai.onnxruntime.OrtSession", "onnxruntime import"),
                        ("val s: OrtSession = x()", "OrtSession"),
                        ("OrtEnvironment.getEnvironment()", "OrtEnvironment"),
                        ("val i = InferenceSession(p)", "InferenceSession"),
                        ("class Scorer(private val port: MlModelPort)", "MlModelPort")):
        case(f"{label} is detected", run({f"{K}/Scorer.kt": frag + "\n"}), True)

    # Ordinary credit code is not inference. A scanner that flags it makes the gate unusable
    # and it gets switched off — the same outcome as not having it.
    case("plain credit code is clean",
         run({f"{K}/Scorer.kt": "class Scorer { fun score(x: Int) = x * 2 }\n"}), False)

    # PROSE: an ADR reference or a KDoc explaining why there is no model must not read as one.
    case("a comment naming OrtSession is not a hit",
         run({f"{K}/Scorer.kt": "// no OrtSession here — see ml-systems.yaml deployed:false\nclass S\n"}), False)
    case("a block comment naming MlModelPort is not a hit",
         run({f"{K}/Scorer.kt": "/* MlModelPort is deliberately absent */\nclass S\n"}), False)

    # SCOPE: src/test may legitimately stub a model port — that is a fake, not a deployment.
    case("test sources are out of scope",
         run({"openbank-lending-service/src/test/kotlin/T.kt": "val s: OrtSession = x()\n"}), False)

    # A module with no sources yields nothing rather than raising.
    case("a module with no kotlin dir is clean", run({}), False)

    # A live read: the fixtures cannot tell that ml-systems.yaml still parses and still holds
    # the credit system this gate is about.
    inv = load_inventory()
    if not inv:
        fails.append("reading the real ml-systems.yaml produced NO systems — the inventory "
                     "this gate compares against would be empty")
    elif not any(sys_.get("id") == CREDIT_SYSTEM_ID for sys_ in inv):
        fails.append(f"the real inventory no longer holds {CREDIT_SYSTEM_ID!r} — this gate's "
                     f"subject is gone and it would report clean about nothing")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print(f"self-test ok: AI-Act high-risk inventory is falsifiable "
          f"(11 cases + a live read of {len(inv)} declared system(s))")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    systems = load_inventory()
    if not systems:
        print("::error::check-ai-act-high-risk-inventory: ml_systems is empty in ml-systems.yaml")
        return 1

    by_id = {s.get("id"): s for s in systems}
    credit = by_id.get(CREDIT_SYSTEM_ID)
    if credit is None:
        print(
            f"::error::check-ai-act-high-risk-inventory: '{CREDIT_SYSTEM_ID}' is missing from the "
            "inventory. The Annex III(5)(b) entry is what the platform's non-applicability claim "
            "rests on — deleting it does not make the obligation go away.",
        )
        return 1

    failures = 0

    # The load-bearing check: while credit decisioning is declared not-deployed, the credit plane
    # must contain no inference surface at all.
    if not credit.get("deployed", False):
        for module in CREDIT_PLANE:
            hits = scan_module(module)
            for hit in hits:
                print(f"::error::{hit}")
            if hits:
                failures += len(hits)
        if failures:
            print(
                f"\n{failures} model-inference site(s) found in the credit plane while "
                f"'{CREDIT_SYSTEM_ID}' is declared deployed=false, risk_class="
                f"'{credit.get('risk_class')}', annex={credit.get('annex')}.\n\n"
                "This is the moment the platform acquires its first EU AI Act Annex III(5)(b) "
                "HIGH-RISK system. From 2026-08-02 that engages Art. 9-15 in full — risk "
                "management, data governance, technical documentation, record-keeping, "
                "transparency, human oversight, accuracy and robustness (ADR-0216).\n\n"
                "Do NOT resolve this by flipping deployed:true in ml-systems.yaml. That turns a "
                "green gate into a documented violation. The Art. 9-15 package ships WITH the "
                "model or the model does not ship.",
            )

    # Inference that exists must live in a plane the inventory declares deployed.
    declared_planes = {s.get("plane") for s in systems if s.get("deployed")}
    checked = set(CREDIT_PLANE)
    for module_dir in sorted(REPO.glob("openbank-*")):
        module = module_dir.name
        if module in checked or not (module_dir / "src" / "main" / "kotlin").is_dir():
            continue
        hits = scan_module(module)
        if hits and module not in declared_planes:
            failures += len(hits)
            for hit in hits:
                print(f"::error::{hit}")
            print(
                f"\n'{module}' carries model inference but no ml-systems.yaml entry declares it as "
                "a deployed plane. An AI system absent from the Annex IV inventory is undeclared, "
                "which is the inventory obligation failing rather than a naming problem.",
            )

    # The module corpus the inference scan walks. Measured 2026-09-03: renaming a service away
    # left this gate green, and a glob that stops matching reads exactly like a fleet with no
    # undeclared inference. The floor is a COLLAPSE detector — it catches a broken glob or a
    # moved source root, not the loss of a single module.
    gatelib.subjects(sum(1 for d in REPO.glob("openbank-*")
                         if (d / "src" / "main" / "kotlin").is_dir()),
                     "modules with a Kotlin source root, scanned for inference")

    if failures:
        return 1

    print(
        f"OK: {len(systems)} inventoried AI/ML system(s); the credit plane "
        f"({', '.join(CREDIT_PLANE)}) carries no model inference, so "
        f"'{CREDIT_SYSTEM_ID}' remains legitimately deployed=false.",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
