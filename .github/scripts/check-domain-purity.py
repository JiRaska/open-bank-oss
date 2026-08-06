#!/usr/bin/env python3
"""Domain-purity gate (ADR-0002 / rules.yaml architecture.domain_zero_framework_imports).

The hexagonal invariant "the domain layer has zero framework imports" was documented and
repeated in every PR template, but nothing enforced it -- and the fleet had quietly
accumulated violations (Quarkus @RegisterForReflection on a domain type, Jackson JsonNode
in domain models). This gate scans the domain layer for framework references.

WHY THIS REPLACED check-domain-purity.sh (#3670)
------------------------------------------------
The bash version had two independent blind spots, and #3670 landed in the intersection of
both -- `@Authorize` and `@FeatureFlag`, the fleet's most-used annotations, were declared
in openbank-libs-domain as `jakarta.interceptor.InterceptorBinding` (CDI) and the gate was
green about it every run:

  1. SCOPE was a PACKAGE-PATH segment, not a module.  `find ... -path "*/domain/*"` needs a
     literal `/domain/` in the path.  openbank-libs-domain's own sources live under
     `com/openbank/libs/authz/`, `.../flags/`, `.../persistence/outbox/` -- no such segment
     -- so 80 of the module's 107 main sources (75%) were never opened.  The module IS the
     domain side of the ADR-0122 split; its purity does not depend on whether an individual
     package happens to be spelled "domain".  Now scoped by MODULE for libs-domain, and by
     package path for the per-service trees (where `domain/` is the layer marker).

  2. DETECTION was `^import <prefix>`, and a FULLY-QUALIFIED reference has no import line.
     `@jakarta.interceptor.InterceptorBinding` written inline is invisible to an import
     grep, as is `@get:jakarta.enterprise.util.Nonbinding`.  Both offending annotations used
     exactly that form.  This is not libs-domain-specific: the same evasion works in every
     per-service `domain/` package the gate already scanned.  Now both forms are matched, on
     comment-stripped source so prose about a framework type is not a violation.

`javax.*` was also narrowed from the blanket prefix to the legacy Jakarta-EE subpackages.
The blanket form would have manufactured 16 violations for `javax.crypto` and `javax.xml`,
which are JDK, not framework -- baselining those would have made the ratchet a lie.

BASELINE-RATCHETED (the detekt-baseline pattern): pre-existing violations live in
domain-purity-baseline.txt next to this script and do not fail the build; any NEW violation
does.  Burn the baseline down as files are touched -- never add to it (adding an entry needs
the PR to justify why the domain type cannot stay framework-free).  A baseline entry whose
violation no longer occurs is reported as stale, so a fixed file cannot regress silently.

Module exemption: openbank-libs-runtime shares the com.openbank.libs.domain.* PACKAGE name
for its JPA attribute converters, but it IS the framework side of the split (ADR-0122) --
the layering rule is about the module, not the package string.

Usage:  check-domain-purity.py [repo-root]
        check-domain-purity.py --self-test
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
BASELINE = SCRIPT_DIR / "domain-purity-baseline.txt"

# Framework/library packages that must never appear in the domain layer.
# java.*/kotlin.*/kotlinx.coroutines are fine; serialization, DI, persistence, transport and
# runtime frameworks are not.  `javax.` is deliberately NOT blanket -- javax.crypto and
# javax.xml are JDK.  Only the legacy (pre-Jakarta-rename) EE packages count.
FRAMEWORK_PREFIXES = (
    "jakarta.",
    "javax.annotation.",
    "javax.ejb.",
    "javax.enterprise.",
    "javax.inject.",
    "javax.interceptor.",
    "javax.jms.",
    "javax.json.",
    "javax.persistence.",
    "javax.servlet.",
    "javax.transaction.",
    "javax.validation.",
    "javax.ws.",
    "io.quarkus.",
    "org.eclipse.microprofile.",
    "org.hibernate.",
    "io.smallrye.",
    "org.jboss.",
    "com.fasterxml.jackson.",
    "org.apache.kafka.",
    "io.vertx.",
    "org.flywaydb.",
    "org.springframework.",
    "io.micrometer.",
    "io.opentelemetry.",
)

# A package boundary is required on both sides: `io.quarkus.` must not match inside
# `com.acme.io.quarkus.x` (left) nor be satisfied by `io.quarkusish` (right -- the trailing
# dot in each prefix already handles that).  The left guard rejects an identifier char or a
# dot immediately before the prefix.
_PREFIX_ALT = "|".join(re.escape(p) for p in FRAMEWORK_PREFIXES)
FQ_REF = re.compile(rf"(?<![A-Za-z0-9_.])({_PREFIX_ALT})")
IMPORT_LINE = re.compile(rf"^\s*import\s+({_PREFIX_ALT})")

# The module that IS the domain side of the ADR-0122 split: every source in it is domain.
DOMAIN_MODULE = "openbank-libs-domain"
# The framework side of the same split; exempt even though its packages say "domain".
EXEMPT_MODULE = "openbank-libs-runtime"


def strip_kotlin_noise(src: str) -> str:
    """Blank out comments and string literals, preserving line structure.

    Comments must go or the gate flags the very prose that explains a framework type (the
    repo has been bitten by exactly that, twice).  String literals must go or a rego/config
    key written as a string reads as an import.  Kotlin block comments NEST, so the depth
    counter is not optional -- a KDoc containing `/*` would otherwise close early and leak
    the rest of the file back into the scan.
    """
    out: list[str] = []
    i, n = 0, len(src)
    depth = 0
    while i < n:
        ch = src[i]
        if depth > 0:
            if src.startswith("/*", i):
                depth += 1
                out.append("  ")
                i += 2
                continue
            if src.startswith("*/", i):
                depth -= 1
                out.append("  ")
                i += 2
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
            continue
        if src.startswith("/*", i):
            depth = 1
            out.append("  ")
            i += 2
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append("".join("\n" if c == "\n" else " " for c in src[i:j]))
            i = j
            continue
        if ch == '"':
            j = i + 1
            while j < n and src[j] != '"' and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(" " * (j - i))
            i = j
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def in_scope(rel: Path) -> bool:
    parts = rel.parts
    if not parts or not parts[0].startswith("openbank-"):
        return False
    if parts[0] == EXEMPT_MODULE:
        return False
    if rel.suffix != ".kt":
        return False
    if "src/main/kotlin" not in rel.as_posix():
        return False
    # Hole #1: the whole libs-domain module is the domain layer, regardless of package name.
    if parts[0] == DOMAIN_MODULE:
        return True
    return "domain" in parts


def scan(root: Path) -> list[str]:
    violations: list[str] = []
    for module in sorted(root.glob("openbank-*")):
        src_root = module / "src" / "main" / "kotlin"
        if not src_root.is_dir():
            continue
        for path in sorted(src_root.rglob("*.kt")):
            rel = path.relative_to(root)
            if not in_scope(rel):
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            clean = strip_kotlin_noise(text)
            for raw, stripped in zip(text.splitlines(), clean.splitlines()):
                if not FQ_REF.search(stripped):
                    continue
                # Report the ORIGINAL line so a baseline entry stays human-readable and the
                # existing `path:import com.x.Y` entries keep matching byte for byte.
                violations.append(f"{rel.as_posix()}:{raw.strip()}")
    return violations


def read_baseline() -> list[str]:
    if not BASELINE.is_file():
        return []
    entries = []
    for line in BASELINE.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if s and not s.startswith("#"):
            entries.append(s)
    return entries


def run(root: Path) -> int:
    violations = scan(root)
    baseline = read_baseline()
    baseline_set = set(baseline)

    fail = False
    new_count = 0
    for v in violations:
        if v in baseline_set:
            continue
        print(f"::error::domain-purity: NEW framework reference in the domain layer (ADR-0002): {v}")
        fail = True
        new_count += 1

    violation_set = set(violations)
    stale = False
    for b in baseline:
        if b not in violation_set:
            print(
                "::error::domain-purity: baseline entry no longer occurs — delete it from "
                f"domain-purity-baseline.txt (ratchet-only): {b}"
            )
            stale = True

    print(
        f"check-domain-purity: {len(violations)} violation(s) found, {new_count} new, "
        f"{len(baseline)} baseline entr(y/ies)."
    )
    return 1 if (fail or stale) else 0


# --------------------------------------------------------------------------------------
# Self-test: every case below is a case the gate MUST classify correctly.  The two marked
# `#3670` are the exact shapes the bash gate reported clean on.
# --------------------------------------------------------------------------------------
_MUST_FLAG = [
    ("import form (what the old gate caught)", "import com.fasterxml.jackson.databind.JsonNode"),
    ("#3670 fully-qualified annotation", "@jakarta.interceptor.InterceptorBinding"),
    ("#3670 use-site-target annotation", "    @get:jakarta.enterprise.util.Nonbinding val action: String,"),
    ("fully-qualified type in a signature", "fun f(): io.vertx.core.Future<Unit>? = null"),
    ("legacy EE javax package", "import javax.inject.Inject"),
]
_MUST_NOT_FLAG = [
    ("prose naming the framework in a line comment", "// the jakarta.interceptor binding lives in libs-runtime"),
    ("prose in a KDoc block", "/**\n * See io.quarkus.runtime.Startup for why this is not here.\n */\nval x = 1"),
    ("nested block comment must not close early", "/* outer /* inner */ still comment: org.hibernate.Session */\nval y = 2"),
    ("a string literal is data, not a reference", 'val key = "com.fasterxml.jackson.databind.JsonNode"'),
    ("JDK javax.crypto is not a framework", "import javax.crypto.Mac"),
    ("JDK javax.xml is not a framework", "import javax.xml.parsers.DocumentBuilderFactory"),
    ("package boundary on the left", "import com.acme.io.quarkus.NotQuarkus"),
    ("package boundary on the right", "import io.quarkusish.Thing"),
]

_SCOPE_IN = [
    "openbank-libs-domain/src/main/kotlin/com/openbank/libs/authz/Authorize.kt",  # #3670
    "openbank-libs-domain/src/main/kotlin/com/openbank/libs/domain/money/Money.kt",
    "openbank-ledger-service/src/main/kotlin/com/openbank/ledger/domain/Entry.kt",
]
_SCOPE_OUT = [
    "openbank-libs-runtime/src/main/kotlin/com/openbank/libs/domain/conv/MoneyConverter.kt",
    "openbank-ledger-service/src/main/kotlin/com/openbank/ledger/infrastructure/rest/X.kt",
    "openbank-ledger-service/src/test/kotlin/com/openbank/ledger/domain/EntryTest.kt",
]


def self_test() -> int:
    failures = 0

    def hit(snippet: str) -> bool:
        clean = strip_kotlin_noise(snippet)
        return any(FQ_REF.search(line) for line in clean.splitlines())

    for label, snippet in _MUST_FLAG:
        if not hit(snippet):
            print(f"SELF-TEST FAIL (false negative) [{label}]: {snippet!r}")
            failures += 1
    for label, snippet in _MUST_NOT_FLAG:
        if hit(snippet):
            print(f"SELF-TEST FAIL (false positive) [{label}]: {snippet!r}")
            failures += 1
    for p in _SCOPE_IN:
        if not in_scope(Path(p)):
            print(f"SELF-TEST FAIL (scope too narrow): {p}")
            failures += 1
    for p in _SCOPE_OUT:
        if in_scope(Path(p)):
            print(f"SELF-TEST FAIL (scope too wide): {p}")
            failures += 1

    # The import form must still be recognised as an import, so the pre-#3670 baseline
    # entries keep matching without being rewritten.
    if not IMPORT_LINE.match("import io.quarkus.runtime.annotations.RegisterForReflection"):
        print("SELF-TEST FAIL: IMPORT_LINE no longer matches the legacy baseline shape")
        failures += 1

    total = len(_MUST_FLAG) + len(_MUST_NOT_FLAG) + len(_SCOPE_IN) + len(_SCOPE_OUT) + 1
    print(f"check-domain-purity --self-test: {total - failures}/{total} cases correct.")
    return 1 if failures else 0


def main() -> int:
    args = sys.argv[1:]
    if args and args[0] == "--self-test":
        return self_test()
    return run(Path(args[0] if args else ".").resolve())


if __name__ == "__main__":
    sys.exit(main())
