<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-audit-service

Status: implementation review required. Scope: the authenticated REST read/verify surface and its
authorization wiring. This model does not certify production deployment or regulatory compliance.
`openbank-audit-service` is not listed in `rules.yaml: money_path_services`; this document is kept
voluntarily because the service holds the fleet's evidentiary record.

## Assets and trust boundaries

The protected assets are the persisted audit entries, their hash-chain links and the integrity
verdicts the service reports. Callers reach the REST surface through OIDC authentication and the
`@Authorize` interceptor; each authorization decision crosses the boundary to the per-pod OPA
sidecar.

## Change log

### 2026-10-03 — east-west mTLS listener (8443) for the evidence-bundle caller (#11900)

audit-service gains a private-CA mTLS listener on 8443 (`audit-service-internal-tls`, `openbank-ca`;
`quarkus.http.ssl.client-auth=required` baked under `%prod`), same shape as consent-service's.
lending-service's evidence-bundle read uses it with its `lending-internal-tls` client certificate,
because that request carries a person's bearer token and must not cross the cluster in plaintext
(ASVS V9.1, which flagged the first draft's `http://…:8113`). **STRIDE-I/S:** the token is no longer
readable on the wire, and a caller without a CA-issued client certificate cannot reach 8443 at all.
HTTP/8113 stays for existing callers (admin-ui, security-scanner) and the probes
(`insecure-requests: enabled`); their migration is not part of this change.

### 2026-10-03 — evidence bundle route, and a fourth reader role (#11900)

New `GET /api/v1/audit/evidence/{aggregateId}` (ADR-0214 D3): every entry about one aggregate,
oldest first, with each entry's hash recomputed at read time (`VERIFIED` / `MISMATCH` /
`LEGACY_UNVERIFIABLE` / `UNCHAINED`). Read-only; no new write path.

**New reader (STRIDE-I).** `ROLE_CREDIT_RISK` may call this one route, and only this one
(`/entries`, `/integrity` and the anchors stay AUDITOR/ADMIN/COMPLIANCE — asserted by
`AuditEvidenceBundleIT` and the rego tests). Credit-risk staff already read the same loan evidence
from lending-service; lending now sources it here instead of from its own outbox, so the audience is
unchanged and the source becomes the tamper-evident chain.

**New caller, same principal (STRIDE-S/E).** lending-service calls this route, forwarding the
signed-in person's own bearer token — it never uses its service account here. The action is
`audit.evidence.reconstruct`, deliberately not `*.read`: measured with `opa eval` against this
bundle, base `rest.rego`'s `operator-read-any` / `compliance-read-any` grant every `*.read` action to
HUMAN-classified service accounts, so the same route under a read-verb action name was allowed
for `service-account-openbank-services`. With `reconstruct`, the evidence rule — which
excludes `service-account-*` — is the only grant; the must-deny controls (lending SA with
COMPLIANCE + CREDIT_RISK, shared SA with OPERATOR, human OPERATOR) all evaluate to deny.
**Pre-existing, not changed here:** `audit.read` is a `*.read` action, so the same base rules allow it
for a service account at the OPA layer; only `@RolesAllowed` (AUDITOR/ADMIN/COMPLIANCE, which the
realm service accounts do not hold) stops one. The rule comment claiming its service-account
exclusion keeps M2M out is therefore not what enforces it.

**Tampering (STRIDE-T/R).** An edited row is reported as `MISMATCH` and the bundle as `tampered`.
Per-entry recomputation cannot detect a deleted or re-ordered row; the response points at
`/api/v1/audit/integrity` for that, and says so in its contract.

### 2026-10-03 — policy decision point wiring

The service now opts in to the shared libs-runtime `OpaPolicyDecisionPointProducer`
(`openbank.authz.opa-pdp-producer.enabled: true`), which registers `OpaSidecarPolicyDecisionPoint`
as the CDI policy decision point. `opa.url`, `opa.path` and `opa.timeout-ms` select the sidecar
and bound each request. Before this, no `PolicyDecisionPoint` bean existed in the service: a
running OPA sidecar alone did not authorize anything — enforced `@Authorize` requests took the
interceptor's `pdp_unconfigured` branch and advisory mode never consulted the policy.

- **Elevation of privilege:** with the producer present, an OPA deny is enforced even for a caller
  whose role would otherwise look eligible. `AuditAuthzWiringIT` drives real HTTP through CDI and
  the interceptor against a local policy fixture and asserts both an allow (200) and a deny (403).
- **Denial of service:** a missing or unreachable sidecar remains fail-closed when enforcement is
  enabled; the timeout bounds how long a request waits on it.

Residual: the deployed enforcement setting and the role/action policy are unchanged by this
wiring and are not attested by the test, which uses a fixture rather than the generated bundle.

### 2026-10-03 — ingestion durability and serialized chain appends

Scope widens to Kafka audit ingestion, PostgreSQL append and failure visibility. Producers cross a
Kafka authorization boundary; the consumer crosses a separate PostgreSQL boundary. Dead-letter
delivery retains failed input for recovery. Neither receipt by Kafka nor an intact chain
establishes that every expected producer event exists.

- **Loss after a store failure (Repudiation/Tampering):** acknowledge only after persistence.
  Parsing or persistence failure invokes NACK, and a failed NACK propagates. Configured DLQ
  serialization preserves the original String payload instead of JSON-encoding it again.
  `AuditDlqIT` exercises malformed input, a rejected database insert, continued consumption and
  producer-ID replay with a real broker.
- **Duplicate evidence after a lost reply:** a producer event ID identifies the entry. Without one,
  the original Kafka topic/partition/offset supplies a deterministic fallback. Body-only replay
  cannot recreate that fallback identity. Existing rows are not backfilled. A reused context
  commitment ID carrying different evidence is still rejected, now inside the locked append.
- **Forked chain across replicas (Tampering):** a transaction-scoped PostgreSQL advisory lock
  covers the duplicate check, current head and append, replacing the in-process mutex that was
  only correct for a single replica. `AuditConcurrentAppendIT` forces two independent writers to
  overlap; hash and timestamp canonicalization remain covered by the existing round-trip tests.
  Each append allocates its row ID from `audit_entries_seq` while holding the lock; per-process
  cached ID blocks are not used, so gaps between IDs are expected and are not missing-event
  evidence.
- **Silent failure despite a healthy chain:** `AuditIngestionFailed` observes recent failed
  ingestion, including the first observed positive counter after startup. Its resolution is not
  replay proof.
- **Information disclosure in error handling:** ingestion failures log the exception, not the
  source payload.

Residual: producer authenticity and Kafka ACLs remain deployment controls. Local tests do not prove
production retention, replication, authorization or completeness of all producers. All writers
must adopt the append lock before relying on concurrent safety; rollback to
acknowledgement-on-failure is unsafe. See `docs/runbooks/audit-ingestion-recovery.md`.

### 2026-10-03 — online checkpoint verification consistency

- **Tampering / Repudiation:** online anchor verification now compares the recomputed checkpoint
  digest with the stored digest and requires the captured chain status to be INTACT before
  counting a checkpoint as verified. Digest mismatch and a non-intact captured chain are reported
  separately from signature failure (`anchorDigestMismatch`, `capturedChainNotIntact`): a
  correctly signed broken checkpoint is evidence of the producer's observation, not of signature
  forgery. This aligns operational results with the independent verifier's rejection of such
  checkpoints. `AuditAnchorCheckpointConsistencyTest` covers both cases with a valid signature.

Residual: unsigned coherent checkpoints and unavailable historical keys remain UNVERIFIED. These
checks do not establish external custody, completeness of an export or retention guarantees;
independent verification remains necessary.

### 2026-10-03 — trail reads refuse service accounts at the policy decision

- **Information disclosure / Elevation of privilege:** the trail reads (`/entries/{aggregateId}`,
  `/entries/by-actor/{actorId}`) were authorized as `audit.read`. Ending in `.read`, that action was
  granted by base `rest.rego`'s `operator-read-any` / `compliance-read-any` to any HUMAN-classified
  principal holding ROLE_OPERATOR / ROLE_COMPLIANCE — and Keycloak client_credentials tokens are
  classified HUMAN. Measured with `opa eval` against the audit OPA bundle:
  `service-account-openbank-services` (ROLE_API, ROLE_OPERATOR) was allowed with reason
  `operator-read-any`. The audit extension rule's `service-account-` exclusion was therefore not
  load-bearing; only `@RolesAllowed` stopped a machine caller. The action is now
  `audit.trail.inspect`, outside base's `{list, read}` verbs, and the role-action matrix no longer
  lists the old name. Against the regenerated bundle, service accounts with ROLE_OPERATOR,
  ROLE_COMPLIANCE or ROLE_AUDITOR are denied; human AUDITOR, COMPLIANCE and ADMIN are allowed.
  `rest_test.rego` and `AuditResourceSecurityTest` pin the deny and the verb.

Residual: `@RolesAllowed` and the OPA rule both depend on realm role assignment; a realm that grants
a staff client's tokens a non-`service-account-` id with an oversight role is outside this control.
`AUTHZ_ENFORCE` for audit-service decides whether the OPA deny is enforced or advisory.
