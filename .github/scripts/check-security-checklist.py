#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Money-path PRs must actually TICK the security checklist, not just carry it.

WHY (ADR-0279 WS4 #26). The PR template's "## Security checklist" is the fleet's
per-PR security control — secrets/PII sweep, no unjustified suppressions, dependency
review, review-required labels. Measured behaviour says a checkbox nobody has to tick
gets read never: the template renders, the boxes stay empty, the review proceeds.
The champions/pitfall programme (AGENTS.md engineering notes) only works if the
money-path PR pauses on the lines written for exactly it.

WHAT IT CHECKS — only when the PR diff touches a `money_path_services` directory
(read from rules.yaml, the one list, so the two-lists-drift bug class cannot apply):
  * the PR body keeps a "## Security checklist" section (deleting the section is a
    finding, same as leaving it blank — otherwise the fix is "delete the question");
  * every checkbox inside that section is ticked (`[x]`/`[X]`). An unticked box is
    listed verbatim in the error, so the author ticks it or states why in the PR.

Deliberately NOT checked: the checkboxes' truthfulness (CI cannot know whether the
author really ran a secrets sweep — the control is the pause, not the proof) and
non-money-path PRs (the pause is priced; spend it where a mistake moves money).

RELEASE-DERIVED FILES ARE NOT MONEY-PATH CODE (#9230). A release-please PR touches
`CHANGELOG.md`, `version.txt` and `.release-please-manifest.json` under every service
that released, and nothing else — no Kotlin, no config, no migration, no spec. Counted
as money-path files those tripped this gate on a PR with no code in it at all, and the
remedy the error names is unanswerable: there is no secrets sweep to run and no
suppression to justify over a generated changelog, and the PR is authored by a bot that
cannot tick a box. Measured on #9230: 42 "money-path files" across 21 services, 88 files
in the diff, and ZERO of them outside the four derived names below — the release train
sat blocked from 2026-09-08.

This is the `gate whose remedy cannot be performed` shape, so the fix is the SCOPE, not
the severity: strip the derived names before deciding whether the PR is money-path. A
release PR that also carried real code still trips the gate, because the strip is
per-file and the code file survives it. That case is the self-test's must-FAIL control —
without it this exclusion would be indistinguishable from switching the gate off.

MECHANICALLY DECIDABLE ITEMS ARE ANSWERED BY THE GATE (#11299, ADR-0279 #26).
Six boxes, and on a test-only or docs-only money-path PR every one of them has an answer
the diff already determines — yet the gate made a human tick all six, and 11+ PRs sat red
on it at once. So each box is now classified, and only the boxes a human must judge FOR
THIS DIFF are required:

  item            decided by                                             auto-pass when
  secrets/PII     gitleaks gate (secrets) + file classification          every money-path file is INERT
  suppressions    `+` lines of the diff: @Suppress/@SuppressWarnings/as Any  none added anywhere
  dependency      build/dependency manifests in the diff                 none changed, or only Gradle build
                                                                         scripts whose changed lines declare
                                                                         no dependency/plugin/version (line-level)
  auth/crypto/pay path classification                                    money-path files all INERT and no
                                                                         auth/crypto path changed anywhere
  PII path        path classification                                    same, PII patterns
  cardholder      path classification                                    same, cardholder patterns

INERT is a closed, positive list: `src/test/`, `src/testFixtures/`, `src/integrationTest/`,
`e2e/`, `docs/`, and `*.md` files. Everything else under a money-path service — src/main,
application.yaml, a migration, openapi.yaml, a Dockerfile, build.gradle.kts, and any path
this list does not name — is UNKNOWN, and every judgement item stays human (fail closed):
in a money-path service, production code IS payment code. A box the classifier does not
recognise stays required too.

DECLARED SENSITIVE PATHS (#11301). Production code in a money-path service is no longer
automatically "everything sensitive" when that service declares, in
`rules.yaml: security_sensitive_paths`, which of its paths are auth/crypto/payment, PII and
cardholder code. For a declared service the auth/PII/cardholder boxes auto-pass iff no changed
production file matches that category's globs (and no non-money-path file matches the path
patterns above); the secrets box auto-passes iff additionally no declared PII path, no
application config and no added log statement is in the diff. Undeclared services stay fail
closed. A diff that changes the declaration itself makes all three judgement boxes human, so
the list cannot be narrowed silently. `--coverage` keeps it honest: a strong signal (crypto/JWT/
TLS import, the shared approval library, a PII-/card-named field or column) outside its
category's globs is a finding. The gate writes its evidence for each auto-answered item to
the job log and $GITHUB_STEP_SUMMARY, so the answer is reviewable, not silent.

LINE-LEVEL REFINEMENTS (line-level). Three triggers used to fire on the FILE when only its LINES can
say whether the question arises; each now reads the changed lines, and each fails closed:
  * dependency — a changed `build.gradle(.kts)` is a dependency change only if a changed non-`//`
    line matches DEP_LINE (configurations, plugins, coordinate strings, versions, `libs.`, `val`/
    `$` indirection, resolution tampering, any block-comment marker). Every other manifest
    (catalog, lockfiles, settings, gradle.properties, Dockerfile) still counts on any change.
    Measured over the last 200 money-path commits on main: 12 coverage-floor / test-heap /
    comment-only script edits no longer ask for a dependency review; the two PRs that added a
    plugin or `implementation(` line (#11636, #11860) still do.
  * secrets — an added log call counts only when it can write a runtime value: anything but a
    single plain string literal (`$x`, `{}`+arg, `+`, lambda, raw string, a call split across
    lines) is interpolating. A file whose every changed line is a comment, and a Gradle build
    script, are treated like docs/*.md for this item only (gitleaks still scans them for secrets).
    Comment-only fails closed on a REMOVED block-comment marker and on unbalanced added markers —
    either can make commented-out code live.
  * auth / PII / cardholder are NOT relaxed: comment-only or build-only changes in an undeclared
    money-path service still need those ticks.

Usage:  check-security-checklist.py --body-file <file> [--base origin/main]
        check-security-checklist.py --self-test
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from collections.abc import Callable
from pathlib import Path

RULES = Path("openbank-libs/governance/rules.yaml")
SECTION = re.compile(r"^##\s+Security checklist\s*$", re.M | re.I)
NEXT_HEADING = re.compile(r"^##\s+", re.M)
BOX = re.compile(r"^\s*-\s*\[(?P<mark>[ xX])\]\s*(?P<text>.*)$", re.M)

# Files release-please writes, and the only files a release-only PR contains. Matched on the
# BASENAME, because each lives under its own service directory (and the manifest at the root).
# Deliberately a closed list of four exact names, not a glob: `*.md` would swallow a threat model
# and `*.json` an OPA bundle, and both of those are exactly the money-path evidence this gate must
# keep seeing.
RELEASE_DERIVED = frozenset({
    "CHANGELOG.md",
    "version.txt",
    ".release-please-manifest.json",
})


def is_release_derived(path: str) -> bool:
    """True for a file release-please generates, which carries no code and no attack surface."""
    return path.rsplit("/", 1)[-1] in RELEASE_DERIVED


# ── mechanical classification ────────────────────────────────────────────────────────────────
# Positive list of paths that carry no runtime behaviour. Anything not matched is UNKNOWN.
INERT_SEGMENTS = ("/src/test/", "/src/testFixtures/", "/src/integrationTest/", "/e2e/", "/docs/")
DEP_MANIFEST = re.compile(
    r"(^|/)(build\.gradle(\.kts)?|settings\.gradle(\.kts)?|libs\.versions\.toml|gradle\.properties|"
    r"verification-metadata\.xml|package(-lock)?\.json|pnpm-lock\.yaml|yarn\.lock|"
    r"requirements[^/]*\.txt|pyproject\.toml|go\.(mod|sum)|Dockerfile[^/]*)$")
SUPPRESSION = re.compile(r"@Suppress\b|@SuppressWarnings\b|@file:Suppress\b|\bas\s+Any\b")
AUTH_CRYPTO = re.compile(
    r"(/(security|crypto|auth|authz|authn|oidc|keycloak|sca|signing|kms|hsm|tls)/)|"
    r"(auth|crypt|cipher|jwt|token|signer|signature|keystore|password|secret|hsm|kms|tls|mtls)"
    r"[^/]*$|\.rego$|realm[^/]*\.json$", re.I)
PII = re.compile(
    r"(pii|gdpr|personal|kyc|customer|party|consent|identity|onboarding|contact|address|"
    r"email|phone|birth|/pid)", re.I)
CARDHOLDER = re.compile(r"(card|pci|cardholder|\bpan\b|cvv|emv|tokeni[sz])", re.I)

# The six template boxes, recognised by stable phrases. A box matching none is UNKNOWN → human.
ITEMS = (
    ("secrets", re.compile(r"secrets", re.I)),
    ("suppressions", re.compile(r"Suppress|as Any", re.I)),
    ("dependency", re.compile(r"third-party dependency", re.I)),
    ("auth", re.compile(r"Auth\s*/\s*crypto", re.I)),
    ("pii", re.compile(r"PII path", re.I)),
    ("cardholder", re.compile(r"Cardholder", re.I)),
)


def is_inert(path: str) -> bool:
    p = "/" + path
    return path.endswith(".md") or any(seg in p for seg in INERT_SEGMENTS)


def item_of(text: str) -> str | None:
    for key, rx in ITEMS:
        if rx.search(text):
            return key
    return None


def diff_lines(base: str) -> tuple[list[tuple[str, str]], list[tuple[str, str]]]:
    """(added, removed) lines of the diff as (path, text). A deleted file's lines carry its old path."""
    out = subprocess.run(["git", "diff", "-U0", "--no-color", f"{base}...HEAD"],
                         capture_output=True, text=True, check=True).stdout
    cur, old, add, rem = "", "", [], []
    for ln in out.splitlines():
        if ln.startswith("diff --git "):
            cur = old = ""
        elif ln.startswith("--- "):
            old = ln[6:] if ln.startswith("--- a/") else ""
        elif ln.startswith("+++ "):
            cur = ln[6:] if ln.startswith("+++ b/") else old
        elif ln.startswith("+"):
            add.append((cur, ln[1:]))
        elif ln.startswith("-"):
            rem.append((cur, ln[1:]))
    return add, rem


def added_lines(base: str) -> list[tuple[str, str]]:
    return diff_lines(base)[0]


# ── line-level refinements (each one narrows a trigger; none widens an auto-answer) ──────────
# Gradle build scripts are the only manifests parsed. Every other manifest (version catalog,
# lockfiles, package.json, verification-metadata, settings.gradle, gradle.properties, Dockerfile)
# is a dependency change whenever it changes at all — those files ARE coordinates.
GRADLE_SCRIPT = re.compile(r"(^|/)build\.gradle(\.kts)?$")
# A changed build-script line is a POSSIBLE dependency change when it matches any of these. The
# list is deliberately over-broad: a false match costs one human tick, a missed one is a false
# "no new dependency". Covers configurations (`implementation(`, `fooImplementation(`, `kapt`,
# `platform(`), plugin declarations (`id(`, `kotlin(`, `alias(`, `version "x"`), coordinate-shaped
# strings (`"g:a:v"`, `":project"`), resolution tampering (`force`, `exclude`, `substitute`,
# repositories), and every way a version can be indirected: `val`/`var`/`extra`/`$` interpolation,
# `libs.` catalog refs, `*Version` properties (pitest's `junit5PluginVersion` pulls an artifact).
# A block-comment marker counts too: deleting `/*` … `*/` UNcomments a dependency while every
# changed line is comment syntax. Only `//` line comments are ignored.
DEP_LINE = re.compile(
    r"\b(implementation|api|compileOnly|runtimeOnly|developmentOnly|kapt|ksp|annotationProcessor|"
    r"classpath|platform|enforcedPlatform|dependencies|constraints|plugins|pluginManagement|id|kotlin|"
    r"alias|force|exclude|resolutionStrategy|substitute|substitution|dependencySubstitution|"
    r"repositories|maven|mavenCentral|mavenLocal|google|gradlePluginPortal|ivy|flatDir|apply|from|"
    r"module|project|files|fileTree|configurations|buildscript|libs|catalogs?|val|var|extra|ext|by)\b|"
    r"[Vv]ersion|\w(Implementation|Api|CompileOnly|RuntimeOnly|AnnotationProcessor)\b|"
    r"\"[^\"\s]*:[^\"\s]*\"|\$|/\*|\*/")
LINE_COMMENT = re.compile(r"^\s*//")
# A log call whose ONLY argument is a plain string literal (no `$`, no `+`, no second argument, no
# lambda/raw string) cannot carry a runtime value into a log line. Anything else — `{}` with args,
# `$x`, concatenation, a lambda, a call continued on the next line — is "interpolating".
LOG_LITERAL_ONLY = re.compile(r'\s*\(\s*"(?:[^"\\$]|\\.)*"\s*\)')
SOURCE_COMMENT = re.compile(r"^\s*(//|\*|/\*)")
HASH_COMMENT = re.compile(r"^\s*#")


def log_interpolates(text: str) -> bool:
    """True when the line holds a log call that may write a runtime value."""
    return any(not LOG_LITERAL_ONLY.match(text, m.end()) for m in LOG_CALL.finditer(text))


def comment_only(path: str, add: list[str], rem: list[str]) -> bool:
    """True when every changed line of the file is comment text, so its runtime behaviour cannot
    change. Fail closed: needs at least one changed line, a known comment syntax for the file type,
    no block-comment marker on a REMOVED line (deleting `/*` or `*/` uncomments code), and balanced
    markers on the added side (adding `/* … */` around code only ever disables it)."""
    if not add and not rem:
        return False
    if path.endswith((".kt", ".kts", ".java")):
        if any("/*" in t or "*/" in t for t in rem):
            return False
        joined = "\n".join(add)
        if joined.count("/*") != joined.count("*/"):
            return False
        for t in add + rem:
            if not t.strip():
                continue
            if not SOURCE_COMMENT.match(t):
                return False
            if t.lstrip().startswith(("/*", "*")) and re.search(r"\*/\s*\S", t):
                return False  # `/* x */ code()` — code after the comment closes
        return True
    if path.endswith((".yaml", ".yml", ".properties")):
        return all(not t.strip() or HASH_COMMENT.match(t) for t in add + rem)
    return False


def dependency_lines(f: str, add: list[str], rem: list[str]) -> list[str]:
    """Changed lines of a build manifest that may declare or move a dependency. A non-Gradle-script
    manifest, or a script with no visible changed line (rename, mode change), answers itself."""
    if not GRADLE_SCRIPT.search(f):
        return ["(manifest is all coordinates — any change is a dependency change)"]
    lines = [t for t in add + rem if t.strip() and not LINE_COMMENT.match(t)]
    if not add and not rem:
        return ["(no changed line visible — rename or mode change)"]
    return [t.strip() for t in lines if DEP_LINE.search(t)]


def decide(files: list[str], touched: list[str], added: list[tuple[str, str]],
           decls: dict[str, dict[str, list[str]]] | None = None,
           decl_changed: bool = False,
           removed: list[tuple[str, str]] | None = None) -> dict[str, str | None]:
    """item -> evidence string when the diff answers it (auto-pass), or None when a human must.
    The None branches are the default; an item is auto-answered only by positive evidence.
    `removed=None` (the caller has no removed lines) disables every line-level refinement."""
    decls = decls or {}
    lines_known = removed is not None
    add_by: dict[str, list[str]] = {}
    rem_by: dict[str, list[str]] = {}
    for f, t in added:
        add_by.setdefault(f, []).append(t)
    for f, t in removed or []:
        rem_by.setdefault(f, []).append(t)
    unknown = [f for f in touched if not is_inert(f)]
    touched_set = set(touched)
    other_code = [f for f in files if not is_inert(f) and f not in touched_set]
    undeclared = [f for f in unknown if f.split("/", 1)[0] not in decls]
    declared = [f for f in unknown if f.split("/", 1)[0] in decls]

    def cat_hits(cat: str) -> list[str]:
        return [f for f in declared if matches(f.split("/", 1)[1], decls[f.split("/", 1)[0]][cat])]

    ans: dict[str, str | None] = {k: None for k, _ in ITEMS}
    inert_note = (f"all {len(touched)} money-path file(s) are tests/docs "
                  f"(src/test, e2e, docs, *.md): " + ", ".join(sorted(touched)[:8])
                  + (" …" if len(touched) > 8 else ""))
    decl_note = (f"{len(declared)} production file(s) in declared service(s) "
                 f"({', '.join(sorted({f.split('/', 1)[0] for f in declared}))}) checked against "
                 f"rules.yaml: {DECL_KEY}")
    # secrets/PII: a file whose every changed line is a comment, and a Gradle build script, put no
    # value into a runtime log line or runtime config — the same standing as docs/*.md, which is
    # already INERT. Secrets anywhere in the diff (comments and build scripts included) remain the
    # gitleaks gate's job. Only this item uses the relaxation; auth/PII/cardholder do not.
    quiet = {f for f in unknown if lines_known and (
        comment_only(f, add_by.get(f, []), rem_by.get(f, [])) or GRADLE_SCRIPT.search(f))}
    relevant = [f for f in unknown if f not in quiet]
    quiet_note = (f"; {len(quiet)} other money-path file(s) change only comments or Gradle build "
                  "script lines: " + ", ".join(sorted(quiet)[:6])) if quiet else ""
    if not relevant:
        lead = inert_note if not quiet else (
            f"{len(touched) - len(quiet)} money-path file(s) are tests/docs")
        ans["secrets"] = lead + quiet_note + (
            "; no production code/config/log statement changed on the money path, and secrets across "
            "the whole diff are scanned by the gitleaks gate")
    elif not [f for f in relevant if f.split("/", 1)[0] not in decls] and not decl_changed:
        cfg = [f for f in relevant if CONFIG_FILE.search("/" + f)]
        logs = [f"{f}: {t.strip()[:60]}" for f, t in added if f in declared and LOG_CALL.search(t)
                and (log_interpolates(t) or not lines_known)]
        if not cfg and not logs and not cat_hits("pii"):
            lit = sum(1 for f, t in added if f in declared and LOG_CALL.search(t))
            ans["secrets"] = (decl_note + "; none is a declared PII path, no application config changed "
                              "(beyond comments), no log statement added that interpolates a value"
                              + (f" ({lit} literal-only log line(s))" if lit else "") + quiet_note
                              + " — secrets across the diff are scanned by the gitleaks gate")
    sup = [f"{f}: {t.strip()[:80]}" for f, t in added if SUPPRESSION.search(t)]
    if not sup:
        ans["suppressions"] = (f"no added line in the diff ({len(added)} added) contains "
                               "@Suppress, @SuppressWarnings or `as Any`")
    deps = [f for f in files if DEP_MANIFEST.search(f)]
    if not deps:
        ans["dependency"] = ("no dependency manifest changed (build.gradle*, libs.versions.toml, "
                             "verification-metadata.xml, package*.json, lockfiles, Dockerfile)")
    elif lines_known:
        hits = {f: dependency_lines(f, add_by.get(f, []), rem_by.get(f, [])) for f in deps}
        if not any(hits.values()):
            ans["dependency"] = (f"{len(deps)} Gradle build script(s) changed ({', '.join(sorted(deps)[:6])}) "
                                 "but no changed line declares, versions, excludes or repositories a "
                                 "dependency or plugin (only e.g. coverage floors, test heap, task config)")
    for key, rx, what in (("auth", AUTH_CRYPTO, "auth/crypto"), ("pii", PII, "PII"),
                          ("cardholder", CARDHOLDER, "cardholder-data")):
        if decl_changed:
            continue  # the declaration itself changed: a human answers every judgement item
        if [f for f in other_code if rx.search(f)] or undeclared:
            continue
        if not unknown:
            ans[key] = inert_note + f"; no non-test file anywhere in the diff matches a {what} path pattern"
            continue
        cat = dict(CATEGORIES)[key]
        if not cat_hits(cat):
            ans[key] = (decl_note + f"; none matches the service's `{cat}` globs, and no non-money-path "
                        f"file in the diff matches a {what} path pattern")
    return ans


# ── declarative sensitive-path classification (#11301) ───────────────────────────────────────
# `rules.yaml: security_sensitive_paths` maps a money-path service to, per judgement category,
# the path globs (relative to the service directory) that hold that category's code. A service
# WITH a declaration gets the three judgement items answered per category: the item auto-passes
# iff no changed production file of that service matches the category's globs. A service
# WITHOUT one stays fail-closed. Editing the declaration itself is governance: it can only be
# NARROWED by a PR that also makes a human tick all three items, so it cannot shrink silently.
DECL_KEY = "security_sensitive_paths"
CATEGORIES = (("auth", "auth_crypto_payment"), ("pii", "pii"), ("cardholder", "cardholder"))
CONFIG_FILE = re.compile(r"/src/main/resources/application[^/]*\.(ya?ml|properties)$")
LOG_CALL = re.compile(r"\b(log|logger|LOG|LOGGER|Log)\s*\.\s*(trace|debug|info|warn|warning|error|infof|warnf|errorf|debugf)\b")

# Coverage-guard signals. Deliberately STRONG signals only (a crypto/JWT import, a PII- or
# card-named property or column, the shared four-eyes library): every hit must sit under that
# service's globs for the category, or the declaration is lying by omission. Not every sensitive
# file carries one — payment execution/posting has no mechanical signature — which is why the
# declaration is reviewed code, and the guard is a floor under it, not the classifier.
SIGNALS: dict[str, list[re.Pattern[str]]] = {
    "auth_crypto_payment": [
        re.compile(r"^\s*import\s+(javax\.crypto|java\.security|javax\.net\.ssl|io\.smallrye\.jwt|"
                   r"org\.eclipse\.microprofile\.jwt|org\.jose4j|com\.nimbusds|org\.bouncycastle|"
                   r"io\.quarkus\.oidc|com\.openbank\.libs\.approval)\b", re.M),
    ],
    "pii": [
        re.compile(r"\b(val|var)\s+(email|emailAddress|dateOfBirth|birthDate|birthNumber|nationalId|"
                   r"personalId|firstName|lastName|fullName|givenName|familyName|phone|phoneNumber|"
                   r"passportNumber|taxId|postalAddress|residentialAddress)\s*:"),
        re.compile(r"^\s*(\"?)(email|date_of_birth|birth_date|birth_number|national_id|first_name|"
                   r"last_name|full_name|given_name|family_name|phone|phone_number|passport_number|"
                   r"tax_id|postal_address|residential_address)\1\s+[A-Za-z]", re.M | re.I),
        re.compile(r"@(Pii|PersonalData|Sensitive)\b"),
    ],
    "cardholder": [
        re.compile(r"\b(val|var)\s+(pan|maskedPan|cvv|cvc|cardNumber|primaryAccountNumber|track2)\s*:"),
        re.compile(r"^\s*(\"?)(pan|masked_pan|cvv|cvc|card_number|primary_account_number)\1\s+[A-Za-z]",
                   re.M | re.I),
    ],
}


def glob_rx(glob: str) -> re.Pattern[str]:
    """`**/` = zero or more directories, `*` = within one segment, `?` = one char."""
    out, i = "", 0
    while i < len(glob):
        if glob.startswith("**/", i):
            out += "(?:.*/)?"; i += 3
        elif glob.startswith("**", i):
            out += ".*"; i += 2
        elif glob[i] == "*":
            out += "[^/]*"; i += 1
        elif glob[i] == "?":
            out += "[^/]"; i += 1
        else:
            out += re.escape(glob[i]); i += 1
    return re.compile(out + r"\Z")


def declarations_from_text(text: str) -> dict[str, dict[str, list[str]]] | None:
    """Parse the declaration block. None = unreadable (the caller fails closed)."""
    try:
        import yaml  # noqa: PLC0415 — only this path needs it
        data = yaml.safe_load(text) or {}
    except Exception:  # noqa: BLE001 — any parse problem is fail-closed, never a pass
        return None
    raw = data.get(DECL_KEY) or {}
    if not isinstance(raw, dict):
        return None
    out: dict[str, dict[str, list[str]]] = {}
    for svc, cats in raw.items():
        if not isinstance(cats, dict) or any(c not in cats for _, c in CATEGORIES):
            continue  # an incomplete declaration is no declaration: fail closed for that service
        if not all(isinstance(cats[c], list) for _, c in CATEGORIES):
            continue
        out[str(svc)] = {c: [str(g) for g in cats[c]] for _, c in CATEGORIES}
    return out


def load_declarations(root: Path) -> dict[str, dict[str, list[str]]]:
    d = declarations_from_text((root / RULES).read_text())
    if d is None:
        print(f"::warning::{DECL_KEY} unreadable — every money-path service treated as undeclared (fail closed)")
        return {}
    return d


def declaration_changed(base: str) -> bool:
    """True when the diff changes the declaration block (or it cannot be compared)."""
    try:
        old = subprocess.run(["git", "show", f"{base}:{RULES}"], capture_output=True, text=True,
                             check=True).stdout
    except subprocess.CalledProcessError:
        return False  # no rules.yaml at base: nothing to narrow
    new = RULES.read_text() if RULES.exists() else ""
    a, b = declarations_from_text(old), declarations_from_text(new)
    return a is None or b is None or a != b


def matches(rel: str, globs: list[str]) -> bool:
    return any(glob_rx(g).match(rel) for g in globs)


def coverage_findings(root: Path, decls: dict[str, dict[str, list[str]]]) -> list[str]:
    """Files under a declared service's production tree that carry a strong signal for a
    category but are not covered by that category's globs."""
    bad: list[str] = []
    for svc, cats in sorted(decls.items()):
        main = root / svc / "src" / "main"
        if not main.is_dir():
            bad.append(f"{svc}: declared but has no src/main — stale declaration")
            continue
        for f in sorted(main.rglob("*")):
            if f.suffix not in (".kt", ".java", ".sql") or not f.is_file():
                continue
            rel = f.relative_to(root / svc).as_posix()
            text = f.read_text(encoding="utf-8", errors="replace")
            for cat, rxs in SIGNALS.items():
                hit = next((m.group(0).strip() for rx in rxs for m in [rx.search(text)] if m), None)
                if hit and not matches(rel, cats[cat]):
                    bad.append(f"{svc}/{rel}: `{hit[:60]}` is a {cat} signal but no {cat} glob covers it")
    return bad


def money_path_dirs(root: Path) -> list[str]:
    """The one list from rules.yaml; a bare `- openbank-foo-service` under
    `money_path_services:` — no YAML dependency for a 20-line block."""
    names: list[str] = []
    in_block = False
    for line in (root / RULES).read_text().splitlines():
        if line.startswith("money_path_services:"):
            in_block = True
            continue
        if in_block:
            m = re.match(r"\s+-\s+([a-z0-9-]+)", line)
            if m:
                names.append(m.group(1))
            elif line and not line.startswith(" ") and not line.startswith("#"):
                break
    if not names:
        print("::error::money_path_services not parseable from rules.yaml — fail closed")
        sys.exit(1)
    return names


def changed_files(base: str) -> list[str]:
    out = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD"],
        capture_output=True, text=True, check=True,
    )
    return [ln for ln in out.stdout.splitlines() if ln]


def unticked(body: str) -> tuple[str, list[str]] | None:
    """None = section absent; otherwise (section, [unticked box texts])."""
    m = SECTION.search(body)
    if not m:
        return None
    rest = body[m.end():]
    nxt = NEXT_HEADING.search(rest)
    section = rest[: nxt.start()] if nxt else rest
    missing = [b.group("text").strip() for b in BOX.finditer(section) if b.group("mark") == " "]
    return section, missing


def run(root: Path, body: str, base: str, enforce: bool,
        live_body: Callable[[], str | None] | None = None) -> int:
    dirs = money_path_dirs(root)
    files = changed_files(base)
    in_money_path = [f for f in files if any(f == d or f.startswith(d + "/") for d in dirs)]
    touched = [f for f in in_money_path if not is_release_derived(f)]
    derived = len(in_money_path) - len(touched)
    decl_changed = declaration_changed(base)
    if decl_changed:
        print(f"security-checklist: rules.yaml `{DECL_KEY}` changed — governance change, the auth, PII "
              "and cardholder items need a human tick regardless of which files moved")
    if not touched and not decl_changed:
        if derived:
            # Say it out loud. A gate that narrows its own scope silently is how a control becomes
            # a no-op nobody notices, so the release-only case reports what it skipped and why.
            print(f"security-checklist: {derived} money-path file(s) are release-derived "
                  f"(CHANGELOG.md / version.txt / .release-please-manifest.json) and carry no code; "
                  f"no other money-path file in this PR — not applicable")
        else:
            print(f"security-checklist: PR touches no money-path directory ({len(files)} files) — not applicable")
        return 0
    if derived:
        print(f"security-checklist: ignoring {derived} release-derived money-path file(s)")
    print(f"security-checklist: {len(touched)} money-path file(s) touched "
          f"({', '.join(sorted({t.split('/')[0] for t in touched}))})")

    decls = load_declarations(root)
    for svc in sorted({t.split("/")[0] for t in touched if not is_inert(t)}):
        print(f"security-checklist: {svc}: " + (f"declared in {DECL_KEY}" if svc in decls
              else f"NOT declared in {DECL_KEY} — judgement items fail closed"))
    add, rem = diff_lines(base)
    answers = decide(files, touched, add, decls, decl_changed, removed=rem)
    auto = {k: v for k, v in answers.items() if v is not None}
    human = [k for k, v in answers.items() if v is None]
    summary = ["### security-checklist-money-path", ""]
    for k, ev in auto.items():
        print(f"security-checklist: [auto] {k}: {ev}")
        summary.append(f"- **{k}** answered by the diff: {ev}")
    if not human:
        print("security-checklist: every checklist item is mechanically answered by the diff "
              "— no human tick required")
        summary.append("\nNo item needs a human for this diff.")
        _write_summary(summary)
        return 0

    if live_body is not None:
        current = live_body()
        if current is not None:
            body = current
    res = unticked(body)
    why = {
        "secrets": ("non-comment production code in an undeclared service, a declared PII path, app config, "
                    "or an added log statement that interpolates a value changed — only a human can say "
                    "no PII reaches logs/config"),
        "suppressions": "the diff ADDS a suppression or `as Any` — state the justification in the PR",
        "dependency": ("a changed manifest line may add/move a dependency or plugin (or a non-Gradle-script "
                       "manifest changed) — attach the dependency review"),
        "auth": ("a declared auth/crypto/payment path, an undeclared money-path service, an auth/crypto path "
                 f"elsewhere, or {DECL_KEY} itself changed — decide on `security-review-required`"),
        "pii": ("a declared PII path, an undeclared money-path service, a PII-pattern path elsewhere, "
                f"or {DECL_KEY} itself changed — decide on `gdpr-review-required`"),
        "cardholder": ("a declared cardholder path, an undeclared money-path service, a cardholder-pattern "
                       f"path elsewhere, or {DECL_KEY} itself changed — decide on `pci-review-required`"),
    }
    need_lines = [f"  - {k}: {why[k]}" for k in human]
    summary += ["", "Items that need a human for this diff:"] + [ln.strip() for ln in need_lines]
    if res is None:
        print("::error::PR touches money-path code and these Security checklist items need a human "
              "(the others were answered from the diff), but the body has no '## Security checklist' section:")
        print("\n".join(need_lines))
        print("::notice::How to fix: copy the '## Security checklist' block from "
              ".github/PULL_REQUEST_TEMPLATE.md into the PR body (or run "
              ".github/scripts/append-security-checklist.py), tick the items listed above, then RE-RUN "
              "this check — this gate reads the live PR body (#8940), so no empty commit is needed. "
              "A PR created with `gh pr create --body-file` never gets the template automatically.")
        _write_summary(summary)
        return 1 if enforce else 0
    _, missing = res
    required = [t for t in missing if item_of(t) is None or item_of(t) in human]
    waived = [t for t in missing if t not in required]
    for t in waived:
        print(f"security-checklist: unticked but answered by the diff: [{item_of(t)}] {t}")
    if required:
        print(f"::error::PR touches money-path code and {len(required)} Security checklist box(es) "
              f"need a human answer for THIS diff and are unticked:")
        for t in required:
            k = item_of(t)
            print(f"  - [ ] {t}\n        why a human: {why.get(k, 'unrecognised checklist line — fail closed')}")
        print("Tick each once true (and apply the review label it names), or state the exception in the PR body.")
        _write_summary(summary)
        return 1 if enforce else 0
    print("security-checklist: every human-judgement box ticked")
    _write_summary(summary)
    return 0


def unknown_box_required(section: str) -> bool:
    """An unticked box the classifier cannot map is always required (used by the self-test)."""
    r = unticked(section)
    return bool(r and any(item_of(t) is None for t in r[1]))


def _write_summary(lines: list[str]) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    try:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
    except OSError:
        pass


def self_test() -> int:
    bad = 0
    tpl = (Path(__file__).resolve().parents[2] / ".github/PULL_REQUEST_TEMPLATE.md").read_text()
    # The template itself ships unticked boxes — it must be flagged.
    r = unticked(tpl)
    if r is None or not r[1]:
        print("self-test FAIL: pristine template not detected as unticked"); bad += 1
    ticked = re.sub(r"-\s*\[ \]", "- [x]", tpl)
    r2 = unticked(ticked)
    if r2 is None or r2[1]:
        print("self-test FAIL: fully ticked template still flagged"); bad += 1
    if unticked("## Summary\nno checklist here\n") is not None:
        print("self-test FAIL: missing section not detected"); bad += 1
    # A box OUTSIDE the section must not count.
    other = "## Security checklist\n- [x] done\n\n## Compliance impact\n- [ ] GDPR\n"
    r3 = unticked(other)
    if r3 is None or r3[1]:
        print("self-test FAIL: box in the next section leaked into the check"); bad += 1
    # ── the release-derived exclusion, both directions (#9230) ───────────────────────────────
    #
    # The must-FAIL half is the load-bearing one: an exclusion that also let a real money-path
    # source file through would be indistinguishable from switching the gate off, and every case
    # above would still pass. So drive the real `run()` over a throwaway git repo — the parser,
    # the diff and the decision, not a mock of them.
    import subprocess as _sp
    import tempfile as _tf

    money = money_path_dirs(Path("."))
    if not money:
        print("self-test FAIL: no money-path service to build a fixture from"); bad += 1
        money = ["openbank-ledger-service"]
    svc = sorted(money)[0]

    def fixture(paths: list[str] | dict[str, str], body: str,
                live_body: Callable[[], str | None] | None = None) -> int:
        with _tf.TemporaryDirectory() as td:
            root = Path(td)
            (root / "openbank-libs" / "governance").mkdir(parents=True)
            (root / "openbank-libs" / "governance" / "rules.yaml").write_text(
                "money_path_services:\n  - " + svc + "\n", encoding="utf-8")
            env = {"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
                   "GIT_COMMITTER_EMAIL": "t@t", "PATH": __import__("os").environ.get("PATH", "")}
            def git(*a):
                _sp.run(["git", "-C", str(root), *a], check=True, capture_output=True, env=env)
            git("init", "-q", "-b", "base")
            git("add", "-A"); git("commit", "-q", "-m", "base")
            items = paths.items() if isinstance(paths, dict) else ((p, "x\n") for p in paths)
            for rel, content in items:
                f = root / rel
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(content, encoding="utf-8")
            git("checkout", "-q", "-b", "head")
            git("add", "-A"); git("commit", "-q", "-m", "head")
            cwd = __import__("os").getcwd()
            try:
                __import__("os").chdir(root)
                return run(root, body, "base", enforce=True, live_body=live_body)
            finally:
                __import__("os").chdir(cwd)

    no_checklist = "## Summary\nrelease\n"
    release_only = [f"{svc}/CHANGELOG.md", f"{svc}/version.txt", ".release-please-manifest.json"]

    rc = fixture(release_only, no_checklist,
                 live_body=lambda: (_ for _ in ()).throw(AssertionError("unneeded API read")))
    if rc != 0:
        print("self-test FAIL: a release-only PR (CHANGELOG/version.txt/manifest) still trips the gate")
        bad += 1

    rc = fixture(release_only + [f"{svc}/src/main/kotlin/Money.kt"], no_checklist)
    if rc == 0:
        print("self-test FAIL: a release PR that ALSO changes money-path source passed — the "
              "exclusion is swallowing real code, which is the gate switched off")
        bad += 1

    rc = fixture([f"{svc}/src/main/kotlin/Money.kt"], no_checklist,
                 live_body=lambda: ticked)
    if rc != 0:
        print("self-test FAIL: money-path PR did not use its live checklist"); bad += 1

    rc = fixture([f"{svc}/src/main/kotlin/Money.kt"], ticked,
                 live_body=lambda: "")
    if rc == 0:
        print("self-test FAIL: deleted live checklist was hidden by stale event body"); bad += 1

    # A name that merely resembles a derived one must not be excluded: the strip is exact-basename.
    rc = fixture([f"{svc}/docs/CHANGELOG.md.bak", f"{svc}/src/main/kotlin/Money.kt"], no_checklist)
    if rc == 0:
        print("self-test FAIL: a near-miss filename was treated as release-derived"); bad += 1


    # ── mechanically decidable items (auto-answer) — required cases (a)–(d) ────────────────────
    test_only = [f"{svc}/src/test/kotlin/MoneyTest.kt", f"{svc}/docs/notes.md", f"{svc}/CLAUDE.md"]
    no_api = lambda: (_ for _ in ()).throw(AssertionError("unneeded API read"))
    # (a) test/docs-only money-path PR, no checklist at all → passes, and never reads the live body.
    if fixture(test_only, no_checklist, live_body=no_api) != 0:
        print("self-test FAIL (a): a test/docs-only money-path PR still demanded ticks"); bad += 1
    # (b) auth/crypto: a money-path signer, and — the case the path pattern alone decides — a
    #     non-money-path security file alongside money-path tests. Both must fail without ticks.
    if fixture(test_only + [f"{svc}/src/main/kotlin/security/TokenSigner.kt"], no_checklist) == 0:
        print("self-test FAIL (b): money-path auth/crypto file passed without a tick"); bad += 1
    auth_elsewhere = test_only + ["openbank-libs-runtime/src/main/kotlin/com/openbank/libs/security/CryptoBox.kt"]
    if fixture(auth_elsewhere, no_checklist) == 0:
        print("self-test FAIL (b2): an auth/crypto path outside the money path was not classified"); bad += 1
    # …and ticking every box EXCEPT auth must still fail: the item is required, not the section.
    only_auth_unticked = "\n".join(
        ln if "Auth / crypto" in ln else ln.replace("- [ ]", "- [x]") for ln in tpl.splitlines())
    if fixture(auth_elsewhere, only_auth_unticked) == 0:
        print("self-test FAIL (b3): auth item unticked yet the gate passed"); bad += 1
    if fixture(auth_elsewhere, ticked) != 0:
        print("self-test FAIL (b4): fully ticked auth PR did not pass"); bad += 1
    # (c) a dependency manifest outside the money path still makes the dependency item human.
    if fixture(test_only + ["gradle/libs.versions.toml"], no_checklist) == 0:
        print("self-test FAIL (c): a dependency change passed without a tick"); bad += 1
    # (d) PII path.
    if fixture(test_only + ["openbank-kyc-service/src/main/kotlin/CustomerAddress.kt"], no_checklist) == 0:
        print("self-test FAIL (d): a PII path passed without a tick"); bad += 1
    # (e) a test that ADDS a suppression makes that item human; ticking just it suffices.
    sup = {f"{svc}/src/test/kotlin/MoneyTest.kt": '@Suppress("UNCHECKED_CAST")\nval x = y as Any\n'}
    if fixture(sup, no_checklist) == 0:
        print("self-test FAIL (e): an added @Suppress passed without a tick"); bad += 1
    sup_ticked = "## Security checklist\n- [x] No new `@SuppressWarnings`, `as Any`, `@Suppress(\"...\")`\n"
    if fixture(sup, sup_ticked) != 0:
        print("self-test FAIL (e2): ticking the one human item did not pass"); bad += 1
    # (f) an unrecognised checklist line stays required (fail closed on template drift).
    odd = "## Security checklist\n- [ ] something new nobody classified\n"
    if fixture([f"{svc}/src/main/kotlin/Money.kt"], odd.replace("[ ]", "[x]") + "- [ ] Auth / crypto x\n") == 0:
        print("self-test FAIL (f): unticked human item inside a partial section passed"); bad += 1
    if unknown_box_required(odd) is False:
        print("self-test FAIL (f2): an unrecognised box was treated as answerable"); bad += 1
    # every real template box must map to a known item, or the waiver silently stops applying.
    r_tpl = unticked(tpl)
    if r_tpl is None or any(item_of(t) is None for t in r_tpl[1]):
        print("self-test FAIL: a template checklist line maps to no classified item"); bad += 1


    # ── declarative sensitive paths (#11301) — each failing case proven to fail ─────────────────
    decl_rules = ("money_path_services:\n  - " + svc + "\n" + DECL_KEY + ":\n  " + svc + ":\n"
                  "    auth_crypto_payment:\n      - \"src/main/kotlin/**/security/**\"\n"
                  "    pii:\n      - \"src/main/kotlin/**/*Customer*.kt\"\n    cardholder: []\n")

    def dfixture(rules_head: str, paths: dict[str, str], body: str, rules_base: str = decl_rules) -> int:
        with _tf.TemporaryDirectory() as td:
            root = Path(td)
            env = {"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
                   "GIT_COMMITTER_EMAIL": "t@t", "PATH": os.environ.get("PATH", "")}
            def git(*a):
                _sp.run(["git", "-C", str(root), *a], check=True, capture_output=True, env=env)
            (root / RULES).parent.mkdir(parents=True)
            (root / RULES).write_text(rules_base, encoding="utf-8")
            git("init", "-q", "-b", "base"); git("add", "-A"); git("commit", "-q", "-m", "base")
            git("checkout", "-q", "-b", "head")
            (root / RULES).write_text(rules_head, encoding="utf-8")
            for rel, content in paths.items():
                f = root / rel
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(content, encoding="utf-8")
            git("add", "-A"); git("commit", "-q", "-m", "head")
            cwd = os.getcwd()
            try:
                os.chdir(root)
                return run(root, body, "base", enforce=True)
            finally:
                os.chdir(cwd)

    refactor = {f"{svc}/src/main/kotlin/com/x/domain/Money.kt": "class Money(val amount: Long)\n"}
    # (g) refactor outside every sensitive glob, declared service → passes with NO checklist.
    if dfixture(decl_rules, refactor, no_checklist) != 0:
        print("self-test FAIL (g): declared-service refactor outside sensitive globs still demanded ticks"); bad += 1
    # (h) change inside the auth glob → fails without the tick.
    if dfixture(decl_rules, {f"{svc}/src/main/kotlin/com/x/security/Guard.kt": "x\n"}, no_checklist) == 0:
        print("self-test FAIL (h): change inside a declared auth glob passed without a tick"); bad += 1
    # (h2) inside the PII glob → fails too, and an added log line makes the secrets item human.
    if dfixture(decl_rules, {f"{svc}/src/main/kotlin/com/x/CustomerView.kt": "x\n"}, no_checklist) == 0:
        print("self-test FAIL (h2): change inside a declared PII glob passed without a tick"); bad += 1
    if dfixture(decl_rules, {f"{svc}/src/main/kotlin/com/x/domain/Money.kt": 'log.info("amount $amount")\n'},
                no_checklist) == 0:
        print("self-test FAIL (h3): an added interpolating log statement passed without a tick"); bad += 1
    # ── line-level refinements — must-PASS / must-FAIL pairs ────────────────────────────────────
    # Two layers. A few cases run end-to-end through git (so diff_lines' parsing — removed lines,
    # deleted files — is exercised as the gate runs it); the full matrix drives decide() with the
    # changed lines directly, because thirty throwaway repos cost ~15 s of the gate's budget.
    decl = declarations_from_text(decl_rules) or {}
    src = f"{svc}/src/main/kotlin/com/x/Pay.kt"
    gradle = f"{svc}/build.gradle.kts"
    cfg = f"{svc}/src/main/resources/application.yaml"

    def answers(changes: dict[str, tuple[list[str], list[str]]], declared: bool = True) -> dict:
        files = list(changes)
        add = [(f, t) for f, (a, _) in changes.items() for t in a]
        rem = [(f, t) for f, (_, r) in changes.items() for t in r]
        return decide(files, [f for f in files if f.startswith(svc + "/")], add,
                      decl if declared else {}, False, removed=rem)

    def expect(tag: str, item: str, auto: bool, changes: dict, declared: bool = True) -> None:
        nonlocal bad
        got = answers(changes, declared)[item] is not None
        if got != auto:
            print(f"self-test FAIL ({tag}): {item} {'still needs a human' if auto else 'auto-answered'} "
                  f"for {changes}"); bad += 1

    # secrets ← log lines. must-FAIL: every interpolation shape; must-PASS: literal-only.
    for i, ln in enumerate(('log.info("amount {}", amount)', 'LOG.warn("iban " + iban)',
                            'logger.debug { "x=$x" }', 'log.error(', 'log.info("""raw $x""")',
                            'log.info("ok"); log.warn("pan $pan")', 'log.info("a $b")')):
        expect(f"h4.{i}", "secrets", False, {f"{svc}/src/main/kotlin/com/x/domain/M.kt": ([ln], [])})
    for i, ln in enumerate(('log.info("projection started")', 'LOG.warn("retrying \\$ literal");',
                            'if (x) logger.debug("skip")')):
        expect(f"h5.{i}", "secrets", True, {f"{svc}/src/main/kotlin/com/x/domain/M.kt": ([ln], [])})
    # dependency ← build-script lines. must-PASS: floors, heap, comments, task config.
    for i, (a, r) in enumerate(((["        minValue = 85"], ["        minValue = 80"]),
                                (['    maxHeapSize = "3g"'], ['    maxHeapSize = "2g"']),
                                (["// why the heap is 3g", "tasks.withType<Test>().configureEach {",
                                  '    maxHeapSize = "2g"', "}"], []),
                                (["// implementation(libs.old) was removed upstream"], []))):
        expect(f"l.{i}", "dependency", True, {gradle: (a, r)})
    # must-FAIL: every way a script can add, version, move or un-comment a dependency or plugin.
    for i, (a, r) in enumerate(((["    implementation(libs.quarkus.kafka)"], []),
                                (['    testImplementation("org.foo:bar:1.0")'], []),
                                (['    id("info.solidsoft.pitest") version "1.19.0"'], []),
                                (['    junit5PluginVersion = "1.2.3"'], []),
                                (['val fooRev = "2.0"'], []),
                                (['    implementation(libs.a) { exclude(group = "x") }'],
                                 ["    implementation(libs.a)"]),
                                ([], ["/*", "*/"]),
                                (['    integrationTestImplementation(libs.x)'], []),
                                (['    "org.foo:bar:2.0",'], ['    "org.foo:bar:1.0",']),
                                (['    systemProperty("v", "${rootProject.version}")'], []),
                                (["    mavenLocal()"], []),
                                (["    force(libs.netty)"], []),
                                ([], []))):
        expect(f"l2.{i}", "dependency", False, {gradle: (a, r)})
    # a non-script manifest is a dependency change on any edit, even a comment.
    expect("l3", "dependency", False, {"gradle/libs.versions.toml": (["# note"], [])})
    expect("l3b", "dependency", False, {f"{svc}/gradle.properties": (["org.gradle.caching=true"], [])})
    # secrets ← comment-only / build-only, UNDECLARED service. must-PASS secrets; auth stays human.
    kdoc = (["/**", " * Executes the payment.", " */", "    // no retries here"], [])
    expect("m", "secrets", True, {src: kdoc}, declared=False)
    expect("m.b", "secrets", True, {gradle: (["        minValue = 85"], ["        minValue = 80"])},
           declared=False)
    for item in ("auth", "pii", "cardholder"):
        expect(f"m2.{item}", item, False, {src: kdoc}, declared=False)
        expect(f"m2b.{item}", item, False, {gradle: (["// x"], [])}, declared=False)
    # must-FAIL: a code line beside a comment; uncommenting; an early or unbalanced close; code
    # after an inline block comment; a no-line change (rename) is not "comment-only".
    for i, ch in enumerate(((["// note", '    fun x() = log.info("iban $iban")'], []),
                            (['    fun leak() = log.info("pan $pan")'], ["/*", "*/"]),
                            ([], ["*/"]),
                            (["*/"], []),
                            (['/* off */ fun leak() = 1'], []),
                            ([" * doc */ val x = 1"], []),
                            ([], []))):
        expect(f"m3.{i}", "secrets", False, {src: ch}, declared=False)
    # config: comment-only is quiet; a real value is not (declared service).
    expect("n", "secrets", True, {cfg: (["  # why b is 1"], [])})
    expect("n2", "secrets", False, {cfg: (["  # why", "  b: 2"], ["  b: 1"])})
    # removed=None (lines unknown) must fall back to the file-level rules, never to a pass.
    if decide([gradle], [gradle], [(gradle, "    minValue = 85")], decl, False,
              removed=None)["dependency"] is not None:
        print("self-test FAIL (o): unknown lines answered the dependency item"); bad += 1

    # end-to-end through git (parser included): one must-PASS and two must-FAIL.
    base_gradle = ('plugins {\n    id("openbank.quarkus-service")\n}\n\ndependencies {\n'
                   '    implementation(libs.quarkus.core)\n}\n\nkover {\n    minValue = 80\n}\n')

    def efixture(base_files: dict[str, str], head_files: dict[str, str], body: str) -> int:
        with _tf.TemporaryDirectory() as td:
            root = Path(td)
            env = {"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
                   "GIT_COMMITTER_EMAIL": "t@t", "PATH": os.environ.get("PATH", "")}
            def git(*a):
                _sp.run(["git", "-C", str(root), *a], check=True, capture_output=True, env=env)
            def put(files: dict[str, str]) -> None:
                for rel, content in files.items():
                    f = root / rel
                    f.parent.mkdir(parents=True, exist_ok=True)
                    f.write_text(content, encoding="utf-8")
            (root / RULES).parent.mkdir(parents=True)
            (root / RULES).write_text(decl_rules, encoding="utf-8")
            put(base_files)
            git("init", "-q", "-b", "base"); git("add", "-A"); git("commit", "-q", "-m", "base")
            git("checkout", "-q", "-b", "head")
            put(head_files)
            git("add", "-A"); git("commit", "-q", "-m", "head")
            cwd = os.getcwd()
            try:
                os.chdir(root)
                return run(root, body, "base", enforce=True)
            finally:
                os.chdir(cwd)

    floors = base_gradle.replace("minValue = 80", "minValue = 85")
    if efixture({gradle: base_gradle}, {gradle: floors}, no_checklist) != 0:
        print("self-test FAIL (e2e-l): coverage-floor-only build script edit still demanded ticks"); bad += 1
    added_dep = base_gradle.replace("    implementation(libs.quarkus.core)\n",
                                    "    implementation(libs.quarkus.core)\n    implementation(libs.evil)\n")
    if efixture({gradle: base_gradle}, {gradle: added_dep}, no_checklist) == 0:
        print("self-test FAIL (e2e-l2): an added implementation() line passed the dependency item"); bad += 1
    hidden = base_gradle.replace("}\n\nkover", "/*\n    implementation(libs.evil)\n*/\n}\n\nkover")
    if efixture({gradle: hidden}, {gradle: added_dep}, no_checklist) == 0:
        print("self-test FAIL (e2e-l3): un-commenting a dependency passed the dependency item"); bad += 1
    # (i) the same refactor in an UNDECLARED service → fails (fail closed).
    undeclared_rules = "money_path_services:\n  - " + svc + "\n"
    if dfixture(undeclared_rules, refactor, no_checklist, rules_base=undeclared_rules) == 0:
        print("self-test FAIL (i): undeclared money-path service auto-passed"); bad += 1
    # (j) narrowing the declaration → fails, even in the same PR as a harmless refactor, and even
    #     when no money-path file moved at all (a rules.yaml-only PR).
    narrowed = decl_rules.replace('      - \"src/main/kotlin/**/security/**\"\n', "").replace(
        "auth_crypto_payment:\n", "auth_crypto_payment: []\n")
    if declarations_from_text(narrowed) == declarations_from_text(decl_rules):
        print("self-test FAIL (j0): fixture did not narrow the declaration"); bad += 1
    if dfixture(narrowed, refactor, no_checklist) == 0:
        print("self-test FAIL (j): narrowing security_sensitive_paths passed without a tick"); bad += 1
    if dfixture(narrowed, {}, no_checklist) == 0:
        print("self-test FAIL (j2): a rules.yaml-only edit of security_sensitive_paths passed"); bad += 1
    if dfixture(narrowed, refactor, ticked) != 0:
        print("self-test FAIL (j3): ticked declaration change did not pass"); bad += 1
    # (k) coverage guard: an uncovered crypto import is caught; covered one is not.
    with _tf.TemporaryDirectory() as td:
        root = Path(td)
        (root / RULES).parent.mkdir(parents=True)
        (root / RULES).write_text(decl_rules, encoding="utf-8")
        f = root / svc / "src/main/kotlin/com/x/domain/Hasher.kt"
        f.parent.mkdir(parents=True)
        f.write_text("import javax.crypto.Mac\nclass Hasher\n", encoding="utf-8")
        if run_coverage(root) == 0:
            print("self-test FAIL (k): uncovered javax.crypto import not caught by coverage guard"); bad += 1
        f.rename(root / svc / "src/main/kotlin/com/x/security_moved.kt")
        g = root / svc / "src/main/kotlin/com/x/security/Hasher.kt"
        g.parent.mkdir(parents=True)
        g.write_text("import javax.crypto.Mac\n", encoding="utf-8")
        (root / svc / "src/main/kotlin/com/x/security_moved.kt").unlink()
        if run_coverage(root) != 0:
            print("self-test FAIL (k2): covered crypto import still flagged"); bad += 1
        (root / svc / "src/main/kotlin/com/x/Card.kt").write_text("data class Card(val pan: String)\n")
        if run_coverage(root) == 0:
            print("self-test FAIL (k3): uncovered PAN field not caught"); bad += 1

    print("security-checklist self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


def run_coverage(root: Path) -> int:
    decls = declarations_from_text((root / RULES).read_text())
    if decls is None:
        print(f"::error::{DECL_KEY} unreadable"); return 1
    money = set(money_path_dirs(root))
    stray = sorted(set(decls) - money)
    bad = coverage_findings(root, decls)
    print(f"SUBJECTS={len(decls)}")
    for svc in stray:
        bad.append(f"{svc}: declared in {DECL_KEY} but not a money_path_services entry")
    print(f"sensitive-paths coverage: {len(decls)} declared, {len(money - set(decls))} money-path "
          f"service(s) undeclared (fail closed): {', '.join(sorted(money - set(decls))) or '-'}")
    for b in bad:
        print(f"::error::{b}")
    if bad:
        print(f"{len(bad)} finding(s): add a glob to that service's category in rules.yaml: {DECL_KEY} "
              "(a governance change — the PR then needs the human checklist tick).")
        return 1
    print("sensitive-paths coverage: clean")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--body-file", required=False)
    ap.add_argument("--body", default=None, help="PR body inline (the runner passes $PR_BODY)")
    ap.add_argument("--base", default="origin/main")
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--live-body", action="store_true",
                    help="Read the current PR body only if money-path files were changed")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--coverage", action="store_true",
                    help=f"fail when a strong sensitive-code signal sits outside {DECL_KEY}")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.coverage:
        return run_coverage(Path(args.root))
    body = Path(args.body_file).read_text() if args.body_file else args.body
    if body is None:
        ap.error("--body-file or --body is required outside --self-test")
    def fetch_live_body() -> str | None:
        repo = os.environ.get("GITHUB_REPOSITORY", "")
        number = os.environ.get("PR_NUMBER", "")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) or not number.isdigit():
            return None
        try:
            result = subprocess.run(
                ["gh", "api", f"repos/{repo}/pulls/{number}", "--jq", '.body // ""'],
                capture_output=True, text=True, check=True, timeout=10,
            )
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            return None
        return result.stdout.strip()

    return run(Path(args.root), body, args.base, args.enforce,
               live_body=fetch_live_body if args.live_body else None)


if __name__ == "__main__":
    sys.exit(main())
