# ADR-0256 contract supplement: KYC → case-coordinator

Status: design only (delivery remains partial). This document records the implementation contract; it does not claim that the integration is deployed. Related follow-ups: #4458 (party-event materiality) and #4459 (adverse-media source).

## Sequence and contract

1. A live ADR-0256 trigger is classified by `kyc-service` and persisted with the KYC case state.
2. The adapter calls `POST /api/v1/case-coordinator/cases` with:

```json
{"caseClass":"AML_ALERT","subjectRef":"<party UUID>","openedBy":"kyc-service","dispositionTarget":"kyc-rescreen"}
```

The path and field names are the coordinator OpenAPI contract. `AML_ALERT` is the wire enum spelling; governance uses the `aml-alert` class key.

3. The call carries a service OIDC access token. The coordinator must authorize `ROLE_OPERATOR` and `openedBy=kyc-service` through its asserted-identity gate. A caller-selected `openedBy` must never be trusted without that gate.
4. `201` records the returned coordinator case id. `409` is an idempotent duplicate and records/links the existing KYC trigger. `429`, `503`, timeout, or malformed responses are failures to open, not a reason to clear or auto-dispose the KYC case.

## Durable failure handling

The current KYC `kyc_outbox` carries lifecycle Kafka events but has no operator-task type. Before production wiring, add a migration and domain event (for example `KYC_COORDINATOR_OPEN_FAILED`) with trigger id, party id, reason class, retry count, and next-attempt timestamp. The event must be written transactionally with the trigger state and retried by a dedicated worker; a terminal failure must be visible in the operator queue. Logging alone is not compliant with D2.

## GitOps and security

- Configure a required coordinator base URL, bounded connect/request timeouts, and fail-closed OIDC client credentials in KYC's existing ExternalSecret.
- Add KYC egress to the coordinator service and coordinator ingress from the KYC namespace; keep default-deny policies intact.
- Do not reuse an upstream provider key or make the URL optional in production.

## Pact and acceptance tests

Add a KYC consumer pact for the literal POST path and body, covering 201, 409, and 503. Extend the existing sole `@PactFolder` provider verifier in `openbank-case-coordinator-agent` with matching provider states; do not add a second provider class. Acceptance tests must prove:

- OIDC/role and asserted-agent denial (403) before coordinator availability is checked;
- 201 stores the coordinator id and is retry-idempotent;
- 409 links without duplicate work;
- 503/timeout creates a durable operator task and retries;
- network policy and secret wiring are present in rendered GitOps.

## Rollout and rollback

Roll out the durable event/migration first, then deploy the adapter disabled by default, exercise Pact and a sandbox coordinator, and enable only after the operator-task and retry metrics are observed. Rollback disables the adapter and leaves the durable failure rows for replay; never delete them or mark the KYC trigger successful.
