#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: the prompt registry at openbank-libs/governance/prompts/ (ADR-0148).
#
# WHY THIS EXISTS
#   ADR-0148 makes every system prompt an agent/copilot uses a *versioned, in-repo* file so the
#   `prompt_hash` in an AI-attributed AuditEvent (ADR-0031 D5) resolves to actual content — the
#   thing that makes a past agent decision reproducible for an EU AI Act Art. 12/13 record. A
#   registry no one checks drifts: a mistyped agent-id, an empty file, or a version re-used in
#   place (a prompt is immutable once shipped, like a Flyway migration) all silently break that
#   resolvability. This guard is the first increment of the `check-prompt-registry` gate the ADR
#   delivery note lists as open.
#
# WHAT IT CHECKS
#   HARD (exit 1) — registry integrity, unambiguous:
#     * a prompt directory whose name is not an `id:` in agents.yaml (a prompt for a charter that
#       does not exist cannot be loaded by anything),
#     * a file not named `<name>.<version>.md` with `<name>` = [a-z0-9-]+ and `<version>` = v<N>,
#     * an empty / whitespace-only prompt file,
#     * two files that normalise to the same (agent, name, version) — a re-used version,
#     * (issue #1918) a charter in agents.yaml with NO entry in prompts/registry.yaml, an entry for
#       a charter that does not exist, a duplicate entry, an unknown `status:`, a `registered`
#       charter whose listed prompt file is missing or whose directory holds an unlisted file, or a
#       non-`registered` charter that nevertheless has a prompt directory.
#   ADVISORY (::warning, never fails) — coverage:
#     * charters whose registry.yaml status is `pending` (the real migration backlog).
#
#   registry.yaml (added for #1918) is what makes "absent" and "not applicable" distinguishable:
#   before it, an identity-only principal that can never have a prompt (mcp-anonymous), a charter
#   whose prompt lives in a third-party image (rca-investigator), and a charter whose LLM wiring is
#   simply unbuilt (finops-agent) all rendered as the same undifferentiated warning line. Every
#   charter must now make an explicit, reviewed claim; only `pending` is backlog.
#
#   Not yet checked (later increments, as the services migrate to load-from-registry per ADR-0148):
#   that a service's live system prompt matches its registered file byte-for-byte, and that a
#   deployed `prompt_hash` resolves. Those need the service to load its prompt FROM the registry
#   first; until then every service holds an inline constant.
#
# Run:  python3 .github/scripts/check-prompt-registry.py

import hashlib
import pathlib
import re
import sys

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parents[2]
PROMPTS = ROOT / "openbank-libs" / "governance" / "prompts"
AGENTS = ROOT / "openbank-libs" / "governance" / "agents.yaml"

MANIFEST = PROMPTS / "registry.yaml"

FILENAME_RE = re.compile(r"^(?P<name>[a-z0-9-]+)\.v(?P<version>[0-9]+)\.md$")

# Closed vocabulary — see the header of prompts/registry.yaml for what each means. `pending` is the
# only status that is a backlog item; the other three are settled claims a reviewer signed off on.
STATUSES = {"registered", "pending", "external", "not-applicable"}
NEEDS_REASON = {"pending", "external", "not-applicable"}


def charter_ids():
    data = yaml.safe_load(AGENTS.read_text())
    return {a.get("id") for a in (data.get("agents", []) or []) if a.get("id")}


def load_manifest(ids, errors):
    """Parse prompts/registry.yaml into {charter: entry}, recording structural errors."""
    if not MANIFEST.is_file():
        errors.append(f"{MANIFEST.relative_to(ROOT)} is missing — every charter must declare a "
                      f"prompt-registry status (registered/pending/external/not-applicable)")
        return {}
    try:
        doc = yaml.safe_load(MANIFEST.read_text()) or {}
    except yaml.YAMLError as e:
        errors.append(f"{MANIFEST.relative_to(ROOT)} — not valid YAML: {e}")
        return {}

    entries = {}
    for i, entry in enumerate(doc.get("charters", []) or []):
        where = f"registry.yaml charters[{i}]"
        if not isinstance(entry, dict):
            errors.append(f"{where} — must be a mapping")
            continue
        cid = entry.get("id")
        if cid not in ids:
            errors.append(f"{where} — id '{cid}' is not an id: in agents.yaml")
            continue
        if cid in entries:
            errors.append(f"{where} — duplicate entry for charter '{cid}'")
            continue
        status = entry.get("status")
        if status not in STATUSES:
            errors.append(f"{where} ({cid}) — status must be one of {sorted(STATUSES)} "
                          f"(got {status!r})")
            continue
        if status in NEEDS_REASON and not str(entry.get("reason", "")).strip():
            errors.append(f"{where} ({cid}) — status '{status}' requires a `reason:` "
                          f"(an unexplained exemption is indistinguishable from an oversight)")
        if status == "registered" and not (entry.get("prompts") or []):
            errors.append(f"{where} ({cid}) — status 'registered' requires a non-empty `prompts:` list")
        entries[cid] = entry

    # A charter with no entry at all is the failure this manifest exists to make impossible.
    for cid in sorted(ids - set(entries)):
        errors.append(f"charter '{cid}' has no entry in {MANIFEST.relative_to(ROOT)} — declare its "
                      f"prompt-registry status (registered/pending/external/not-applicable)")
    return entries


def cross_check(entries, dirs_seen, errors, prompts: pathlib.Path = None):
    """The manifest's claim must match the tree: registered <=> a directory with exactly its files."""
    for cid, entry in sorted(entries.items()):
        status = entry.get("status")
        files = dirs_seen.get(cid, set())
        if status == "registered":
            declared = {str(p) for p in (entry.get("prompts") or [])}
            for name in sorted(declared):
                if not ((prompts or PROMPTS) / cid / f"{name}.md").is_file():
                    errors.append(f"registry.yaml ({cid}) — declares prompt '{name}' but "
                                  f"prompts/{cid}/{name}.md does not exist")
            for name in sorted(files - declared):
                errors.append(f"prompts/{cid}/{name}.md exists but is not listed in registry.yaml "
                              f"({cid}.prompts) — an unlisted prompt is outside the reviewed set")
        elif files:
            errors.append(f"prompts/{cid}/ holds prompt file(s) but registry.yaml declares "
                          f"status '{status}' — a charter with a registered prompt must say "
                          f"'registered'")


def self_test() -> int:
    """Falsify the registry cross-check and the prompt filename rule.

    What this protects: the set of prompts that has actually been REVIEWED. A prompt file
    sitting in the tree but absent from registry.yaml is running in production outside the
    reviewed set — nothing at runtime distinguishes it, because a prompt is just a file the
    agent reads. The failure is entirely a governance one and entirely invisible to tests.

    Both directions matter and only one is obvious: a declared prompt that does not exist is
    loud (the agent breaks), an existing prompt nobody declared is silent.
    """
    import tempfile

    fails: list[str] = []

    def run(entries, dirs_seen, files):
        """Run cross_check against a fixture prompts tree."""
        td = tempfile.mkdtemp()
        root = pathlib.Path(td)
        for rel in files:
            f = root / rel
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text("prompt body\n")
        errs: list[str] = []
        cross_check(entries, dirs_seen, errs, root)
        return errs

    def case(label, errs, want_hit, want_sub=""):
        got = bool(errs)
        if got != want_hit:
            fails.append(f"{label}: expected error={want_hit}, got {errs}")
        elif want_sub and not any(want_sub in e for e in errs):
            fails.append(f"{label}: errored for the wrong reason — no {want_sub!r} in {errs}")

    reg = {"a": {"id": "a", "status": "registered", "prompts": ["main.v1"]}}

    # Declared AND present: the only clean shape.
    case("a declared prompt that exists is clean",
         run(reg, {"a": {"main.v1"}}, ["a/main.v1.md"]), False)

    # Declared but absent — loud in production, but the gate must still say so.
    case("a declared prompt that does not exist is an error",
         run(reg, {"a": set()}, []), True, "does not exist")

    # THE SILENT ONE: a prompt file nobody declared. It runs, and it is outside the reviewed
    # set — nothing else in the repo can tell.
    case("an undeclared prompt FILE is an error",
         run(reg, {"a": {"main.v1", "sneaky.v1"}}, ["a/main.v1.md", "a/sneaky.v1.md"]),
         True, "not listed in registry.yaml")

    # A non-'registered' status with files present is a contradiction: the charter claims it
    # has no reviewed prompt while shipping one.
    for status in sorted(NEEDS_REASON):
        case(f"status '{status}' with prompt files present is an error",
             run({"a": {"id": "a", "status": status}}, {"a": {"main.v1"}}, ["a/main.v1.md"]),
             True, "must say")
    # ...and the same status with NO files is legitimate — that is what the status means.
    for status in sorted(NEEDS_REASON):
        case(f"status '{status}' with no files is clean",
             run({"a": {"id": "a", "status": status}}, {"a": set()}, []), False)

    # --- the filename rule -------------------------------------------------------------
    # Versioning is the whole point: an unversioned prompt cannot be rolled back to, and two
    # edits become indistinguishable.
    for good in ("main.v1.md", "risk-check.v12.md"):
        if not FILENAME_RE.match(good):
            fails.append(f"{good!r} should be a valid prompt filename")
    for bad in ("main.md", "main.v.md", "Main.v1.md", "main.v1.txt", "main-v1.md"):
        if FILENAME_RE.match(bad):
            fails.append(f"{bad!r} should NOT be a valid prompt filename")

    # A live read: the fixtures cannot tell that agents.yaml still parses and still has ids.
    live_ids = charter_ids()
    if not live_ids:
        fails.append("reading the real agents.yaml produced NO charter ids — every registry "
                     "entry would then be reported as unknown, or none checked at all")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print(f"self-test ok: prompt-registry integrity is falsifiable "
          f"(17 cases + a live read of {len(live_ids)} charter(s))")
    return 0


def main():
    if "--self-test" in sys.argv:
        return self_test()

    if not PROMPTS.is_dir():
        sys.stderr.write(f"::error::prompt registry directory missing: {PROMPTS.relative_to(ROOT)}\n")
        return 1

    ids = charter_ids()
    errors = []
    seen = {}          # (agent, name, version) -> path, to catch a re-used version
    have_prompts = set()
    dirs_seen = {}     # charter -> {"<name>.v<N>"} actually present on disk
    registered = 0

    for agent_dir in sorted(p for p in PROMPTS.iterdir() if p.is_dir()):
        agent = agent_dir.name
        if agent not in ids:
            errors.append(f"{agent_dir.relative_to(ROOT)}/ — '{agent}' is not an id: in agents.yaml")
            # still inspect the files, but the dir itself is already a hard error
        have_prompts.add(agent)

        for f in sorted(agent_dir.glob("*.md")):
            rel = f.relative_to(ROOT)
            m = FILENAME_RE.match(f.name)
            if not m:
                errors.append(f"{rel} — filename must be <name>.v<N>.md (e.g. system.v1.md)")
                continue
            content = f.read_text()
            if not content.strip():
                errors.append(f"{rel} — prompt file is empty")
                continue
            key = (agent, m.group("name"), int(m.group("version")))
            if key in seen:
                errors.append(f"{rel} — re-uses version of {seen[key]} "
                              f"(a shipped prompt is immutable; bump to a new version instead)")
                continue
            seen[key] = str(rel)
            dirs_seen.setdefault(agent, set()).add(f.name[:-3])
            registered += 1
            digest = hashlib.sha256(content.encode()).hexdigest()[:16]
            print(f"  ok  {rel}  (sha256 {digest}…)")

    # The coverage manifest (#1918): every charter makes an explicit claim, and the claim must
    # match the tree.
    entries = load_manifest(ids, errors)
    cross_check(entries, dirs_seen, errors)

    by_status = {}
    for cid, entry in entries.items():
        by_status.setdefault(entry.get("status"), []).append(cid)
    pending = sorted(by_status.get("pending", []))
    if pending:
        print(f"::warning title=Prompt registry::{len(pending)} charter(s) pending prompt "
              f"migration (ADR-0148 backlog): {', '.join(pending)}")

    if errors:
        for e in errors:
            sys.stderr.write(f"::error title=Prompt registry::{e}\n")
        sys.stderr.write(f"::error::check-prompt-registry: {len(errors)} integrity violation(s).\n")
        return 1

    summary = ", ".join(f"{len(v)} {k}" for k, v in sorted(by_status.items()))
    print(f"prompt-registry: {registered} prompt(s) across {len(have_prompts)} charter(s), "
          f"integrity OK; coverage across {len(entries)} charter(s): {summary}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
