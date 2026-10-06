# Fraud investigation source evidence

Fraud owns its case facts and exposes a case-scoped HTTPS source API. The dedicated Kafka event is a minimized pointer (event type, case ID, revision and time); it contains no account or counterparty identifiers. Context must not infer case linkage from that pointer alone.

`GET /api/v1/fraud/cases/{caseId}/match-assigned` is for the authenticated Context investigation service with purpose `FRAUD_INVESTIGATION` and the human investigator's bearer. Fraud asks Context for the bounded set of currently assigned candidate cases, then matches source-owned account/counterparty references only within that set and returns a bounded response with candidate count and truncation. A matching identifier is an investigative lead, not proof of common ownership or wrongdoing. The case must still be open; a missing or unavailable authorization dependency fails closed.

Opening, reading evidence and closing a Fraud case use independent case-scoped policy checks. Context applies its own current role, purpose, exact-root assignment, policy, read and disclosure audits before presenting any matched case. Responses carry `Cache-Control: no-store`. Keep the source detail on the mTLS path; do not copy it into Kafka pointers or a broad graph projection. The additive contract is in [`openapi.yaml`](../openapi.yaml), and Flyway V9 stores durable investigation cases.
