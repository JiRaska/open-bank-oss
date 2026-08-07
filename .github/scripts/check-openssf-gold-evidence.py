#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Evidence discipline for docs/compliance/openssf-gold-gap.md (#3884).

WHY THIS EXISTS
---------------
That document feeds the OpenSSF BadgeApp form -- a public attestation to a third party.
It shipped with `crypto_used_network` / `crypto_tls12` scored met on the justification
"TLS + Istio mTLS", while the live cluster has ZERO Istio CRDs and ZERO Istio namespaces
(#1914 is open for exactly that). Three other rows were scored met on justifications that
named no checkable thing at all: "Attest", "Falco, NetworkPolicies, OPA, seccomp,
PSS-restricted", "Owner attests GitHub 2FA is enforced". An assessor checks the EVIDENCE,
not the tick, so a justification naming a control that does not exist is worse than an
honest gap. Hand-corrected conformance rows rot exactly the way the originals did --
`check-compliance-matrix.py` exists for the admin-ui page for the same reason. Nothing
read this file before this gate.

WHY IT IS STRUCTURAL, NOT A WORD DENY-LIST
------------------------------------------
The obvious design -- ban the string "Istio" -- is the classic gate-over-prose failure:
it matches the sentence EXPLAINING that no mesh is deployed just as readily as a false
citation, and the corrected document necessarily contains that sentence. So this gate
never reasons about what a word means. It asserts a structural property instead:

  R1  A row scored met must cite at least one RESOLVABLE ANCHOR -- a repo path that
      exists, an issue reference, or a URL. "TLS + Istio mTLS" contains none of those
      and fails; so do "Attest" and the bare control-name list. The corrected rows pass
      because they name files. A row scored partial or not-met is EXEMPT: describing a
      gap is what those rows are for, and demanding evidence for a confessed gap would
      be nonsense.

  R2  Every path-shaped citation ANYWHERE in the tables must resolve on disk (globs
      allowed). This is the actual rot mechanism -- a cited file gets renamed and the
      justification silently becomes fiction.

Scope is the CONSTRUCT, not the file: only 3-cell markdown table rows whose first cell
begins with a backticked criterion id are parsed, and only the third cell is read. Body
prose, blockquotes and headings are never examined, so the note explaining the mesh gap
cannot trip anything.

The row count is printed on every run: a green that says "0 rows" is a gate that never
opened the file, and that must be visible.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[2]
DOC_REL = "docs/compliance/openssf-gold-gap.md"

MET = "✅"  # white heavy check mark -- "met, with measured evidence"

# A table row we care about: exactly three cells, first cell opens with a backtick.
ROW_RE = re.compile(r"^\|([^|]*)\|([^|]*)\|([^|]*)\|\s*$")
BACKTICKED = re.compile(r"`([^`]+)`")
ISSUE_REF = re.compile(r"#\d+")
URL_REF = re.compile(r"https?://")


def looks_like_path(token: str) -> bool:
    """A backticked token we should try to resolve against the working tree.

    Deliberately narrow. A token qualifies only if it contains a path separator and
    nothing that marks it as prose, a URL, config syntax or markup -- otherwise
    `<verify-signatures>false</verify-signatures>` and `https://open-bank.tech` would be
    treated as filenames.
    """
    if not token or " " in token or "\t" in token:
        return False
    if token.startswith(("http://", "https://")):
        return False
    if any(c in token for c in "<>=:()\"'"):
        return False
    return "/" in token


def resolve_path(repo: pathlib.Path, token: str) -> bool:
    token = token.rstrip("/")
    if not token:
        return False
    if "*" in token or "?" in token:
        return any(repo.glob(token))
    return (repo / token).exists()


def parse_rows(text: str) -> list[tuple[int, str, str, str]]:
    """Yield (lineno, criterion, state, evidence) for criterion table rows only."""
    rows = []
    for i, line in enumerate(text.splitlines(), start=1):
        m = ROW_RE.match(line)
        if not m:
            continue
        crit, state, evidence = (c.strip() for c in m.groups())
        # Header rows ("| Criterion | State | ... |") and separators never start with a
        # backtick; requiring one is what keeps this scoped to real criterion rows.
        if not crit.startswith("`"):
            continue
        rows.append((i, crit, state, evidence))
    return rows


def check(repo: pathlib.Path, doc_rel: str) -> tuple[list[str], int]:
    doc = repo / doc_rel
    if not doc.is_file():
        return ([f"{doc_rel}: not found"], 0)

    text = doc.read_text(encoding="utf-8")
    rows = parse_rows(text)
    problems: list[str] = []

    if not rows:
        problems.append(f"{doc_rel}: no criterion rows parsed -- the table shape changed")
        return (problems, 0)

    for lineno, crit, state, evidence in rows:
        # R2 applies to every row: a cited path must exist.
        for token in BACKTICKED.findall(evidence):
            if looks_like_path(token) and not resolve_path(repo, token):
                problems.append(
                    f"{doc_rel}:{lineno}: {crit} cites `{token}`, which does not exist"
                )

        # R1 applies only to rows scored met.
        if MET not in state:
            continue

        has_path = any(
            looks_like_path(t) and resolve_path(repo, t)
            for t in BACKTICKED.findall(evidence)
        )
        if has_path or ISSUE_REF.search(evidence) or URL_REF.search(evidence):
            continue
        problems.append(
            f"{doc_rel}:{lineno}: {crit} is scored {MET} but its justification cites no "
            f"resolvable evidence (no existing repo path, issue ref or URL): "
            f"{evidence!r}. Cite a checkable artefact, or downgrade the row."
        )

    return (problems, len(rows))


# --------------------------------------------------------------------------- self-test

_HEADER = "| Criterion | State | Evidence / action |\n|---|---|---|\n"


def _self_test() -> int:
    """Falsify in BOTH directions: it must flag what is false and pass what is true."""
    cases: list[tuple[str, str, bool]] = [
        # (name, table body, expect_problem)
        (
            "the real #3884 defect: met, justification names only a control",
            "| `crypto_tls12` | ✅ | TLS + Istio mTLS |\n",
            True,
        ),
        (
            "met with bare 'Attest' as the justification",
            "| `test_invocation` | ✅ | Attest |\n",
            True,
        ),
        (
            "met on a list of control names with no artefact",
            "| `hardening` | ✅ | Falco, NetworkPolicies, OPA, seccomp, PSS-restricted |\n",
            True,
        ),
        (
            "met citing a repo path that does not exist",
            "| `dynamic_analysis` | ✅ | `.github/workflows/nope-does-not-exist.yml` |\n",
            True,
        ),
        (
            "TRUE ROW must not be flagged: met citing an existing path",
            "| `dynamic_analysis` | ✅ | `.github/scripts/check-openssf-gold-evidence.py` |\n",
            False,
        ),
        (
            "TRUE ROW must not be flagged: met citing an issue",
            "| `crypto_tls12` | ✅ | Edge TLS via cert-manager; not mesh mTLS, see #1914 |\n",
            False,
        ),
        (
            "TRUE ROW must not be flagged: met citing a URL",
            "| `hardened_site` | ✅ | HSTS+CSP measured on https://open-bank.tech |\n",
            False,
        ),
        (
            "PROSE-VS-THING: a NOT-MET row may name a mesh freely",
            "| `crypto_tls12` | ❌ | No Istio mesh mTLS is deployed |\n",
            False,
        ),
        (
            "PROSE-VS-THING: a PARTIAL row needs no anchor",
            "| `achieve_silver` | ⚠️ | Not verifiable from this repo |\n",
            False,
        ),
    ]

    failures = 0
    with tempfile.TemporaryDirectory() as d:
        tmp = pathlib.Path(d)
        # Give the sandbox the one real file the "true row" cases cite.
        real = tmp / ".github/scripts"
        real.mkdir(parents=True)
        (real / "check-openssf-gold-evidence.py").write_text("x", encoding="utf-8")
        rel = "doc.md"

        for name, body, expect_problem in cases:
            # Prose that would trip a naive word deny-list, present in every case.
            prose = (
                "> No service mesh is deployed. There are zero Istio CRDs; the "
                "istio.yaml manifest is referenced by no ArgoCD Application (#1914).\n\n"
            )
            (tmp / rel).write_text(prose + _HEADER + body, encoding="utf-8")
            problems, n_rows = check(tmp, rel)
            got = bool(problems)
            if n_rows != 1:
                print(f"::error::self-test '{name}': parsed {n_rows} rows, expected 1")
                failures += 1
                continue
            if got != expect_problem:
                verb = "expected a finding, got none" if expect_problem else "false positive"
                print(f"::error::self-test '{name}': {verb} -- {problems}")
                failures += 1
            else:
                print(f"  ok  [{'flags' if expect_problem else 'passes'}] {name}")

        # Scope guard: a doc with no criterion rows must be reported, never silently green.
        (tmp / rel).write_text("# nothing here\n", encoding="utf-8")
        problems, n_rows = check(tmp, rel)
        if not problems or n_rows != 0:
            print("::error::self-test: an empty document was not reported")
            failures += 1
        else:
            print("  ok  [flags] a document with zero criterion rows is reported")

    if failures:
        print(f"::error::self-test: {failures} case(s) failed")
        return 1
    print(f"self-test: all {len(cases) + 1} cases passed (both directions)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--self-test", action="store_true", help="run the built-in cases")
    args = ap.parse_args()

    if args.self_test:
        return _self_test()

    problems, n_rows = check(REPO, DOC_REL)
    # Always print the count: a green claiming 0 rows means the file was never opened.
    print(f"openssf-gold-evidence: checked {n_rows} criterion row(s) in {DOC_REL}")
    if problems:
        for p in problems:
            print(f"::error::{p}")
        print(f"::error::{len(problems)} evidence problem(s) in {DOC_REL}")
        return 1
    print("openssf-gold-evidence: every row scored met cites resolvable evidence")
    return 0


if __name__ == "__main__":
    sys.exit(main())
