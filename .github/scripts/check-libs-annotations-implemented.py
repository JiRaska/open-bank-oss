#!/usr/bin/env python3
"""Annotations openbank-libs publishes must DO something, and prose must not name one that
does not exist.

Why this exists (#4011). `openbank-libs-domain` shipped three annotations — `@Idempotent`,
`@Audited`, `@MaskSensitive` — that were plain `@Retention(RUNTIME)` markers: no
`@InterceptorBinding`, no interceptor, zero usages. Applying one to an endpoint compiled,
reviewed as correct, and did nothing at runtime. On a payment endpoint `@Idempotent` would
have let a duplicate POST through while the source read as protected. Worse, two of them were
recorded as SHIPPED CONTROLS — ADR-0014 Phase 3 "Shipped: PiiMask/@MaskSensitive", and a
STRIDE Repudiation mitigation in docs/threat-models/onboarding-service.md — so an
unimplemented idea had become evidence someone would cite.

Two rules, because the defect has two halves and each needs its own falsification:

RULE A (inertness). Every `annotation class` declared under an `openbank-libs-*` module's
`src/main` must either
  (a) carry `@InterceptorBinding` AND have an interceptor class that applies it, or
  (b) be applied at least once in some module's `src/main` outside its own declaring file.
An annotation that is neither bound nor used is inert by construction.

RULE B (prose truth). A markdown file in the documentation trees this repo publishes as
governance evidence (`docs/adr`, `docs/threat-models`, `openbank-libs/docs`) may not name an
`@Annotation` that does not exist anywhere in the codebase. The set of annotations that DO
exist is derived, not hand-kept: a name is real if some Kotlin source declares
`annotation class <Name>` or imports `<Name>`. So deleting an inert annotation without
correcting the documents that advertise it turns this rule red — which is precisely the
failure that made #4011 dangerous rather than merely untidy.

CODE-ABOUT-CODE, decided before the first run. A guard over source text will flag the very
prose that explains the defect it exists to catch — this repo has been bitten three times.
The precedence here: naming a non-existent annotation is permitted when the surrounding
paragraph CITES AN ISSUE (`#1234`). A document that says "`@Audited` was inert and is gone
(#4011)" is the correction; a document that says "`@Audited` audits the detail endpoint" is
the defect. Requiring the citation means the author has to point at the record, and the rule
is derivable rather than a list of blessed sentences.

Prose legitimately names annotations this codebase deliberately did not adopt (ADR-0016
rejects `@RunOnVirtualThread`; a residual-risk column may name the control that is still
open). Those sit in PROSE_ALLOWLIST with a reason each — the same shape
`check-accounting-clock.py` uses — and the script FAILS on a STALE entry in either
direction, so an exemption and its removal move together and the list cannot quietly become
permanent.

No network, no cluster; pure source check. `--self-test` feeds both rules an input each MUST
flag and an input each must NOT.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import gatelib

LIBS_GLOB = "openbank-libs*"

# Markdown trees whose statements are cited as governance evidence (ADR-0029/0030).
PROSE_DIRS = ("docs/adr", "docs/threat-models", "openbank-libs/docs")

# (path, annotation name) -> why naming a non-existent annotation is CORRECT there.
# Every entry is prose ABOUT something this repo deliberately did not build. A stale entry —
# the name became real, or the document stopped naming it — fails the gate.
PROSE_ALLOWLIST: dict[tuple[str, str], str] = {
    ("docs/adr/0016-virtual-threads-not-adopted-yet.md", "RunOnVirtualThread"):
        "the ADR's whole subject is the decision NOT to adopt it; its absence is the outcome",
    ("docs/adr/0016-virtual-threads-not-adopted-yet.md", "Transactional"):
        "contrasted against the reactive stack that ADR chose; imperative JTA is not used here",
    ("docs/adr/0048-decouple-api-contract-version-from-service-release-version.md",
     "ApplicationPath"):
        "illustrative JAX-RS snippet showing where a version prefix would live",
    ("docs/adr/0077-observability-three-pillar-strategy.md", "RegisterMetrics"):
        "an illustrative annotation for a convenience layer the ADR did not deliver",
    ("docs/adr/0106-postgresql-18-uuidv7-and-platform-feature-adoption.md", "Deprecated"):
        "kotlin stdlib builtin — real, but needs no import, so source derivation cannot see it",
    ("docs/adr/0034-unified-opa-authz-mcp-and-rest.md", "Target"):
        "kotlin stdlib builtin, quoted inside @Authorize's real declaration; needs no import",
    ("docs/adr/0124-oss-readiness-and-public-launch-hardening.md", "JiRaska"):
        "a GitHub handle, not an annotation",
    ("docs/threat-models/openbank-dispute-service.md", "RateLimited"):
        "named in the residual-risk column as the control that is still OPEN, not as a mitigation",
}
# The libs docs show adopters the MicroProfile REST Client call-site shape for
# BearerTokenClientHeadersFactory. The annotation is third-party and no service imports it
# yet, so source derivation cannot see it; the docs are not claiming libs implements it.
for _f in ("01-overview", "02-architecture", "03-api"):
    for _s in ("", ".en", ".cs"):
        PROSE_ALLOWLIST[(f"openbank-libs/docs/{_f}{_s}.md", "RegisterClientHeaders")] = (
            "MicroProfile REST Client annotation shown as the adopter's call-site shape"
        )
# Same shape for the JPA converter sample: jakarta.persistence.@Convert is applied by adopters,
# and this repo's own converters set autoApply, so no source here imports the name.
for _s in ("", ".en", ".cs"):
    PROSE_ALLOWLIST[(f"openbank-libs/docs/03-api{_s}.md", "Convert")] = (
        "jakarta.persistence annotation in an adopter-facing entity sample"
    )

ANNOTATION_DECL = re.compile(r"^\s*annotation\s+class\s+([A-Za-z_][A-Za-z0-9_]*)", re.M)
INTERCEPTOR_BINDING = re.compile(r"@InterceptorBinding\b")
IMPORT_TAIL = re.compile(r"^\s*import\s+(?:[\w.]+\.)?([A-Z][A-Za-z0-9_]*)", re.M)
# An annotation counts as a documentation CLAIM when it is written as code — inside backticks,
# or inside a fenced block (a code sample or a mermaid node). A bare @Foo in running prose is
# too often an email fragment or a handle, so it is not matched.
PROSE_ANNOTATION = re.compile(r"`@([A-Z][A-Za-z0-9_]*)")
FENCED_ANNOTATION = re.compile(r"(?<![\w`])@([A-Z][A-Za-z0-9_]*)")
FENCE = re.compile(r"^\s*```")
ISSUE_REF = re.compile(r"#\d{2,}")


def paragraph_of(lines: list[str]) -> list[str]:
    """For each line index, the text of the blank-line-delimited block it belongs to.

    A correction spans several lines (a bullet, a table row, a code sample and its comment),
    so the issue citation that licenses naming an absent annotation is looked for across the
    whole block rather than on the one line.
    """
    out: list[str] = [""] * len(lines)
    start = 0
    for i in range(len(lines) + 1):
        if i == len(lines) or not lines[i].strip():
            block = "\n".join(lines[start:i])
            for j in range(start, i):
                out[j] = block
            start = i + 1
    return out

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")


def strip_comments(src: str) -> str:
    """Kotlin block comments NEST, so a naive non-greedy strip closes early. Walk them."""
    out, i, depth, n = [], 0, 0, len(src)
    while i < n:
        if src.startswith("/*", i):
            depth += 1
            i += 2
        elif src.startswith("*/", i) and depth:
            depth -= 1
            i += 2
        elif depth:
            i += 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def kotlin_sources(root: Path) -> list[Path]:
    return [
        p
        for p in gatelib.rglob(root, "*.kt")
        if "/build/" not in str(p) and "/.git/" not in str(p)
    ]


def declared_annotations(root: Path) -> dict[str, Path]:
    """name -> declaring file, for annotations declared in an openbank-libs-* module's src/main."""
    found: dict[str, Path] = {}
    for module in sorted(root.glob(LIBS_GLOB)):
        main = module / "src" / "main"
        if not main.is_dir():
            continue
        for path in kotlin_sources(main):
            text = strip_comments(gatelib.read_text(path, errors="replace"))
            for name in ANNOTATION_DECL.findall(text):
                found[name] = path
    return found


def is_interceptor_binding(path: Path, name: str) -> bool:
    """True when the declaration of `name` in `path` is preceded by @InterceptorBinding."""
    text = strip_comments(gatelib.read_text(path, errors="replace"))
    m = re.search(rf"^\s*annotation\s+class\s+{re.escape(name)}\b", text, re.M)
    if not m:
        return False
    # the annotation block sits between the previous blank line and the declaration
    head = text[: m.start()]
    block = head.rsplit("\n\n", 1)[-1]
    return bool(INTERCEPTOR_BINDING.search(block))


def usages(root: Path, name: str, declaring: Path) -> list[Path]:
    """Files under any module's src/main (other than the declaring file) that APPLY @name."""
    applied = re.compile(rf"@{re.escape(name)}\s*[(\s@\n]")
    hits: list[Path] = []
    for module in gatelib.glob(root, "openbank-*"):
        main = module / "src" / "main"
        if not main.is_dir():
            continue
        for path in kotlin_sources(main):
            if path.resolve() == declaring.resolve():
                continue
            text = strip_comments(gatelib.read_text(path, errors="replace"))
            if applied.search(text):
                hits.append(path)
    return hits


def has_interceptor(root: Path, name: str, declaring: Path) -> bool:
    """An interceptor is a class that both is @Interceptor and carries the binding."""
    for module in sorted(root.glob(LIBS_GLOB)):
        main = module / "src" / "main"
        if not main.is_dir():
            continue
        for path in kotlin_sources(main):
            if path.resolve() == declaring.resolve():
                continue
            text = strip_comments(gatelib.read_text(path, errors="replace"))
            if "@Interceptor" in text and re.search(rf"@{re.escape(name)}\s*\(", text):
                return True
    return False


def real_annotation_names(root: Path) -> set[str]:
    """Every annotation name this codebase actually knows: declared here, or imported.

    Derived from the sources so the set cannot drift from a hand-kept allowlist.
    """
    names: set[str] = set()
    for module in sorted(root.iterdir()):
        if not module.is_dir() or module.name.startswith("."):
            continue
        for path in kotlin_sources(module):
            text = gatelib.read_text(path, errors="replace")
            names.update(ANNOTATION_DECL.findall(text))
            names.update(IMPORT_TAIL.findall(text))
    return names


def check_rule_a(root: Path) -> list[str]:
    problems = []
    for name, declaring in sorted(declared_annotations(root).items()):
        if is_interceptor_binding(declaring, name) and has_interceptor(root, name, declaring):
            continue
        if usages(root, name, declaring):
            continue
        rel = declaring.relative_to(root)
        problems.append(
            f"{rel}: @{name} is INERT — no @InterceptorBinding+interceptor, and zero usages "
            f"in any src/main. Applying it would compile and do nothing (#4011). "
            f"Implement it (binding + interceptor in the same change) or delete it."
        )
    return problems


def check_rule_b(
    root: Path,
    real: set[str] | None = None,
    allowlist: dict[tuple[str, str], str] | None = None,
) -> list[str]:
    real = real if real is not None else real_annotation_names(root)
    allowlist = PROSE_ALLOWLIST if allowlist is None else allowlist
    problems: list[str] = []
    exercised: set[tuple[str, str]] = set()

    for rel_dir in PROSE_DIRS:
        base = root / rel_dir
        if not base.is_dir():
            continue
        for path in gatelib.rglob(base, "*.md"):
            rel = str(path.relative_to(root))
            text = gatelib.read_text(path, errors="replace")
            lines = text.splitlines()
            paragraphs = paragraph_of(lines)
            in_fence = False
            for lineno, line in enumerate(lines, 1):
                if FENCE.match(line):
                    in_fence = not in_fence
                    continue
                pattern = FENCED_ANNOTATION if in_fence else PROSE_ANNOTATION
                for name in dict.fromkeys(pattern.findall(line)):
                    if name in real:
                        continue
                    if (rel, name) in allowlist:
                        exercised.add((rel, name))
                        continue
                    if ISSUE_REF.search(paragraphs[lineno - 1]):
                        # code-about-code: prose explaining an absent annotation, with a
                        # pointer to the record that decided it. See the module docstring.
                        continue
                    problems.append(
                        f"{rel}:{lineno}: names `@{name}`, which is not declared or imported "
                        f"anywhere in this codebase — a document advertising a control that "
                        f"does not exist (#4011). Correct the document, implement the "
                        f"annotation, or add a PROSE_ALLOWLIST entry with a reason."
                    )

    for key, reason in sorted(allowlist.items()):
        if key in exercised:
            continue
        rel, name = key
        why = "the name is now real" if name in real else "the document no longer names it"
        problems.append(
            f"STALE PROSE_ALLOWLIST entry ({rel}, @{name}) — {why}, so the exemption "
            f"(\"{reason}\") no longer describes anything. Remove it."
        )
    return problems


def self_test() -> int:
    """Feed each rule an input it MUST flag and one it must NOT. A gate that has only ever
    passed is unfalsified."""
    import tempfile

    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        libs = root / "openbank-libs-domain" / "src" / "main" / "kotlin"
        rt = root / "openbank-libs-runtime" / "src" / "main" / "kotlin"
        svc = root / "openbank-demo-service" / "src" / "main" / "kotlin"
        for d in (libs, rt, svc):
            d.mkdir(parents=True)

        # (1) inert annotation — MUST be flagged
        (libs / "Inert.kt").write_text("annotation class Inert(val x: String = \"\")\n")
        # (2) bound annotation with an interceptor — must NOT be flagged
        (rt / "Bound.kt").write_text("@InterceptorBinding\nannotation class Bound\n")
        (rt / "BoundInterceptor.kt").write_text("@Bound()\n@Interceptor\nclass BoundInterceptor\n")
        # (3) unbound but USED annotation — must NOT be flagged
        (libs / "Used.kt").write_text("annotation class Used\n")
        (svc / "Res.kt").write_text("class Res {\n    @Used\n    fun f() {}\n}\n")

        a = check_rule_a(root)
        flagged = {p.split("@")[1].split(" ")[0] for p in a}
        if "Inert" not in flagged:
            print("SELF-TEST FAIL: rule A did not flag the inert annotation")
            ok = False
        for good in ("Bound", "Used"):
            if good in flagged:
                print(f"SELF-TEST FAIL: rule A flagged @{good}, which is implemented/used")
                ok = False

        # rule B: a doc naming a non-existent annotation MUST be flagged; a real one must not.
        adr = root / "docs" / "adr"
        adr.mkdir(parents=True)
        (adr / "0001-x.md").write_text(
            "- Shipped: `@Ghost` for masking\n- Also `@Used` which is real\n"
        )
        b = check_rule_b(root, allowlist={})
        if not any("@Ghost" in p for p in b):
            print("SELF-TEST FAIL: rule B did not flag a doc naming a non-existent annotation")
            ok = False
        if any("@Used`" in p for p in b):
            print("SELF-TEST FAIL: rule B flagged a doc naming a real annotation")
            ok = False

        # an allowlisted phantom must NOT be flagged ...
        allowed = {("docs/adr/0001-x.md", "Ghost"): "deliberately not built"}
        if any("@Ghost" in p for p in check_rule_b(root, allowlist=allowed)):
            print("SELF-TEST FAIL: rule B flagged an allowlisted phantom")
            ok = False
        # ... and an allowlist entry nothing exercises MUST be flagged as stale.
        stale = {("docs/adr/0001-x.md", "Vanished"): "was here once"}
        if not any("STALE" in p for p in check_rule_b(root, allowlist=stale)):
            print("SELF-TEST FAIL: rule B did not flag a stale allowlist entry")
            ok = False

        # code-about-code precedence: a paragraph explaining the absence and citing the
        # record is a correction, not a claim — and it must survive across lines.
        (adr / "0002-y.md").write_text(
            "- `@Phantom` was inert and has been removed;\n  see the record for why (#4011).\n"
        )
        if any("@Phantom" in p for p in check_rule_b(root, allowlist={})):
            print("SELF-TEST FAIL: rule B flagged a correction that cites its issue")
            ok = False
        # the same sentence WITHOUT the citation is the defect, and must be flagged
        (adr / "0003-z.md").write_text("- `@Phantom` masks the field for you.\n")
        if not any("0003-z.md" in p and "@Phantom" in p
                   for p in check_rule_b(root, allowlist={})):
            print("SELF-TEST FAIL: rule B did not flag an uncited phantom claim")
            ok = False

    print("SELF-TEST PASS" if ok else "SELF-TEST FAILED")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("root", nargs="?", default=".", help="repository root")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    root = Path(args.root).resolve()
    problems = check_rule_a(root) + check_rule_b(root)
    if problems:
        for p in problems:
            print(f"::error::{p}")
        print(f"\n{len(problems)} problem(s). See #4011.")
        return 1
    print("openbank-libs annotations: all implemented or used; no document names a phantom one.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
