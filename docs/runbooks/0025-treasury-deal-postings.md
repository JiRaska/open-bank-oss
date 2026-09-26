---
# Treasury deal postings and first deployment (ADR-0315)

Operational runbook for openbank-treasury-service's ledger postings and its one-off bootstrap. Kept
here rather than in the generated svc-treasury.md, which generate-service-runbooks.py owns — hand
edits there are drift (#2255).

## Ledger postings (ADR-0315 D5)

Every posting is a balanced journal sent to ledger-service `POST /api/v1/journals` over its
private-CA mTLS listener (8443, client cert `treasury-internal-tls`), authenticated as
`service-account-openbank-treasury`, whose only ledger grant is `ledger.create`. Idempotency key:
`treasury:<dealId>:<settled|matured|reversed>`, so a retried posting never books twice.

| Transition | Deal type | Debit | Credit |
|---|---|---|---|
| SETTLED | `MM_PLACEMENT` | 1500 (CZK) / 1501 (EUR) placements | nostro 1001 (CZK) / 1002 (EUR) |
| SETTLED | `CNB_DEPOSIT_FACILITY` | 1510 | nostro 1001 |
| SETTLED | `MM_BORROWING` | nostro 1001 / 1002 | 2300 (CZK) / 2301 (EUR) borrowings |
| MATURED | placement / ČNB facility | nostro, principal + interest | asset account, principal; 4200 / 4201 MM interest income, interest |
| MATURED | `MM_BORROWING` | borrowing account, principal; 5200 / 5201 MM interest expense, interest | nostro, principal + interest |
| REVERSED | from SETTLED | the settlement journal with sides flipped | (offsetting journal) |
| REVERSED | from BOOKED | nothing is posted | |

Accrued-interest accounts 1520 / 1521 / 2310 / 2311 are seeded in the ledger but unused in the MVP.
A reversal is an offsetting journal treasury posts itself; it never calls `ledger.reverse`.

**Triage a missing posting.** Look the deal's idempotency key up in the ledger
(`treasury:<dealId>:settled`). Present: the ledger side is done, look at the deal state. Absent:
check the pod log for a 401 (the M2M client secret or the Keycloak client), a 403 (the ledger OPA
rule `service-treasury-ledger-post`, or a token without `preferred_username` because the client
lacks the `profile` scope, which makes the principal id a UUID) or a TLS handshake error
(`treasury-internal-tls` not issued or expired).

## First deployment and access (one-off)

1. **BEFORE merge:** create Keycloak client `openbank-treasury` (ROLE_API only, `profile` scope)
   and its Vault KV entry `keycloak/treasury` (runbook 0009, "Treasury deal postings"). The pod
   env ref is `optional: false`, so without it the pod stays in `CreateContainerConfigError`.
2. **After merge:** run the `platform-tofu` apply. It creates the ECR repository (derived from the
   gitops image refs) that auto-deploy's first build pushes to, and the `treasury-db` pod identity
   association for the S3 backup. Then force the first base backup and assert
   `status.firstRecoverabilityPoint` is set; verify with `aws s3 ls`, never by a condition.
3. **Staff access:** assign the realm roles `ROLE_TREASURY_DEALER` / `ROLE_TREASURY_APPROVER`
   (they already exist in the realm template) to the treasury staff with `kcadm`. Never give one
   person both; the domain also refuses an approver who created or submitted the deal.

`OPENBANK_TREASURY_SIMULATED_MARKET_ENABLED=true` in the sandbox: synthetic counterparties settle
and mature booked deals (ADR-0315 D9). An environment with real market connectivity sets it to
`false`.
