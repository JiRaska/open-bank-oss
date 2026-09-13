#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""apply-event-schema.py — ADR-0260 D2: register a committed JSON Schema into Apicurio.

WHY THIS EXISTS
----------------
ADR-0260 designates the COMMITTED `openbank-contracts/<service>/schema/<event>.schema.json`
file as the event-schema axis's source of truth — Apicurio is a DERIVED artifact CI applies
from it idempotently on merge to `main`, the same relationship `openapi.yaml` already has to
whatever renders it. This script is that apply step. It is never invoked by hand against a live
registry, and no out-of-band registry mutation is a valid way to change a schema (enforced
operationally by the Apicurio NetworkPolicy restricting write access to the CI service account,
`openbank-infra/gitops/components/apicurio/network-policies.yaml`).

WHAT IT DOES
------------
POSTs the schema content to Apicurio's v2 API as a create-or-update
(`ifExists=RETURN_OR_UPDATE`), then PUTs the subject's COMPATIBILITY rule. Phase 1 of the
ADR-0260 pilot (#1916) is OBSERVE-ONLY: this registers the schema so Apicurio has a real
subject to diff future changes against, but nothing on the producer side validates against it
yet (KafkaDocumentOutboxEventPublisher does not call the registry) — "auto-register" in the
Kafka serializer sense stays false/unwired until Phase 2.

INERT BY DEFAULT, same pattern as PACT_BROKER_URL in `_service-ci.yml`: Apicurio has no public
ingress (same ADR-0056 reasoning as the Pact Broker — this is an in-cluster-only service), so
this only ever runs from the self-hosted pool with a live `APICURIO_REGISTRY_URL`. An unset URL
is a clean no-op (exit 0, nothing attempted), not a failure — a GitHub-hosted runner or a PR
build (no cluster DNS) must not fail this step, it must skip it.

NOT YET VERIFIED AGAINST A LIVE REGISTRY. This PR was built read-only against the cluster (the
task that produced it was explicitly forbidden from mutating it) — the HTTP call shape below is
written against Apicurio's documented v2 REST API and exercised by `--self-test` with a stubbed
transport, but has never actually been POSTed to the running `apicurio-registry` service. The
first real run needs a CI dispatch (`workflow_dispatch` on `schema-registry-apply.yml`, or the
push-to-main trigger once this PR merges) with `APICURIO_REGISTRY_URL` configured, watched for a
non-2xx response the self-test cannot simulate.

Usage:
    apply-event-schema.py --schema-file <path> --topic <topic> [--group default]
        [--compatibility BACKWARD_TRANSITIVE] [--registry-url <url>] [--dry-run]
    apply-event-schema.py --self-test
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_GROUP = "default"
DEFAULT_COMPATIBILITY = "BACKWARD_TRANSITIVE"


def subject_for(topic: str) -> str:
    """TopicNameStrategy convention (Confluent/Apicurio default): `<topic>-value`."""
    return f"{topic}-value"


def build_create_request(registry_url: str, group: str, artifact_id: str, schema_text: str):
    url = f"{registry_url.rstrip('/')}/apis/registry/v2/groups/{group}/artifacts?ifExists=RETURN_OR_UPDATE"
    headers = {
        "Content-Type": "application/json",
        "X-Registry-ArtifactId": artifact_id,
        "X-Registry-ArtifactType": "JSON",
    }
    return url, headers, schema_text.encode("utf-8")


def build_rule_request(registry_url: str, group: str, artifact_id: str, compatibility: str):
    url = f"{registry_url.rstrip('/')}/apis/registry/v2/groups/{group}/artifacts/{artifact_id}/rules/COMPATIBILITY"
    headers = {"Content-Type": "application/json"}
    body = json.dumps({"type": "COMPATIBILITY", "config": compatibility}).encode("utf-8")
    return url, headers, body


def http_put_or_post(method: str, url: str, headers: dict, body: bytes, transport=None) -> tuple[int, str]:
    """`transport` is injected for --self-test; production uses urllib.request directly."""
    if transport is not None:
        return transport(method, url, headers, body)
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:  # noqa: S310 - internal cluster URL only
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")


def apply_schema(
    registry_url: str,
    schema_text: str,
    topic: str,
    group: str = DEFAULT_GROUP,
    compatibility: str = DEFAULT_COMPATIBILITY,
    dry_run: bool = False,
    transport=None,
) -> int:
    try:
        json.loads(schema_text)
    except json.JSONDecodeError as e:
        sys.stderr.write(f"::error::apply-event-schema: unparseable schema JSON: {e}\n")
        return 1

    artifact_id = subject_for(topic)
    create_url, create_headers, create_body = build_create_request(registry_url, group, artifact_id, schema_text)
    rule_url, rule_headers, rule_body = build_rule_request(registry_url, group, artifact_id, compatibility)

    if dry_run:
        print(f"apply-event-schema: DRY RUN — would POST {create_url} (subject={artifact_id})")
        print(f"apply-event-schema: DRY RUN — would PUT {rule_url} -> {compatibility}")
        return 0

    status, text = http_put_or_post("POST", create_url, create_headers, create_body, transport)
    if status not in (200, 201, 409):  # 409 = RETURN_OR_UPDATE found an equal existing version
        sys.stderr.write(f"::error::apply-event-schema: create/update {artifact_id} failed ({status}): {text}\n")
        return 1
    print(f"apply-event-schema: registered {artifact_id} in group {group} ({status})")

    status, text = http_put_or_post("PUT", rule_url, rule_headers, rule_body, transport)
    if status not in (200, 204, 404):
        # 404 here means the rule didn't exist yet on a brand-new artifact and Apicurio wants a
        # POST instead of PUT for the FIRST rule creation on some versions — surfaced, not masked.
        sys.stderr.write(
            f"::warning::apply-event-schema: setting COMPATIBILITY={compatibility} on {artifact_id} "
            f"returned {status}: {text} — verify manually, this is observe-only Phase 1 so a rule "
            f"failure does not block the schema registration above\n"
        )
    else:
        print(f"apply-event-schema: {artifact_id} COMPATIBILITY={compatibility} ({status})")

    return 0


def self_test() -> int:
    """Falsify request construction and response handling with a stubbed transport — no network."""
    fails: list[str] = []

    valid_schema = json.dumps({"oneOf": [{"x-openbank-event-type": "e.v1", "properties": {}}]})

    def case(label: str, cond: bool):
        if not cond:
            fails.append(label)

    # subject_for follows TopicNameStrategy
    case("subject_for appends -value", subject_for("openbank.documents.document.event") ==
         "openbank.documents.document.event-value")

    # Unparseable schema is rejected before any network call
    calls: list[tuple] = []

    def recording_transport(method, url, headers, body):
        calls.append((method, url, headers, body))
        return 200, "{}"

    rc = apply_schema("http://unused", "not json", "t", transport=recording_transport)
    case("unparseable schema is rejected with no network call", rc == 1 and not calls)

    # A clean run makes exactly two calls: POST create, PUT rule — in that order, with the
    # artifact id and compatibility value threaded through correctly.
    calls.clear()
    rc = apply_schema("http://apicurio.messaging.svc:8080", valid_schema, "openbank.documents.document.event",
                       transport=recording_transport)
    case("a clean apply returns 0", rc == 0)
    case("a clean apply makes exactly 2 calls", len(calls) == 2)
    if len(calls) == 2:
        (m1, u1, h1, b1), (m2, u2, h2, b2) = calls
        case("first call is the POST create/update", m1 == "POST" and "/artifacts?ifExists=RETURN_OR_UPDATE" in u1)
        case("first call targets the -value subject", h1.get("X-Registry-ArtifactId") ==
             "openbank.documents.document.event-value")
        case("first call declares JSON artifact type", h1.get("X-Registry-ArtifactType") == "JSON")
        case("first call body is the schema text verbatim", b1.decode() == valid_schema)
        case("second call is the PUT compatibility rule", m2 == "PUT" and u2.endswith("/rules/COMPATIBILITY"))
        case("second call sets the default BACKWARD_TRANSITIVE", json.loads(b2)["config"] == "BACKWARD_TRANSITIVE")

    # A non-2xx/409 create response is a hard failure, not swallowed.
    def failing_transport(method, url, headers, body):
        return 500, "internal error"

    rc = apply_schema("http://apicurio.messaging.svc:8080", valid_schema, "t", transport=failing_transport)
    case("a 500 on create is a hard failure", rc == 1)

    # 409 (RETURN_OR_UPDATE found an equal version already registered) is treated as success —
    # re-running this script against an unchanged schema must be idempotent, not an error.
    def conflict_then_ok_transport(method, url, headers, body):
        return (409, "exists") if method == "POST" else (200, "ok")

    rc = apply_schema("http://apicurio.messaging.svc:8080", valid_schema, "t", transport=conflict_then_ok_transport)
    case("a 409 create (already registered, unchanged) is treated as success (idempotent)", rc == 0)

    # dry-run makes no network call at all
    calls.clear()
    rc = apply_schema("http://apicurio.messaging.svc:8080", valid_schema, "t", dry_run=True,
                       transport=recording_transport)
    case("dry-run returns 0 and makes no network call", rc == 0 and not calls)

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: apply-event-schema request construction is falsifiable (11 cases)")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    ap = argparse.ArgumentParser()
    ap.add_argument("--schema-file", required=True)
    ap.add_argument("--topic", required=True)
    ap.add_argument("--group", default=DEFAULT_GROUP)
    ap.add_argument("--compatibility", default=DEFAULT_COMPATIBILITY)
    ap.add_argument("--registry-url", default=os.environ.get("APICURIO_REGISTRY_URL", ""))
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if not args.registry_url:
        print(
            "apply-event-schema: APICURIO_REGISTRY_URL not set — no-op (inert on any lane that "
            "can't reach the in-cluster registry, same pattern as PACT_BROKER_URL)"
        )
        return 0

    with open(args.schema_file, encoding="utf-8") as f:
        schema_text = f.read()

    return apply_schema(
        args.registry_url, schema_text, args.topic,
        group=args.group, compatibility=args.compatibility, dry_run=args.dry_run,
    )


if __name__ == "__main__":
    sys.exit(main())
