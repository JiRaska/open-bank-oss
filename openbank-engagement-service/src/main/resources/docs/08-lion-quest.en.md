# Lion Quest chapter catalogue

The service owns chapter availability, ordering, season bounds, unlock requirements
and party-scoped completions. The mobile application owns worlds and gameplay.
Chapter numbering starts at 0 (Embankment), then 1 (rooftops), 2 (Charles Bridge).

`GET /api/v1/game/admin/catalog` permits operator, admin and compliance reads.
`PUT /api/v1/game/admin/catalog` is admin-only and compares the submitted revision.
The new catalogue and before/after audit record commit atomically; a stale write
returns 409. Null array elements and invalid domain values are rejected with 400.
`GET /api/v1/game/admin/audit` returns the latest 50 changes to admin/compliance.

Customer-edge calls `GET /api/v1/game/levels` and
`POST /api/v1/game/levels/{levelId}/completion` with the verified party. Completion
requires a currently visible, unlocked chapter and is idempotent. It writes no
points, money or gamification awards. PREVIOUS_LEVEL uses visible catalogue order;
EARN_SOURCE reads existing recorded awards. Education completion is the currently
produced achievement. Beta is a presentation hint, not a banking entitlement.

V12 seeds chapter 0 LIVE and chapters 1/2 BETA with sequential unlocks. Catalogue
and completion operations share database locking. Party erasure removes progress;
the permanent erasure flag prevents delayed device replays from recreating it.

Deploy this service and policy before the edge/admin/mobile rollout. Roll back
applications first and retain the new tables to preserve audit and progress. The
migration includes a separate destructive reverse operation. See
`docs/game/lion-quest-admin.md` in the repository for full rollout and test details.
