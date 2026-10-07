# 05 — Operations

## camt.053 XML intake

Treasury parses untrusted camt.053 uploads through `SecureXml`. Its DOM builder rejects DOCTYPE declarations and external entities before constructing a document. A rejected or malformed upload must be corrected at its source; do not weaken parser settings to accept it. The service does not make an outbound fetch for entities named by the file.

## First checks for a stalled deal

Read the deal and its last transition through the authenticated treasury API before retrying an action. A pending approval still requires a different human approver; a rejected product mandate cannot be cleared with the senior counterparty override. For a booked deal whose financial effect is missing, compare the treasury transition, its outbox delivery and the ledger's idempotent journal result. Replaying the same transition without identifying which boundary failed can obscure a ledger or event-delivery fault.

For reconciliation, inspect the statement's reconciliation result and outstanding breaks. A break represents a difference between expected and observed settlement; do not mark it settled from a successful XML upload alone. The repository runbook `docs/runbooks/svc-treasury.md` covers service health and recovery; deployment configuration remains in GitOps.
