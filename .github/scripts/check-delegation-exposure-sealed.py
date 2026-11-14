#!/usr/bin/env python3
"""Grant-level `exposure` is refused, and nothing may enforce it by decorating a live read
(rules.yaml: delegation_exposure_delivery, ADR-0375 D6).

Why this exists
---------------
`DelegationGrant.exposure` carries `redactionRules`, `maxViews`, `watermark` and `allowDownload`.
`DelegationService` refuses it on every path that could create or revive authority, because the
platform cannot transform a shared object or count views atomically on a streaming route — and
returning the ORIGINAL bytes with a redaction or view-count field merely echoed is a
confidentiality defect that reads, from the grantor's screen, exactly like an enforced one.

ADR-0375 D6 turns that refusal from a provisional gap into the DECLARED END STATE: a redacted,
watermarked, view-counted share is a rendered sealed artifact (document-service renders and
PAdES-signs it) delivered as a counted `ExternalDisclosure`, whose view consumption is a locked
read-modify-write inside one transaction. Grant-level `exposure` is therefore never the mechanism.

That decision needs a guard, because the obvious way to "implement exposure" is one line in a read
path — pass `maxViews` through, stamp a watermark flag onto a response DTO — and that line is
indistinguishable from progress in review. It also cannot be caught by the sibling capability gate:
`exposure` is not a capability, so it is outside `CAPABILITY_MATRIX` entirely.

What this checks
----------------
The subject set is DERIVED by scanning `openbank-delegation-service/src/main` for references to the
grant exposure field or its type, never hand-kept. Each referencing file must carry one registry
entry in `rules.yaml: delegation_exposure_delivery.referenced_in`, with a `role` from a closed set,
and the comparison runs BOTH ways:

  * a file that references exposure with no entry      -> UNCLASSIFIED finding (someone added a
                                                          reader; say which role it plays, or stop)
  * an entry whose file no longer references exposure  -> STALE finding (the registry describes
                                                          code that is gone)
  * `role: enforcement` anywhere                       -> finding: D6 says grant-level exposure is
                                                          never enforced; the sealed artifact is

Plus two liveness assertions, because a registry of readers says nothing about the refusal still
being there: both refusal sites must still raise `EXPOSURE_UNSUPPORTED`, and the sealed path's view
consumption must still go through a locking mutate. Deleting either is the defect this gate exists
for, and neither deletion changes any other check in the estate.

Scope, and why it is sound rather than narrow
---------------------------------------------
`Exposure` is a delegation-service domain type with no shared-library export, so no other module
can reference it and a tree-wide scan would only collect false positives: `openbank-anacredit-service`
has an unrelated regulatory `CreditExposure`, and a word-level scan for "exposure" hits 20+ lines
of prose about information disclosure. A gate that cries wolf about correct code is worth less than
nothing (the `entity-column-names` lesson), so the scan is keyed on the delegation field and type
forms and scoped to the module that owns them. The customer-edge relay is checked separately and
negatively: it must contain no exposure FIELD read at all.
"""

import argparse
from pathlib import Path
import re
import sys

import yaml

MODULE = "openbank-delegation-service/src/main/kotlin"
EDGE = "openbank-customer-edge/src/main/kotlin"
RULES = Path("openbank-libs/governance/rules.yaml")
KEY = "delegation_exposure_delivery"

# The grant exposure field or its type, in the forms Kotlin can reach them. Deliberately NOT the
# bare word: `maxViews` alone also belongs to ExternalDisclosure, which is the SANCTIONED path.
REFERENCE = re.compile(r"\.exposure\b|\bexposure\?\.|\bExposure\(|data class Exposure\b|exposure\s*=")

ROLES = {
    # The type itself and the aggregate field that holds it.
    "model",
    # Persistence of historical grants: readable for audit, never authority.
    "historical_audit",
    # The refusal that keeps a new or revived grant from carrying it.
    "refusal",
    # Request-fingerprint canonicalisation: exposure contributes to an idempotency digest only.
    "fingerprint",
}

REFUSAL_FILE = f"{MODULE}/com/openbank/delegation/application/usecase/DelegationService.kt"
REFUSAL_FUNCTIONS = ("rejectUnsupportedExposure", "rejectLegacyExposure")
SEALED_REPO = (
    "openbank-delegation-service/src/main/kotlin/com/openbank/delegation/"
    "infrastructure/persistence/repository/ExternalDisclosureRepositoryImpl.kt"
)


def referencing_files(root: Path) -> set[str]:
    found = set()
    base = root / MODULE
    for path in sorted(base.rglob("*.kt")):
        # Comments are stripped, the same way the customer-edge scan already does it. A file whose
        # only mention of exposure is prose explaining why it must NOT be read is not a reader, and
        # forcing a registry entry for it would put a misleading `role` on a file that plays none —
        # the registry would stop describing the code. `ExternalDisclosureService` is exactly that
        # file after ADR-0375 D6: it documents the trap it used to contain.
        code = "\n".join(
            line for line in path.read_text(encoding="utf-8").splitlines()
            if not (line.strip().startswith("//") or line.strip().startswith("*"))
        )
        if REFERENCE.search(code):
            found.add(str(path.relative_to(root)))
    return found


def declared(root: Path) -> tuple[dict[str, str], list[str]]:
    data = yaml.safe_load((root / RULES).read_text(encoding="utf-8")) or {}
    block = data.get(KEY)
    if not isinstance(block, dict):
        return {}, [f"rules.yaml has no `{KEY}` block — ADR-0375 D6 is undeclared"]
    entries = block.get("referenced_in")
    if not isinstance(entries, list):
        return {}, [f"rules.yaml `{KEY}.referenced_in` must be a list"]
    out: dict[str, str] = {}
    problems: list[str] = []
    for entry in entries:
        if not isinstance(entry, dict) or "path" not in entry or "role" not in entry:
            problems.append(f"{KEY}.referenced_in entry needs `path` and `role`: {entry!r}")
            continue
        role = str(entry["role"])
        if role not in ROLES:
            problems.append(f"{entry['path']}: role `{role}` is not one of {sorted(ROLES)}")
        if role == "enforcement":
            problems.append(
                f"{entry['path']}: role `enforcement` is refused by ADR-0375 D6 — grant-level "
                "exposure is never enforced; deliver a sealed ExternalDisclosure instead",
            )
        out[str(entry["path"])] = role
    return out, problems


def liveness(root: Path) -> list[str]:
    problems = []
    refusal = root / REFUSAL_FILE
    if not refusal.exists():
        return [f"{REFUSAL_FILE} is missing — the exposure refusal cannot be located"]
    text = refusal.read_text(encoding="utf-8")
    for fn in REFUSAL_FUNCTIONS:
        if f"fun {fn}" not in text:
            problems.append(f"{REFUSAL_FILE}: `{fn}` is gone — a path that may carry exposure is unguarded")
    if text.count("CODE_EXPOSURE_UNSUPPORTED") < 3:
        problems.append(
            f"{REFUSAL_FILE}: fewer than the expected EXPOSURE_UNSUPPORTED sites (declaration + two "
            "refusals) — the refusal was weakened",
        )
    sealed = root / SEALED_REPO
    if not sealed.exists():
        problems.append(f"{SEALED_REPO} is missing — the sealed delivery path D6 names is gone")
    else:
        sealed_text = sealed.read_text(encoding="utf-8")
        if "mutateById" not in sealed_text or "LOCK_BY_ID_SQL" not in sealed_text:
            problems.append(
                f"{SEALED_REPO}: the locked read-modify-write behind the counted view is gone, so the "
                "mechanism D6 points at no longer enforces maxViews atomically",
            )
    return problems


def edge_has_no_field_read(root: Path) -> list[str]:
    base = root / EDGE
    if not base.exists():
        return []
    offenders = []
    for path in sorted(base.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        for num, line in enumerate(text.splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            if re.search(r"\.exposure\b|\bexposure\?\.", line):
                offenders.append(f"{path.relative_to(root)}:{num}: customer-edge reads a grant exposure field")
    return offenders


def run(root: Path) -> int:
    found = referencing_files(root)
    registry, problems = declared(root)
    findings = list(problems)
    for path in sorted(found - set(registry)):
        findings.append(f"UNCLASSIFIED {path}: references grant exposure with no `{KEY}.referenced_in` entry")
    for path in sorted(set(registry) - found):
        findings.append(f"STALE {path}: declared in `{KEY}.referenced_in` but references no grant exposure")
    findings += liveness(root)
    findings += edge_has_no_field_read(root)

    print(f"SUBJECTS={len(found)}  # delegation-service src/main files referencing grant exposure")
    if findings:
        for finding in findings:
            print(f"::error::delegation-exposure-sealed: {finding}", file=sys.stderr)
        return 1
    roles = ", ".join(f"{role}={sum(1 for r in registry.values() if r == role)}" for role in sorted(ROLES))
    print(
        f"OK: {len(found)} file(s) reference grant exposure, all classified ({roles}); both refusals and "
        "the sealed path's locked view consumption are present; customer-edge reads no exposure field.",
    )
    return 0


def self_test() -> int:
    import tempfile

    cases: list[tuple[str, bool]] = []

    def fixture() -> Path:
        tmp = Path(tempfile.mkdtemp())
        (tmp / MODULE / "com/openbank/delegation/domain/model").mkdir(parents=True)
        (tmp / MODULE / "com/openbank/delegation/application/usecase").mkdir(parents=True)
        (tmp / MODULE / "com/openbank/delegation/infrastructure/persistence/repository").mkdir(parents=True)
        (tmp / RULES.parent).mkdir(parents=True)
        (tmp / MODULE / "com/openbank/delegation/domain/model/DelegationGrant.kt").write_text(
            "data class Exposure(val maxViews: Int? = null)\n", encoding="utf-8",
        )
        (tmp / REFUSAL_FILE).write_text(
            "const val CODE_EXPOSURE_UNSUPPORTED = \"EXPOSURE_UNSUPPORTED\"\n"
            "fun rejectUnsupportedExposure() { CODE_EXPOSURE_UNSUPPORTED }\n"
            "fun rejectLegacyExposure(g: G) { if (g.exposure != null) CODE_EXPOSURE_UNSUPPORTED }\n",
            encoding="utf-8",
        )
        (tmp / SEALED_REPO).write_text("val LOCK_BY_ID_SQL = \"\"\nfun mutateById() {}\n", encoding="utf-8")
        (tmp / RULES).write_text(
            yaml.safe_dump(
                {
                    KEY: {
                        "referenced_in": [
                            {"path": f"{MODULE}/com/openbank/delegation/domain/model/DelegationGrant.kt",
                             "role": "model"},
                            {"path": REFUSAL_FILE, "role": "refusal"},
                        ],
                    },
                },
            ),
            encoding="utf-8",
        )
        return tmp

    base = fixture()
    cases.append(("a classified tree passes", run(base) == 0))

    unclassified = fixture()
    (unclassified / MODULE / "com/openbank/delegation/application/usecase/Reader.kt").write_text(
        "fun shape(g: G) = g.exposure?.maxViews\n", encoding="utf-8",
    )
    cases.append(("a new exposure reader is UNCLASSIFIED", run(unclassified) == 1))

    stale = fixture()
    (stale / MODULE / "com/openbank/delegation/domain/model/DelegationGrant.kt").write_text(
        "data class Nothing(val x: Int)\n", encoding="utf-8",
    )
    cases.append(("a declared file that stopped referencing it is STALE", run(stale) == 1))

    enforcement = fixture()
    data = yaml.safe_load((enforcement / RULES).read_text(encoding="utf-8"))
    data[KEY]["referenced_in"][0]["role"] = "enforcement"
    (enforcement / RULES).write_text(yaml.safe_dump(data), encoding="utf-8")
    cases.append(("role `enforcement` is refused outright", run(enforcement) == 1))

    norefusal = fixture()
    (norefusal / REFUSAL_FILE).write_text("fun nothing() {}\n", encoding="utf-8")
    cases.append(("deleting the refusals is caught", run(norefusal) == 1))

    unlocked = fixture()
    (unlocked / SEALED_REPO).write_text("fun mutateById() {}\n", encoding="utf-8")
    cases.append(("losing the sealed path's row lock is caught", run(unlocked) == 1))

    edge = fixture()
    (edge / EDGE / "com/openbank/customeredge").mkdir(parents=True)
    (edge / EDGE / "com/openbank/customeredge/Relay.kt").write_text(
        "fun relay(g: G) = g.exposure?.watermark\n", encoding="utf-8",
    )
    cases.append(("customer-edge reading an exposure field is caught", run(edge) == 1))

    edge_comment = fixture()
    (edge_comment / EDGE / "com/openbank/customeredge").mkdir(parents=True)
    (edge_comment / EDGE / "com/openbank/customeredge/Relay.kt").write_text(
        "// no component can prove a redaction, watermark or maxViews via g.exposure\nfun relay() = 1\n",
        encoding="utf-8",
    )
    cases.append(("a comment in customer-edge is NOT a finding", run(edge_comment) == 0))

    missing_block = fixture()
    (missing_block / RULES).write_text("other: {}\n", encoding="utf-8")
    cases.append(("an undeclared rules.yaml block fails", run(missing_block) == 1))

    failed = [name for name, ok in cases if not ok]
    for name, ok in cases:
        print(f"  {'ok' if ok else 'FAILED'}: {name}")
    print(f"self-test: {len(cases) - len(failed)}/{len(cases)} passed")
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return run(Path(args.root).resolve())


if __name__ == "__main__":
    raise SystemExit(main())
