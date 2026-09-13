#!/usr/bin/env python3
"""Every action declared four-eyes must be reachable by at least one allow reason.

WHY THIS EXISTS
---------------
`rules.yaml: four_eyes.verbs` declares which verbs pause for a second approver. Whether the
approval can ever be *requested* depends on something else entirely: the action must have an
allow reason, either in `authz.role_action_matrix` or in a service-scoped `*_rest_ext.rego`.
Nothing connected the two.

Measured on `origin/main` (#4754): `transaction.sweep` is declared four-eyes and granted in
neither place. Two consequences, and the second is the reason this is a gate rather than a
comment on one issue:

1. `response_attributes` rides on the **allow** object in `rest.rego`, so a denied decision
   carries no attributes at all. `four_eyes_required = true` is computed and never reaches
   `AuthorizeInterceptor` -- the four-eyes gate on that endpoint is structurally unreachable, and
   no toggle changes it. `rules.yaml` asserts the merge flow's two halves "both pause for a second
   approver, or neither does". For the sweep half that is not true and cannot become true.
2. The day `AUTHZ_ENFORCE` flips to `true` for that service, the endpoint 403s for every
   principal. So the state is the worst of both: the advertised control cannot fire, and enabling
   enforcement breaks the endpoint outright.

Neither is visible in normal operation, because the deny is advisory today and a control that
cannot fire produces no signal -- the same shape as a gate whose scope list is short reading as
passing rather than as unchecked.

WHAT IT CHECKS, AND WHAT IT DOES NOT
------------------------------------
For every `@Authorize(action = "x.verb")` in `*/src/main/**.kt` whose trailing verb appears in
`four_eyes.verbs`, the action must appear in `role_action_matrix` or in some `.rego` under
`openbank-infra/gitops/components`.

It asks whether an allow reason EXISTS, not whether it is correct or appropriately scoped. A
grant that is present but wrong is out of scope here -- `opa eval` against the bundle answers
that, and reading the rego cannot (several reasons can match; only evaluation says which fires).
Do not read a green here as "this action's authorization is right".
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys

REF = "origin/main"
AUTHORIZE_RE = re.compile(r'@Authorize\(\s*action\s*=\s*"([^"]+)"')
VERB_RE = re.compile(r"^\s+- (\w+)", re.MULTILINE)

# Declared, not inferred. An entry needs a reason and is expected to shrink; the gate also fails
# on an entry that has become granted, so the declaration cannot outlive the debt.
#
# All three are money-path and all three are the SAME defect, found together (#4754). They are
# baselined rather than fixed here because granting them is an authoring decision: adding them to
# `role_action_matrix` would be a grant to a machine (M2M callers authenticate with a
# client_credentials JWT and are classified HUMAN, and `shared_m2m_write_prohibition` is not
# emitted into any bundle, so no policy can veto it). The defensible shape is a service-scoped
# rego rule pinned to human operators -- which for transaction-service means creating its first
# `*_rest_ext.rego` -- and each such edit restamps ~73 bundle files.
KNOWN_UNGRANTED: dict[str, str] = {
    "transaction.sweep": (
        "#4754. Declared four-eyes, granted nowhere. Measured with `opa eval` against the "
        "materialised transaction bundle: allow=false for every principal probed, with a "
        "must-DENY and a must-ALLOW control in the same run. AUTHZ_ENFORCE defaults false for "
        "transaction-service with no gitops override, so the deny is advisory today."
    ),
    "swift.send": (
        "#4754 sibling, same shape. No literal and no prefix rule anywhere under "
        "openbank-infra/gitops/components. AUTHZ_ENFORCE defaults false for swift-service."
    ),
    "sctInstPayment.recall": (
        "#4754 sibling, same shape. No literal and no prefix rule anywhere under "
        "openbank-infra/gitops/components. AUTHZ_ENFORCE defaults false for sepa-instant."
    ),
}


def sh(*args: str) -> str:
    # check=False: a `git grep` with no match exits 1, which is data here, not an error.
    return subprocess.run(args, capture_output=True, text=True, check=False).stdout


def four_eyes_verbs(rules: str) -> set[str]:
    m = re.search(r"^four_eyes:\n  verbs:\n((?:\s+-.*\n|\s+#.*\n|\s{10,}#.*\n)+)", rules, re.MULTILINE)
    if not m:
        raise SystemExit("::error::could not locate four_eyes.verbs in rules.yaml")
    return set(VERB_RE.findall(m.group(1)))


def authorize_actions(ref: str) -> dict[str, str]:
    files = [
        line.split(":", 1)[1]
        for line in sh("git", "grep", "-l", "@Authorize", ref, "--", "*/src/main/*.kt").split()
    ]
    out: dict[str, str] = {}
    for path in files:
        for action in AUTHORIZE_RE.findall(sh("git", "show", f"{ref}:{path}")):
            out.setdefault(action, path)
    return out


def matrix_text(rules: str) -> str:
    m = re.search(r"^\s+role_action_matrix:\n((?:.*\n)*?)^\s{2}\w", rules, re.MULTILINE)
    return m.group(1) if m else ""


PREFIX_RE = re.compile(r'startswith\(\s*input\.action\s*,\s*"([^"]+)"')


def rego_corpus(ref: str) -> str:
    """Every rego AND every bundle ConfigMap, concatenated.

    Bundles are read too because a service's policy may live only inside its generated
    ConfigMap -- transaction-service has no `*_rest_ext.rego` file at all, so a rego-only
    search would report a clean sweep of a corpus it never saw.
    """
    parts = []
    for pathspec in (
        "openbank-infra/gitops/components/**/*.rego",
        "openbank-infra/gitops/components/**/*opa-bundle*.yaml",
    ):
        for line in sh("git", "grep", "-l", "--", "input.action", ref, "--", pathspec).split():
            parts.append(sh("git", "show", f"{ref}:{line.split(':', 1)[1]}"))
    return "\n".join(parts)


def granted_in_policy(corpus: str, action: str) -> str | None:
    """Return the granting mechanism, or None.

    Two mechanisms, and missing the second produces false positives that read as live outages:
    a literal action string, and a PREFIX rule -- customer-edge grants every `customer.*` action
    through one `startswith(input.action, "customer.")`, so `customer.pockets.convert` appears
    nowhere as a literal and is nonetheless allowed. Checked against that exact case.
    """
    if f'"{action}"' in corpus:
        return "literal action in policy"
    for prefix in PREFIX_RE.findall(corpus):
        if action.startswith(prefix):
            return f'prefix rule startswith(input.action, "{prefix}")'
    return None


def evaluate(actions: dict[str, str], verbs: set[str], matrix: str, corpus: str):
    """Return (ungranted, granted) as lists of (action, path, where)."""
    ungranted, granted = [], []
    for action, path in sorted(actions.items()):
        if action.split(".")[-1] not in verbs:
            continue
        if action in matrix:
            granted.append((action, path, "role_action_matrix"))
            continue
        where = granted_in_policy(corpus, action)
        if where:
            granted.append((action, path, where))
        else:
            ungranted.append((action, path, None))
    return ungranted, granted


def self_test() -> int:
    """Drive `evaluate` over a fixture, with must-FLAG cases as well as must-pass ones.

    A self-test that only exercises the passing direction cannot detect a dead gate -- this repo
    has shipped several of those, including one that passed with its own guard removed.
    """
    verbs = {"sweep", "reverse", "send", "convert"}
    matrix = 'transaction.reverse\nledger.reverse\n'
    actions = {
        "transaction.reverse": "a.kt",   # in matrix          -> pass
        "transaction.sweep": "b.kt",     # nowhere            -> FLAG (the real #4754 case)
        "account.list": "c.kt",          # verb not four-eyes -> ignored entirely
        "customer.pockets.convert": "d.kt",  # granted only by a PREFIX rule -> pass
    }
    # A corpus with one prefix rule and no literal for the sweep case.
    corpus = 'startswith(input.action, "customer.")'
    ungranted, granted = evaluate(actions, verbs, matrix, corpus)

    failures = 0
    got_ungranted = {a for a, _, _ in ungranted}
    got_granted = {a for a, _, _ in granted}
    checks = [
        ("flags an action declared four-eyes and granted nowhere", "transaction.sweep" in got_ungranted),
        ("does NOT flag one present in the matrix", "transaction.reverse" in got_granted),
        ("ignores an action whose verb is not four-eyes", "account.list" not in got_ungranted | got_granted),
        ("does NOT flag one granted only by a prefix rule", "customer.pockets.convert" in got_granted),
        ("flags exactly one in this fixture", len(ungranted) == 1),
    ]
    for label, ok in checks:
        print(f"  {'ok ' if ok else 'FAIL'} {label}")
        failures += 0 if ok else 1
    print(f"self-test: {'ok' if failures == 0 else 'FAILED'} ({failures} failure(s))")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ref", default=REF)
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    rules = sh("git", "show", f"{args.ref}:openbank-libs/governance/rules.yaml")
    if not rules.strip():
        print(f"::error::could not read rules.yaml at {args.ref} — refusing to run vacuously")
        return 1

    verbs = four_eyes_verbs(rules)
    actions = authorize_actions(args.ref)
    if not actions:
        print("::error::found no @Authorize actions at all — the corpus vanished, not a clean run")
        return 1

    corpus = rego_corpus(args.ref)
    if "input.action" not in corpus:
        print("::error::policy corpus is empty — refusing to report a clean sweep of nothing")
        return 1
    ungranted, granted = evaluate(actions, verbs, matrix_text(rules), corpus)
    subjects = len(ungranted) + len(granted)
    print(f"four-eyes-verb-grantable: {len(verbs)} verb(s), {subjects} four-eyes action(s) found")
    for action, _path, where in granted:
        print(f"  ok   {action}  ({where})")

    level = "error" if args.enforce else "warning"
    findings = []
    for action, path, _ in ungranted:
        if action in KNOWN_UNGRANTED:
            print(f"  BASELINED {action}: {KNOWN_UNGRANTED[action]}")
            continue
        print(f"  FAIL {action}  ({path})")
        findings.append(
            f"{action} is declared four-eyes (verb '{action.split('.')[-1]}' in "
            f"rules.yaml four_eyes.verbs) but has no allow reason in role_action_matrix or any "
            f"service rego. The approval can never be requested: response_attributes ride on the "
            f"allow object, so four_eyes_required never reaches AuthorizeInterceptor — and the day "
            f"AUTHZ_ENFORCE flips for this service, the endpoint 403s for every principal. "
            f"Declared at {path}."
        )

    stale = [a for a in KNOWN_UNGRANTED if a not in {x[0] for x in ungranted}]
    for action in stale:
        findings.append(f"{action} is baselined in KNOWN_UNGRANTED but is now granted — remove the entry")

    print(f"SUBJECTS={subjects}")
    for f in findings:
        print(f"::{level}::four-eyes-verb-grantable: {f}")
    return 1 if (findings and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
