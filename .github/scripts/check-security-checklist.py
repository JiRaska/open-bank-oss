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
  dependency      build/dependency manifests in the diff                 none changed anywhere
  auth/crypto/pay path classification                                    money-path files all INERT and no
                                                                         auth/crypto path changed anywhere
  PII path        path classification                                    same, PII patterns
  cardholder      path classification                                    same, cardholder patterns

INERT is a closed, positive list: `src/test/`, `src/testFixtures/`, `src/integrationTest/`,
`e2e/`, `docs/`, and `*.md` files. Everything else under a money-path service — src/main,
application.yaml, a migration, openapi.yaml, a Dockerfile, build.gradle.kts, and any path
this list does not name — is UNKNOWN, and every judgement item stays human (fail closed):
in a money-path service, production code IS payment code. A box the classifier does not
recognise stays required too. The gate writes its evidence for each auto-answered item to
the job log and $GITHUB_STEP_SUMMARY, so the answer is reviewable, not silent.

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
    r"(^|/)(build\.gradle(\.kts)?|settings\.gradle(\.kts)?|libs\.versions\.toml|"
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


def added_lines(base: str) -> list[tuple[str, str]]:
    out = subprocess.run(["git", "diff", "-U0", f"{base}...HEAD"],
                         capture_output=True, text=True, check=True).stdout
    cur, res = "", []
    for ln in out.splitlines():
        if ln.startswith("+++ "):
            cur = ln[6:] if ln.startswith("+++ b/") else ""
        elif ln.startswith("+") and not ln.startswith("+++"):
            res.append((cur, ln[1:]))
    return res


def decide(files: list[str], touched: list[str], added: list[tuple[str, str]]) -> dict[str, str | None]:
    """item -> evidence string when the diff answers it (auto-pass), or None when a human must.
    The None branches are the default; an item is auto-answered only by positive evidence."""
    unknown = [f for f in touched if not is_inert(f)]
    code = [f for f in files if not is_inert(f)]
    ans: dict[str, str | None] = {k: None for k, _ in ITEMS}
    inert_note = (f"all {len(touched)} money-path file(s) are tests/docs "
                  f"(src/test, e2e, docs, *.md): " + ", ".join(sorted(touched)[:8])
                  + (" …" if len(touched) > 8 else ""))
    if not unknown:
        ans["secrets"] = (inert_note + "; no production code/config/log statement changed on the "
                          "money path, and secrets across the whole diff are scanned by the gitleaks gate")
    sup = [f"{f}: {t.strip()[:80]}" for f, t in added if SUPPRESSION.search(t)]
    if not sup:
        ans["suppressions"] = (f"no added line in the diff ({len(added)} added) contains "
                               "@Suppress, @SuppressWarnings or `as Any`")
    deps = [f for f in files if DEP_MANIFEST.search(f)]
    if not deps:
        ans["dependency"] = ("no dependency manifest changed (build.gradle*, libs.versions.toml, "
                             "verification-metadata.xml, package*.json, lockfiles, Dockerfile)")
    for key, rx, what in (("auth", AUTH_CRYPTO, "auth/crypto"), ("pii", PII, "PII"),
                          ("cardholder", CARDHOLDER, "cardholder-data")):
        hits = [f for f in code if rx.search(f)]
        if not unknown and not hits:
            ans[key] = (inert_note + f"; no non-test file anywhere in the diff matches a {what} path pattern")
    return ans


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
    if not touched:
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

    answers = decide(files, touched, added_lines(base))
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
        "secrets": "money-path production code/config changed — only a human can say no PII reaches logs/config",
        "suppressions": "the diff ADDS a suppression or `as Any` — state the justification in the PR",
        "dependency": "a dependency/build manifest changed — attach the dependency review",
        "auth": "non-test money-path code or an auth/crypto path changed — decide on `security-review-required`",
        "pii": "non-test money-path code or a PII-pattern path changed — decide on `gdpr-review-required`",
        "cardholder": "non-test money-path code or a cardholder-pattern path changed — decide on `pci-review-required`",
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

    print("security-checklist self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


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
    args = ap.parse_args()
    if args.self_test:
        return self_test()
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
