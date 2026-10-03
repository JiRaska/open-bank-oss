# Settlement operator approvals

Four-eyes (maker/checker) for settlement origination, #10041 slice 10. Service runbook:
`svc-settlement.md`; threat model: `docs/threat-models/openbank-settlement-service.md`.

- **Flow:** `settlement.create` is four-eyes gated by
  policy; enforcement is `AUTHZ_FOUR_EYES_ENFORCE` (default `false`). When on, a maker's
  `POST /api/v1/settlements` answers 202 with an `approvalId` and creates nothing. A different
  operator reviews `GET /api/v1/settlements/approvals/{id}` (the `summary` is the bound instruction)
  and decides with `PATCH /api/v1/settlements/approvals/{id}` `{"approve": true|false}`; the maker
  repeats the IDENTICAL request with header `X-Approval-Id`, which executes once. Any change to the
  instruction re-parks it. Service accounts are refused the queue by policy (human operators only).
- **Admin review:** the admin approval inbox lists this queue (source `settlement`) and links to
  `/approvals/settlement/{id}` (operators and administrators only), which shows the bound
  `summary`, offers no decision on an expired approval or to the maker, and after a lost response
  requires a reload before another decision.
- **Approval retention:** `settlement_operator_approvals` rows are deleted daily
  (`SETTLEMENT_APPROVAL_PURGE_CRON`, default 04:15) once their authorization expired more than
  `SETTLEMENT_APPROVAL_RETENTION_DAYS` (default 1826) ago, in bounded batches. Watch
  `openbank_workflow_last_success_age_seconds{workflow="settlement-operator-approval-purge"}` and the
  `openbank.settlement.operator.approval.purged` counter; `SETTLEMENT_APPROVAL_PURGE_ENABLED=false`
  stops it (no liveness is registered then). The `SETTLEMENT_OPERATOR_APPROVAL_CHANGED` outbox events
  are the retained evidence and are never purged.

- **Least privilege:** reading or deciding `/api/v1/settlements/approvals` is human-operator only.
  Every `service-account-*` principal is denied by `settlement_rest_ext.rego` (and the SCA queue by
  `sca_rest_ext.rego`). A 403 for a backend caller there is the intended outcome, not an outage.
- **Rollback:** set `AUTHZ_FOUR_EYES_ENFORCE=false`; keep `settlement_operator_approvals` and the
  outbox evidence (never DROP). Approvals parked before the rollback simply expire.
