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


def cross_check(entries, dirs_seen, errors):
    """The manifest's claim must match the tree: registered <=> a directory with exactly its files."""
    for cid, entry in sorted(entries.items()):
        status = entry.get("status")
        files = dirs_seen.get(cid, set())
        if status == "registered":
            declared = {str(p) for p in (entry.get("prompts") or [])}
            for name in sorted(declared):
                if not (PROMPTS / cid / f"{name}.md").is_file():
                    errors.append(f"registry.yaml ({cid}) — declares prompt '{name}' but "
                                  f"prompts/{cid}/{name}.md does not exist")
            for name in sorted(files - declared):
                errors.append(f"prompts/{cid}/{name}.md exists but is not listed in registry.yaml "
                              f"({cid}.prompts) — an unlisted prompt is outside the reviewed set")
        elif files:
            errors.append(f"prompts/{cid}/ holds prompt file(s) but registry.yaml declares "
                          f"status '{status}' — a charter with a registered prompt must say "
                          f"'registered'")


def main():
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
