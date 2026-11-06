# Lion Quest chapter administration

Implements the bank-owned catalogue proposed by the mobile app's APP-0004. The
console is `/loyalty/game`, linked from Lípa. Chapter numbering begins at 0:
Embankment, Old Town rooftops, Charles Bridge. The initial catalogue publishes
Embankment as LIVE and the other two worlds as BETA.

The bank owns availability (OFF/BETA/LIVE), unique order, Czech/English titles,
optional UTC season bounds and unlock requirements. The mobile app owns painted
worlds, geometry, heroes and controls. Unknown themes are hidden by the client;
adding art or mechanics still requires a mobile release. Stable chapter ids
identify stored completions and should be retained when changing names or order.

## Authority and writes

- Staff read: `GET /api/v1/game/admin/catalog`, operator/admin/compliance.
- Admin write: `PUT /api/v1/game/admin/catalog`, admin only. Send the revision from
  the read. PostgreSQL serializes updates; stale revisions receive 409. The new
  document and audit record (verified actor and before/after values) commit in one
  transaction. Reload before retrying a conflict; the form retains unsaved edits.
- Audit: `GET /api/v1/game/admin/audit`, admin/compliance, latest 50 changes.
- Customer read: `GET /customer/v1/game/levels?beta=true|false`.
- Completion: `POST /customer/v1/game/levels/{levelId}/completion?beta=true|false`.

Customer-edge derives the party from the verified customer and acting-for context;
the device cannot supply another party id. Its M2M grant admits exactly the two
game-play actions, excluding catalogue administration. Customer responses project
only chapter data and unlock state, never operator audit attribution.

Beta is a presentation hint used by testing builds, not a security boundary or a
banking entitlement. OFF and inactive chapters are absent. PREVIOUS_LEVEL refers
to the previous currently visible chapter in catalogue order. A completed chapter
retains its earned progress when requirements are changed. Completion is admitted
only while a chapter is visible and unlocked, serialized against catalogue edits,
and idempotent on `(party_id, level_id)`. It never creates a gamification award,
points, money or a credit decision.

EARN_SOURCE uses the closed existing `EarnSource` catalogue and actual persisted
awards. Education completion is currently the only wired producer and the only
source offered by the console. This change does not invent savings/login/repayment
signals or bypass their future reviewed implementation. No marketing consent is
required to read or play an explicitly opened game.

## Persistence and rollout

V12 creates `game_catalog`, `game_catalog_audit` and `game_completion`; its SQL file
contains the reverse migration. Party erasure removes game completions through
the existing erasure consumer, with failure retained in its bounded retry/DLQ path.
Catalogue history contains operator attribution, not customer game progress.

Deploy engagement-service and its migration/policy first, then customer-edge and
admin-ui, then the mobile client. Older apps ignore the new additive endpoints.
An unavailable catalogue is an explicit failure, never a fabricated empty list.
The mobile client keeps the last known locks per human/profile/build channel,
offers bundled Embankment offline, and queues wins without unlocking more chapters
until the bank confirms them. A genuinely empty online catalogue remains empty.

Rollback the applications first. Leave the new tables in place when retaining
progress/audit is needed; dropping them is an explicit destructive database
rollback, not part of an application rollback.

## Verification

The engagement suite exercises domain boundaries, real HTTP roles, PostgreSQL
migration/audit, stale revision conflicts, party-isolated idempotent completion and
absence of reward writes. Edge tests exercise party binding, projection and failure
handling. Admin unit and browser tests cover permissions, save/conflict recovery
and both light/dark themes. Shared OPA tests bound the edge's new authority.

```sh
./gradlew :openbank-engagement-service:test :openbank-engagement-service:detekt \
  :openbank-engagement-service:ktlintCheck :openbank-customer-edge:detekt \
  :openbank-customer-edge:ktlintCheck
./gradlew :openbank-customer-edge:test --tests '*CustomerGameResourceTest'
bash openbank-infra/opa/build-bundle.sh
cd openbank-admin-ui
npx vitest run src/test/game-catalog-bff.test.ts src/test/roles.test.ts
npx playwright test e2e/game-catalog.spec.ts
```
