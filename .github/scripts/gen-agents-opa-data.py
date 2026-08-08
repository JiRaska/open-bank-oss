#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Derive the OPA policy-input subset of `agents.yaml` — the only part any bundle needs.

WHAT THIS FIXES

`gen-rules-opa-data.py` (#3357) did this for `rules.yaml`; the identical problem was left
standing next door for `agents.yaml`, and it is now the larger half. Every one of the 42
service OPA bundles embeds `openbank-libs/governance/agents.yaml` VERBATIM (50 KB) and
hashes it into that service's `openbank.tech/policy-checksum` pod-roll annotation. So a
charter's prose `description`, its `model` binding, its `limits`, its `schedule` — none of
which any `.rego` can read — restamp 42 ConfigMaps and roll every policy-bearing pod in
the fleet. Measured on `origin/main`:

    agents.yaml embedded per bundle          50 390 B  x 42 = 2 116 380 B
    the part a .rego can actually reach       ~4 700 B  x 42 =  ~197 000 B

That is ~90% of the embedded charter document, and ~38% of the entire 5.02 MB bundle
estate, carried and hashed for data no policy consults.

WHAT IT EMITS

`openbank-libs/governance/agents-opa-data.yaml`: the paths of `agents.yaml` that a `.rego`
in this repo actually references under `data.agents.`, and nothing else.

THE PATH SET IS DERIVED, NEVER LISTED — same argument as gen-rules-opa-data.py. A
hand-kept list of "what the policy reads" goes stale, the subset silently omits a path the
policy started reading, and `data.agents.<path>` then evaluates to undefined — which in
Rego is not an error, it is a rule that quietly stops firing.

TWO GRANULARITIES, AND WHY THE SECOND ONE EXISTS

`rules.yaml`'s waste was at top-level-key granularity. `agents.yaml`'s is one level down:
`data.agents.agents` IS read, and it is 22 KB of the 50 KB — but `agents.rego` never reads
an agent object wholesale, it reads exactly `a.id`, `a.skills`, `a.tools.allow` and
`a.tools.deny` off each element. Subsetting only top-level keys would strip ~7% and miss
the point. So this generator additionally projects each element of a referenced
list-of-mappings down to the field chains that iteration variables bound to that list
actually dereference.

FAILURE DEGRADES TOWARDS KEEPING MORE DATA, NEVER LESS

The element analysis is a small static pass over Rego text, and a static pass over a real
language is never complete. It is therefore written so that anything it does not
understand — a variable bound to a charter list and then used bare, a dynamic index, an
alias it cannot follow — makes it KEEP THAT LIST WHOLE and say so, rather than emit a
projection it cannot justify. The unsound direction (dropping a field a policy reads) is
not reachable by "the parser did not recognise this"; it is only reachable by a bug, which
is what the differential proof below exists to catch.

THE DIFFERENTIAL PROOF

Static reasoning is not the safety argument. Before writing, the generator builds two OPA
bundles that differ ONLY in whether `agents/data.yaml` is the full charter document or the
derived subset, and evaluates both over an input matrix derived from the charters
themselves (every agent id x every tool pattern any charter or tier mentions x every
chartered skill, plus negatives, plus the REST-bridge action space). Both the MCP decision
(`openbank/agents/decision`) and the REST PEP decision (`openbank/rest/allow`) must be
IDENTICAL for every case. A single divergence aborts the write.

That is the analogue of gen-rules-opa-data.py's per-key deep-equality assertion, at the
only granularity that means anything here: the decisions the cluster actually makes.

Usage:  gen-agents-opa-data.py [--check] [--self-test] [--no-opa]
        (no flags)  write the derived file (differential proof required)
        --check     exit 1 if the committed file differs from a fresh derivation
        --no-opa    skip the differential proof (for environments without the opa binary;
                    never use this in CI — the opa-policy workflow installs opa itself)
        --self-test prove the check, the projection and the differential proof can fail
"""

from __future__ import annotations

import argparse
import copy
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
AGENTS = REPO / "openbank-libs" / "governance" / "agents.yaml"
OUT = REPO / "openbank-libs" / "governance" / "agents-opa-data.yaml"
RULES_DATA = REPO / "openbank-libs" / "governance" / "rules-opa-data.yaml"
POLICY_DIRS = (
    REPO / "openbank-infra" / "opa" / "policies",
    REPO / "openbank-libs" / "governance" / "policies",
)

# `data.agents.<a>.<b>...` — the only shape the policies use.
REF = re.compile(r"\bdata\.agents((?:\.[A-Za-z_][A-Za-z_0-9]*)+)")
# A dynamic index cannot be resolved statically and would make subsetting unsound.
DYNAMIC_REF = re.compile(r"\bdata\.agents(?:\.[A-Za-z_][A-Za-z_0-9]*)*\s*\[")

# `some V in data.agents.<path>` — binds V to an element of that collection.
SOME_IN_DATA = re.compile(
    r"\bsome\s+([A-Za-z_][A-Za-z_0-9]*)\s+in\s+data\.agents((?:\.[A-Za-z_][A-Za-z_0-9]*)+)"
)
# `NAME contains V if` — NAME becomes an alias collection over whatever V is.
CONTAINS_RULE = re.compile(
    r"^\s*([A-Za-z_][A-Za-z_0-9]*)\s+contains\s+([A-Za-z_][A-Za-z_0-9]*)\s+if\b", re.M
)
# `some V in NAME` — iterate an alias collection.
SOME_IN_NAME = re.compile(
    r"\bsome\s+([A-Za-z_][A-Za-z_0-9]*)\s+in\s+([A-Za-z_][A-Za-z_0-9]*)\b"
)

HEADER = """# GENERATED by .github/scripts/gen-agents-opa-data.py — do not hand-edit.
#
# The subset of openbank-libs/governance/agents.yaml that OPA actually reads: the paths
# some .rego in this repo references under data.agents.*, with each charter projected to
# the fields an iteration variable actually dereferences. Mounted into every service
# bundle at /bundle/agents/data.yaml, so it loads as data.agents.*.
#
# Edit agents.yaml, then re-run the generator and re-run every gen-*opa-bundle*.sh. The
# generator proves — by evaluating both documents through the real policy — that every
# MCP and REST decision is unchanged before it writes.
#
# Derived paths (read out of the .rego sources, never listed by hand):
"""


# --------------------------------------------------------------------------------------
# Static derivation
# --------------------------------------------------------------------------------------
def strip_rego_comments(text: str) -> str:
    """Drop `#` comments. A comment that mentions `data.agents.x` is prose about the
    policy, not the policy — counting it would let a stale doc-comment pin a path into the
    emitted data forever (the code-about-code collision, #2450)."""
    out = []
    for line in text.splitlines():
        in_str = False
        cut = len(line)
        i = 0
        while i < len(line):
            c = line[i]
            if c == "\\" and in_str:
                i += 2
                continue
            if c == '"':
                in_str = not in_str
            elif c == "#" and not in_str:
                cut = i
                break
            i += 1
        out.append(line[:cut])
    return "\n".join(out)


def policy_sources(repo: pathlib.Path) -> list[pathlib.Path]:
    """Every .rego in the tree except the generated bundle copies.

    Test policies are included on purpose: over-including a path costs a few lines of
    generated YAML, under-including one silently disables a rule.
    """
    out = []
    for rego in sorted(repo.rglob("*.rego")):
        parts = rego.parts
        if ".git" in parts or "dist" in parts:
            continue
        out.append(rego)
    return out


def split_chain(dotted: str) -> tuple[str, ...]:
    return tuple(p for p in dotted.split(".") if p)


def analyse(repo: pathlib.Path) -> tuple[set[tuple[str, ...]], dict[tuple[str, ...], set[tuple[str, ...]]], list[str]]:
    """Return (referenced paths, element-field chains per collection path, notes).

    `element_fields[P]` is the set of field chains dereferenced off a variable bound to an
    element of the collection at path P. An empty set means "keep the collection whole".
    """
    paths: set[tuple[str, ...]] = set()
    element_fields: dict[tuple[str, ...], set[tuple[str, ...]]] = {}
    opaque: set[tuple[str, ...]] = set()
    notes: list[str] = []

    for rego in policy_sources(repo):
        body = strip_rego_comments(rego.read_text(encoding="utf-8"))
        if DYNAMIC_REF.search(body):
            sys.exit(
                f"::error file={rego.relative_to(repo)}::dynamic `data.agents[...]` index — "
                "the emitted subset cannot be derived statically. Use data.agents.<path>."
            )
        for chain in REF.findall(body):
            paths.add(split_chain(chain))

        # --- element variables, by fixpoint over `some V in <collection>` + alias rules
        var_path: dict[str, tuple[str, ...]] = {}
        for var, chain in SOME_IN_DATA.findall(body):
            var_path[var] = split_chain(chain)

        alias_path: dict[str, tuple[str, ...]] = {}
        for _ in range(8):  # tiny fixpoint; the real corpus needs one round
            changed = False
            for name, var in CONTAINS_RULE.findall(body):
                if var in var_path and alias_path.get(name) != var_path[var]:
                    alias_path[name] = var_path[var]
                    changed = True
            for var, src in SOME_IN_NAME.findall(body):
                if src in alias_path and var_path.get(var) != alias_path[src]:
                    var_path[var] = alias_path[src]
                    changed = True
            if not changed:
                break

        # --- what each element variable dereferences, and whether anything is opaque
        for var, path in var_path.items():
            fields = element_fields.setdefault(path, set())
            for m in re.finditer(
                r"\b" + re.escape(var) + r"\b((?:\.[A-Za-z_][A-Za-z_0-9]*)*)", body
            ):
                chain = m.group(1)
                after = body[m.end() : m.end() + 1]
                if chain:
                    fields.add(split_chain(chain))
                    continue
                if after == "[":
                    opaque.add(path)
                    notes.append(
                        f"{rego.relative_to(repo)}: `{var}` is indexed dynamically — "
                        f"keeping data.agents.{'.'.join(path)} whole"
                    )
                    continue
                # A bare mention. The binding site (`some V in ...`) and an alias
                # (`NAME contains V if`) are the two we understand; anything else means
                # the whole element escapes into a value we cannot project.
                start = body.rfind("\n", 0, m.start()) + 1
                prefix = body[start : m.start()]
                if re.search(r"\bsome\s+$", prefix) or re.search(r"\bcontains\s+$", prefix):
                    continue
                if re.search(r"\bsome\s+[A-Za-z_][A-Za-z_0-9]*\s+in\s+$", prefix):
                    continue
                opaque.add(path)
                notes.append(
                    f"{rego.relative_to(repo)}: `{var}` is used as a whole value — "
                    f"keeping data.agents.{'.'.join(path)} whole"
                )

    for path in opaque:
        element_fields[path] = set()
    return paths, element_fields, notes


# --------------------------------------------------------------------------------------
# Projection
# --------------------------------------------------------------------------------------
def prune(value, keep: set[tuple[str, ...]], prefix: tuple[str, ...]):
    """Keep only the sub-paths of `value` that lie on a kept path."""
    if not isinstance(value, dict):
        return copy.deepcopy(value)
    out = {}
    for k, v in value.items():
        here = prefix + (k,)
        if any(p == here for p in keep):  # exact leaf — keep the whole subtree
            out[k] = copy.deepcopy(v)
        elif any(p[: len(here)] == here for p in keep):  # on the way to a leaf
            out[k] = prune(v, keep, here)
    return out


def project(
    source: dict,
    paths: set[tuple[str, ...]],
    element_fields: dict[tuple[str, ...], set[tuple[str, ...]]],
) -> dict:
    doc = prune(source, paths, ())
    for path, fields in element_fields.items():
        if not fields:
            continue
        node = doc
        for p in path[:-1]:
            node = node.get(p, {}) if isinstance(node, dict) else {}
        if not isinstance(node, dict) or path[-1] not in node:
            continue
        coll = node[path[-1]]
        if not isinstance(coll, list) or not all(isinstance(e, dict) for e in coll):
            continue  # not a list of mappings — nothing to project
        node[path[-1]] = [prune(e, fields, ()) for e in coll]
    return doc


def render(doc: dict, paths: set[tuple[str, ...]], element_fields) -> str:
    lines = []
    for p in sorted(paths):
        fields = element_fields.get(p) or set()
        suffix = (
            "  (each element projected to: "
            + ", ".join(".".join(f) for f in sorted(fields))
            + ")"
            if fields
            else ""
        )
        lines.append(f"#   data.agents.{'.'.join(p)}{suffix}")
    return HEADER + "\n".join(lines) + "\n\n" + yaml.safe_dump(doc, sort_keys=False)


def verify_projection(source: dict, derived: dict, paths, element_fields) -> None:
    """Every emitted value must equal the source value restricted to the derived paths.
    A re-serialised document cannot promise byte-identity the way gen-rules-opa-data.py's
    verbatim extraction does, so it must promise VALUE identity instead."""
    expected = project(source, paths, element_fields)
    if derived != expected:
        sys.exit("::error::derived document is not the source restricted to the read paths")


# --------------------------------------------------------------------------------------
# Differential proof: identical decisions under the full document and the subset
# --------------------------------------------------------------------------------------
def _stage(tmp: pathlib.Path, agents_doc: dict) -> pathlib.Path:
    stage = tmp / f"stage{len(list(tmp.iterdir()))}"
    (stage / "agents").mkdir(parents=True)
    (stage / "rules").mkdir(parents=True)
    (stage / "agents" / "data.yaml").write_text(
        yaml.safe_dump(agents_doc, sort_keys=False), encoding="utf-8"
    )
    (stage / "rules" / "data.yaml").write_text(
        RULES_DATA.read_text(encoding="utf-8"), encoding="utf-8"
    )
    for d in POLICY_DIRS:
        for f in sorted(d.glob("*.rego")):
            if f.name.endswith("_test.rego"):
                continue
            (stage / f.name).write_text(f.read_text(encoding="utf-8"), encoding="utf-8")
    return stage


def _matrix(source: dict) -> tuple[list[dict], list[dict]]:
    """Inputs derived from the charters themselves — every id, every tool pattern any
    charter or tier mentions, every chartered skill, plus negatives."""
    agent_ids = [a.get("id") for a in source.get("agents", []) if a.get("id")]
    callers = [i for i in agent_ids] + [f"agent:{i}" for i in agent_ids] + ["ghost", ""]

    tools: set[str] = {"run.skill", "gh.pr.merge", "no.such.tool"}
    for tier in (source.get("tool_tiers") or {}).values():
        if isinstance(tier, list):
            tools.update(t for t in tier if isinstance(t, str))
    for a in source.get("agents", []):
        for side in ("allow", "deny"):
            for p in (a.get("tools") or {}).get(side, []) or []:
                if isinstance(p, str):
                    tools.add(p)
                    tools.add(p.replace("*", "probe"))
    # The REST bridge action space: agents.rego maps `query.<x>.readonly` onto real
    # @Authorize action strings, so the matrix must contain that string space too.
    for scope in ("ledger", "account", "transaction", "balance", "gl", "catalog", "aml",
                  "amlCase", "sanctions", "fx", "clearing", "dispute", "complaint",
                  "interest", "party"):
        for verb in ("list", "read", "search", "post", "update"):
            tools.add(f"{scope}.{verb}")

    skills: set[str] = {"not-a-chartered-skill"}
    for a in source.get("agents", []):
        skills.update(s for s in (a.get("skills") or []) if isinstance(s, str))

    mcp = [
        {"agent": c, "tool": t, "resource": "r-1"}
        for c in callers
        for t in sorted(tools)
    ]
    mcp += [
        {"agent": c, "tool": "run.skill", "resource": None, "attributes": {"skill": s}}
        for c in callers
        for s in sorted(skills)
    ]
    rest = [
        {
            "principal": {"id": c if c.startswith("agent:") else f"agent:{c}",
                          "type": "AI_AGENT", "roles": []},
            "action": t,
            "resource": "r-1",
            "attributes": {"skill": next(iter(sorted(skills)))},
        }
        for c in callers
        for t in sorted(tools)
    ]
    return mcp, rest


def _exec(stage: pathlib.Path, decision: str, inputs: list[dict], tmp: pathlib.Path) -> str:
    d = tmp / f"in-{stage.name}-{decision.replace('/', '_')}"
    d.mkdir()
    for i, case in enumerate(inputs):
        (d / f"{i:05d}.json").write_text(json.dumps(case), encoding="utf-8")
    proc = subprocess.run(
        ["opa", "exec", "--decision", decision, "--bundle", str(stage), str(d)],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        sys.exit(f"::error::opa exec failed for {decision}: {proc.stderr.strip()}")
    # Normalise away the per-stage input path so only the decisions are compared.
    out = json.loads(proc.stdout)
    return json.dumps(
        [r.get("result") for r in sorted(out.get("result", []), key=lambda r: r["path"])],
        sort_keys=True,
    )


def differential_proof(source: dict, derived: dict) -> int:
    """Both documents must produce byte-identical decisions across the whole matrix."""
    if shutil.which("opa") is None:
        sys.exit(
            "::error::the differential proof needs the `opa` binary on PATH. Install it "
            "(the opa-policy workflow does) or pass --no-opa to skip — never in CI."
        )
    mcp, rest = _matrix(source)
    with tempfile.TemporaryDirectory() as d:
        tmp = pathlib.Path(d)
        full, sub = _stage(tmp, source), _stage(tmp, derived)
        for decision, cases in (
            ("openbank/agents/decision", mcp),
            ("openbank/rest/allow", rest),
        ):
            a = _exec(full, decision, cases, tmp)
            b = _exec(sub, decision, cases, tmp)
            if a != b:
                print(
                    f"::error::the derived subset changes `{decision}` — the projection "
                    "dropped a field the policy reads. NOT writing the file.",
                    file=sys.stderr,
                )
                return 1
    print(
        f"differential proof: {len(mcp)} MCP + {len(rest)} REST decisions identical "
        "under the full charter document and the derived subset"
    )
    return 0


# --------------------------------------------------------------------------------------
def build(no_opa: bool) -> tuple[str, list[str], int]:
    source = yaml.safe_load(AGENTS.read_text(encoding="utf-8")) or {}
    paths, element_fields, notes = analyse(REPO)
    if not paths:
        sys.exit(
            "::error::no `data.agents.<path>` reference found in any .rego — the derivation "
            "is broken, not the repo. Refusing to emit an empty subset."
        )
    doc = project(source, paths, element_fields)
    text = render(doc, paths, element_fields)
    verify_projection(source, yaml.safe_load(text) or {}, paths, element_fields)
    rc = 0 if no_opa else differential_proof(source, doc)
    return text, notes, rc


def self_test() -> int:
    ok = True

    sample_rego = (
        "package t\nimport rego.v1\n"
        "charter contains a if {\n\tsome a in data.agents.agents\n"
        "\ta.id == input.agent\n}\n"
        "d if input.tool in data.agents.tool_tiers.deny\n"
        "p := {x | some c in charter; some x in c.tools.allow}\n"
        "# prose mentioning data.agents.ghost must not pin ghost\n"
    )
    with tempfile.TemporaryDirectory() as d:
        repo = pathlib.Path(d)
        (repo / "p").mkdir()
        (repo / "p" / "t.rego").write_text(sample_rego, encoding="utf-8")
        paths, fields, _ = analyse(repo)

    if paths != {("agents",), ("tool_tiers", "deny")}:
        print(f"FAIL: derived paths wrong: {sorted(paths)}")
        ok = False
    if fields.get(("agents",)) != {("id",), ("tools", "allow")}:
        print(f"FAIL: element fields wrong: {fields.get(('agents',))}")
        ok = False

    src = {
        "schema_version": 1,
        "tool_tiers": {"deny": ["x"], "read": ["y"]},
        "agents": [{"id": "a", "tools": {"allow": ["t"], "deny": ["u"]}, "charter": "prose"}],
    }
    got = project(src, paths, fields)
    want = {"tool_tiers": {"deny": ["x"]}, "agents": [{"id": "a", "tools": {"allow": ["t"]}}]}
    if got != want:
        print(f"FAIL: projection wrong: {got}")
        ok = False
    if "schema_version" in got or "charter" in json.dumps(got):
        print("FAIL: an unreferenced key leaked into the subset")
        ok = False

    # A doc-comment reference must not pin a path.
    if REF.findall(strip_rego_comments("# reads data.agents.ghost\nx := 1\n")):
        print("FAIL: a doc-comment reference was counted as a policy reference")
        ok = False
    if not REF.findall(strip_rego_comments("y := data.agents.real\n")):
        print("FAIL: a real reference was stripped as a comment")
        ok = False

    # Degradation direction: an element variable used as a whole value must KEEP the
    # collection whole, never emit a projection the analysis cannot justify.
    with tempfile.TemporaryDirectory() as d:
        repo = pathlib.Path(d)
        (repo / "p").mkdir()
        (repo / "p" / "t.rego").write_text(
            "package t\nimport rego.v1\n"
            "all_charters := {a | some a in data.agents.agents}\n",
            encoding="utf-8",
        )
        _, fields2, notes2 = analyse(repo)
    if fields2.get(("agents",)) != set() or not notes2:
        print(f"FAIL: an opaque use did not fall back to keeping the collection whole: {fields2}")
        ok = False

    # --check must be able to fail, and the differential proof must be able to fail.
    text_a = render({"agents": []}, {("agents",)}, {})
    if text_a == render({"agents": [{"id": "x"}]}, {("agents",)}, {}):
        print("FAIL: staleness comparison cannot distinguish differing documents")
        ok = False

    if shutil.which("opa") is None:
        print("self-test: SKIPPED the differential-proof falsification (no `opa` on PATH)")
    else:
        source = yaml.safe_load(AGENTS.read_text(encoding="utf-8")) or {}
        paths, element_fields, _ = analyse(REPO)
        broken_fields = {
            p: {f for f in fs if f != ("skills",) and f != ("tools", "allow")}
            for p, fs in element_fields.items()
        }
        broken = project(source, paths, broken_fields)
        if differential_proof(source, broken) == 0:
            print("FAIL: dropping `skills` and `tools.allow` did not fail the differential proof")
            ok = False
        else:
            print("  (differential proof correctly rejected a projection missing read fields)")

    print("self-test: PASS" if ok else "self-test: FAIL")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--no-opa", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    text, notes, rc = build(args.no_opa)
    for n in notes:
        print(f"::notice::{n}", file=sys.stderr)
    if rc:
        return rc

    if args.check:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != text:
            print(
                "::error::openbank-libs/governance/agents-opa-data.yaml is stale — run "
                ".github/scripts/gen-agents-opa-data.py and commit the result, then "
                "re-run the OPA bundle generators.",
                file=sys.stderr,
            )
            return 1
        print(f"agents-opa-data.yaml is in sync ({len(text)} bytes)")
        return 0

    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT.relative_to(REPO)} ({len(text)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
