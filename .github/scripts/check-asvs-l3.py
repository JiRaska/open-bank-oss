#!/usr/bin/env python3
"""OWASP ASVS 4.0 L3 mechanical subset gate (docs/strategy/04-security-baseline.md Layer 4).

The security baseline requires "OWASP ASVS L3 baseline for all services — verifiable in CI
where possible". Most of ASVS L3 is judgement (architecture reviews, process controls), but a
handful of controls are fully mechanical in this codebase, and this gate verifies them so they
stay true — measured clean on 2026-07-29, which is why it enforces from day one with no
baseline file (same landing shape as check-openapi-server-port.py).

What it checks, per ASVS 4.0 chapter, and why each is mechanical here:

  V2.1 Authentication — no service issues its own JWTs (Keycloak is the sole IdP,
    baseline Layer 2): no JWT-SIGNING library (jjwt / nimbus-jose-jwt / java-jwt / jose4j)
    may appear in a service's build files. Token VALIDATION libs (smallrye-jwt) are fine.
  V8.1 Data protection cryptography — baseline pins AES-256-GCM, RSA-3072/Ed25519, SHA-256+
    and bans MD5, SHA-1, DES, 3DES, RC4 (Layer 3): those names must not appear in service
    Kotlin CODE. Comments are stripped before matching — this docstring claimed that
    ("MD5-for-checksums-in-comments are not matches by pattern") while `find_v8` scanned
    every raw line, so the property was asserted and not implemented. #3594 is what it
    costs: a money-path PR that deliberately chose SHA-256 over SHA-1 was blocked by the
    KDoc sentence explaining that choice — the guard flagging the prose that documents
    why the banned primitive was avoided. Kotlin block comments NEST, so the stripper
    tracks depth rather than scanning for the first `*/`.
  V9.1 Communications — no plaintext http:// URL for an inter-service call in gitops env
    values (Layer 1 says mTLS everywhere; until then, at least no NEW plaintext edges).
    Exemptions: localhost/127.0.0.1, the in-cluster Keycloak http leg (documented as the
    sandbox's current state, tracked by the mTLS work).
  V10.1 Malicious code / injection — no string concatenation or interpolation inside
    createQuery/createNativeQuery arguments (parameterised queries only, Layer 4).
  V13.1 API — every service with a resource class publishes an openapi.yaml (already the
    route-conformance gate's universe; the cheap existence half lives here).

What it deliberately does NOT check: ASVS chapters that need judgement (V1 architecture,
V3 session management beyond the IdP, V6 stored crypto key mgmt, V11 business logic,
V12 file upload content, V14 config hardening) — a mechanical verdict there would be noise,
and the baseline tracks them per-service instead.

stdlib-only; reads the working tree so it runs identically in CI and locally.

Usage:
    check-asvs-l3.py [--enforce]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]

SKIP_SERVICE_DIRS = {
    "openbank-libs", "openbank-libs-domain", "openbank-libs-runtime",
    "openbank-admin-ui", "openbank-infra",
}

JWT_SIGNING_LIBS = ("jjwt", "nimbus-jose-jwt", "java-jwt", "jose4j")
BANNED_CRYPTO = re.compile(
    # MD5/SHA-1/DES/3DES/RC4 as primitives. "ECB" alone is not a match — the fleet
    # documents ECB/CNB reference rates (European Central Bank) in plain prose; only
    # an explicit cipher-mode spelling (AES-ECB, ECB mode) counts.
    r"\b(MD5|SHA-1(?!-?2)|SHA1\b|DESede|3DES|RC4|\"DES\"|AES-ECB|ECB mode)"
)
SQL_CONCAT = re.compile(
    r'create(Native)?Query\s*\(\s*[^)]*(\+|\$\{)'
)
PLAINTEXT_EXEMPTIONS = ("localhost", "127.0.0.1", "keycloak.iam.svc")


def service_dirs() -> list[pathlib.Path]:
    return sorted(
        p for p in REPO.glob("openbank-*")
        if p.is_dir() and p.name not in SKIP_SERVICE_DIRS and (p / "src/main/kotlin").is_dir()
    )


def find_v2() -> list[str]:
    findings = []
    for svc in service_dirs():
        for build in [svc / "build.gradle.kts"]:
            if not build.is_file():
                continue
            text = build.read_text(encoding="utf-8")
            for lib in JWT_SIGNING_LIBS:
                if lib in text:
                    findings.append(f"V2.1 {svc.name}: JWT-signing library '{lib}' in {build.name}")
    return findings


def strip_kotlin_comments(text: str) -> list[str]:
    """Blank out comments, preserving line numbering so findings still point at the source.

    Kotlin block comments NEST — `/* /* */ */` closes once, not twice — so this tracks
    depth instead of looking for the first `*/`. A stripper that does not mirror that
    ends a comment early and starts matching prose again from the middle of a KDoc.

    String literals are deliberately NOT parsed. `MessageDigest.getInstance("SHA-1")` is
    the exact call this rule exists to catch, and it lives in a string.
    """
    out: list[str] = []
    depth = 0
    for line in text.splitlines():
        buf: list[str] = []
        i = 0
        while i < len(line):
            two = line[i:i + 2]
            if depth == 0 and two == "//":
                break
            if two == "/*":
                depth += 1
                i += 2
                continue
            if two == "*/" and depth > 0:
                depth -= 1
                i += 2
                continue
            if depth == 0:
                buf.append(line[i])
            i += 1
        out.append("".join(buf))
    return out


def find_v8() -> list[str]:
    findings = []
    for svc in service_dirs():
        for kt in (svc / "src/main/kotlin").rglob("*.kt"):
            text = kt.read_text(encoding="utf-8", errors="ignore")
            for i, line in enumerate(strip_kotlin_comments(text), 1):
                if BANNED_CRYPTO.search(line):
                    findings.append(f"V8.1 {svc.name}:{kt.relative_to(REPO)}:{i}: banned crypto primitive")
    return findings


def find_v9() -> list[str]:
    findings = []
    for yaml in REPO.glob("openbank-infra/gitops/components/**/*.yaml"):
        text = yaml.read_text(encoding="utf-8", errors="ignore")
        for i, line in enumerate(text.splitlines(), 1):
            m = re.search(r"value:\s*[\"']?(http://[^\"'\s]+)", line)
            if m and not any(x in m.group(1) for x in PLAINTEXT_EXEMPTIONS):
                findings.append(f"V9.1 {yaml.relative_to(REPO)}:{i}: plaintext http:// env value {m.group(1)[:60]}")
    return findings


def find_v10() -> list[str]:
    findings = []
    for svc in service_dirs():
        for kt in (svc / "src/main/kotlin").rglob("*.kt"):
            text = kt.read_text(encoding="utf-8", errors="ignore")
            for i, line in enumerate(text.splitlines(), 1):
                if SQL_CONCAT.search(line):
                    findings.append(f"V10.1 {svc.name}:{kt.relative_to(REPO)}:{i}: SQL built by concatenation")
    return findings


def find_v13() -> list[str]:
    findings = []
    for svc in service_dirs():
        has_resource = any(
            "@Path(" in kt.read_text(encoding="utf-8", errors="ignore")
            for kt in (svc / "src/main/kotlin").rglob("*Resource.kt")
        )
        spec = svc / "src/main/resources/openapi.yaml"
        if has_resource and not spec.is_file():
            findings.append(f"V13.1 {svc.name}: serves REST resources but publishes no openapi.yaml")
    return findings


SELF_TEST_CASES = [
    # (label, kotlin source, must_flag)
    # The first two are the pair that matters: prose ABOUT a banned primitive must not
    # flag, and the same name inside a real call must. A stripper that satisfies only the
    # first has silently turned V8.1 off.
    (
        "KDoc explaining why SHA-1 was avoided (#3594)",
        '/**\n * v5 is defined as SHA-1, so this uses v8.\n */\n'
        'val d = MessageDigest.getInstance("SHA-256")\n',
        False,
    ),
    (
        "a real banned call",
        'val d = MessageDigest.getInstance("SHA-1")\n',
        True,
    ),
    ("line comment", "// legacy used MD5 here\nval x = 1\n", False),
    (
        "NESTED block comment does not reopen matching",
        "/* outer /* inner */ still comment: SHA-1 */\nval ok = 1\n",
        False,
    ),
    (
        "code after a nested block comment is still scanned",
        '/* /* */ */ val d = MessageDigest.getInstance("SHA-1")\n',
        True,
    ),
    (
        "a trailing comment does not hide code before it",
        'val d = MessageDigest.getInstance("SHA-1") // why\n',
        True,
    ),
]


def self_test() -> int:
    failures = 0
    for label, src, must_flag in SELF_TEST_CASES:
        flagged = any(BANNED_CRYPTO.search(ln) for ln in strip_kotlin_comments(src))
        ok = flagged == must_flag
        failures += not ok
        print(f"  [{'ok' if ok else 'FAIL'}] {label}: flagged={flagged}, expected={must_flag}")
    if failures:
        print(f"\nself-test: {failures} case(s) wrong — V8.1 does not measure what it claims.")
        return 1
    print("\nself-test: OK — prose about a banned primitive is ignored, a real call is not.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument(
        "--baseline",
        help="declared-debt file (one finding per line). A finding NOT in the baseline fails "
        "(new debt); a baseline line with no matching finding ALSO fails (debt was paid — "
        "delete the line, or the file outlives it). Used for V9.1/V13.1, which carry real "
        "backlog (in-cluster plaintext until mTLS, agent services without a published spec).",
    )
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    baseline: set[str] = set()
    if args.baseline:
        bp = pathlib.Path(args.baseline)
        if bp.is_file():
            baseline = {ln.strip() for ln in bp.read_text(encoding="utf-8").splitlines() if ln.strip() and not ln.startswith("#")}

    checks = [
        ("V2.1 no service-issued JWTs", find_v2, False),
        ("V8.1 no banned crypto primitives", find_v8, False),
        ("V9.1 no new plaintext inter-service edges", find_v9, True),
        ("V10.1 parameterised SQL only", find_v10, False),
        ("V13.1 every resource publishes a spec", find_v13, True),
    ]

    def norm(f: str) -> str:
        # Baselines compare without the :NN: line fragment — a yaml edit shifting a line
        # must not read as new debt; the edge itself is the identity.
        return re.sub(r":\d+:", ":", f)

    new_debt = 0
    for label, fn, uses_baseline in checks:
        findings = fn()
        if uses_baseline:
            normed_baseline = {norm(b) for b in baseline if b.startswith(label.split()[0])}
            fresh = [f for f in findings if norm(f) not in normed_baseline]
            stale = sorted(b for b in normed_baseline if b not in {norm(f) for f in findings})
            print(f"{label}: {len(findings)} finding(s), {len(fresh)} NEW vs baseline, {len(stale)} baseline lines paid-off")
            for f in fresh:
                new_debt += 1
                print(("::error::" if args.enforce else "::warning::") + f)
            for s in stale:
                new_debt += 1
                print(("::error::" if args.enforce else "::warning::") + f"STALE baseline entry (debt paid, delete the line): {s}")
        else:
            print(f"{label}: {'FAIL' if findings else 'ok'} ({len(findings)})")
            for f in findings:
                new_debt += 1
                print(("::error::" if args.enforce else "::warning::") + f)
    if new_debt and args.enforce:
        print(f"asvs-l3: {new_debt} NEW finding(s) against the mechanical subset (baseline covers the declared backlog) — correct the code, not the gate")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
