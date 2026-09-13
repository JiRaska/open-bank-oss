#!/usr/bin/env python3
"""Release-registration consistency gate (CLAUDE.md rule #3, ADR-0029).

Enforces the invariant: a module is a released component **iff it has a
`version.txt`**, and every released component is registered in BOTH
`release-please-config.json` (`packages`) and `.release-please-manifest.json`.

This catches the drift where a service ships with a `version.txt` (added by the
feat PR that deployed it) but is never registered with release-please — so it
silently never gets a Release PR, changelog, or tag. Two services
(customer-edge, security-scanner) had drifted this way before this gate existed.

stdlib only — runs in PR CI with no extra deps, like the sibling governance
gates (check-threat-models.py, check-version-lifecycle.py).
"""
from __future__ import annotations

import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
CONFIG = REPO / "release-please-config.json"
MANIFEST = REPO / ".release-please-manifest.json"


def modules_with_version_txt(root: pathlib.Path = None) -> set[str]:
    """Every top-level `openbank-*` module that carries a `version.txt`.

    `version.txt` presence is the rule-#3 definition of a released component, so
    it is the source of truth this gate compares the release config against.
    """
    return {p.parent.name for p in (root or REPO).glob("openbank-*/version.txt")}


def registered_packages(root: pathlib.Path = None) -> set[str]:
    data = json.loads(((root / "release-please-config.json") if root else CONFIG).read_text(encoding="utf-8"))
    return set(data.get("packages", {}).keys())


def manifest_keys(root: pathlib.Path = None) -> set[str]:
    data = json.loads(((root / ".release-please-manifest.json") if root else MANIFEST).read_text(encoding="utf-8"))
    return set(data.keys())


def reading_is_empty(have_version: set[str], in_config: set[str], in_manifest: set[str]) -> bool:
    """True when any of the three readings came back empty.

    Three empty sets are internally CONSISTENT, so the comparison rightly reports no
    violations — which means a broken glob, a wrong CWD or a truncated JSON would print "OK".
    That is the shape where a gate over a list passes precisely because it found nothing, so
    the emptiness has to be its own verdict rather than an input to the comparison.

    Lives here, not inline in main(), so the self-test can reach it: the first version of this
    guard sat in main() and the deliberate break that removed it was NOT caught.
    """
    return not have_version or not in_config or not in_manifest


def violations_for(
    have_version: set[str],
    in_config: set[str],
    in_manifest: set[str],
    pending: set[str] | None = None,
) -> list[str]:
    """The comparison, separated from the I/O so a self-test can drive it directly.

    `pending` names modules whose `version.txt` is ADDED BY THIS PR and which are therefore
    allowed to be unregistered *yet*: the registry is derived from the version.txt set
    (`gen-release-registry.py`) and written on main by `derived-artefact-autoheal`, so a feature
    branch no longer has to edit the two shared JSON maps — which is what made five sibling PRs
    conflict on them and what silently dropped a registered component from two branches.

    The deferral is deliberately narrow, and only the FIRST two rules below honour it:

      * an orphan (registered with no version.txt) still fails, pending or not;
      * a HALF registration still fails — config and manifest must move together, so a pending
        module that appears in one of them is a broken write, not a deferral;
      * a module that already exists on the base and is unregistered still fails, which is the
        drift this gate was built for (customer-edge and security-scanner sat that way for weeks).

    What it costs, stated rather than glossed: between the merge and the autoheal PR landing, a new
    service is on main unregistered, so a release-please run in that window does not cut its first
    release. That is a one-cycle DELAY under a gate that still runs on main and is watched by
    `main-red-watch` — not the original defect, which was *silently never*.
    """
    pending = pending or set()
    violations: list[str] = []

    # A component with version.txt must be registered in both files.
    for missing in sorted(have_version - in_config - pending):
        violations.append(
            f"{missing} has version.txt but is NOT in release-please-config.json `packages` "
            f"— it will never get a Release PR/changelog/tag. Add: "
            f'"{missing}": {{ "component": "{missing.removeprefix("openbank-")}" }}'
        )
    for missing in sorted(have_version - in_manifest - pending):
        violations.append(
            f"{missing} has version.txt but is NOT in .release-please-manifest.json "
            f"— add it with its current version.txt value as the baseline."
        )

    # A registered component must actually have a version.txt (no phantom entries).
    for orphan in sorted(in_config - have_version):
        violations.append(
            f"{orphan} is in release-please-config.json `packages` but has no version.txt "
            f"— either add version.txt or remove the package entry."
        )
    for orphan in sorted(in_manifest - have_version):
        violations.append(
            f"{orphan} is in .release-please-manifest.json but has no version.txt "
            f"— either add version.txt or remove the manifest entry."
        )

    # config and manifest must cover exactly the same components.
    for diff in sorted(in_config ^ in_manifest):
        side = "config but not manifest" if diff in in_config else "manifest but not config"
        violations.append(f"{diff} is in release-please {side} — the two must stay in lockstep.")

    return violations


def self_test() -> int:
    """Falsify the comparison against known answers.

    This gate is what caught `.release-please-manifest.json` going from 56 entries to 55: two
    branches added neighbouring entries, git merged the text cleanly and kept ONE, and nothing
    else in CI could see it — no test and no review notices a line quietly not being there.
    Had the gate been wrong, a service with a `version.txt` on main would be invisible to
    release-please and would simply never get its first release.

    It shipped without a self-test, so its RED path was code nobody had run.
    """
    fails: list[str] = []

    def case(label: str, have: set, cfg: set, man: set, want_any: bool, want_sub: str = "",
             pending: set | None = None) -> None:
        v = violations_for(have, cfg, man, pending)
        if bool(v) != want_any:
            fails.append(f"{label}: expected violations={want_any}, got {len(v)}")
        elif want_sub and not any(want_sub in x for x in v):
            fails.append(f"{label}: violated for the wrong reason — no message contains {want_sub!r}: {v}")

    three = {"openbank-a", "openbank-b", "openbank-c"}

    # In lockstep: the only shape that may pass. Without this case a gate that flags
    # everything would look identical to a working one.
    case("all three in lockstep is clean", three, three, three, False)

    # THE MEASURED DEFECT: an entry silently dropped from the manifest by a clean merge.
    case("a component missing from the MANIFEST is caught",
         three, three, {"openbank-a", "openbank-b"}, True, "NOT in .release-please-manifest.json")

    # Its sibling: registered nowhere, so release-please never proposes a Release PR for it.
    case("a component missing from the CONFIG is caught",
         three, {"openbank-a", "openbank-b"}, three, True, "NOT in release-please-config.json")

    # The other direction — a registration with nothing behind it. Left alone it produces a
    # Release PR for an artifact that does not exist.
    case("a phantom config entry is caught",
         {"openbank-a"}, {"openbank-a", "openbank-ghost"}, {"openbank-a"}, True, "has no version.txt")
    case("a phantom manifest entry is caught",
         {"openbank-a"}, {"openbank-a"}, {"openbank-a", "openbank-ghost"}, True, "has no version.txt")

    # config and manifest must agree with EACH OTHER even where both disagree with the tree,
    # because release-please reads both and a mismatch is what strands a release.
    case("config/manifest asymmetry is caught",
         set(), {"openbank-x"}, set(), True, "lockstep")

    # A DIRECTORY-SHAPED absence: nothing anywhere. Three empty sets are trivially consistent,
    # so the COMPARISON is right to report clean — and that is exactly the reading a broken
    # glob produces. The emptiness is therefore its own verdict, asserted here directly.
    case("three empty sets are internally consistent", set(), set(), set(), False)

    # ── the `pending` deferral, held in BOTH directions ────────────────────────────────────────
    #
    # A branch adding a service no longer edits the two shared JSON maps; the registry is derived
    # from the version.txt set and written on main. The deferral must be exactly that wide and no
    # wider, so every case below except the first is a MUST-FAIL.
    case("a module whose version.txt this PR ADDS may be unregistered yet",
         three, {"openbank-a", "openbank-b"}, {"openbank-a", "openbank-b"}, False,
         pending={"openbank-c"})
    case("a module unregistered on the BASE still fails — the drift this gate exists for",
         three, {"openbank-a", "openbank-b"}, {"openbank-a", "openbank-b"}, True,
         "NOT in release-please-config.json", pending={"openbank-zzz"})
    case("a HALF-registered pending module still fails: config and manifest move together",
         three, three, {"openbank-a", "openbank-b"}, True, "lockstep", pending={"openbank-c"})
    case("a phantom entry is never deferred, pending or not",
         {"openbank-a"}, {"openbank-a", "openbank-ghost"}, {"openbank-a"}, True,
         "has no version.txt", pending={"openbank-ghost"})
    case("an empty pending set leaves the gate exactly as strict as before",
         three, {"openbank-a", "openbank-b"}, {"openbank-a", "openbank-b"}, True,
         "NOT in release-please-config.json", pending=set())
    for label, have, cfg, man, want in (
        ("no version.txt found at all", set(), three, three, True),
        ("config read as empty", three, set(), three, True),
        ("manifest read as empty", three, three, set(), True),
        ("a full reading is not empty", three, three, three, False),
    ):
        if reading_is_empty(have, cfg, man) != want:
            fails.append(f"{label}: reading_is_empty should be {want}")

    # And the real tree must be readable — a self-test that only ever drives fixtures cannot
    # tell that the globs and paths still resolve.
    try:
        have, cfg, man = modules_with_version_txt(), registered_packages(), manifest_keys()
        if not have or not cfg or not man:
            fails.append(f"reading the real repo produced an empty set "
                         f"(version.txt={len(have)}, config={len(cfg)}, manifest={len(man)}) "
                         f"— the paths or glob no longer resolve")
    except Exception as exc:  # noqa: BLE001 - any failure here is a real finding
        fails.append(f"reading the real repo raised: {exc}")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: release-registration is falsifiable "
          "(12 comparison + 4 empty-read cases + a live read)")
    return 0


def newly_added_modules(base: str) -> set[str]:
    """Modules whose `version.txt` does not exist on `base` — i.e. this PR introduces them.

    Read from git rather than from the filesystem: "new" is a statement about the BASE, and the
    working tree cannot answer it. A base that does not resolve returns the empty set, so the gate
    falls back to its strict behaviour — an unreadable base must never widen a control.
    """
    import subprocess

    res = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", base],
        capture_output=True, text=True, cwd=REPO,
    )
    if res.returncode != 0:
        print(f"::warning::release-registration: base ref {base!r} did not resolve — "
              f"treating every module as pre-existing (strict).")
        return set()
    on_base = {
        line.split("/", 1)[0]
        for line in res.stdout.splitlines()
        if line.endswith("/version.txt") and line.startswith("openbank-")
    }
    return modules_with_version_txt() - on_base


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    pending: set[str] = set()
    if "--pr-base" in sys.argv:
        pending = newly_added_modules(sys.argv[sys.argv.index("--pr-base") + 1])

    have_version = modules_with_version_txt()
    in_config = registered_packages()
    in_manifest = manifest_keys()
    violations = violations_for(have_version, in_config, in_manifest, pending)

    # Never report a pass about an empty reading. Three empty sets are internally consistent,
    # so a broken glob or a wrong CWD would print "OK" — the shape where a gate over a list
    # passes precisely because it found nothing.
    if reading_is_empty(have_version, in_config, in_manifest):
        print("::error::release-registration: read an EMPTY set "
              f"(version.txt={len(have_version)}, config={len(in_config)}, "
              f"manifest={len(in_manifest)}) — refusing to report a pass about nothing.")
        return 1

    print("Release-registration consistency gate (rule #3, ADR-0029)")
    print(f"  modules with version.txt: {len(have_version)}")
    print(f"  config packages:          {len(in_config)}")
    print(f"  manifest entries:         {len(in_manifest)}")
    if violations:
        print("  VIOLATIONS:")
        for v in violations:
            print(f"    - {v}")
        return 1
    if pending:
        print(f"  DEFERRED (version.txt added by this PR, registry is written on main by "
              f"derived-artefact-autoheal): {sorted(pending)}")
    print("  OK: every released component is registered in config + manifest, and vice versa.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
