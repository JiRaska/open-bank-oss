#!/usr/bin/env python3
"""Guard: no class under any service's `src/main` may return
`AuthzDecision(allow = true, ...)` UNCONDITIONALLY (rules.yaml: authz_policy).

WHY THIS EXISTS: `AllowAllPolicyDecisionPoint` — the fixture every `@Authorize`-decorated
unit test wires in place of the real OPA sidecar — used to live in
`openbank-libs-domain/src/main/kotlin/com/openbank/libs/authz/`. That put a class whose
whole job is to allow every request, unconditionally, on the same `src/main` classpath every
production service builds against. Nothing stopped a service's OWN production
`AuthzProducer` from `@Produces`-ing it by mistake (a copy-paste from a test profile, a
stale `@Alternative` left enabled) — the compiler cannot tell "wired for tests" from "wired
for prod" once the class lives in `src/main`. The class has since moved to
`openbank-libs-testing` (test-scope only, never on a service's production classpath — see
`PolicyDecisionPoint.kt`'s kdoc), and this guard makes sure it — or anything shaped like it —
never comes back to `src/main`.

WHAT IT CHECKS: every `fun` body under `<service>/src/main/kotlin/**/*.kt` (and
`openbank-libs-domain`, `openbank-libs-runtime`) whose body contains, in real code (never a
comment or a string literal), a bare return of `AuthzDecision(allow = true`. The function is
flagged UNLESS its body also contains a branching keyword (`if`, `when`, `?:`, `&&`, `||`,
`try`) that could make the `allow = true` conditional. A function with no branching at all
that resolves to `allow = true` can never resolve to anything else — that is the exact
"AllowAll" shape `AllowAllPolicyDecisionPoint` had, wherever it turns up.

This intentionally does NOT flag `DenyAllPolicyDecisionPoint` (`allow = false`) — the
kill-switch alternative that is genuinely deployed to production (`rules.yaml: authz_policy`
documents it as such) — nor a conditional decision function that merely CONTAINS the string
`allow = true` on one branch among several.

stdlib-only (regex + brace matching); no Kotlin parser dependency, matching the fleet's other
Kotlin-source guards (check-no-runblocking-in-scheduled.py, check-nonnull-jaxrs-params.py).

ENFORCED. Usage:
  check-no-allow-all-pdp-in-main.py [root]        # scan (default root: .)
  check-no-allow-all-pdp-in-main.py --self-test    # falsify the guard itself
"""
from __future__ import annotations

import pathlib
import re
import sys

FUN_RE = re.compile(r"\bfun\s+`?[\w<>]+`?\s*\(")
ALLOW_TRUE_RE = re.compile(r"AuthzDecision\s*\(\s*allow\s*=\s*true\b")
BRANCH_RE = re.compile(r"\b(if|when|try)\b|\?\?|\?:|&&|\|\|")

EXCLUDED_DIR_PARTS = {"build", "worktrees", ".git", "node_modules"}

# openbank-libs-testing is a test-fixture LIBRARY: every consumer pulls it in via
# `testImplementation(project(":openbank-libs-testing"))` (never `implementation`/`api` from
# a service's own src/main — checked against every current consumer's build.gradle.kts), so a
# class under ITS OWN src/main never reaches any service's production classpath. Its whole
# purpose is fixtures shaped exactly like the thing this guard looks for
# (AllowAllPolicyDecisionPoint lives there on purpose), so it is out of scope by module name,
# not by directory shape.
EXCLUDED_MODULES = {"openbank-libs-testing"}


def strip_comments_and_strings(src: str) -> str:
    """Best-effort: blank out //-comments, /* */ comments, and string/char literals so a
    match inside prose (the exact trap check-no-service-principal-type.sh's own self-test
    exists to cover) never counts as a hit. Not a full Kotlin lexer — good enough for this
    guard's narrow vocabulary, same tradeoff the sibling scripts make."""
    out = []
    i = 0
    n = len(src)
    while i < n:
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            j = n if j == -1 else j
            out.append(" " * (j - i))
            i = j
        elif two == "/*":
            j = src.find("*/", i + 2)
            j = n if j == -1 else j + 2
            out.append(" " * (j - i))
            i = j
        elif src[i] == '"':
            # handle triple-quoted strings too
            if src[i:i + 3] == '"""':
                j = src.find('"""', i + 3)
                j = n if j == -1 else j + 3
                out.append(" " * (j - i))
                i = j
            else:
                j = i + 1
                while j < n and src[j] != '"':
                    if src[j] == "\\":
                        j += 1
                    j += 1
                j = min(j + 1, n)
                out.append(" " * (j - i))
                i = j
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def find_matching_brace(src: str, open_idx: int) -> int:
    depth = 0
    i = open_idx
    n = len(src)
    while i < n:
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return n - 1


def find_violations(path: pathlib.Path, raw: str) -> list[str]:
    findings = []
    clean = strip_comments_and_strings(raw)
    for m in FUN_RE.finditer(clean):
        brace_idx = clean.find("{", m.end() - 1)
        # expression-body functions (`fun x() = AuthzDecision(...)`) have no `{`
        # before the next `fun`/EOF — handle by taking up to the next top-level fun start.
        eq_idx = clean.find("=", m.end() - 1)
        next_fun = FUN_RE.search(clean, m.end())
        stop = next_fun.start() if next_fun else len(clean)
        if brace_idx != -1 and brace_idx < stop:
            body = clean[m.start():find_matching_brace(clean, brace_idx) + 1]
        elif eq_idx != -1 and eq_idx < stop:
            body = clean[m.start():stop]
        else:
            continue

        if not ALLOW_TRUE_RE.search(body):
            continue
        if BRANCH_RE.search(body):
            continue

        line_no = raw.count("\n", 0, m.start()) + 1
        fn_sig = clean[m.start():m.start() + 80].splitlines()[0].strip()
        findings.append(
            f"::error file={path}::{path}:{line_no}: function `{fn_sig}` "
            f"unconditionally returns AuthzDecision(allow = true, ...) with no branching "
            f"in its body — an allow-all PDP must live in openbank-libs-testing "
            f"(test scope), never under src/main (rules.yaml: authz_policy)."
        )
    return findings


def iter_kotlin_main_files(root: pathlib.Path):
    for path in root.rglob("*.kt"):
        parts = set(path.parts)
        if parts & EXCLUDED_DIR_PARTS:
            continue
        if "src" not in path.parts or "main" not in path.parts:
            continue
        # only .../src/main/... (not src/main-something) and only inside a module named
        # openbank-* or openbank-libs-*, matching the fleet's module naming.
        try:
            src_idx = path.parts.index("src")
        except ValueError:
            continue
        if src_idx + 1 >= len(path.parts) or path.parts[src_idx + 1] != "main":
            continue
        if src_idx == 0:
            continue
        module = path.parts[src_idx - 1]
        if module in EXCLUDED_MODULES:
            continue
        yield path


def scan(root: pathlib.Path) -> int:
    all_findings: list[str] = []
    subjects = 0
    for path in iter_kotlin_main_files(root):
        subjects += 1
        raw = path.read_text(encoding="utf-8", errors="replace")
        if "AuthzDecision" not in raw:
            continue
        all_findings.extend(find_violations(path, raw))

    print(f"SUBJECTS={subjects}")
    if all_findings:
        for f in all_findings:
            print(f)
        print(f"::error::check-no-allow-all-pdp-in-main found {len(all_findings)} violation(s)")
        return 1
    print("check-no-allow-all-pdp-in-main: no unconditional allow-all PDP under src/main")
    return 0


# --- self-test ------------------------------------------------------------------------
def self_test() -> int:
    import tempfile

    fails = 0

    def put(root: pathlib.Path, rel: str, content: str) -> None:
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")

    def expect(label: str, root: pathlib.Path, want_rc: int, sub: str = "") -> None:
        nonlocal fails
        old_stdout = sys.stdout
        import io
        buf = io.StringIO()
        sys.stdout = buf
        try:
            rc = scan(root)
        finally:
            sys.stdout = old_stdout
        out = buf.getvalue()
        if rc != want_rc:
            print(f"::error::self-test: {label} — expected rc={want_rc}, got rc={rc}: {out}")
            fails += 1
        elif sub and sub not in out:
            print(f"::error::self-test: {label} — rc right, missing substring {sub!r}: {out}")
            fails += 1

    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)

        # THE DEFECT: the exact shape AllowAllPolicyDecisionPoint had, under src/main.
        a = root / "a"
        put(a, "openbank-x-service/src/main/kotlin/com/openbank/x/authz/AllowAllPolicyDecisionPoint.kt", """
package com.openbank.x.authz
class AllowAllPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = true, reason = "test-stub", policyVersion = "allow-all")
}
""")
        expect("unconditional allow-all under src/main is FLAGGED", a, 1, "unconditionally returns")

        # THE FIX: the genuinely-conditional production PDP is NOT flagged.
        b = root / "b"
        put(b, "openbank-x-service/src/main/kotlin/com/openbank/x/authz/OpaSidecarPolicyDecisionPoint.kt", """
package com.openbank.x.authz
class OpaSidecarPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision {
        val resp = httpCall(query)
        return if (resp.isAllowed()) {
            AuthzDecision(allow = true, policyVersion = resp.version)
        } else {
            AuthzDecision(allow = false, reason = resp.reason)
        }
    }
}
""")
        expect("conditional PDP is NOT flagged", b, 0)

        # THE KILL-SWITCH: unconditional allow = FALSE must never be flagged.
        c = root / "c"
        put(c, "openbank-libs-domain/src/main/kotlin/com/openbank/libs/authz/DenyAllPolicyDecisionPoint.kt", """
package com.openbank.libs.authz
class DenyAllPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = false, reason = "kill-switch-engaged", policyVersion = "deny-all")
}
""")
        expect("deny-all kill-switch is NOT flagged", c, 0)

        # SAME TEXT IN A COMMENT/KDOC must not trip the guard.
        d = root / "d"
        put(d, "openbank-libs-domain/src/main/kotlin/com/openbank/libs/authz/PolicyDecisionPoint.kt", """
package com.openbank.libs.authz
/**
 * See AuthzDecision(allow = true, ...) in the testing module for the allow-all fixture.
 */
interface PolicyDecisionPoint {
    suspend fun allow(query: AuthzQuery): AuthzDecision
}
""")
        expect("the same text in a KDOC comment is not a hit", d, 0)

        # SAME SHAPE, but under src/test — out of this guard's scope (a different, existing
        # convention already keeps test fixtures out of src/main; this guard's job is only
        # src/main).
        e = root / "e"
        put(e, "openbank-libs-testing/src/test/kotlin/com/openbank/libs/testing/authz/AllowAllPolicyDecisionPointTest.kt", """
package com.openbank.libs.testing.authz
class AllowAllPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = true, reason = "test-stub", policyVersion = "allow-all")
}
""")
        expect("the identical class under src/test is out of scope", e, 0)

        # SAME SHAPE under openbank-libs-testing's OWN src/main is deliberately out of scope:
        # that module is a test-fixture library every consumer pulls in via
        # testImplementation, so this class never reaches a service's production classpath.
        f = root / "f"
        put(f, "openbank-libs-testing/src/main/kotlin/com/openbank/libs/testing/authz/AllowAllPolicyDecisionPoint.kt", """
package com.openbank.libs.testing.authz
class AllowAllPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = true, reason = "test-stub", policyVersion = "allow-all")
}
""")
        expect("openbank-libs-testing's own src/main is excluded (test-fixture library)", f, 0)

    if fails:
        print(f"::error::self-test FAILED ({fails} case(s))")
        return 1
    print("self-test ok: check-no-allow-all-pdp-in-main is falsifiable (6 cases)")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    root = pathlib.Path(argv[0]) if argv else pathlib.Path(".")
    return scan(root)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
