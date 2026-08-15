#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard the ADR-0106 identifier intent split: a PR must not ADD a bare
# java.util.UUID.randomUUID() in src/main — new code mints identifiers via
# com.openbank.libs.domain.identifiers.Ids:
#
#   Ids.newId()    — UUIDv7, time-ordered, for durable/indexed identifiers (entity
#                    ids, outbox event_id) → B-tree insert locality.
#   Ids.randomId() — UUIDv4, for values that must NOT carry a creation timestamp or
#                    ordering: idempotency keys, correlation/trace ids, nonces, tokens.
#
# DIFF-SCOPED on purpose: it inspects only the lines THIS change adds, so it never
# fails because some other change elsewhere in the fleet still uses randomUUID(). The
# ~100 pre-existing call sites migrate to Ids as-touched (#1699); they are not flagged
# here. The sanctioned wrapper itself (domain/identifiers/Ids.kt) is excluded.
#
# COMMENTS ARE STRIPPED FIRST, and that is load-bearing rather than tidy. This guard
# matches source TEXT, so before #4308 it could not tell code from prose about code:
# openbank-card-issuance-service's CardOutboxAdminResource KDoc quoted
# openbank-audit-service's own id-minting call verbatim, as the evidence for why outbox
# requeue cannot be automatic, and the gate read that explanation as this file committing
# the defect. It was worked around by rewording the prose (b0e6c02ac) — the wrong
# direction: the file was correct and the checker was not. Same class as
# check-roles-allowed-realm.py, whose first draft flagged the KDoc that #2403 wrote to
# explain the annotation it had just fixed, and as the prod-readiness scorer that graded
# services on the word "contract" appearing in a comment (#2291).
#
# The earlier bash implementation skipped only a FULL-LINE `//` comment (#1475, after a
# false positive on #1430) and deliberately left the inline-trailing-comment and KDoc
# cases open as `rules.yaml: identifier_intent_guard.known_gap` (issue-1511), because
# stripping a trailing `// ...` with a regex breaks on `//` inside a string literal. A
# real Kotlin comment stripper closes all three at once, so this is now a full port of
# the gate to Python, REUSING check-roles-allowed-realm.py's `strip_comments` by import
# rather than carrying a second copy of it (Kotlin block comments NEST — a `/*` inside a
# KDoc opens a second level, and a stripper that does not mirror that closes the KDoc
# early and scans its tail as code).
#
# Shared limitation, inherited with the implementation and unchanged from the bash gate:
# the stripper does not model string literals, so `"…//…"` blanks the rest of that line
# and an unbalanced `"/*"` opens a block. Both fail toward a false NEGATIVE (missing a
# violation), never toward flagging correct code, which is the direction that costs a
# reworded KDoc.
#
# Usage:
#   check-new-random-uuid.py <base-ref-or-sha>   # <base> = the PR base to diff HEAD against.
#                                                # Empty/unset => no-op (a push build with no
#                                                # base), so this is safe to wire unconditionally.
#   check-new-random-uuid.py --self-test         # falsify the gate: exits 0 iff every case
#                                                # below lands on its expected verdict.

import argparse
import importlib.util
import pathlib
import re
import subprocess
import sys
import tempfile

CALL = "UUID.randomUUID()"

# Pathspec note (do NOT "fix" to :(glob)/**): a git pathspec WITHOUT the `:(glob)` magic word
# matches `*` across `/` (fnmatch, FNM_PATHNAME *unset*), so `*/src/main/*.kt` and
# `:(exclude)*/domain/identifiers/Ids.kt` correctly hit deep paths like
# `openbank-ledger-service/src/main/kotlin/.../Foo.kt`. `:(glob)` is what would RESTRICT `*` to a
# single segment (requiring `**`). Verified: a deep src/main change is flagged and a deep Ids.kt
# change is excluded.
PATHSPEC = ["*/src/main/*.kt", ":(exclude)*/domain/identifiers/Ids.kt"]

HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def _strip_comments():
    """Import the one Kotlin comment stripper this repo has, rather than copying it.

    A second copy is a second thing to keep correct — and the nesting rule is exactly the
    detail a re-implementation gets wrong.
    """
    path = pathlib.Path(__file__).with_name("check-roles-allowed-realm.py")
    spec = importlib.util.spec_from_file_location("check_roles_allowed_realm", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.strip_comments


strip_comments = _strip_comments()


def git(args, cwd):
    return subprocess.run(
        ["git", *args], cwd=cwd, check=True, capture_output=True, text=True,
    ).stdout


def added_lines(base, cwd):
    """Map path -> set of line numbers this change ADDS, for src/main Kotlin outside Ids.kt."""
    diff = git(["diff", "--unified=0", base, "HEAD", "--", *PATHSPEC], cwd)
    per_file, path, lineno = {}, None, 0
    for raw in diff.splitlines():
        if raw.startswith("+++ "):
            target = raw[4:].strip()
            path = None if target == "/dev/null" else re.sub(r"^b/", "", target)
            continue
        m = HUNK_RE.match(raw)
        if m:
            lineno = int(m.group(1))
            continue
        if path is None:
            continue
        if raw.startswith("+"):
            per_file.setdefault(path, set()).add(lineno)
            lineno += 1
        elif raw.startswith(" "):
            lineno += 1
    return per_file


def violations(base, cwd="."):
    """Return [(path, lineno, text)] for added lines that call UUID.randomUUID() in real code."""
    found = []
    for path, lines in sorted(added_lines(base, cwd).items()):
        src = git(["show", f"HEAD:{path}"], cwd)
        raw = src.splitlines()
        # strip_comments preserves line numbering (it blanks characters, never drops newlines),
        # so a comment-free view of the file can be indexed by the diff's line numbers directly.
        stripped = strip_comments(src).splitlines()
        for n in sorted(lines):
            if n <= len(stripped) and CALL in stripped[n - 1]:
                found.append((path, n, raw[n - 1]))
    return found


def report(found):
    print(f"::error::This change adds {len(found)} bare UUID.randomUUID() call(s) in src/main (ADR-0106).")
    print("Mint identifiers via com.openbank.libs.domain.identifiers.Ids:")
    print("    Ids.newId()    — UUIDv7, for durable/indexed identifiers (entity ids, outbox event_id)")
    print("    Ids.randomId() — UUIDv4, for idempotency keys, correlation/trace ids, nonces, tokens")
    print("")
    for path, n, text in found:
        print(f"{path}:{n}:  {text}")


# --------------------------------------------------------------------------------------------
# Self-test. Each case is a real git repository with a real base commit and a real HEAD commit,
# driven through the same `violations()` the gate calls — never a hand-fed string, because the
# defect this fix closes lived in the interaction between the diff scoping and the matcher.
# --------------------------------------------------------------------------------------------

SVC = "openbank-demo-service/src/main/kotlin/com/openbank/demo/Demo.kt"

# The exact KDoc from PR #4308's CardOutboxAdminResource — the false positive that forced a
# reword (b0e6c02ac). It quotes openbank-audit-service's id-minting call as evidence.
KDOC_4308 = """package com.openbank.demo

/**
 * - `openbank-audit-service` is **not**. `AuditConsumer` builds every entry with
 *   `id = UUID.randomUUID()` and calls `repo.save(entry)` with no dedup on the outbox `ce-id`
 *   header; `AuditRepository.save` links the row into a hash chain, and `audit_entries` is
 *   append-only at the database.
 */
class Demo { fun id() = Ids.newId() }
"""

# A KDoc that contains a BALANCED inner `/* … */`. Kotlin block comments nest, so the whole
# block is still one comment and the mention on the following line is prose. A stripper that
# does not mirror the nesting closes at the inner `*/`, scans the tail as code, and flags it.
NESTED_KDOC = """package com.openbank.demo

/**
 * The old glob was `/* legacy */` and it swallowed too much; note that the sibling still
 * calls `UUID.randomUUID()` directly, which is what #1699 migrates as-touched.
 */
class Demo { fun id() = Ids.newId() }
"""

# Nesting again, with a REAL violation after the KDoc: proves tracking two levels in and back out
# leaves the code that FOLLOWS the comment live, rather than swallowing the rest of the file.
NESTED_KDOC_THEN_CALL = """package com.openbank.demo

/**
 * Historic note: the pattern `/* like this */` appears here on purpose.
 */
class Demo { fun id() = UUID.randomUUID() }
"""

# (name, files, expected violation line numbers in SVC-or-only-file order)
CASES = [
    (
        "bare call in src/main is FLAGGED",
        {SVC: "package com.openbank.demo\n\nclass Demo { fun id() = UUID.randomUUID() }\n"},
        [3],
    ),
    ("#4308 KDoc quoting another service's call is NOT flagged", {SVC: KDOC_4308}, []),
    ("KDoc with a nested `/* … */` is NOT flagged", {SVC: NESTED_KDOC}, []),
    ("real call AFTER a nested-comment KDoc is still FLAGGED", {SVC: NESTED_KDOC_THEN_CALL}, [6]),
    (
        "full-line // comment is NOT flagged (#1430/#1475)",
        {SVC: "package com.openbank.demo\n\n// sibling still uses UUID.randomUUID()\nclass Demo\n"},
        [],
    ),
    (
        "inline trailing // comment is NOT flagged (closes issue-1511)",
        {SVC: "package com.openbank.demo\n\nval x = Ids.newId()  // not UUID.randomUUID() any more\n"},
        [],
    ),
    (
        "the Ids wrapper itself is excluded",
        {
            "openbank-libs-domain/src/main/kotlin/com/openbank/libs/domain/identifiers/Ids.kt":
                "package com.openbank.libs.domain.identifiers\n\nfun randomId() = UUID.randomUUID()\n",
        },
        [],
    ),
    (
        "src/test is out of scope",
        {
            "openbank-demo-service/src/test/kotlin/com/openbank/demo/DemoTest.kt":
                "package com.openbank.demo\n\nval t = UUID.randomUUID()\n",
        },
        [],
    ),
]


def _commit(repo, message):
    git(["add", "-A"], repo)
    git(
        [
            "-c", "user.email=selftest@openbank.invalid",
            "-c", "user.name=selftest",
            "-c", "commit.gpgsign=false",
            "commit", "-q", "-m", message,
        ],
        repo,
    )
    return git(["rev-parse", "HEAD"], repo).strip()


def run_case(name, files, expected):
    with tempfile.TemporaryDirectory() as tmp:
        repo = pathlib.Path(tmp)
        git(["init", "-q", "-b", "main"], repo)
        (repo / "README.md").write_text("base\n")
        base = _commit(repo, "base")
        for rel, body in files.items():
            f = repo / rel
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(body)
        _commit(repo, "change")
        found = violations(base, cwd=repo)
    got = [n for _, n, _ in found]
    ok = got == expected
    detail = "" if ok else f"  (got {[f'{p}:{n}' for p, n, _ in found]})"
    print(f"  [{'ok' if ok else 'FAIL'}] {name} — expected violation(s) on line(s) {expected}{detail}")
    return ok


def self_test():
    print("check-new-random-uuid --self-test: driving the gate over real base/HEAD commits.")
    results = [run_case(*c) for c in CASES]
    bad = results.count(False)
    if bad:
        print(f"::error::check-new-random-uuid self-test: {bad} of {len(results)} case(s) wrong.")
        return 1
    print(f"check-new-random-uuid self-test: {len(results)} case(s), each on its expected verdict.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("base", nargs="?", default="")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    base = args.base.strip()
    if not base:
        print("check-new-random-uuid: no base ref (not a PR) — skipping.")
        return 0
    if subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"{base}^{{commit}}"], capture_output=True,
    ).returncode:
        print(f"::error::check-new-random-uuid: base ref '{base}' not found (fetch it before running).")
        return 1

    found = violations(base)
    if found:
        report(found)
        return 1
    print("check-new-random-uuid: this change adds no bare UUID.randomUUID() in src/main.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
