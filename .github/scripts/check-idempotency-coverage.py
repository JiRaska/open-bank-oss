#!/usr/bin/env python3
"""Guard: money-path POST endpoints must declare idempotency (issue #8351, ROADMAP M2).

WHY THIS EXISTS: M2 (Resilience) lists "idempotency coverage" as a remaining gap, and until this
gate there was no measured inventory — which mutating endpoints require an idempotency key, which
merely tolerate one, which silently accept duplicates. A duplicate POST on a money-path endpoint
is a double debit; the fleet knows this (ADR bullet: a non-nullable @HeaderParam is a 500, and
three services shipped exactly that), so the convention exists — what was missing is the ratchet
that keeps a NEW money-path POST from shipping without it.

THE FLEET HAS TWO IDIOMS, and both count as coverage:

  1. a REQUIRED `Idempotency-Key` header parameter (account-service, domestic-payment, …), or
  2. a REQUIRED `idempotencyKey` request-body property (transaction-service).

An optional key is classified separately — it deduplicates clients that bother, and silently
double-books clients that do not, which on a money-path POST is the defect, not a feature.

WHAT IT CHECKS: every released service (`version.txt` present — the release-please registry rule)
with `src/main/resources/openapi.yaml`. Every operation with method post/put/patch/delete is
classified required-header / required-body / optional-header / optional-body / absent, resolving
one level of local `$ref` (components/{parameters,requestBodies,schemas}, allOf merged). The
hard rule is scoped to POST on services in `rules.yaml: money_path_services`: PUT/PATCH/DELETE
address an existing resource by id (a retry replays the same write against the same row), while a
POST creates — that is where a retry double-books. Today's uncovered money-path POSTs are carried
in `idempotency-coverage-baseline.txt` (shrink-only, one `# reason` per entry, the same
individually-justified-exception idiom as the other baselines); a NEW uncovered money-path POST
fails the gate, and a baseline entry that stops being observed fails too, so an exception cannot
quietly outlive its reason. The full inventory (all classes, all methods, released services) is
printed on every run — that printout is the enumeration + classification the issue asks for.

ENFORCED: findings are ::error:: annotations and exit 1.

Usage: check-idempotency-coverage.py [--root .] [--rules openbank-libs/governance/rules.yaml]
       check-idempotency-coverage.py --self-test   # prove the gate can fail
"""
from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib

REPO = Path(__file__).resolve().parents[2]
RULES = REPO / "openbank-libs/governance/rules.yaml"
BASELINE = Path(__file__).resolve().with_name("idempotency-coverage-baseline.txt")

MUTATING = ("post", "put", "patch", "delete")
# Both header idioms count: the fleet's own `Idempotency-Key`, and the Berlin Group
# `X-Request-ID` — psd2-service builds its idempotency key from it
# (`"psd2:v1:consent:$tppId:$xRequestId"` in BerlinConsentResource), which IS the
# deduplication handle the Berlin Group spec defines for payment initiation.
IDEM_HEADERS = {"idempotency-key", "x-request-id", "x-idempotency-key"}
IDEM_BODY_PROP = "idempotencyKey"

# Classification, strongest first.
REQ_HEADER = "required-header"
REQ_BODY = "required-body"
OPT_HEADER = "optional-header"
OPT_BODY = "optional-body"
ABSENT = "absent"
COVERED = {REQ_HEADER, REQ_BODY}


def resolve(node, components: dict, depth: int = 0):
    """Resolve one local `$ref` at a time (guarded against cycles)."""
    seen = 0
    while isinstance(node, dict) and "$ref" in node and seen < 5:
        ref = node["$ref"]
        if not ref.startswith("#/components/"):
            return node
        parts = ref.removeprefix("#/components/").split("/")
        cur = components
        for p in parts:
            cur = cur.get(p) if isinstance(cur, dict) else None
        if cur is None:
            return node
        node = cur
        seen += 1
    return node


def body_schema(request_body, components) -> dict:
    """First content schema, with allOf members merged shallowly."""
    rb = resolve(request_body, components)
    if not isinstance(rb, dict):
        return {}
    content = rb.get("content") or {}
    for media in content.values():
        schema = resolve((media or {}).get("schema"), components)
        if not isinstance(schema, dict):
            continue
        if "allOf" in schema:
            merged: dict = {"properties": {}, "required": []}
            for part in schema["allOf"]:
                part = resolve(part, components)
                if not isinstance(part, dict):
                    continue
                merged["properties"].update(part.get("properties") or {})
                merged["required"] += part.get("required") or []
            merged["properties"].update(schema.get("properties") or {})
            merged["required"] += schema.get("required") or []
            return merged
        return schema
    return {}


def classify(operation: dict, path_item: dict, components: dict) -> str:
    params = [
        resolve(p, components)
        for p in (path_item.get("parameters") or []) + (operation.get("parameters") or [])
    ]
    header = next(
        (
            p
            for p in params
            if isinstance(p, dict)
            and p.get("in") == "header"
            and str(p.get("name", "")).lower() in IDEM_HEADERS
        ),
        None,
    )
    schema = body_schema(operation.get("requestBody"), components)
    props = schema.get("properties") or {}
    required = schema.get("required") or []
    has_body_prop = IDEM_BODY_PROP in props
    body_required = has_body_prop and IDEM_BODY_PROP in required
    if header is not None and header.get("required") is True:
        return REQ_HEADER
    if body_required:
        return REQ_BODY
    if header is not None:
        return OPT_HEADER
    if has_body_prop:
        return OPT_BODY
    return ABSENT


def load_baseline() -> dict[str, str]:
    entries: dict[str, str] = {}
    if not BASELINE.exists():
        return entries
    for line in BASELINE.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, _, reason = line.partition("#")
        entries[key.strip()] = reason.strip()
    return entries


def audit(root: Path, rules: Path):
    money_path = set(gatelib.load_yaml(rules).get("money_path_services") or [])
    inventory: dict[str, int] = {c: 0 for c in (REQ_HEADER, REQ_BODY, OPT_HEADER, OPT_BODY, ABSENT)}
    violations: list[str] = []  # "service POST /path"
    examined = 0
    for spec in gatelib.rglob(root, "openbank-*/src/main/resources/openapi.yaml"):
        service = spec.relative_to(root).parts[0]
        if not (root / service / "version.txt").exists():
            continue  # not a released component — same rule release-please uses
        doc = gatelib.load_yaml(spec)
        if not isinstance(doc, dict):
            continue
        examined += 1
        components = doc.get("components") or {}
        for path, path_item in (doc.get("paths") or {}).items():
            if not isinstance(path_item, dict):
                continue
            for method in MUTATING:
                op = path_item.get(method)
                if not isinstance(op, dict):
                    continue
                cls = classify(op, path_item, components)
                inventory[cls] += 1
                if service in money_path and method == "post" and cls not in COVERED:
                    violations.append(f"{service} {method.upper()} {path}  [{cls}]")
    return inventory, violations, examined


def self_test() -> int:
    spec_covered = """
paths:
  /api/v1/journals:
    post:
      parameters:
        - name: Idempotency-Key
          in: header
          required: true
"""
    spec_body = """
paths:
  /api/v1/adjustments:
    post:
      requestBody:
        content:
          application/json:
            schema:
              properties: {idempotencyKey: {type: string}}
              required: [idempotencyKey]
"""
    spec_absent = """
paths:
  /api/v1/payments:
    post:
      responses: {'200': {description: ok}}
"""
    spec_optional = """
paths:
  /api/v1/payments:
    post:
      parameters:
        - name: Idempotency-Key
          in: header
"""
    spec_put_absent = """
paths:
  /api/v1/accounts/{id}:
    put:
      responses: {'200': {description: ok}}
"""
    money_rules = "money_path_services:\n  - openbank-probe-service\n"
    cases = [
        ("required header on money-path POST -> silent", spec_covered, 0),
        ("required body key on money-path POST -> silent", spec_body, 0),
        ("absent on money-path POST -> MUST fire", spec_absent, 1),
        ("optional header on money-path POST -> MUST fire (optional is the defect)", spec_optional, 1),
        ("absent on money-path PUT -> silent (addresses a row by id)", spec_put_absent, 0),
        ("absent on NON-money-path POST -> silent", spec_absent, 0),
    ]
    failures = 0
    for i, (label, spec, want) in enumerate(cases):
        with tempfile.TemporaryDirectory() as tmp:
            svc = Path(tmp) / ("openbank-probe-service" if i < 5 else "openbank-other-service")
            src = svc / "src" / "main" / "resources"
            src.mkdir(parents=True)
            (src / "openapi.yaml").write_text(spec)
            (svc / "version.txt").write_text("1.0.0\n")
            rules = Path(tmp) / "rules.yaml"
            rules.write_text(money_rules)
            _, violations, examined = audit(Path(tmp), rules)
            ok = len(violations) == want and examined == 1
            print(f"  {'ok  ' if ok else 'FAIL'}  {label}")
            if not ok:
                failures += 1
                for v in violations:
                    print(f"        got: {v}")
    if failures:
        print(f"SELF-TEST FAILED: {failures} case(s)", file=sys.stderr)
        return 1
    print("self-test: PASS — both idioms count, optional/absent fire on money-path POST only")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(REPO))
    ap.add_argument("--rules", default=str(RULES))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    inventory, violations, examined = audit(Path(args.root), Path(args.rules))
    gatelib.subjects(examined, "released services with an OpenAPI spec")
    print("idempotency inventory (mutating operations, released services):")
    for cls, count in inventory.items():
        print(f"  {cls}: {count}")

    baseline = load_baseline()
    observed = {v.split("  [")[0] for v in violations}
    new_violations = [v for v in violations if v.split("  [")[0] not in baseline]
    stale = [k for k in baseline if k not in observed]

    rc = 0
    if new_violations:
        print("\nMoney-path POST endpoints with no required idempotency key:\n", file=sys.stderr)
        for v in new_violations:
            print(f"::error::{v}", file=sys.stderr)
        print(
            f"\n{len(new_violations)} new finding(s). Require the key (header `Idempotency-Key` "
            f"or body `idempotencyKey`, required in both halves — see rules.yaml: commits and "
            f"check-nonnull-jaxrs-params.py for the nullable-param half), or record an "
            f"ADR-linked exception in idempotency-coverage-baseline.txt.",
            file=sys.stderr,
        )
        rc = 1
    if stale:
        print("\nStale baseline entries (violation no longer observed — remove them):",
              file=sys.stderr)
        for s in stale:
            print(f"::error::{s}", file=sys.stderr)
        rc = 1
    if rc == 0:
        covered = sum(inventory[c] for c in COVERED)
        print(
            f"OK: {covered}/{sum(inventory.values())} mutating operations covered; "
            f"every money-path POST requires idempotency or carries a baselined exception "
            f"({len(baseline)} baselined)."
        )
    return rc


if __name__ == "__main__":
    sys.exit(main())
