#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""A service that owns a four-eyes action must ENFORCE four-eyes in its deployed manifest (#15748).

WHY THIS EXISTS: `rules.yaml: four_eyes` decides which actions OPA flags `four_eyes_required`, but
AuthorizeInterceptor only acts on the flag when `authz.four-eyes.enforce` is true, and its code
default is false (ADR-0155: merging the mechanism must not start blocking traffic by itself). On
2026-11-11 no gitops manifest set AUTHZ_FOUR_EYES_ENFORCE and no running workload carried it, so
every money-path action the policy calls dual-control executed on a single operator — while the
policy, the approval stores and the inboxes all existed and read as a working control.

SUBJECTS are DERIVED, never listed: every `openbank-*` module whose src/main declares
`@Authorize(action = "...")` for an action rest.rego flags four-eyes, by the same rule —
  - the action is in `four_eyes.actions`, or
  - its prefix is a money-path scope (money_path_services minus `openbank-` / `-service`, or the
    `money_path_action_prefixes` override) and it ends in `.<verb>` for a `four_eyes.verbs` verb.
`four_eyes.exemptions` does not remove a subject: it exempts named principals, every other caller
is still flagged. (`featureflag.flip` is four-eyes only for some flag values and has no
@Authorize site today, so it is out of scope.)

CHECK: each subject must have at least one gitops Deployment/Rollout/StatefulSet running its image,
and EVERY such workload must carry the literal env `AUTHZ_FOUR_EYES_ENFORCE: "true"` — or the
module is on `rules.yaml: four_eyes_enforcement.allowlist` with a live `expires` date and a
`reason`. The allowlist fails the gate when an entry
  - has expired (today > expires) — a lapsed exception is a violation, not a silent pass;
  - is for a module that already enforces, or owns no four-eyes action (stale);
  - lacks `expires`/`reason`, has an unparseable date, or expires more than MAX_HORIZON_DAYS out.
An entry within WARN_DAYS of expiry prints a `::warning::` (never fails); expiry itself fails. The
exceptions are staggered in waves so one date never reddens every open PR at once.
A gitops manifest that mentions `kind:` and fails to parse is a hard failure (it could hide a workload).
`@Authorize` is matched with `action = "..."` anywhere in the argument list; `action = CONSTANT` is
fail-closed (the gate cannot tell which action it is) — write a literal.
The application.yaml default is deliberately NOT consulted: communication-service defaults to true,
and a code default is exactly what a later edit can flip with no manifest diff to review.

Usage: check-four-eyes-enforce.py [--root .] [--today YYYY-MM-DD] [--self-test]
"""
from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import sys
import tempfile

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

RULES = pathlib.Path("openbank-libs/governance/rules.yaml")
COMPONENTS = pathlib.Path("openbank-infra/gitops/components")
WORKLOAD_KINDS = {"Deployment", "Rollout", "StatefulSet"}
ENV_NAME = "AUTHZ_FOUR_EYES_ENFORCE"
MAX_HORIZON_DAYS = 120
WARN_DAYS = 14
AUTHORIZE_OPEN = re.compile(r"@Authorize\(")
ACTION_ARG = re.compile(r'(?:^|[,(\s])action\s*=\s*(?:"([^"]+)"|([A-Za-z_][\w.]*))')


def load_rules(root: pathlib.Path) -> dict:
    data = gatelib.load_yaml(root / RULES) or {}
    if not isinstance(data.get("money_path_services"), list) or not isinstance(data.get("four_eyes"), dict):
        raise SystemExit("check-four-eyes-enforce: rules.yaml money_path_services / four_eyes missing")
    return data


def money_path_scopes(rules: dict) -> set[str]:
    overrides = rules.get("money_path_action_prefixes") or {}
    scopes: set[str] = set()
    for svc in rules["money_path_services"]:
        derived = re.sub(r"-service$", "", str(svc).removeprefix("openbank-"))
        scopes.update(overrides.get(derived) or [derived])
    return scopes


def is_four_eyes(action: str, rules: dict, scopes: set[str]) -> bool:
    fe = rules["four_eyes"]
    if action in (fe.get("actions") or []):
        return True
    return any(action.startswith(f"{s}.") for s in scopes) and any(
        action.endswith(f".{v}") for v in (fe.get("verbs") or []))


def _annotation_args(text: str, start: int) -> str:
    """The argument text of the annotation whose '(' is at start-1 (balanced parens, strings skipped)."""
    depth, i, in_str = 1, start, False
    while i < len(text) and depth:
        ch = text[i]
        if in_str:
            if ch == "\\":
                i += 1
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        i += 1
    return text[start:i - 1]


def four_eyes_owners(root: pathlib.Path, rules: dict) -> tuple[dict[str, set[str]], list[str]]:
    """(module -> four-eyes actions its @Authorize sites declare, unresolvable-site errors).

    Comment lines are skipped. `action` may be any argument, not only the first. `action = CONST`
    cannot be classified, so it is an error naming the file (fail closed), never a silent skip.
    """
    scopes = money_path_scopes(rules)
    owners: dict[str, set[str]] = {}
    errors: list[str] = []
    for module in sorted(p for p in root.glob("openbank-*") if (p / "src" / "main" / "kotlin").is_dir()):
        for kt in gatelib.rglob(module / "src" / "main" / "kotlin", "*.kt"):
            text = gatelib.read_text(kt, errors="replace")
            for m in AUTHORIZE_OPEN.finditer(text):
                line_start = text.rfind("\n", 0, m.start()) + 1
                prefix = text[line_start:m.start()].strip()
                if prefix.startswith(("*", "//")) or "`" in prefix:
                    continue  # KDoc / comment prose that quotes an annotation
                arg = ACTION_ARG.search(_annotation_args(text, m.end()))
                if arg is None:
                    continue
                if arg.group(2):
                    errors.append(f"{module.name}: {kt.relative_to(root)} declares @Authorize(action = {arg.group(2)}) — the gate "
                                  f"cannot classify a non-literal action; write the action as a string literal")
                elif is_four_eyes(arg.group(1), rules, scopes):
                    owners.setdefault(module.name, set()).add(arg.group(1))
    return owners, errors


def _image_matches(image: str, module: str) -> bool:
    return re.search(rf"(^|/){re.escape(module)}([:@]|$)", image) is not None


def workload_containers(root: pathlib.Path, errors: list[str]) -> list[tuple[pathlib.Path, str, str | None]]:
    """(manifest, image, literal AUTHZ_FOUR_EYES_ENFORCE or None) for every workload container.

    Parsed once: the gitops tree is ~1k YAML files and re-reading it per subject cost ~30 s.
    """
    out: list[tuple[pathlib.Path, str, str | None]] = []
    for path in gatelib.rglob(root / COMPONENTS, "*.yaml"):
        if "kind:" not in gatelib.read_text(path, errors="replace"):
            continue
        try:
            docs = gatelib.load_yaml_all(path)
        except yaml.YAMLError as exc:
            # Every committed manifest mentioning `kind:` parses today (874 files, 0 failures), so a
            # failure is a regression that could hide a workload from this gate: hard error.
            errors.append(f"{path.relative_to(root)}: gitops manifest does not parse as YAML ({type(exc).__name__}) — "
                          f"the gate cannot see the workloads in it")
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") not in WORKLOAD_KINDS:
                continue
            pod = (((doc.get("spec") or {}).get("template") or {}).get("spec") or {})
            for c in pod.get("containers") or []:
                if not isinstance(c, dict):
                    continue
                value = None
                for env in c.get("env") or []:
                    if isinstance(env, dict) and env.get("name") == ENV_NAME:
                        value = None if env.get("value") is None else str(env.get("value"))
                out.append((path, str(c.get("image") or ""), value))
    return out


def manifest_values(containers, module: str) -> list[tuple[pathlib.Path, str | None]]:
    """(manifest, literal AUTHZ_FOUR_EYES_ENFORCE or None) for every workload running `module`."""
    return [(p, v) for p, image, v in containers if _image_matches(image, module)]


def run(root: pathlib.Path, today: dt.date) -> tuple[list[str], int, list[str]]:
    """(errors, subject count, warnings)."""
    rules = load_rules(root)
    owners, errors = four_eyes_owners(root, rules)
    warnings: list[str] = []
    section = rules.get("four_eyes_enforcement") or {}
    allowlist = section.get("allowlist") or {}
    if not isinstance(allowlist, dict):
        return ["rules.yaml four_eyes_enforcement.allowlist must be a mapping of module -> {expires, reason}"], len(owners), warnings
    containers = workload_containers(root, errors)

    for module in sorted(owners):
        actions = ", ".join(sorted(owners[module]))
        workloads = manifest_values(containers, module)
        enforcing = bool(workloads) and all((v or "").strip().lower() == "true" for _, v in workloads)
        entry = allowlist.get(module)
        if enforcing:
            if entry is not None:
                errors.append(f"{module}: STALE allowlist entry — every workload already sets {ENV_NAME}=true; "
                              f"delete the four_eyes_enforcement.allowlist entry")
            continue
        if entry is None:
            if not workloads:
                errors.append(f"{module}: owns four-eyes action(s) [{actions}] but no gitops workload runs its image")
            else:
                bad = ", ".join(f"{p.relative_to(root)}={v!r}" for p, v in workloads if (v or "").lower() != "true")
                errors.append(f"{module}: owns four-eyes action(s) [{actions}] and does not set {ENV_NAME}=\"true\" "
                              f"({bad}) — enforce it, or add a dated four_eyes_enforcement.allowlist entry with the blocker")
            continue
        # Allowlisted and not enforcing: the entry itself must be valid and live.
        if not isinstance(entry, dict):
            errors.append(f"{module}: allowlist entry must be a mapping with expires + reason")
            continue
        reason = str(entry.get("reason") or "").strip()
        try:
            expires = dt.date.fromisoformat(str(entry.get("expires") or ""))
        except ValueError:
            errors.append(f"{module}: allowlist entry has no valid `expires` (YYYY-MM-DD): {entry.get('expires')!r}")
            continue
        if not reason:
            errors.append(f"{module}: allowlist entry has no `reason` — name the concrete blocker")
        if today > expires:
            errors.append(f"{module}: allowlist entry EXPIRED on {expires} (today {today}) — four-eyes is still not "
                          f"enforced for [{actions}]; fix the blocker and flip, or re-argue the exception")
        elif (expires - today).days <= WARN_DAYS:
            warnings.append(f"{module}: four-eyes allowlist entry expires {expires} ({(expires - today).days} days) — "
                            f"enforce it or re-argue the exception before it fails the gate")
        elif (expires - today).days > MAX_HORIZON_DAYS:
            errors.append(f"{module}: allowlist `expires` {expires} is more than {MAX_HORIZON_DAYS} days out — "
                          f"a far-future date is a permanent waiver")

    for module in sorted(set(allowlist) - set(owners)):
        errors.append(f"{module}: STALE allowlist entry — the module owns no four-eyes action")
    return errors, len(owners), warnings


def self_test() -> int:
    fails: list[str] = []
    today = dt.date(2026, 11, 11)

    def tree(allowlist: str, manifests: dict[str, str | None], extra_src: str = "") -> pathlib.Path:
        root = pathlib.Path(tempfile.mkdtemp())
        (root / RULES.parent).mkdir(parents=True)
        (root / RULES).write_text(
            "money_path_services:\n  - openbank-ledger-service\n  - openbank-sepa-payment\n"
            "money_path_action_prefixes:\n  sepa-payment: [sepaPayment]\n"
            "four_eyes:\n  verbs: [reverse, transitionStatus]\n  actions: [opsmessage.compose]\n"
            + allowlist)
        src = {
            "openbank-ledger-service": '@Authorize(action = "ledger.reverse", resource = "#id")\n'
                                       '@Authorize(action = "ledger.read")\n',
            "openbank-sepa-payment": '@Authorize(\n    action = "sepaPayment.transitionStatus",\n)\n',
            "openbank-notification-service": '@Authorize(action = "opsmessage.compose")\n',
            # Not money-path and not an exact action: `.reverse` here is NOT four-eyes.
            "openbank-billing-service": '@Authorize(action = "billing.reverse")\n'
                                        ' * `@Authorize(action = "ledger.reverse")` quoted in KDoc\n' + extra_src,
        }
        for mod, body in src.items():
            d = root / mod / "src/main/kotlin/x"
            d.mkdir(parents=True)
            (d / "R.kt").write_text(body)
        comp = root / COMPONENTS / "c"
        comp.mkdir(parents=True)
        for mod, value in manifests.items():
            env = f'          - name: {ENV_NAME}\n            value: "{value}"\n' if value is not None else ""
            (comp / f"{mod}.yaml").write_text(
                "kind: Deployment\nspec:\n  template:\n    spec:\n      containers:\n"
                f"        - name: app\n          image: registry.example/{mod}:sandbox-1\n          env:\n"
                "          - name: OTHER\n            value: x\n" + env)
        return root

    def check(label: str, root: pathlib.Path, want_substrings: list[str], want_subjects: int = 3) -> None:
        errors, n, warnings = run(root, today)
        if warnings:
            fails.append(f"{label}: unexpected warnings {warnings}")
        if n != want_subjects:
            fails.append(f"{label}: expected {want_subjects} subjects, got {n}")
        if not want_substrings and errors:
            fails.append(f"{label}: expected clean, got {errors}")
        for s in want_substrings:
            if not any(s in e for e in errors):
                fails.append(f"{label}: expected an error containing {s!r}, got {errors}")
        if want_substrings and len(errors) != len(want_substrings):
            fails.append(f"{label}: expected exactly {len(want_substrings)} error(s), got {errors}")

    all_on = {"openbank-ledger-service": "true", "openbank-sepa-payment": "true", "openbank-notification-service": "true"}
    live = '\nfour_eyes_enforcement:\n  allowlist:\n    openbank-ledger-service:\n      expires: "2026-12-15"\n      reason: saga compensation\n'

    # 1. Positive control: every owner enforces, no allowlist. Clean; billing/KDoc are not subjects.
    check("all enforcing", tree("", all_on), [])
    # 2. MISSING env: ledger drops the env and is not allowlisted.
    check("missing env flags", tree("", {**all_on, "openbank-ledger-service": None}),
          ["openbank-ledger-service: owns four-eyes action(s) [ledger.reverse] and does not set"])
    # 3. Literal "false" is not enforcing either.
    check("false flags", tree("", {**all_on, "openbank-sepa-payment": "false"}), ["openbank-sepa-payment: owns"])
    # 4. A live, dated allowlist entry covers the missing env.
    check("live allowlist honoured", tree(live, {**all_on, "openbank-ledger-service": None}), [])
    # 5. EXPIRED entry fails — the defect the authz-enforce allowlist had.
    check("expired allowlist flags",
          tree(live.replace("2026-12-15", "2026-11-10"), {**all_on, "openbank-ledger-service": None}),
          ["openbank-ledger-service: allowlist entry EXPIRED on 2026-11-10"])
    # 6. STALE entry: the service enforces now, the entry must go.
    check("stale (enforcing) flags", tree(live, all_on), ["openbank-ledger-service: STALE allowlist entry — every workload"])
    # 7. STALE entry for a module owning no four-eyes action.
    check("stale (no action) flags",
          tree('\nfour_eyes_enforcement:\n  allowlist:\n    openbank-billing-service:\n      expires: "2026-12-15"\n'
               '      reason: x\n', all_on),
          ["openbank-billing-service: STALE allowlist entry — the module owns no four-eyes action"])
    # 8. Far-future date and missing reason/expires are rejected.
    check("far-future flags", tree(live.replace("2026-12-15", "2099-01-01"), {**all_on, "openbank-ledger-service": None}),
          ["more than 120 days out"])
    check("no expires flags", tree(live.replace('      expires: "2026-12-15"\n', ""), {**all_on, "openbank-ledger-service": None}),
          ["no valid `expires`"])
    # 9. A subject with no workload at all is a finding, never skipped.
    check("no workload flags", tree("", {"openbank-ledger-service": "true", "openbank-sepa-payment": "true"}),
          ["openbank-notification-service: owns four-eyes action(s) [opsmessage.compose] but no gitops workload"])
    # 10. Two workloads for one image: both must enforce (ledger + a second ledger instance).
    root = tree("", all_on)
    (root / COMPONENTS / "c" / "second-ledger.yaml").write_text(
        "kind: Rollout\nspec:\n  template:\n    spec:\n      containers:\n"
        "        - name: app\n          image: registry.example/openbank-ledger-service:sandbox-1\n")
    check("second unenforced workload flags", root, ["openbank-ledger-service: owns"])

    # 11. Expiry warning window (14 days): silent outside, warning (exit-0 class) inside, failure after.
    missing = {**all_on, "openbank-ledger-service": None}
    root = tree(live, missing)
    for label, day, want_err, want_warn in (
            ("expiry not in window is silent", dt.date(2026, 11, 30), 0, 0),
            ("expiry in window warns, does not fail", dt.date(2026, 12, 5), 0, 1),
            ("expiry day itself still warns", dt.date(2026, 12, 15), 0, 1),
            ("expired fails", dt.date(2026, 12, 16), 1, 0)):
        errs, _, warns = run(root, day)
        if len(errs) != want_err or len(warns) != want_warn:
            fails.append(f"{label}: expected {want_err} error(s)/{want_warn} warning(s), got {errs}/{warns}")
        elif want_warn and not ("openbank-ledger-service" in warns[0] and "2026-12-15" in warns[0]):
            fails.append(f"{label}: warning does not name service and date: {warns}")
    # 12. A gitops manifest that fails YAML parsing is a hard failure naming the file, not a skip.
    root = tree("", all_on)
    (root / COMPONENTS / "c" / "broken.yaml").write_text("kind: Deployment\nspec: [unclosed\n  x: : y\n")
    errs, _, _ = run(root, today)
    if not any("broken.yaml" in e and "does not parse" in e for e in errs):
        fails.append(f"unparseable manifest not flagged: {errs}")
    # 13. `action =` need not be the first @Authorize argument.
    check("action not first arg is a subject",
          tree("", all_on, extra_src='@Authorize(resource = "#id", action = "opsmessage.compose")\n'),
          ["openbank-billing-service: owns four-eyes action(s) [opsmessage.compose] but no gitops workload"],
          want_subjects=4)
    # 14. `action = CONSTANT` fails closed instead of being silently ignored.
    check("constant action fails closed", tree("", all_on, extra_src='@Authorize(action = ACTION_X)\n'),
          ["cannot classify a non-literal action"])

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: four-eyes-enforce gate is falsifiable (missing env, false, expired, stale x2, "
          "far-future, no expiry, no workload, partial workloads, expiry warning window, unparseable manifest, "
          "action not first, constant action, plus positive controls)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--today", help="override today's date (YYYY-MM-DD) — tests only")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    today = dt.date.fromisoformat(args.today) if args.today else dt.datetime.now(dt.timezone.utc).date()
    root = pathlib.Path(args.root)
    errors, n, warnings = run(root, today)
    gatelib.subjects(n, "modules owning a four-eyes @Authorize action")
    for w in warnings:
        print(f"::warning::{w}")
    for e in errors:
        print(f"::error::{e}")
    if errors:
        print(f"four-eyes-enforce: {len(errors)} violation(s) across {n} four-eyes-owning modules (#15748)")
        return 1
    print(f"four-eyes-enforce: OK — {n} four-eyes-owning modules enforce in gitops or carry a live, dated exception")
    return 0


if __name__ == "__main__":
    sys.exit(main())
