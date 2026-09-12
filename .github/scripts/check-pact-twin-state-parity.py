#!/usr/bin/env python3
"""Hold the two provider-verification twins of a service to the SAME @State handler set.

Every provider here that publishes verification results has two classes: a `@PactFolder` twin
that runs on every PR, and a `@PactBroker` twin that runs on main-push and is the only one whose
results reach the broker. Their KDocs assert the pair are duplicates — *"a deliberate duplicate of
the broker twin's body: same `@State` handlers"* — and until #9752 nothing checked it.

What the drift costs, measured 2026-09-12: three broker twins were missing one handler each, so
every main push published `success=false` for those interactions (`MissingStateChangeMethod`), and
`can-i-deploy` — which reads the broker and nothing else — blocked vop, interest, sdd and swift as
contract REGRESSIONs, the class no reconcile tick can clear. Four money-path deploys, stuck five
days, with every PR green: the folder twin is the one a PR runs, and the broker twin's failure lands
in the broker rather than in a red check.

The asymmetry is not symmetric in consequence, so the check is not either:

  * a state the FOLDER twin has and the broker twin lacks is fatal — that is the #9752 shape, a
    published failure and a blocked deploy;
  * a state the BROKER twin has and the folder twin lacks is reported as a WARNING — it means a
    contract verified only after the merge, which is a weaker PR gate but breaks no deploy.

Scope is DERIVED from the annotations, never a hand-kept list: a provider added later is covered
without editing this file, and a class that stops being a twin drops out on its own.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]

# `@State("literal")` and `@State(CONSTANT)`; pact-jvm also accepts several values in one annotation.
STATE_RE = re.compile(r'@State\(\s*([^)]*?)\s*\)', re.DOTALL)
STRING_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')
IDENT_RE = re.compile(r'^[A-Za-z_][A-Za-z0-9_.]*$')
CONST_RE = re.compile(r'\b(?:const\s+val|val)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*String\s*)?=\s*"((?:[^"\\]|\\.)*)"')


def strip_comments(text: str) -> str:
    """Drop // and /* */ comments, preserving string literals.

    Load-bearing, not tidiness: these twins DISCUSS each other's annotations in their KDoc — the
    folder twin's header names `@PactBroker` to explain why the twin exists — so classifying off
    raw text put both loaders in one file and silently dropped account-service and party-service
    from the corpus. That is, the two providers carrying the #9752 defect were exactly the ones the
    first version of this probe could not see, while it printed PASS. Kotlin block comments NEST.
    """
    out: list[str] = []
    i, n, depth = 0, len(text), 0
    while i < n:
        if depth:
            if text.startswith("/*", i):
                depth += 1
                i += 2
            elif text.startswith("*/", i):
                depth -= 1
                i += 2
            else:
                if text[i] == "\n":
                    out.append("\n")
                i += 1
            continue
        ch = text[i]
        if ch == '"':
            # Raw strings first — a """ block can legally contain /* and //.
            if text.startswith('"""', i):
                end = text.find('"""', i + 3)
                end = n if end == -1 else end + 3
                out.append(text[i:end])
                i = end
                continue
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:min(j + 1, n)])
            i = j + 1
            continue
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end == -1 else end
            continue
        if text.startswith("/*", i):
            depth = 1
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def module_constants(module: pathlib.Path) -> dict[str, str]:
    """String constants declared anywhere in the module's test sources, by simple name.

    A `@State(NEGATIVE_AUTH_STATE)` is exactly as load-bearing as a literal, and resolving it is
    what lets the two twins share one spelling — the thing that makes them comparable at all.
    """
    out: dict[str, str] = {}
    for path in sorted((module / "src" / "test").rglob("*.kt")):
        source = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        for name, value in CONST_RE.findall(source):
            out.setdefault(name, value)
    return out


def states_in(path: pathlib.Path, constants: dict[str, str]) -> tuple[set[str], set[str]]:
    """(resolved state names, unresolved argument expressions) for one file."""
    text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
    resolved: set[str] = set()
    unresolved: set[str] = set()
    for args in STATE_RE.findall(text):
        literals = STRING_RE.findall(args)
        if literals:
            resolved.update(literals)
            continue
        for part in (p.strip() for p in args.split(",")):
            if not part:
                continue
            simple = part.rsplit(".", 1)[-1]
            if IDENT_RE.match(part) and simple in constants:
                resolved.add(constants[simple])
            else:
                unresolved.add(part)
    return resolved, unresolved


def classify(path: pathlib.Path) -> str | None:
    """'folder', 'broker', or None — read off the loader annotation, not the file name."""
    text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
    if "@Provider(" not in text:
        return None
    has_folder = "@PactFolder(" in text
    has_broker = "@PactBroker(" in text
    if has_folder and not has_broker:
        return "folder"
    if has_broker and not has_folder:
        return "broker"
    return None


def providers(text: str) -> set[str]:
    return set(re.findall(r'@Provider\(\s*"([^"]+)"', text))


def collect() -> dict[str, dict[str, dict]]:
    """provider name -> side -> {states, unresolved, files}."""
    found: dict[str, dict[str, dict]] = {}
    for module in sorted(REPO.glob("openbank-*")):
        test_root = module / "src" / "test"
        if not test_root.is_dir():
            continue
        constants: dict[str, str] | None = None
        for path in sorted(test_root.rglob("*.kt")):
            side = classify(path)
            if side is None:
                continue
            if constants is None:
                constants = module_constants(module)
            text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
            resolved, unresolved = states_in(path, constants)
            for provider in providers(text):
                entry = found.setdefault(provider, {}).setdefault(
                    side, {"states": set(), "unresolved": set(), "files": []}
                )
                entry["states"] |= resolved
                entry["unresolved"] |= unresolved
                entry["files"].append(str(path.relative_to(REPO)))
    return found


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    found = collect()
    pairs = {p: s for p, s in found.items() if "folder" in s and "broker" in s}
    if not pairs:
        # A probe that finds nothing must not read as a pass — this is the repo's own
        # "no hits is a fact about the probe" rule.
        print("ERROR: no @PactFolder/@PactBroker twin pair found at all — the probe is broken, "
              "not the fleet.", file=sys.stderr)
        return 1

    fatal = 0
    warned = 0
    print(f"==> Provider twin @State parity: {len(pairs)} provider(s) with both twins")
    for provider in sorted(pairs):
        sides = pairs[provider]
        folder, broker = sides["folder"], sides["broker"]
        missing_in_broker = sorted(folder["states"] - broker["states"])
        missing_in_folder = sorted(broker["states"] - folder["states"])
        unresolved = sorted(folder["unresolved"] | broker["unresolved"])
        if not missing_in_broker and not missing_in_folder and not unresolved:
            print(f"  OK        {provider}  ({len(folder['states'])} state(s), both twins)")
            continue
        print(f"  DRIFT     {provider}")
        for state in missing_in_broker:
            fatal += 1
            print(f"    FATAL   @State({state!r}) is served by the folder twin and NOT by the broker "
                  f"twin — every main push will publish success=false for the interaction that "
                  f"declares it, and can-i-deploy will block its consumers as a REGRESSION.",
                  file=sys.stderr)
        for state in missing_in_folder:
            warned += 1
            print(f"    WARN    @State({state!r}) is served by the broker twin only — the contract "
                  f"that declares it is verified after the merge, never on a PR.")
        for expr in unresolved:
            fatal += 1
            print(f"    FATAL   @State({expr}) could not be resolved to a string — parity cannot be "
                  f"decided, so this fails rather than passing quietly.", file=sys.stderr)
        for side in ("folder", "broker"):
            for f in sides[side]["files"]:
                print(f"            {side}: {f}")

    print(f"==> {len(pairs)} pair(s), {fatal} fatal, {warned} warning(s)")
    # The subject floor the gate estate declares: a scan that suddenly sees a handful of pairs has
    # broken (that is how the comment-stripping bug above hid two providers), so run-gates compares
    # this against min_subjects rather than trusting a green verdict over a shrunken corpus.
    print(f"SUBJECTS={len(pairs)}  # providers with both a folder and a broker twin")
    if fatal:
        print("PACT TWIN STATE PARITY: FAIL — a handler the PR-lane twin serves is missing from the "
              "twin whose results reach the broker (issue #9752).", file=sys.stderr)
        return 1
    print("PACT TWIN STATE PARITY: PASS — every folder-twin handler is also served by the broker twin.")
    return 0


def selftest() -> int:
    """Hold the checker to a known-positive AND a known-negative, per this repo's gate rule.

    The negative cases are the ones that matter: a checker that cannot see a removed handler is
    decoration, and that is precisely the failure #9752 was.
    """
    import tempfile

    fails: list[str] = []
    folder_src = '''
@Provider("openbank-demo-service")
@PactFolder("../pacts")
class DemoFolderTest {
    @State("a demo exists")
    fun a() {}
    @State(SHARED_STATE)
    fun b() {}
}
const val SHARED_STATE = "no valid M2M identity is presented"
'''
    broker_src_ok = '''
@Provider("openbank-demo-service")
@PactBroker(enablePendingPacts = "true")
class DemoBrokerTest {
    @State("a demo exists")
    fun a() {}
    @State(SHARED_STATE)
    fun b() {}
}
'''
    broker_src_drift = broker_src_ok.replace('    @State(SHARED_STATE)\n    fun b() {}\n', '')
    broker_src_extra = broker_src_ok + '''
@Provider("openbank-demo-service")
@PactBroker(enablePendingPacts = "true")
class DemoBrokerExtraTest {
    @State("only the broker knows this")
    fun c() {}
}
'''
    broker_src_unresolved = broker_src_ok.replace("@State(SHARED_STATE)", "@State(Someone.ELSEWHERE)")

    cases = [
        ("identical twins", broker_src_ok, 0),
        ("handler missing from the broker twin", broker_src_drift, 1),
        ("handler only the broker twin has (warn, not fail)", broker_src_extra, 0),
        ("state argument that cannot be resolved", broker_src_unresolved, 1),
    ]
    global REPO
    real_repo = REPO
    try:
        for label, broker_src, want in cases:
            with tempfile.TemporaryDirectory() as tmp:
                root = pathlib.Path(tmp)
                pkg = root / "openbank-demo-service" / "src" / "test" / "kotlin"
                pkg.mkdir(parents=True)
                (pkg / "DemoFolderTest.kt").write_text(folder_src)
                (pkg / "DemoBrokerTest.kt").write_text(broker_src)
                REPO = root
                rc = main_for_selftest()
                if rc != want:
                    fails.append(f"{label}: expected exit {want}, got {rc}")
                else:
                    print(f"  ok: {label} -> exit {rc}")

        # A tree with no twins at all must FAIL (a broken probe), never pass silently.
        with tempfile.TemporaryDirectory() as tmp:
            REPO = pathlib.Path(tmp)
            rc = main_for_selftest()
            if rc != 1:
                fails.append(f"no twin pair anywhere: expected exit 1 (broken probe), got {rc}")
            else:
                print("  ok: no twin pair anywhere -> exit 1 (probe, not fleet)")
    finally:
        REPO = real_repo

    if fails:
        for f in fails:
            print(f"SELFTEST FAIL: {f}", file=sys.stderr)
        return 1
    print("selftest OK — drift in the fatal direction, the warn direction, an unresolvable argument "
          "and an empty corpus are each driven end to end.")
    return 0


def main_for_selftest() -> int:
    """`main` without argparse, so the selftest can re-enter it against a temporary tree."""
    argv = sys.argv
    sys.argv = [argv[0]]
    try:
        return main()
    finally:
        sys.argv = argv


if __name__ == "__main__":
    sys.exit(main())
