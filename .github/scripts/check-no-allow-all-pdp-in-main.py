#!/usr/bin/env python3
"""Guard: no `AuthzDecision` construction under any service's `src/main` may resolve to
`allow = true` unconditionally (rules.yaml: authz_policy).

WHY THIS EXISTS: `AllowAllPolicyDecisionPoint` — the fixture every `@Authorize`-decorated
unit test wires in place of the real OPA sidecar — used to live in
`openbank-libs-domain/src/main/kotlin/com/openbank/libs/authz/`. That put a class whose
whole job is to allow every request, unconditionally, on the same `src/main` classpath every
production service builds against. Nothing stopped a service's OWN production
`AuthzProducer` from `@Produces`-ing it by mistake (a copy-paste from a test profile, a
stale `@Alternative` left enabled) — the compiler cannot tell "wired for tests" from "wired
for prod" once the class lives in `src/main`. The class has since moved to
`openbank-libs-testing` (test-scope only, never on a service's production classpath — see
`PolicyDecisionPoint.kt`'s kdoc), and this guard checks for the two ways it (or something
shaped like it) could reappear: an `AuthzDecision` construction under `src/main` whose
`allow` value can only ever be `true`, or `openbank-libs-testing` reachable from a service's
production classpath.

WHAT IT CHECKS (round 2, redesigned after two review rounds found bypasses in round 1):
  1. Every construction of `AuthzDecision(...)` under `<service>/src/main/kotlin/**/*.kt`
     (and `openbank-libs-domain`, `openbank-libs-runtime`) — a call to the class by its own
     name OR by an `import ... AuthzDecision as X` alias — whose `allow` argument is the
     literal `true` or the constant-foldable `!false`, in either named (`allow = true`,
     `allow = !false`, any argument position) or positional (`AuthzDecision(true, ...)`,
     `allow` being the first constructor parameter) form. This is checked at EVERY call
     site — a top-level `val`, a companion-object `val`, a class property, or a function
     body — not only inside a function whose own signature returns `AuthzDecision`, because
     a hoisted constant (`private val ALLOW = AuthzDecision(allow = true, ...)`) referenced
     later by a trivial `return ALLOW` is exactly as much an allow-all PDP as the inline
     form.

     A flagged construction is excused ONLY when that SAME call is itself inside a
     conditional branch that decides it: the arm of an `if`/`else` (expression form,
     `if (cond) AuthzDecision(true) else …`, or block form, `if (cond) { AuthzDecision(true) }`)
     or a `when` arm (`cond -> AuthzDecision(true)` or `cond -> { AuthzDecision(true) }`).
     This is determined per call site, not per function: the guard walks outward from the
     call to find its innermost enclosing `{ }` block (if any) and checks whether that
     block's own opening brace — or the call itself, if there is no enclosing block — is
     immediately preceded by `if (...)`, `else`, or `->`. A branching keyword ANYWHERE ELSE
     in the same function (an unrelated `?:`, `&&`, a sibling `if` guarding a different
     value) does not excuse it — only a branch that actually wraps the flagged call does.
     This is deliberately narrower than "the enclosing function contains an `if` somewhere":
     that broader check is what let `val who = query.principal ?: "anon"; return
     AuthzDecision(allow = true, ...)` (an unrelated Elvis operator on an unrelated value)
     read as conditional when the return itself never branches.

  2. Every `build.gradle.kts` under a service module: a dependency on `:openbank-libs-testing`
     (by `project(":openbank-libs-testing")` or the Gradle type-safe accessor
     `projects.openbankLibsTesting`) may only be declared under a test configuration — one
     whose name starts with `test` (`testImplementation`, `testFixturesApi`,
     `testFixturesImplementation`, `testRuntimeOnly`, …) — never a production one
     (`implementation`, `api`, `compileOnly`, `runtimeOnly`, or any other name), which would
     put `openbank-libs-testing`'s `src/main` (fixtures shaped exactly like an allow-all PDP,
     on purpose) on that service's production classpath. All three ways Gradle can spell a
     configuration are checked: the bare identifier form (`implementation(...)`), the
     string-invoke form (`"implementation"(...)`), and `add("implementation", ...)`.
     `openbank-libs-testing`'s own `build.gradle.kts` is exempt (it declares its own
     dependencies, not a dependency on itself).

WHAT THIS DOES NOT CLAIM: this is a targeted syntactic check for the `AuthzDecision(...)`
constructor shape and the `openbank-libs-testing` dependency shape described above — not a
general guarantee that no allow-all authorization path can exist under any name or through
any other mechanism. It does not evaluate whether a `when` branch's own condition is itself
vacuously true (e.g. `when (true) { else -> AuthzDecision(allow = true) }`), and it does not
follow a value across files or through anything other than a same-file constant reference.

This intentionally does NOT flag `DenyAllPolicyDecisionPoint` (`allow = false`) — the
kill-switch alternative that is genuinely deployed to production (`rules.yaml: authz_policy`
documents it as such) — nor a call whose `allow` value is decided by the branch that
contains it.

stdlib-only (regex + brace/paren matching); no Kotlin parser dependency, matching the
fleet's other Kotlin-source guards (check-no-runblocking-in-scheduled.py,
check-nonnull-jaxrs-params.py).

ENFORCED. Usage:
  check-no-allow-all-pdp-in-main.py [root]        # scan (default root: .)
  check-no-allow-all-pdp-in-main.py --self-test    # falsify the guard itself

EXCEPTIONS: justified, reviewed exceptions live in
`.github/gates/allow-all-pdp-exceptions.txt`, one `path:line` per line (`#`-comments and
blank lines ignored). It is expected to stay EMPTY — a new entry needs a reviewed reason in
the same PR, same as any other gate's baseline file.
"""
from __future__ import annotations

import pathlib
import re
import sys

EXCLUDED_DIR_PARTS = {"build", "worktrees", ".git", "node_modules"}

# openbank-libs-testing is a test-fixture LIBRARY: every consumer pulls it in via
# `testImplementation(project(":openbank-libs-testing"))` (never `implementation`/`api` from
# a service's own src/main — checked against every current consumer's build.gradle.kts below),
# so a class under ITS OWN src/main never reaches any service's production classpath. Its whole
# purpose is fixtures shaped exactly like the thing this guard looks for
# (AllowAllPolicyDecisionPoint lives there on purpose), so it is out of scope by module name,
# not by directory shape.
EXCLUDED_MODULES = {"openbank-libs-testing"}

EXCEPTIONS_FILE = pathlib.Path(".github/gates/allow-all-pdp-exceptions.txt")

IMPORT_ALIAS_RE = re.compile(
    r"import\s+(?:[\w.]+\.)?AuthzDecision\s+as\s+(\w+)"
)

WHITESPACE = " \t\r\n"


def strip_comments_and_strings(src: str) -> str:
    """Best-effort: blank out //-comments, /* */ comments, and string/char literals so a
    match inside prose (the exact trap check-no-service-principal-type.sh's own self-test
    exists to cover) never counts as a hit. Not a full Kotlin lexer — good enough for this
    guard's narrow vocabulary, same tradeoff the sibling scripts make. Output is the same
    length as the input so every offset still points at the right line."""
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


def find_matching_close(src: str, open_idx: int, open_ch: str, close_ch: str) -> int:
    depth = 0
    i = open_idx
    n = len(src)
    while i < n:
        if src[i] == open_ch:
            depth += 1
        elif src[i] == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return n - 1


def find_enclosing_blocks(src: str) -> list[tuple[int, int]]:
    """Every `{ ... }` span in the file, as (start_of_open_brace, end_of_close_brace)."""
    spans: list[tuple[int, int]] = []
    stack: list[int] = []
    for i, ch in enumerate(src):
        if ch == "{":
            stack.append(i)
        elif ch == "}":
            if stack:
                start = stack.pop()
                spans.append((start, i))
    return spans


def innermost_enclosing_block(spans_sorted_desc: list[tuple[int, int]], pos: int) -> tuple[int, int] | None:
    for start, end in spans_sorted_desc:
        if start < pos < end:
            return (start, end)
    return None


def _skip_ws_back(text: str, idx: int) -> int:
    while idx > 0 and text[idx - 1] in WHITESPACE:
        idx -= 1
    return idx


def _word_before(text: str, idx: int) -> tuple[str, int]:
    """The identifier/keyword ending exactly at idx (after skipping trailing whitespace),
    plus the index just before that word starts."""
    end = _skip_ws_back(text, idx)
    start = end
    while start > 0 and (text[start - 1].isalnum() or text[start - 1] == "_"):
        start -= 1
    return text[start:end], start


def branch_precedes(text: str, idx: int) -> bool:
    """True if the token sequence immediately before `idx` (skipping whitespace) is the
    head of a branch that would govern whatever starts at `idx`: an `if (...)`  (optionally
    preceded by `else`), a bare `else`, or a `when`-arm `->`."""
    j = _skip_ws_back(text, idx)
    if j >= 2 and text[j - 2:j] == "->":
        return True
    word, word_start = _word_before(text, j)
    if word == "else":
        return True
    if j > 0 and text[j - 1] == ")":
        depth = 0
        k = j - 1
        while k >= 0:
            if text[k] == ")":
                depth += 1
            elif text[k] == "(":
                depth -= 1
                if depth == 0:
                    break
            k -= 1
        if depth == 0 and k >= 0:
            head_word, _ = _word_before(text, k)
            if head_word == "if":
                return True
    return False


def call_is_conditional(text: str, call_start: int, spans_sorted_desc: list[tuple[int, int]]) -> bool:
    """A call at `call_start` is excused only if a branch DIRECTLY governs it: either the
    call itself sits right after a branch head (the no-braces expression-arm form), or its
    innermost enclosing `{ }` block's own opening brace sits right after one (the
    block-arm form). A branching keyword anywhere else in the enclosing function does
    NOT count — that is precisely the laundering this guard closes."""
    if branch_precedes(text, call_start):
        return True
    block = innermost_enclosing_block(spans_sorted_desc, call_start)
    if block is not None and branch_precedes(text, block[0]):
        return True
    return False


def get_top_level_args(arg_text: str) -> list[str]:
    args: list[str] = []
    depth = 0
    current = []
    for ch in arg_text:
        if ch in "([{":
            depth += 1
            current.append(ch)
        elif ch in ")]}":
            depth -= 1
            current.append(ch)
        elif ch == "," and depth == 0:
            args.append("".join(current))
            current = []
        else:
            current.append(ch)
    if current:
        args.append("".join(current))
    return args


ALLOW_NAMED_RE = re.compile(r"^\s*allow\s*=\s*(true|!\s*false)\s*$")
ALLOW_POSITIONAL_RE = re.compile(r"^\s*(true|!\s*false)\s*$")


def call_allow_is_true(open_paren_idx: int, close_paren_idx: int, clean: str) -> bool:
    arg_text = clean[open_paren_idx + 1:close_paren_idx]
    for i, arg in enumerate(get_top_level_args(arg_text)):
        if ALLOW_NAMED_RE.match(arg):
            return True
        if i == 0 and "=" not in arg and ALLOW_POSITIONAL_RE.match(arg):
            return True
    return False


def load_exceptions(root: pathlib.Path) -> set[str]:
    path = root / EXCEPTIONS_FILE
    exceptions: set[str] = set()
    if not path.exists():
        return exceptions
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        exceptions.add(line)
    return exceptions


def find_violations(path: pathlib.Path, rel_path: pathlib.Path, raw: str, exceptions: set[str]) -> list[str]:
    clean = strip_comments_and_strings(raw)

    names = {"AuthzDecision"}
    for m in IMPORT_ALIAS_RE.finditer(clean):
        names.add(m.group(1))

    call_re = re.compile(r"\b(?:" + "|".join(re.escape(n) for n in names) + r")\s*\(")

    spans = find_enclosing_blocks(clean)
    spans_sorted_desc = sorted(spans, key=lambda s: -s[0])

    findings = []
    for m in call_re.finditer(clean):
        open_idx = clean.rfind("(", m.start(), m.end())
        if open_idx == -1:
            continue
        close_idx = find_matching_close(clean, open_idx, "(", ")")
        if not call_allow_is_true(open_idx, close_idx, clean):
            continue
        if call_is_conditional(clean, m.start(), spans_sorted_desc):
            continue

        line_no = raw.count("\n", 0, m.start()) + 1
        key = f"{rel_path}:{line_no}"
        if key in exceptions:
            continue
        call_text = clean[m.start():close_idx + 1]
        snippet = " ".join(call_text.split())[:100]
        findings.append(
            f"::error file={path}::{key}: `{snippet}` constructs an AuthzDecision whose "
            f"`allow` argument is unconditionally true (not inside any if/else/when branch "
            f"that decides it) — an allow-all PDP must live in openbank-libs-testing "
            f"(test scope), never under src/main (rules.yaml: authz_policy)."
        )
    return findings


def strip_comments_only(src: str) -> str:
    """Like strip_comments_and_strings but keeps string literals intact — the build-file
    check needs to read the quoted configuration/project path itself, not just detect that
    a string is present."""
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


TARGET_RE = re.compile(
    r'project\s*\(\s*["\']:openbank-libs-testing["\']\s*\)'
    r'|projects\s*\.\s*openbankLibsTesting\b'
)

# Form 1: a bare or string-invoked configuration immediately calling the target —
# `implementation(project(...))`, `"implementation"(project(...))`,
# `implementation(projects.openbankLibsTesting)`.
CONFIG_INVOKE_RE = re.compile(
    r'(?P<conf>"?\w+"?)\s*\(\s*(?:' + TARGET_RE.pattern + r')\s*\)'
)

# Form 2: `add("<conf>", <target>)`.
ADD_CALL_RE = re.compile(
    r'add\s*\(\s*["\'](?P<conf>\w+)["\']\s*,\s*(?:' + TARGET_RE.pattern + r')\s*\)'
)


def _is_test_scope(conf_raw: str) -> bool:
    conf = conf_raw.strip('"\'')
    return conf.lower().startswith("test")


def find_build_file_violations(path: pathlib.Path, rel_path: pathlib.Path, raw: str, exceptions: set[str]) -> list[str]:
    if path.parent.name == "openbank-libs-testing":
        return []
    clean = strip_comments_only(raw)
    findings = []
    seen_offsets: set[int] = set()

    for regex in (CONFIG_INVOKE_RE, ADD_CALL_RE):
        for m in regex.finditer(clean):
            if m.start() in seen_offsets:
                continue
            conf = m.group("conf")
            if _is_test_scope(conf):
                continue
            seen_offsets.add(m.start())
            line_no = raw.count("\n", 0, m.start()) + 1
            key = f"{rel_path}:{line_no}"
            if key in exceptions:
                continue
            findings.append(
                f"::error file={path}::{key}: `openbank-libs-testing` declared as "
                f"`{conf.strip(chr(34))}` — it is a test-fixture library "
                f"(AllowAllPolicyDecisionPoint and friends live in its src/main on purpose) "
                f"and must only be reachable via a test* configuration "
                f"(testImplementation/testFixturesApi/testFixturesImplementation/…), never "
                f"on a service's production classpath (rules.yaml: authz_policy)."
            )
    return findings


def iter_kotlin_main_files(root: pathlib.Path):
    for path in root.rglob("*.kt"):
        parts = set(path.parts)
        if parts & EXCLUDED_DIR_PARTS:
            continue
        if "src" not in path.parts or "main" not in path.parts:
            continue
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
    exceptions = load_exceptions(root)
    all_findings: list[str] = []
    subjects = 0
    for path in iter_kotlin_main_files(root):
        subjects += 1
        raw = path.read_text(encoding="utf-8", errors="replace")
        if "AuthzDecision" not in raw:
            continue
        rel_path = path.relative_to(root) if path.is_relative_to(root) else path
        all_findings.extend(find_violations(path, rel_path, raw, exceptions))

    build_subjects = 0
    for path in iter_build_files(root):
        build_subjects += 1
        raw = path.read_text(encoding="utf-8", errors="replace")
        if "libs-testing" not in raw and "LibsTesting" not in raw:
            continue
        rel_path = path.relative_to(root) if path.is_relative_to(root) else path
        all_findings.extend(find_build_file_violations(path, rel_path, raw, exceptions))

    print(f"SUBJECTS={subjects}  # build_files={build_subjects}")
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
        expect("unconditional allow-all under src/main is FLAGGED", a, 1, "unconditionally true")

        # THE FIX: the genuinely-conditional production PDP is NOT flagged (block-arm form).
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
        expect("conditional PDP (block-arm) is NOT flagged", b, 0)

        # Conditional PDP, expression-arm form (no braces).
        b2 = root / "b2"
        put(b2, "openbank-x-service/src/main/kotlin/com/openbank/x/authz/ExprArmPolicyDecisionPoint.kt", """
package com.openbank.x.authz
class ExprArmPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        if (query.isTrusted()) AuthzDecision(allow = true, reason = "trusted")
        else AuthzDecision(allow = false, reason = "untrusted")
}
""")
        expect("conditional PDP (expression-arm) is NOT flagged", b2, 0)

        # Conditional PDP, when-arm form.
        b3 = root / "b3"
        put(b3, "openbank-x-service/src/main/kotlin/com/openbank/x/authz/WhenArmPolicyDecisionPoint.kt", """
package com.openbank.x.authz
class WhenArmPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision = when {
        query.isTrusted() -> AuthzDecision(allow = true, reason = "trusted")
        else -> AuthzDecision(allow = false, reason = "untrusted")
    }
}
""")
        expect("conditional PDP (when-arm) is NOT flagged", b3, 0)

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

        # SAME SHAPE, but under src/test — out of this guard's scope.
        e = root / "e"
        put(e, "openbank-libs-testing/src/test/kotlin/com/openbank/libs/testing/authz/AllowAllPolicyDecisionPointTest.kt", """
package com.openbank.libs.testing.authz
class AllowAllPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = true, reason = "test-stub", policyVersion = "allow-all")
}
""")
        expect("the identical class under src/test is out of scope", e, 0)

        # SAME SHAPE under openbank-libs-testing's OWN src/main is deliberately out of scope.
        f = root / "f"
        put(f, "openbank-libs-testing/src/main/kotlin/com/openbank/libs/testing/authz/AllowAllPolicyDecisionPoint.kt", """
package com.openbank.libs.testing.authz
class AllowAllPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = true, reason = "test-stub", policyVersion = "allow-all")
}
""")
        expect("openbank-libs-testing's own src/main is excluded (test-fixture library)", f, 0)

        # BYPASS 1 (round-1 fix, kept green): positional constructor call, no `allow =`.
        g = root / "g"
        put(g, "openbank-y-service/src/main/kotlin/com/openbank/y/authz/PositionalAllowAll.kt", """
package com.openbank.y.authz
class PositionalAllowAll : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(true, "test-stub", "allow-all")
}
""")
        expect("positional AuthzDecision(true, ...) is FLAGGED", g, 1, "unconditionally true")

        # BYPASS 2 (round-1 fix, kept green): unrelated LATER function's branching must not
        # launder an earlier unconditional allow-all.
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
            h, 1, "unconditionally true",
        )

        # BYPASS 3 (round-1 fix, kept green): `openbank-libs-testing` as `implementation`.
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

        # CONTROL: openbank-libs-testing's OWN build.gradle.kts is exempt.
        k = root / "k"
        put(k, "openbank-libs-testing/build.gradle.kts", """
dependencies {
    api(project(":openbank-libs-domain"))
}
""")
        expect("openbank-libs-testing's own build.gradle.kts is exempt", k, 0)

        # --- round-2 bypasses (confirmed against the pre-fix script: each returned rc=0) ---

        # BYPASS a: hoisted constant. The construction happens once, unconditionally, at
        # the `val` site — a trivial `return ALLOW` afterward doesn't matter.
        l = root / "l"
        put(l, "openbank-x-service/src/main/kotlin/com/openbank/x/authz/HoistedConstantAllowAll.kt", """
package com.openbank.x.authz
class HoistedConstantAllowAll : PolicyDecisionPoint {
    private val ALLOW = AuthzDecision(allow = true, reason = "stub", policyVersion = "v1")
    override suspend fun allow(query: AuthzQuery): AuthzDecision = ALLOW
}
""")
        expect(
            "hoisted constant AuthzDecision(allow = true, ...) is FLAGGED at its val site",
            l, 1, "unconditionally true",
        )

        # BYPASS b: unrelated branch token (`?:` on a different value) must not launder a
        # plain, unconditional `return AuthzDecision(allow = true, ...)`.
        n = root / "n"
        put(n, "openbank-x-service/src/main/kotlin/com/openbank/x/authz/LaunderedByElvis.kt", """
package com.openbank.x.authz
class LaunderedByElvis : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision {
        val who = query.principal ?: "anon"
        return AuthzDecision(allow = true, reason = who, policyVersion = "v1")
    }
}
""")
        expect(
            "an unrelated ?: on a different value cannot launder an unconditional return",
            n, 1, "unconditionally true",
        )

        # BYPASS c: import alias.
        o = root / "o"
        put(o, "openbank-x-service/src/main/kotlin/com/openbank/x/authz/AliasAllowAll.kt", """
package com.openbank.x.authz
import com.openbank.libs.authz.AuthzDecision as D
class AliasAllowAll : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        D(true, "stub", "v1")
}
""")
        expect("an import-aliased AuthzDecision constructor call is FLAGGED", o, 1, "unconditionally true")

        # BYPASS c: negation (!false is constant-foldable true).
        p = root / "p"
        put(p, "openbank-x-service/src/main/kotlin/com/openbank/x/authz/NegationAllowAll.kt", """
package com.openbank.x.authz
class NegationAllowAll : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = !false, reason = "stub", policyVersion = "v1")
}
""")
        expect("AuthzDecision(allow = !false, ...) is FLAGGED", p, 1, "unconditionally true")

        # BYPASS d: build files — add("<conf>", project(...)).
        q = root / "q"
        put(q, "openbank-w-service/build.gradle.kts", """
dependencies {
    add("api", project(":openbank-libs-testing"))
}
""")
        expect("add(\"api\", project(...)) form is FLAGGED", q, 1, "must only be reachable via")

        # BYPASS d: build files — string-invoked configuration.
        r = root / "r"
        put(r, "openbank-w-service/build.gradle.kts", """
dependencies {
    "implementation"(project(":openbank-libs-testing"))
}
""")
        expect("string-invoked \"implementation\"(...) form is FLAGGED", r, 1, "must only be reachable via")

        # BYPASS d: build files — Gradle type-safe project accessor.
        s = root / "s"
        put(s, "openbank-w-service/build.gradle.kts", """
dependencies {
    implementation(projects.openbankLibsTesting)
}
""")
        expect("projects.openbankLibsTesting type-safe accessor form is FLAGGED", s, 1, "must only be reachable via")

        # CONTROL: all three round-2 build-file forms are fine under a test configuration.
        t = root / "t"
        put(t, "openbank-w-service/build.gradle.kts", """
dependencies {
    add("testImplementation", project(":openbank-libs-testing"))
    "testFixturesApi"(project(":openbank-libs-testing"))
    testImplementation(projects.openbankLibsTesting)
}
""")
        expect("all three forms under a test* configuration are NOT flagged", t, 0)

        # CONTROL: an explicit, reviewed exception suppresses one specific finding.
        u = root / "u"
        put(u, "openbank-x-service/src/main/kotlin/com/openbank/x/authz/ExceptedAllowAll.kt", """
package com.openbank.x.authz
class ExceptedAllowAll : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = true, reason = "reviewed-exception", policyVersion = "v1")
}
""")
        exc_path = u / ".github" / "gates" / "allow-all-pdp-exceptions.txt"
        exc_path.parent.mkdir(parents=True, exist_ok=True)
        exc_path.write_text(
            "openbank-x-service/src/main/kotlin/com/openbank/x/authz/ExceptedAllowAll.kt:5\n",
            encoding="utf-8",
        )
        expect("a listed path:line exception suppresses that one finding", u, 0)

    if fails:
        print(f"::error::self-test FAILED ({fails} case(s))")
        return 1
    print("self-test ok: check-no-allow-all-pdp-in-main is falsifiable (22 cases)")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    root = pathlib.Path(argv[0]) if argv else pathlib.Path(".")
    return scan(root)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
