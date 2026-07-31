# Jurisdictional compliance packs (ADR-0212)

Reference pack content lives here as **reviewed data**, not configuration. Packs take
effect only through the runtime four-eyes activation flow — a file in this directory
does nothing until a compliance maker proposes it and a DIFFERENT compliance principal
approves it via the admin endpoint. Activation is therefore a legal-sign-off action
with a durable audit record, not a deploy.

## Packs

| File | Jurisdiction | Product | Notes |
|---|---|---|---|
| `cz-consumer-credit-v1.json` | CZ | CONSUMER_CREDIT | Reference pack: 257/2016 Sb. + CCD2 duties (14-day withdrawal, RPSN, 1 % early-repayment cap, 90-DPD default, DSTI affordability floor) |

## Activate a pack (per environment)

```bash
# 1. Maker proposes (ROLE_COMPLIANCE)
curl -X POST https://<host>/api/v1/lending/compliance-packs/proposals \
  -H "Authorization: Bearer $MAKER_TOKEN" -H "Content-Type: application/json" \
  --data-binary @openbank-lending-service/src/main/resources/compliance-packs/cz-consumer-credit-v1.json
# → 201 { "id": "<proposalId>", ... }

# 2. A DIFFERENT compliance principal approves (ROLE_COMPLIANCE)
curl -X POST https://<host>/api/v1/lending/compliance-packs/proposals/<proposalId>/decide \
  -H "Authorization: Bearer $CHECKER_TOKEN" -H "Content-Type: application/json" \
  -d '{"approve": true, "reason": "reviewed against 257/2016 Sb."}'

# 3. Verify
curl https://<host>/api/v1/lending/compliance-packs/active -H "Authorization: Bearer $TOKEN"
```

## Flip enforcement (ADR-0212 D4 bootstrap — LAST step, never before activation)

Set `LENDING_ENFORCE_PACK=true` in the environment's gitops config and roll the
service. From that moment origination requests must carry `jurisdiction` +
`productType` with an active pack, or are refused fail-closed. Flipping the flag
before the pack is active refuses every origination — the bootstrap order is:
**seed → four-eyes activate → verify `/active` → flip**.

## Editing rules

- Never edit an already-activated version — propose the next integer `version` with a
  new `effectiveFrom`. In-flight contracts keep their pinned version.
- Schema is closed (unknown keys rejected); every change is a compliance-reviewed PR.
