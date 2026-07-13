# Compliance

> **Money-path status:** document-service is **NOT** in `rules.yaml: money_path_services`. It renders and
> stores documents and orchestrates e-signature; it never moves money or gates a fund release. It IS a
> **trust-boundary change** (ingests template + data into rendered legal documents, holds `restricted`
> content under a 10-year retention obligation, orchestrates e-signature) ⇒ a threat model is required
> (`docs/threat-models/document-service.md`, ADR-0030).

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **eIDAS** (Reg. (EU) 910/2014) | e-signature ceremonies; `ADVANCED` today, `QUALIFIED` (QES) phase-2 | `SignatureCeremony` + `SignatureSealPort` (PAdES sealing phase-2, EU DSS + QSeal/HSM) |
| **GDPR** (Reg. (EU) 2016/679) | Documents embed party references and restricted content | classification `restricted`, role-gated access, retention window |
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience | health probes, outbox resilience stack, metrics, `/api/v1/info` build identification |
| **NIS2** | Network & information security | OIDC auth, OPA authz, in-cluster mTLS, security response headers (CSP/HSTS/…) |
| **eArchiving / retention** | Legal documents kept for evidence | `governance.yaml: retentionPolicy: 10 years`; `retain_until` per document; WORM via S3 Object Lock (ADR-0161, follow-up) |

## GDPR mapping

### Lawful basis (Art. 6)
- **Contract** (Art. 6(1)(b)) — rendering and retaining agreements/statements is necessary to perform the
  banking contract.
- **Legal obligation** (Art. 6(1)(c)) — document/evidence record-keeping.

### Personal data held

| Data | Role | Source |
|---|---|---|
| `party_ref` | subject reference (pseudonymized) | render request |
| `metadata_json`, rendered content | may contain PII embedded from the data map | render request |
| `signers_json` (party refs) | e-signature participants | ceremony request |

### Data subject rights
- **Access (Art. 15):** `GET /documents/{id}` + `/{id}/content`; documents by party via the repository.
- **Erasure (Art. 17):** constrained by the 10-year evidence retention window; archival sets a terminal
  status, content retained under WORM for the retention period.
- **Restriction (Art. 18):** a document can be archived to remove it from active use without deletion.

### Retention (Art. 5(1)(e))
`governance.yaml: retentionPolicy: 10 years`. `retain_until` is captured per document; automated
retention/erasure enforcement is a tracked follow-up (see the threat model residual risks).

## Authorization (ADR-0034)
- Decisions delegate to an **OPA sidecar** via `openbank-libs` `@Authorize`.
- `authz.enforce=${AUTHZ_ENFORCE:true}` — enforced by default in this scaffold.
- Coverage today: `documentTemplate.publish` and `signatureCeremony.recordDecision` are annotated;
  completing `@Authorize` coverage across the remaining endpoints is a tracked follow-up.

## Security controls
- ✅ AuthN: Keycloak OIDC bearer (realm `openbank`).
- ✅ AuthZ: OPA sidecar (`@Authorize`); every endpoint role-gated (reflection guard test).
- ✅ Content integrity: SHA-256 content addressing; WORM object store planned (ADR-0161).
- ✅ SSTI/XSS mitigation: logic-less renderer + HTML-escaping of substituted values.
- ✅ Transactional outbox with at-least-once delivery + resilience stack.
- ✅ Security response headers: CSP `default-src 'self'`, HSTS, X-Frame-Options DENY, nosniff, …
- ⚠️ PDF rendering + PAdES sealing are placeholders — the signed artifact is not yet cryptographically
  verifiable (phase-2, ADR-0162/0007).
- ⚠️ Retention enforcement not yet automated — TBD.
