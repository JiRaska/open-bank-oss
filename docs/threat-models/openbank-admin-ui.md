<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — admin-ui money-path screens

- **Date:** 2026-11-06
- **Status:** Lightweight STRIDE (ADR-0030 D2). Scoped to operator access to the money-path screens;
  the edge gate for internal tools is covered by `admin-ui-tools-gate.md`.

## 1. Scope

Staff reach money-path screens through Keycloak SSO and the admin-ui session. The route guard in
`src/lib/auth/roles.ts` decides who may open a screen. The services still authorize each call.

## 2. STRIDE

| ID | Threat | Mitigation | Residual |
|---|---|---|---|
| S1 | A signed-in session cannot be obtained through the deployed SSO, or is obtained without it. | Scheduled sign-in through the real Keycloak form in the deployed lane: `verifies:admin-ui/E2E-SSO`. | Proves sign-in only for the two lane operators. |
| E1 | An operator opens a money-path screen their role does not admit, or a role meant to see one is locked out. | Deployed lane checks each screen against the route guard: `verifies:admin-ui/E2E-payments`, `verifies:admin-ui/E2E-sepa-instant`, `verifies:admin-ui/E2E-ledger`, `verifies:admin-ui/E2E-accounts`, `verifies:admin-ui/E2E-transactions`, `verifies:admin-ui/E2E-fx`, `verifies:admin-ui/E2E-clearing`, `verifies:admin-ui/E2E-swift`, `verifies:admin-ui/E2E-settlements`, `verifies:admin-ui/E2E-interest`, `verifies:admin-ui/E2E-sdd`, `verifies:admin-ui/E2E-standing-orders`, `verifies:admin-ui/E2E-treasury`, `verifies:admin-ui/E2E-cards`, `verifies:admin-ui/E2E-lending`, `verifies:admin-ui/E2E-fees`, `verifies:admin-ui/E2E-disputes`, `verifies:admin-ui/E2E-fraud`, `verifies:admin-ui/E2E-sanctions`, `verifies:admin-ui/E2E-pension`, `verifies:admin-ui/E2E-day-end`, `verifies:admin-ui/E2E-delegations`, `verifies:admin-ui/E2E-consents`, `verifies:admin-ui/E2E-approvals`. | `/treasury` is proven only to refuse both operators. Smoke depth: the displayed values are not asserted. |
