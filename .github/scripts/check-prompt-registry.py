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
#     * two files that normalise to the same (agent, name, version) — a re-used version.
#   ADVISORY (::warning, never fails) — coverage:
#     * charters in agents.yaml with no registered prompt directory (the migration backlog). This
#       is advisory because agents.yaml carries no structured "uses-an-LLM" field, so the guard
#       cannot assert that a promptless charter is wrong — only surface it.
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

FILENAME_RE = re.compile(r"^(?P<name>[a-z0-9-]+)\.v(?P<version>[0-9]+)\.md$")


def charter_ids():
    data = yaml.safe_load(AGENTS.read_text())
    return {a.get("id") for a in (data.get("agents", []) or []) if a.get("id")}


def main():
    if not PROMPTS.is_dir():
        sys.stderr.write(f"::error::prompt registry directory missing: {PROMPTS.relative_to(ROOT)}\n")
        return 1

    ids = charter_ids()
    errors = []
    seen = {}          # (agent, name, version) -> path, to catch a re-used version
    have_prompts = set()
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
            registered += 1
            digest = hashlib.sha256(content.encode()).hexdigest()[:16]
            print(f"  ok  {rel}  (sha256 {digest}…)")

    # Advisory coverage — charters with no registered prompt directory.
    missing = sorted(ids - have_prompts)
    if missing:
        print(f"::warning title=Prompt registry::{len(missing)} charter(s) have no registered "
              f"prompt yet (migration backlog, ADR-0148): {', '.join(missing)}")

    if errors:
        for e in errors:
            sys.stderr.write(f"::error title=Prompt registry::{e}\n")
        sys.stderr.write(f"::error::check-prompt-registry: {len(errors)} integrity violation(s).\n")
        return 1

    print(f"prompt-registry: {registered} prompt(s) across {len(have_prompts)} charter(s), "
          f"integrity OK; {len(missing)} charter(s) pending migration.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
