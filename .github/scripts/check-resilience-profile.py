#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Ratchet on undeclared resilience posture (ADR-0321 D2): every SmallRye / MicroProfile Fault
# Tolerance annotation (@Timeout/@Retry/@CircuitBreaker/@Bulkhead) on a service `src/main`
# declaration must carry `@ResilienceProfile(...)` (openbank-libs-runtime,
# `com.openbank.libs.resilience`) naming one of the four named profiles, or `CUSTOM` with a
# non-blank `reason`. Advisory, baselined per file: today's sites are grandfathered by count, a
# NEW undeclared site in a baselined file still fails, and a healed baseline entry is reported
# stale so the list can only shrink toward zero.
#
# Declaration shape assumed (true of every FT annotation site measured on 2026-09-26): the FT
# annotation(s) sit immediately above the `fun`/`class`/`object` they apply to, on their own
# line(s) (a multi-line `@CircuitBreaker(...)` is one logical annotation). `@ResilienceProfile`
# satisfies every FT annotation in the SAME contiguous annotation block, OR a class-level
# `@ResilienceProfile` on the enclosing class satisfies every FT-annotated method below it in that
# file (this gate does not track brace nesting, so a second top-level class in the same file resets
# the class-level cover — documented limitation, not a false negative source: it can only make the
# gate MORE strict, never quieter).
#
# EXIT CODES
#   0  no file exceeds its baseline count of undeclared sites
#   1  a new undeclared site, or a stale baseline entry
#   2  could not run / self-test failed
#
# Run: python3 .github/scripts/check-resilience-profile.py [--root .] [--self-test] [--print-baseline]

import argparse
import pathlib
import re
import sys

FT_ANNOTATIONS = {"Timeout", "Retry", "CircuitBreaker", "Bulkhead"}
PROFILE_ANNOTATION = "ResilienceProfile"
BASELINE_REL = ".github/scripts/resilience-profile-baseline.txt"
EXTENSIONS = {".kt"}

ANNOTATION_START = re.compile(r"^\s*@(\w+)\b(.*)$")
DECL_START = re.compile(
    r"^\s*(?:(?:private|internal|public|protected|open|override|abstract|suspend|inline|final)\s+)*"
    r"(fun|class|object|interface)\b",
)


def source_files(root: pathlib.Path):
    for module in sorted(root.iterdir()):
        if not module.is_dir() or not module.name.startswith("openbank-") or module.name.startswith("openbank-libs"):
            continue
        main = module / "src" / "main"
        if not main.is_dir():
            continue
        for p in sorted(main.rglob("*")):
            if p.is_file() and p.suffix in EXTENSIONS:
                yield p


def strip_line_comments(text: str) -> list:
    return [line.split("//", 1)[0] for line in text.splitlines()]


def scan_file(lines: list):
    """Return list of (line_no, kind) violations: kind is a short reason string."""
    violations = []
    pending_names = set()
    pending_texts = []
    pending_start_line = None
    class_profile_active = False
    depth = 0  # paren depth of an in-progress multi-line annotation
    acc = ""
    acc_start = None

    def flush_pending_on_declaration(decl_line_no):
        nonlocal class_profile_active
        has_ft = bool(pending_names & FT_ANNOTATIONS)
        has_profile = PROFILE_ANNOTATION in pending_names
        if has_profile:
            joined = " ".join(pending_texts)
            m = re.search(r"ResilienceProfile\s*\(([^)]*)\)", joined)
            args = m.group(1) if m else ""
            is_custom = bool(re.search(r'"custom"', args, re.IGNORECASE)) or "CUSTOM" in args
            reason_m = re.search(r'reason\s*=\s*"([^"]*)"', args)
            reason_blank = (reason_m is None) or (reason_m.group(1).strip() == "")
            if is_custom and reason_blank:
                violations.append((decl_line_no, "CUSTOM profile with no reason"))
        if has_ft and not has_profile and not class_profile_active:
            violations.append((pending_start_line or decl_line_no, "FT annotation with no @ResilienceProfile"))

    for i, raw in enumerate(lines, start=1):
        line = raw
        if depth > 0:
            acc += " " + line
            depth += line.count("(") - line.count(")")
            if depth <= 0:
                depth = 0
                name = re.match(r"^\s*@(\w+)", acc).group(1)
                pending_names.add(name)
                pending_texts.append(acc)
                if pending_start_line is None:
                    pending_start_line = acc_start
            continue

        m = ANNOTATION_START.match(line)
        if m:
            name, rest = m.group(1), m.group(2)
            opens = rest.count("(")
            closes = rest.count(")")
            if opens == 0:
                # bare annotation, e.g. @ApplicationScoped
                pending_names.add(name)
                pending_texts.append(line)
                if pending_start_line is None:
                    pending_start_line = i
            elif opens - closes <= 0:
                pending_names.add(name)
                pending_texts.append(line)
                if pending_start_line is None:
                    pending_start_line = i
            else:
                depth = opens - closes
                acc = line
                acc_start = i
            continue

        if not line.strip():
            continue

        if DECL_START.match(line):
            kind = DECL_START.match(line).group(1)
            flush_pending_on_declaration(i)
            if kind in ("class", "object") and PROFILE_ANNOTATION in pending_names:
                class_profile_active = True
            elif kind in ("class", "object"):
                # a new top-level type with no class-level profile: stop covering with the old one
                class_profile_active = False
            pending_names = set()
            pending_texts = []
            pending_start_line = None
            continue

        # any other real code line breaks a pending annotation block that never reached a decl
        pending_names = set()
        pending_texts = []
        pending_start_line = None

    return violations


def scan(root: pathlib.Path):
    counts, files = {}, 0
    for p in source_files(root):
        files += 1
        text = p.read_text(encoding="utf-8", errors="replace")
        lines = strip_line_comments(text)
        violations = scan_file(lines)
        if violations:
            counts[str(p.relative_to(root))] = len(violations)
    return counts, files


def load_baseline(path: pathlib.Path):
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        f, n = line.split("\t")
        out[f] = int(n)
    return out


def evaluate(counts, baseline):
    errors = []
    for f, n in sorted(counts.items()):
        allowed = baseline.get(f, 0)
        if n > allowed:
            errors.append(
                f"NEW undeclared resilience-annotation site in {f}: {n} site(s), baseline {allowed} "
                "— add @ResilienceProfile (ADR-0321 D2)",
            )
    for f, allowed in sorted(baseline.items()):
        if counts.get(f, 0) < allowed:
            errors.append(f"STALE baseline: {f} now has {counts.get(f, 0)} (baseline {allowed}) — lower or remove the entry")
    return errors


def run(root: pathlib.Path) -> int:
    bpath = root / BASELINE_REL
    if not bpath.is_file():
        print(f"baseline {BASELINE_REL} missing", file=sys.stderr)
        return 2
    counts, files = scan(root)
    errors = evaluate(counts, load_baseline(bpath))
    print(f"SUBJECTS={files}  # service src/main .kt files scanned")
    print(f"undeclared resilience-annotation sites: {sum(counts.values())} in {len(counts)} file(s)")
    for e in errors:
        print(e)
    return 1 if errors else 0


def self_test() -> int:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        svc = root / "openbank-foo-service" / "src" / "main" / "kotlin"
        svc.mkdir(parents=True)
        libs = root / "openbank-libs-runtime" / "src" / "main" / "kotlin"
        libs.mkdir(parents=True)

        # known-negative: profiled method-level annotation passes
        (svc / "Good.kt").write_text(
            "class GoodClient {\n"
            "    @ResilienceProfile(ResilienceProfiles.READ)\n"
            "    @Timeout(2000)\n"
            "    @Retry(maxRetries = 2, delay = 200, jitter = 100)\n"
            "    fun call() {}\n"
            "}\n",
        )
        # known-positive: undeclared FT annotation
        (svc / "Bad.kt").write_text(
            "class BadClient {\n"
            "    @Timeout(2000)\n"
            "    @Retry(maxRetries = 2, delay = 200, jitter = 100)\n"
            "    fun call() {}\n"
            "}\n",
        )
        # class-level profile covers the method below
        (svc / "ClassLevel.kt").write_text(
            "@ResilienceProfile(ResilienceProfiles.BATCH)\n"
            "class BatchClient {\n"
            "    @Timeout(30000)\n"
            "    fun sweep() {}\n"
            "}\n",
        )
        # multi-line CircuitBreaker, profiled
        (svc / "MultiLine.kt").write_text(
            "class MultiLineClient {\n"
            "    @ResilienceProfile(ResilienceProfiles.EXTERNAL_SCHEME)\n"
            "    @CircuitBreaker(\n"
            "        requestVolumeThreshold = 4,\n"
            "        failureRatio = 0.5,\n"
            "    )\n"
            "    fun call() {}\n"
            "}\n",
        )
        # CUSTOM with no reason
        (svc / "CustomNoReason.kt").write_text(
            "class CustomClient {\n"
            "    @ResilienceProfile(ResilienceProfiles.CUSTOM)\n"
            "    @Timeout(9999)\n"
            "    fun call() {}\n"
            "}\n",
        )
        # CUSTOM with a reason: fine
        (svc / "CustomWithReason.kt").write_text(
            "class CustomOkClient {\n"
            '    @ResilienceProfile(ResilienceProfiles.CUSTOM, reason = "vendor SLA is 9.9s")\n'
            "    @Timeout(9999)\n"
            "    fun call() {}\n"
            "}\n",
        )
        # a KDoc/comment mention must not count as a real annotation
        (svc / "Commented.kt").write_text(
            "class CommentedClient {\n"
            "    // @Timeout(2000) left here for reference, not live\n"
            "    fun call() {}\n"
            "}\n",
        )
        (libs / "L.kt").write_text(
            "class LibsClient {\n"
            "    @Timeout(2000)\n"
            "    fun call() {}\n"
            "}\n",
        )
        base = root / BASELINE_REL
        base.parent.mkdir(parents=True)

        checks = []

        base.write_text("openbank-foo-service/src/main/kotlin/Bad.kt\t1\n")
        counts, _ = scan(root)
        checks.append((counts.get("openbank-foo-service/src/main/kotlin/Good.kt") is None, "profiled method passes"))
        checks.append((counts.get("openbank-foo-service/src/main/kotlin/ClassLevel.kt") is None, "class-level profile covers method"))
        checks.append((counts.get("openbank-foo-service/src/main/kotlin/MultiLine.kt") is None, "multi-line annotation matched"))
        checks.append((counts.get("openbank-foo-service/src/main/kotlin/CustomWithReason.kt") is None, "CUSTOM with reason passes"))
        checks.append((counts.get("openbank-foo-service/src/main/kotlin/Commented.kt") is None, "commented-out annotation ignored"))
        checks.append((counts.get("openbank-foo-service/src/main/kotlin/CustomNoReason.kt") == 1, "CUSTOM with no reason is a finding (known-positive)"))
        checks.append(("openbank-libs-runtime" not in str(counts.keys()), "libs-runtime is out of scope"))
        checks.append((run(root) == 1, "CustomNoReason.kt is a new finding not yet in the baseline"))

        # add CustomNoReason to baseline too, now clean
        base.write_text(
            "openbank-foo-service/src/main/kotlin/Bad.kt\t1\n"
            "openbank-foo-service/src/main/kotlin/CustomNoReason.kt\t1\n",
        )
        checks.append((run(root) == 0, "fully baselined tree passes"))

        # a NEW second undeclared site in Bad.kt fails even though the file is baselined at 1
        (svc / "Bad.kt").write_text(
            "class BadClient {\n"
            "    @Timeout(2000)\n"
            "    fun call() {}\n"
            "    @Retry(maxRetries = 1, delay = 100)\n"
            "    fun other() {}\n"
            "}\n",
        )
        checks.append((run(root) == 1, "a second undeclared site in an already-baselined file fails"))

        # heal Bad.kt: now stale baseline entry
        (svc / "Bad.kt").write_text(
            "class BadClient {\n"
            "    @ResilienceProfile(ResilienceProfiles.READ)\n"
            "    @Timeout(2000)\n"
            "    fun call() {}\n"
            "}\n",
        )
        checks.append((run(root) == 1, "a healed baseline entry is reported stale"))

        base.write_text("openbank-foo-service/src/main/kotlin/CustomNoReason.kt\t1\n")
        checks.append((run(root) == 0, "re-synced baseline over the healed tree passes"))

        base.unlink()
        checks.append((run(root) == 2, "missing baseline cannot run"))

    failed = [msg for ok, msg in checks if not ok]
    for msg in failed:
        print(f"SELF-TEST FAIL: {msg}", file=sys.stderr)
    print(f"self-test: {len(checks) - len(failed)}/{len(checks)} passed")
    return 2 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--print-baseline", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    root = pathlib.Path(args.root).resolve()
    if args.print_baseline:
        counts, _ = scan(root)
        for f, n in sorted(counts.items()):
            print(f"{f}\t{n}")
        return 0
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
