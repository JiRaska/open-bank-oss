# Context Fraud source E2E evidence — 2026-09-26

Status: local integration evidence, not complete scenario acceptance or sandbox delivery.

Source commit: `44de0fa3ecb441ff451f9e1c1cba514336853028`. Both packaged services used this checkout and shared libraries. Context assignment validation passed four real HTTP contract tests, lint, Detekt and Quarkus packaging. Runtime copies were preserved with SHA-256 manifests outside the repository.

The isolated fixture used synthetic accounts and counterparties, separate PostgreSQL databases, Redis, Kafka, a dedicated Context Fraud consumer group, and a separate Keycloak realm. Both services used verified HTTPS; service-to-service source calls used the configured TLS 1.3 trust. OIDC and OPA were active, using the production Context and Fraud policies. No database assignment insert, permissive policy replacement, or disabled certificate verification was used.

## Observed behavior

- Actual Fraud scoring returned HTTP 200 and `REVIEW`.
- Opening the source case returned 201; the transactional reference outbox reached `SENT`, and the dedicated Context consumer persisted the reference.
- A maker proposed `FRAUD_INVESTIGATION` with the exact source case root through the API (201). A distinct checker approved it (200, `APPROVED`).
- The assigned investigator read the source-backed network (200). An unassigned administrator and an unauthorized role each received 403.
- A second source-open case sharing the same synthetic account was independently assigned. The network returned one related case, one inspected candidate and one compared candidate, through the dedicated service identity and the investigator bearer.
- Closing that related source case returned 200 and `CLOSED_NO_FINDING`. The original network then contained no related case; requesting the closed root returned 403.
- Stopping the isolated Fraud process made the original Context network return 503. Restarting the same runtime with the same database restored 200 for the original root.
- A third source-open case sharing the account was not assigned. Its reference reached Context, but neither its identifier nor its relation appeared in the assigned investigator’s network. Direct access to its root returned 403.

## Limits and remaining acceptance

This proves one local synthetic source-backed flow and its access/state/outage boundaries. It does not prove deployed Kafka ACLs, actual Audit-service ingestion, historical role changes, concurrency or race coverage, annual 1x/10x performance, the other banking scenarios, required human review, or sandbox deployment. Runtime and request evidence is machine-local. The portable harness described below covers the API lifecycle; runtime provisioning and the outage/recovery drill remain separate.

Initial fixture errors were corrected without changing production security gates: an undeclared Kafka channel override prevented startup, the source HTTPS port must satisfy the existing 8443 gate, and the isolated consumer needed its own actual group. The first synthetic CA lacked a required key-usage extension; it was corrected instead of disabling verification. Initial assertion assumptions were corrected to use the actual `CLOSED_NO_FINDING` status and allow an empty HTTP 403 body.

## Portable lifecycle probe

`perf/scripts/context-fraud-source-e2e.py` was executed against the same isolated real services with verified TLS, OIDC, production OPA policies and Kafka. It exited 0: three review-queue checks, assigned shared-account matching, unassigned administrator/non-admin/hidden-root denials, closed-related removal, closed-root denial, and normal API cleanup all passed.

Provision an isolated synthetic fixture first. Supply HTTPS Fraud/Context base URLs, the trusted CA certificate, and a private JSON file containing `maker`, `checker`, `reader`, and `denied` bearer tokens. The first three must be distinct administrators and the fourth a distinct non-admin; tokens must remain valid through execution and cleanup. The servers verify token signatures and policies; parsing claims in the runner is input validation only.

```sh
python3 perf/scripts/context-fraud-source-e2e.py --synthetic-fixture \
  --fraud-url "$FRAUD_E2E_URL" --context-url "$CONTEXT_E2E_URL" \
  --ca-file "$E2E_CA_FILE" --tokens-json "$E2E_TOKENS_FILE"
```

The probe creates random synthetic accounts/counterparties and owns only its created cases and assignment proposals. It closes owned cases and revokes their assignments through normal APIs, failing if cleanup fails. Immutable source scores and audit evidence remain intentionally. An indeterminate transport failure during creation can leave an unidentified synthetic record and requires fixture-owner reconciliation. No production fixture or real client data should be supplied.

The automated probe does not stop services, provision Kafka ACLs, prove hidden-case projection readiness independently, exercise browser login/BFF/UI, or certify performance. The source outage/recovery result above came from the separate controlled runtime drill.

## Actual browser login and graph rendering

A headless Chromium probe used the actual Admin UI, Keycloak authorization-code login, and synthetic assigned administrator. The authenticated browser called the actual Context BFF, received HTTP 200 with the expected source root, and rendered the starting-case evidence. Visual inspection confirmed the source case, score reference, account, counterparty and shared-account connection to one assigned related case. No injected session cookie, mocked BFF response or bypassed authorization was used.

The probe initially timed out while waiting for complete page loading after authentication; waiting for the document instead of all ancillary requests allowed the real flow to complete. This was a probe correction, not a production authentication change. The separate review-queue panel remained unavailable because its generic local service mapping targets a different port from the isolated Fraud fixture. Therefore this proves graph login/BFF/rendering, not complete acceptance of the entire Fraud page. Browser evidence and synthetic screenshots remain machine-local.
