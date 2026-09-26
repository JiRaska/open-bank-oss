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
never comes back to `src/main`, and that `openbank-libs-testing` itself never becomes reachable
from a service's own production classpath.

WHAT IT CHECKS:
  1. Every `fun` body under `<service>/src/main/kotlin/**/*.kt` (and `openbank-libs-domain`,
     `openbank-libs-runtime`) whose body contains, in real code (never a comment or a string
     literal), an allow-all `AuthzDecision` construction — either the named-arg form
     (`AuthzDecision(allow = true, ...)`, in any argument position) or the positional form
     (`AuthzDecision(true, ...)`, `allow` as the first constructor parameter). The function is
     flagged UNLESS its body also contains a branching keyword (`if`, `when`, `?:`, `&&`, `||`,
     `try`) that could make the decision conditional. A function with no branching at all that
     resolves to an allow-all decision can never resolve to anything else — that is the exact
     "AllowAll" shape `AllowAllPolicyDecisionPoint` had, wherever it turns up.

     Body extraction is brace-matched for block bodies (`fun x() { ... }`) and, for
     expression-bodied functions (`fun x() = ...`), bounded to the expression itself: scanning
     stops at the first point where bracket depth returns to zero AND what follows is either
     end-of-file, the closing brace of the enclosing class/object (i.e. depth would go
     negative), or the start of a new top-level declaration (`fun`/`val`/`var`/`class`/
     `object`/a visibility modifier/an annotation) seen at that same zero depth. This is
     deliberately NOT "scan to the next `fun` anywhere in the file" — a later, unrelated
     function's own `if`/`?:`/`&&` must never be able to launder an earlier unconditional
     allow-all by falling inside the scanned span.

  2. Every `build.gradle.kts` under a service module: `openbank-libs-testing` (the test-fixture
     library `AllowAllPolicyDecisionPoint` now lives in) may only be declared as
     `testImplementation` or `testFixturesApi`/`testFixturesImplementation` — never
     `implementation`, `api`, `compileOnly`, or `runtimeOnly`, which would put its `src/main`
     (fixtures shaped exactly like an allow-all PDP, on purpose) on that service's production
     classpath. `openbank-libs-testing`'s own `build.gradle.kts` is exempt (it declares its own
     dependencies, not a dependency on itself).

This intentionally does NOT flag `DenyAllPolicyDecisionPoint` (`allow = false`) — the
kill-switch alternative that is genuinely deployed to production (`rules.yaml: authz_policy`
documents it as such) — nor a conditional decision function that merely CONTAINS an allow-all
construction on one branch among several.

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
# Named-arg allow=true anywhere inside an AuthzDecision(...) call, or the positional form
# where `true` is the first constructor argument (AuthzDecision's first parameter is `allow`).
ALLOW_TRUE_RE = re.compile(
    r"AuthzDecision\s*\(\s*true\b"          # positional: AuthzDecision(true, ...)
    r"|\ballow\s*=\s*true\b"                # named, anywhere: allow = true
)
BRANCH_RE = re.compile(r"\b(if|when|try)\b|\?\?|\?:|&&|\|\|")

# A new top-level declaration starting at zero bracket depth — the point where an
# expression-bodied function's own expression has definitely ended, regardless of what
# branching keywords appear afterward in the file.
NEXT_DECL_RE = re.compile(
    r"\s*(@\w+(\([^)]*\))?\s*)*"
    r"\b(fun|val|var|class|object|interface|private|public|internal|protected|"
    r"override|abstract|open|companion|init)\b"
)

EXCLUDED_DIR_PARTS = {"build", "worktrees", ".git", "node_modules"}

# openbank-libs-testing is a test-fixture LIBRARY: every consumer pulls it in via
# `testImplementation(project(":openbank-libs-testing"))` (never `implementation`/`api` from
# a service's own src/main — checked against every current consumer's build.gradle.kts below),
# so a class under ITS OWN src/main never reaches any service's production classpath. Its whole
# purpose is fixtures shaped exactly like the thing this guard looks for
# (AllowAllPolicyDecisionPoint lives there on purpose), so it is out of scope by module name,
# not by directory shape.
EXCLUDED_MODULES = {"openbank-libs-testing"}

# Gradle configurations that put a dependency on a service's PRODUCTION classpath.
PRODUCTION_SCOPE_RE = re.compile(
    r"\b(implementation|api|compileOnly|runtimeOnly)\s*\(\s*project\s*\(\s*"
    r'["\']:openbank-libs-testing["\']\s*\)\s*\)'
)


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


OPEN = {"(": ")", "[": "]", "{": "}"}
CLOSE = {")": "(", "]": "[", "}": "{"}


def find_expression_body_end(src: str, start: int) -> int:
    """Bound an expression-bodied function (`fun x() = <expr>`) to just `<expr>`.

    Scans forward from `start` (the first character of the expression) tracking bracket
    depth across all three bracket kinds. The expression ends at the first position, at
    depth zero, where:
      - the source ends, or
      - the next character is a `}` that would take depth negative (the enclosing
        class/object's own closing brace), or
      - what follows matches the start of a new top-level declaration (NEXT_DECL_RE).
    Crucially this never runs past a point just because a `fun` appears somewhere later in
    the file with unrelated branching in between — the span is bounded by brackets actually
    closing, not by regex-searching for the next `fun` keyword.
    """
    depth = 0
    n = len(src)
    i = start
    while i < n:
        ch = src[i]
        if ch in OPEN:
            depth += 1
        elif ch in CLOSE:
            if depth == 0:
                # closing brace of the enclosing scope — stop before it.
                return i
            depth -= 1
        if depth == 0:
            m = NEXT_DECL_RE.match(src, i + 1)
            if m and i + 1 > start:
                return i + 1
        i += 1
    return n


def find_violations(path: pathlib.Path, raw: str) -> list[str]:
    findings = []
    clean = strip_comments_and_strings(raw)
    for m in FUN_RE.finditer(clean):
        brace_idx = clean.find("{", m.end() - 1)
        eq_idx = clean.find("=", m.end() - 1)
        next_fun = FUN_RE.search(clean, m.end())
        fun_scope_stop = next_fun.start() if next_fun else len(clean)

        if brace_idx != -1 and brace_idx < fun_scope_stop:
            # block body: the signature's own `{` is definitely before the parameter list's
            # `=` for a default value could also contain `=` earlier — only trust `brace_idx`
            # here if there is no `=` before it that would itself indicate an expression body
            # preceded by a return-type annotation containing braces (generics) — in practice
            # a `{` found via `clean.find` here is the function body opener because default
            # parameter values are themselves already inside the signature's own `(...)`,
            # which `find("{", ...)` starting right after the outer `fun name(` search anchor
            # does not skip over incorrectly (a default value's own braces are nested inside
            # the parameter list's parens, not before it).
            body = clean[m.start():find_matching_brace(clean, brace_idx) + 1]
        elif eq_idx != -1 and eq_idx < fun_scope_stop:
            body_end = find_expression_body_end(clean, eq_idx + 1)
            body = clean[m.start():body_end]
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


def strip_comments_only(src: str) -> str:
    """Like strip_comments_and_strings but keeps string literals intact — the build-file
    check needs to read the quoted project path itself, not just detect that a string is
    present."""
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
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def find_build_file_violations(path: pathlib.Path, raw: str) -> list[str]:
    if path.parent.name == "openbank-libs-testing":
        return []
    clean = strip_comments_only(raw)
    findings = []
    for m in PRODUCTION_SCOPE_RE.finditer(clean):
        line_no = raw.count("\n", 0, m.start()) + 1
        config = clean[m.start():m.end()].split("(", 1)[0].strip()
        findings.append(
            f"::error file={path}::{path}:{line_no}: `openbank-libs-testing` declared as "
            f"`{config}` — it is a test-fixture library (AllowAllPolicyDecisionPoint and "
            f"friends live in its src/main on purpose) and must only be reachable via "
            f"testImplementation/testFixturesImplementation/testFixturesApi, never on a "
            f"service's production classpath (rules.yaml: authz_policy)."
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


def iter_build_files(root: pathlib.Path):
    for path in root.rglob("build.gradle.kts"):
        parts = set(path.parts)
        if parts & EXCLUDED_DIR_PARTS:
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

    build_subjects = 0
    for path in iter_build_files(root):
        build_subjects += 1
        raw = path.read_text(encoding="utf-8", errors="replace")
        if "libs-testing" not in raw:
            continue
        all_findings.extend(find_build_file_violations(path, raw))

    print(f"SUBJECTS={subjects} BUILD_FILES={build_subjects}")
    if all_findings:
        for f in all_findings:
            print(f)
        print(f"::error::check-no-allow-all-pdp-in-main found {len(all_findings)} violation(s)")
        return 1
    print("check-no-allow-all-pdp-in-main: no unconditional allow-all PDP under src/main, "
          "no non-test dependency on openbank-libs-testing")
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

        # BYPASS 1: positional constructor call — AuthzDecision(true, "r", "v") — no `allow =`
        # keyword argument at all. The pre-fix regex only matched the named form and missed
        # this entirely.
        g = root / "g"
        put(g, "openbank-y-service/src/main/kotlin/com/openbank/y/authz/PositionalAllowAll.kt", """
package com.openbank.y.authz
class PositionalAllowAll : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(true, "test-stub", "allow-all")
}
""")
        expect("positional AuthzDecision(true, ...) is FLAGGED", g, 1, "unconditionally returns")

        # BYPASS 2: expression-bodied function whose unconditional allow-all is followed, later
        # in the same file, by an unrelated function containing branching keywords. The pre-fix
        # scanner bounded the expression body to "everything up to the next `fun` in the file",
        # so this later, unrelated `if`/`&&` incorrectly whitelisted the allow-all above it.
        h = root / "h"
        put(h, "openbank-z-service/src/main/kotlin/com/openbank/z/authz/LaunderedAllowAll.kt", """
package com.openbank.z.authz
class LaunderedAllowAll : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = true, reason = "test-stub", policyVersion = "allow-all")

    private val unrelatedFlag: Boolean = if (System.getenv("X") != null) true else false

    fun somethingElse(a: Int, b: Int): Int = if (a > b) a else b
}
""")
        expect(
            "an unrelated later function's branching cannot launder an earlier allow-all",
            h, 1, "unconditionally returns",
        )

        # BYPASS 3: openbank-libs-testing declared as `implementation` (not `testImplementation`)
        # in a service's build.gradle.kts — puts its src/main (fixtures shaped exactly like an
        # allow-all PDP) on that service's PRODUCTION classpath. The pre-fix gate had no check
        # of build files at all.
        i = root / "i"
        put(i, "openbank-w-service/build.gradle.kts", """
dependencies {
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-testing"))
    testImplementation(project(":openbank-libs-runtime"))
}
""")
        expect(
            "openbank-libs-testing declared as `implementation` is FLAGGED",
            i, 1, "must only be reachable via",
        )

        # CONTROL: openbank-libs-testing declared correctly (testImplementation) is fine.
        j = root / "j"
        put(j, "openbank-v-service/build.gradle.kts", """
dependencies {
    implementation(project(":openbank-libs-domain"))
    testImplementation(project(":openbank-libs-testing"))
}
""")
        expect("openbank-libs-testing declared as testImplementation is NOT flagged", j, 0)

        # CONTROL: openbank-libs-testing's OWN build.gradle.kts is exempt from the build-file
        # check (it declares its own dependencies, not a dependency on itself).
        k = root / "k"
        put(k, "openbank-libs-testing/build.gradle.kts", """
dependencies {
    api(project(":openbank-libs-domain"))
}
""")
        expect("openbank-libs-testing's own build.gradle.kts is exempt", k, 0)

    if fails:
        print(f"::error::self-test FAILED ({fails} case(s))")
        return 1
    print("self-test ok: check-no-allow-all-pdp-in-main is falsifiable (11 cases)")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    root = pathlib.Path(argv[0]) if argv else pathlib.Path(".")
    return scan(root)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
