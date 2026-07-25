#!/usr/bin/env python3
"""openapi-request-schema-conformance gate — published request bodies vs the DTOs that parse them.

WHY THIS EXISTS: check-openapi-route-conformance.py (#2360) closed the ROUTE half of "does the
published contract describe the code" — it never looks inside a schema. That gap is exactly how
aml-service published `CreateAmlCaseRequest.triggerType/riskScore/notes` and
`UpdateDecisionRequest.decision/analystId`, fields that exist nowhere in the DTOs the resource
actually deserializes into (#2312). This closes the request-body half of that gap.

WHY NOT THE RESPONSE HALF TOO. Investigated and rejected — not deferred out of laziness. 457
handler methods fleet-wide return a raw `jakarta.ws.rs.core.Response`, which erases the body type
at the JVM level: the MicroProfile OpenAPI scanner that produces the generated document below has
no static type to build a response schema from, and emits `"schema": {}` for the response body's
`200`/`201`. Measured on aml-service (#2312's own defect): `AmlCaseResponse` does not appear
ANYWHERE in the generated document. A generic response-schema gate over this fleet would report
"empty schema" as if it were "no schema published" on the majority of endpoints — indistinguishable
from the real defect, so it cannot be built without producing more noise than signal. The response
half needs a per-endpoint contract test asserting the DTO shape directly (the shape
`CopilotApiContractTest` and the fx/aml pact tests already use), not a generic diff. See the
tracking issue for specifics.

WHAT THIS CHECKS: for each REQUEST body schema referenced from `paths:` (only bodies with a
`$ref` to a `components.schemas` entry — inline schemas are already visible in the spec text and
not this gate's job), the PROPERTY NAME SET declared in `openapi.yaml` against the same schema
in the Quarkus-generated document (`quarkus.smallrye-openapi.store-schema-directory`, produced as
a side effect of the existing `:service:build` — confirmed via `--dry-run` that `quarkusBuild` is
already in that task's graph, so this costs no extra build). Reports both directions:

    published-not-parsed   a property the spec declares that the DTO does not have
    parsed-not-published   a property the DTO has that the spec does not declare

WHAT THIS DELIBERATELY DOES NOT CHECK, and why a green here is not "this schema is true":
  - `required` / optional-ness — SmallRye OpenAPI does not read Kotlin nullability (no
    jackson-module-kotlin integration in the scanner), so it marks nearly every constructor
    parameter required regardless of `?`. Comparing required-ness would be near-100% noise.
    Measured on aml: `accountId: UUID?` and `transactionId: UUID?` both come back `required`.
  - types, formats, enum values — inconsistently present (a `$ref`'d type loses the nullable
    union a plain `String` keeps; an enum typed as `String` in the DTO with a manual `.valueOf()`
    conversion carries no enum constraint at all, which is aml's own pattern).
  - response bodies — see above.
  - anything for a service whose generated document was not supplied (this is a per-service check
    invoked with an explicit path, not a fleet crawl — the CI wiring runs it once per service,
    right after the build that produces the artifact it reads).

stdlib-only.

Usage:
    check-openapi-request-schema-conformance.py --service <name> --generated <path/to/openapi.yaml> [--enforce]

Exits 0 (with a ::notice) if the generated file does not exist — a missing artifact is a CI wiring
question, not a schema-conformance finding, and must never silently read as "no findings".
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("::error::openapi-request-schema-conformance: PyYAML not available")
    sys.exit(1)


def request_body_refs(spec: dict) -> dict[str, str]:
    """{schema_name: first "operationId or METHOD path" that references it as a requestBody}."""
    refs: dict[str, str] = {}
    for path, methods in (spec.get("paths") or {}).items():
        if not isinstance(methods, dict):
            continue
        for verb, op in methods.items():
            if verb not in ("get", "put", "post", "delete", "patch", "head", "options"):
                continue
            if not isinstance(op, dict):
                continue
            body = (op.get("requestBody") or {}).get("content", {}).get("application/json", {})
            ref = (body.get("schema") or {}).get("$ref", "")
            m = re.match(r"^#/components/schemas/(\S+)$", ref)
            if m and m.group(1) not in refs:
                refs[m.group(1)] = op.get("operationId") or f"{verb.upper()} {path}"
    return refs


def schema_properties(spec: dict, name: str) -> set[str] | None:
    schema = (spec.get("components") or {}).get("schemas", {}).get(name)
    if not isinstance(schema, dict):
        return None
    return set((schema.get("properties") or {}).keys())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--service", required=True)
    ap.add_argument("--generated", required=True, help="path to the Quarkus-generated openapi.yaml")
    ap.add_argument("--enforce", action="store_true")
    args = ap.parse_args()

    level = "error" if args.enforce else "warning"
    committed_path = Path(args.service) / "src/main/resources/openapi.yaml"
    generated_path = Path(args.generated)

    if not generated_path.is_file():
        print(
            f"::notice::openapi-request-schema-conformance: {args.service}: no generated schema at "
            f"{generated_path} — check-api-contract's build step may not have produced one for this "
            "service (e.g. it serves no own /api/v{N} path). Not a finding."
        )
        return 0

    committed = yaml.safe_load(committed_path.read_text(encoding="utf-8"))
    generated = yaml.safe_load(generated_path.read_text(encoding="utf-8"))

    committed_refs = request_body_refs(committed)
    generated_refs = request_body_refs(generated)

    findings = 0
    checked = 0
    for name, where in sorted(committed_refs.items()):
        if name not in generated_refs:
            # The route-conformance gate (#2360) already reports the route itself as
            # published-not-served in this case; don't double-report the schema.
            continue
        pub = schema_properties(committed, name)
        real = schema_properties(generated, name)
        if pub is None or real is None:
            continue
        checked += 1
        for prop in sorted(pub - real):
            findings += 1
            print(
                f"::{level}::openapi-request-schema-conformance: {args.service}: "
                f"published-not-parsed — {name}.{prop} (used by {where}) is declared in "
                f"openapi.yaml but the request DTO has no such property"
            )
        for prop in sorted(real - pub):
            findings += 1
            print(
                f"::{level}::openapi-request-schema-conformance: {args.service}: "
                f"parsed-not-published — {name}.{prop} (used by {where}) exists on the request "
                f"DTO and is absent from openapi.yaml"
            )

    print(
        f"check-openapi-request-schema-conformance: {args.service}: {checked} request schema(s) "
        f"compared, {findings} finding(s)."
    )
    if findings and not args.enforce:
        print(
            "check-openapi-request-schema-conformance: advisory — will become a hard gate once the "
            "backlog is cleared (see rules.yaml: openapi_request_schema_conformance)."
        )
    return 1 if (findings and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
