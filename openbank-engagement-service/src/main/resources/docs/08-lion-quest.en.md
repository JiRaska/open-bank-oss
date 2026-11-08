# Lion Quest chapter catalogue

The service owns chapter availability, ordering, season bounds, unlock requirements
and party-scoped completions. Optional schema-1 course content also carries authored geometry and component placement. The mobile application owns the renderer, supported worlds, heroes, controls and physical validation.
Chapter numbering starts at 0 (Embankment), then 1 (rooftops), 2 (Charles Bridge).

`GET /api/v1/game/admin/catalog` permits operator, admin, compliance and marketing reads.
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

## Visual course authoring

`GameLevel.content` is optional and binds its `id` and `theme` to the catalogue entry.
It contains the existing engine's ground, slopes, platforms, rewards, enemies,
rooftop objects, bridge platforms/traps and boss positions. Lists are bounded to
200 entries, coordinates to 50,000 game units, and known geometric invariants are
checked before saving. The content is data, never downloadable code or a new world.
Old documents without content continue to load without a migration.

`PUT /api/v1/game/admin/drafts` permits verified human marketing/admin authors.
It requires every LIVE entry to remain identical, including its presence and order;
new or changed entries must remain OFF/BETA. The snapshot revision is checked before
this comparison and again under the store's update lock, so a concurrent publisher
causes 409. The administrator's existing write remains the only publication path.
Both paths retain the original transactional before/after audit.

`GET /api/v1/game/levels?contentSchema=1` returns authored content to a capable
client. Without this capability, the original three chapter ids remain available
with bundled geometry and newly authored ids are omitted. Availability, season,
party progress and unlock requirements are still evaluated by the server.

Production explicitly enables the shared OPA PDP producer through the existing
build-time opt-in. Enforcement remains enabled and unavailable policy decisions
fail closed. The integration suite verifies that the CDI bean actually exists.
Test-only advisory policy mode remains confined to the existing HTTP contract tests;
the OPA suite verifies human authoring and publication boundaries independently.

The admin scene is an artwork-based placement preview. Course playthrough and
physical reachability require the native game. Keep a new course in BETA until it
has been played in an editor-capable app, then publish through the administrator.

Nullable request-array elements and null component values are rejected at the wire boundary before constructing the non-null game model. Both publication and draft endpoints return 400 and retain the original catalogue revision for these malformed requests.
