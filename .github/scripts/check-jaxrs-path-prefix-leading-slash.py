#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Ratchet on JAX-RS path-prefix checks whose literal has no leading slash (#10997).
#
# RESTEasy Reactive's `UriInfo.getPath()` returns the path WITH a leading slash
# ("/v1/accounts", not "v1/accounts") -- the convention every other filter in this repo already
# assumes (`RateLimitFilter` / `VopRateLimitFilter` test `startsWith("/q/")`). A prefix check
# written without the slash is always false, so the gate it was meant to be never runs -- and it
# fails CLOSED (a resource downstream still requires whatever the filter was supposed to set),
# so nothing crashes and nothing 5xx's; the endpoint just quietly rejects every call the filter
# should have let through, or a header the filter should have added never appears. #10997 found
# exactly that in `openbank-psd2-service`'s `EidasMtlsFilter`, `QsealSignatureFilter` and
# `BespokeDeprecationFilter`.
#
# WHY A UNIT TEST DID NOT CATCH IT: every existing unit test for these filters mocks
# `uriInfo.path`/`requestUri.path` with the exact slash-less string the code expects, so it
# passes against whatever the framework actually returns. Only a real `@QuarkusTest` HTTP
# request would show the mismatch (see `Psd2FilterPathGatingIT` in #11008).
#
# WHAT THIS SCANS FOR
#   1. A direct chain: `<expr>.uriInfo.path.startsWith("literal")` or
#      `<expr>.requestUri.path.startsWith("literal")` / a bare `.path.startsWith("literal")`.
#   2. The one-hop alias RESTEasy code actually uses in two of the three #10997 sites:
#          val path = ctx.uriInfo.path
#          ...
#          path.startsWith("literal")
#      A regex over `.path.startsWith(` alone is blind to this -- the call site has no ".path."
#      on it at all, just the bare local name. Detecting only the direct-chain form would have
#      missed 2 of the 3 real defects, so this resolves ONE assignment hop per file: find
#      `val <name> = <expr>.(uriInfo|requestUri).path` (or `<expr>.path`), then flag
#      `<name>.startsWith("literal")` anywhere later in the same file.
#
#   A literal is flagged when it is non-empty and does not start with "/". `path.contains(...)`,
#   `path.endsWith(...)` and anything already prefixed correctly are left alone. This is the
#   convention check, not a proof that `UriInfo.getPath()` always returns a leading slash in
#   every deployment shape -- deciding that fully needs the request URI, the JAX-RS base path and
#   RESTEasy's own quirks, which is why this starts ADVISORY
#   (`rules.yaml: jaxrs_path_prefix_leading_slash`).
#
# The baseline is a per-file COUNT (`path<TAB>n`), so a second slash-less prefix added to an
# already-baselined file still fails. Starts advisory; a healed baseline entry is reported stale
# so the list only shrinks.
#
# EXIT CODES
#   0  no file exceeds its baseline count
#   1  a new finding, or a stale baseline entry
#   2  could not run / self-test failed
#
# Run: python3 .github/scripts/check-jaxrs-path-prefix-leading-slash.py [--root .] [--self-test] [--print-baseline]

import argparse
import pathlib
import re
import sys

BASELINE_REL = ".github/scripts/jaxrs-path-prefix-leading-slash-baseline.txt"
EXTENSIONS = {".kt"}

# Direct chain: an expression ending in `.uriInfo.path`, `.requestUri.path` or a bare `.path`,
# immediately followed by `.startsWith("literal")`.
DIRECT_RE = re.compile(
    r"\.path\.startsWith\(\s*\"([^\"]*)\"",
)

# One-hop alias: `val <name> = <expr>.uriInfo.path` / `<expr>.requestUri.path` / `<expr>.path`
ALIAS_ASSIGN_RE = re.compile(
    r"\bval\s+(\w+)\s*=\s*[\w.]*\.(?:uriInfo\.path|requestUri\.path|path)\b(?!\s*\()",
)


def strip_comments(text: str) -> str:
    return "\n".join(line.split("//", 1)[0] for line in text.splitlines())


def _walk_main_kotlin(module: pathlib.Path):
    main = module / "src" / "main"
    if not main.is_dir():
        return
    for p in sorted(main.rglob("*")):
        if p.is_file() and p.suffix in EXTENSIONS:
            yield p


def source_files(root: pathlib.Path):
    for module in sorted(root.iterdir()):
        if not module.is_dir() or not module.name.startswith("openbank-"):
            continue
        yield from _walk_main_kotlin(module)


def findings_in_file(text: str):
    """Return a list of (line_no, literal) for slash-less prefix checks in one file."""
    code = strip_comments(text)
    lines = code.splitlines()
    out = []

    for i, line in enumerate(lines, start=1):
        for m in DIRECT_RE.finditer(line):
            literal = m.group(1)
            if literal and not literal.startswith("/"):
                out.append((i, literal))

    aliases = set(ALIAS_ASSIGN_RE.findall(code))
    if aliases:
        alias_pattern = re.compile(
            r"\b(" + "|".join(re.escape(a) for a in aliases) + r")\.startsWith\(\s*\"([^\"]*)\"",
        )
        for i, line in enumerate(lines, start=1):
            for m in alias_pattern.finditer(line):
                literal = m.group(2)
                if literal and not literal.startswith("/"):
                    out.append((i, literal))

    return out


def scan(root: pathlib.Path):
    counts, files = {}, 0
    for p in source_files(root):
        files += 1
        text = p.read_text(encoding="utf-8", errors="replace")
        found = findings_in_file(text)
        if found:
            counts[str(p.relative_to(root))] = len(found)
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
                f"NEW slash-less JAX-RS path-prefix check in {f}: {n} site(s), baseline {allowed} "
                "-- UriInfo.getPath() returns a leading slash; prefix the literal with \"/\" (#10997)",
            )
    for f, allowed in sorted(baseline.items()):
        if counts.get(f, 0) < allowed:
            errors.append(f"STALE baseline: {f} now has {counts.get(f, 0)} (baseline {allowed}) -- lower or remove the entry")
    return errors


def run(root: pathlib.Path) -> int:
    bpath = root / BASELINE_REL
    if not bpath.is_file():
        print(f"baseline {BASELINE_REL} missing", file=sys.stderr)
        return 2
    counts, files = scan(root)
    errors = evaluate(counts, load_baseline(bpath))
    print(f"SUBJECTS={files}  # service src/main .kt files scanned")
    print(f"slash-less path-prefix sites: {sum(counts.values())} in {len(counts)} file(s)")
    for e in errors:
        print(e)
    return 1 if errors else 0


def self_test() -> int:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        svc = root / "openbank-foo-service" / "src" / "main" / "kotlin"
        svc.mkdir(parents=True)
        tests = root / "openbank-foo-service" / "src" / "test" / "kotlin"
        tests.mkdir(parents=True)
        base = root / BASELINE_REL
        base.parent.mkdir(parents=True)

        (svc / "DirectFilter.kt").write_text(
            'val gated = ctx.uriInfo.path.startsWith("open-banking/")\n',
        )
        (svc / "AliasFilter.kt").write_text(
            "val path = ctx.uriInfo.path\n"
            'val signed = path.startsWith("v1/payments")\n',
        )
        (svc / "GoodFilter.kt").write_text(
            'if (ctx.uriInfo.path.startsWith("/q/")) return\n'
            'val u = ctx.requestUri.path\n'
            'if (u.startsWith("/health")) return\n',
        )
        (svc / "CommentOnly.kt").write_text(
            '// ctx.uriInfo.path.startsWith("open-banking/") is the old, broken form\n'
            'val ok = ctx.uriInfo.path.startsWith("/v1/")\n',
        )
        (svc / "ContainsOnly.kt").write_text(
            'val path = ctx.uriInfo.path\n'
            'if (path.contains("payments")) doThing()\n',
        )
        # Known-negative: Jackson's JsonNode.path("field") is a METHOD CALL, not the UriInfo
        # property this gate targets. A prior draft's alias regex matched the bare `.path` before
        # the `(` and misfired here (measured false-positive on real
        # openbank-customer-edge/CustomerEdgeResource.kt, whose hundreds of `node.path("field")`
        # calls share a file with an unrelated `iban.startsWith("CZ")`).
        (svc / "JacksonPath.kt").write_text(
            'val code = request.path("code").takeIf { it.isTextual }?.textValue()\n'
            'val ok = code?.startsWith("CZ") ?: false\n',
        )
        (tests / "DirectFilterTest.kt").write_text(
            'val gated = ctx.uriInfo.path.startsWith("open-banking/")\n',
        )

        checks = []

        base.write_text("")
        checks.append((run(root) == 1, "empty baseline over a dirty tree fails (2 known-positive files)"))

        direct = "openbank-foo-service/src/main/kotlin/DirectFilter.kt"
        alias = "openbank-foo-service/src/main/kotlin/AliasFilter.kt"
        base.write_text(f"{direct}\t1\n{alias}\t1\n")
        checks.append((run(root) == 0, "both known-positive findings match their baseline"))

        (svc / "DirectFilter.kt").write_text(
            'val gated = ctx.uriInfo.path.startsWith("open-banking/")\n'
            'val more = ctx.uriInfo.path.startsWith("v2/")\n',
        )
        checks.append((run(root) == 1, "a second slash-less site in a baselined file fails"))

        (svc / "DirectFilter.kt").write_text(
            'val gated = ctx.uriInfo.path.startsWith("/open-banking/")\n',
        )
        checks.append((run(root) == 1, "a healed baseline entry (fixed to a leading slash) is reported stale"))

        base.write_text(f"{alias}\t1\n")
        checks.append((run(root) == 0, "known-negative files (correct prefix, comment, .contains, test dir) never appear"))

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
