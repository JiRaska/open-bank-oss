#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""principal-name-trust (enforced, #12448).

A caller guard must never decide "this is service X" by comparing the authenticated principal's
NAME. Quarkus derives `SecurityIdentity.principal.name` from `upn`/`preferred_username`, a USERNAME
claim, so `principal?.name == "service-account-openbank-edge"` (or `principal?.name in CALLERS`, or
`== configuredPrincipal`) trusts a username rather than the client the token was issued to. The
one sanctioned test is `com.openbank.libs.authz.ServiceAccountIdentity` (openbank-libs-runtime),
which binds the name to the verified `azp`.

Flags, in any `src/main` Kotlin file:
  * a principal-name accessor (`principal.name`, `principal?.name`, `principal!!.name`,
    `userPrincipal?.name`, `getName()` on either) used as an operand of `==`, `!=`, `in`, `!in`,
    `.equals(`;
  * a local `val x = …principal?.name` whose `x` is then compared the same way in that file
    (the two-step shape pension shipped before #12447).

Deliberately NOT flagged: `startsWith("service-account-")` (every such site in the fleet REFUSES a
service-account from a human-only path, so a forged name fails closed), and reading the name for
attribution/audit. A line may opt out with a trailing `// principal-name-trust: allow <reason>`,
which the finding text names; there is no baseline because the fleet was migrated in the same
change.
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402  (path shim above must run first)

NAME = r"(?:\b(?:user)?[Pp]rincipal\s*(?:\?|!!)?\s*\.\s*(?:name|getName\(\))\b)"
CMP = r"(?:==|!=|!in\b|\bin\b|\.equals\()"
DIRECT = re.compile(rf"{NAME}\s*\)?\s*{CMP}|{CMP}\s*\(?\s*[\w.]*?{NAME}")
ALIAS = re.compile(rf"\bva[lr]\s+(\w+)(?:\s*:\s*[\w?]+)?\s*=\s*[\w.()?!]*?{NAME}\s*(?:$|\?:|\.orEmpty\(\)|\.trim\(\)|;)")
OPT_OUT = "principal-name-trust: allow"

# Known sites, each tied to the issue/PR that removes it. An entry whose file no longer produces a
# finding is itself reported (stale), so this list can only shrink.
KNOWN_PENDING = {
    # #12447 rebinds pension's relay to azp; left out of the fleet sweep so the two PRs don't fight.
    "openbank-pension-service/src/main/kotlin/com/openbank/pension/infrastructure/authz/ContractAccessGuard.kt": "#12447",
}
SKIP_DIRS = {".git", "build", "node_modules", ".gradle", "worktrees"}


def sources(root: pathlib.Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        parts = pathlib.Path(dirpath).relative_to(root).parts
        if "src" not in parts or parts[parts.index("src") + 1 : parts.index("src") + 2] != ("main",):
            continue
        for name in sorted(filenames):
            if name.endswith(".kt"):
                yield pathlib.Path(dirpath).relative_to(root) / name, pathlib.Path(dirpath) / name


def code_lines(text: str):
    for n, line in enumerate(text.splitlines(), 1):
        s = line.strip()
        if s.startswith(("//", "*", "/*")):
            continue
        yield n, line


def check(root: pathlib.Path, known: dict[str, str] | None = None) -> tuple[list[str], int]:
    known = KNOWN_PENDING if known is None else known
    findings: list[str] = []
    seen_known: set[str] = set()
    scanned = 0
    for rel, path in sources(root):
        scanned += 1
        lines = list(code_lines(path.read_text(encoding="utf-8", errors="replace")))
        aliases = {m.group(1) for _, line in lines for m in [ALIAS.search(line)] if m}
        alias_cmp = (
            re.compile(rf"(?<![.\w])(?:{'|'.join(map(re.escape, aliases))})\b\s*\)?\s*{CMP}|{CMP}\s*\(?\s*(?<![.\w])(?:{'|'.join(map(re.escape, aliases))})\b(?!\s*=)")
            if aliases else None
        )
        for n, line in lines:
            if OPT_OUT in line:
                continue
            code = line.split("//", 1)[0]
            if DIRECT.search(code) or (alias_cmp and alias_cmp.search(code)):
                if rel.as_posix() in known:
                    seen_known.add(rel.as_posix())
                    continue
                findings.append(
                    f"{rel}:{n}: caller trusted by principal NAME — use "
                    f"com.openbank.libs.authz.ServiceAccountIdentity (binds to azp): {line.strip()}"
                )
    for path, ref in sorted(known.items()):
        if path not in seen_known and (root / path).exists():
            findings.append(f"{path}: stale KNOWN_PENDING entry ({ref}) — it no longer trusts a name; remove it")
    return findings, scanned


SELF_TEST = [
    # (name, relpath, text, expect_finding)
    ("== against a service-account literal is flagged", "svc/src/main/kotlin/A.kt",
     'if (identity.principal.name != "service-account-openbank-edge") deny()\n', True),
    ("!in an allow-list is flagged", "svc/src/main/kotlin/A.kt",
     "if (identity.principal?.name !in CALLERS) throw ForbiddenException()\n", True),
    ("== a configured principal is flagged", "svc/src/main/kotlin/A.kt",
     "return permitted.isNotBlank() && securityIdentity.principal?.name == permitted\n", True),
    ("the two-step alias shape is flagged", "svc/src/main/kotlin/A.kt",
     "val principal = identity.principal?.name\nif (principal == null || principal !in trustedRelays) deny()\n", True),
    ("userPrincipal via SecurityContext is flagged", "svc/src/main/kotlin/A.kt",
     'if (ctx.userPrincipal?.name == "service-account-x") ok()\n', True),
    ("the helper is clean", "svc/src/main/kotlin/A.kt",
     "if (!ServiceAccountIdentity.isOneOf(identity, CALLERS)) throw ForbiddenException()\n", False),
    ("reading the name for attribution is clean", "svc/src/main/kotlin/A.kt",
     'val actor = identity.principal?.name ?: "unknown"\naudit(actor)\n', False),
    ("startsWith (fail-closed refusal) is clean", "svc/src/main/kotlin/A.kt",
     'if (actor.startsWith("service-account-")) throw ForbiddenException()\n', False),
    ("a comment is clean", "svc/src/main/kotlin/A.kt",
     "// never write identity.principal?.name == permitted\n", False),
    ("src/test is out of scope", "svc/src/test/kotlin/A.kt",
     "if (identity.principal?.name !in CALLERS) fail()\n", False),
    ("an explicit opt-out is honoured", "svc/src/main/kotlin/A.kt",
     "if (p.principal.name == owner) ok() // principal-name-trust: allow owner is a human sub\n", False),
]


def self_test() -> int:
    ok = True
    for name, rel, text, expect in SELF_TEST:
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            f = root / rel
            f.parent.mkdir(parents=True)
            f.write_text(text)
            findings, _ = check(root, known={})
            if bool(findings) != expect:
                print(f"SELF-TEST FAIL: {name}: findings={findings}")
                ok = False
    # A field access named like an alias (`it.name == x` beside `val name = principal.name`) is clean.
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        f = root / "svc/src/main/kotlin/A.kt"
        f.parent.mkdir(parents=True)
        f.write_text("val name = sc.userPrincipal?.name ?: return\nparams.indexOfFirst { it.name == wanted }\n")
        if check(root, known={})[0]:
            print("SELF-TEST FAIL: a member access sharing the alias name was flagged")
            ok = False
        # KNOWN_PENDING: a listed dirty file passes, a listed clean file is reported stale.
        g = root / "svc/src/main/kotlin/B.kt"
        g.write_text("if (identity.principal?.name !in CALLERS) deny()\n")
        if check(root, known={"svc/src/main/kotlin/B.kt": "#1"})[0]:
            print("SELF-TEST FAIL: a KNOWN_PENDING site was reported")
            ok = False
        g.write_text("if (!ServiceAccountIdentity.isOneOf(identity, CALLERS)) deny()\n")
        if not check(root, known={"svc/src/main/kotlin/B.kt": "#1"})[0]:
            print("SELF-TEST FAIL: a stale KNOWN_PENDING entry was not reported")
            ok = False
    print("self-test: " + ("pass" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings, scanned = check(pathlib.Path(args.root))
    gatelib.subjects(scanned, "src/main Kotlin file(s) scanned for principal-name trust")
    for f in findings:
        print(f"  {f}")
    if findings:
        print(f"{len(findings)} caller check(s) trust the principal NAME (#12448)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
