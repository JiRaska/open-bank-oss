# Lion Quest chapter access

`GET /customer/v1/game/levels?beta=false` exposes the engagement catalogue as flat
chapter metadata and server-evaluated unlock/completion flags. The JWT customer
and validated `X-Acting-For` context determine the party; callers cannot choose a
party id. Unknown themes should be hidden by the mobile client.

`POST /customer/v1/game/levels/{levelId}/completion?beta=false` records a win only
when engagement confirms that the chapter is visible and unlocked. Success is
204, denied progression 403. Repeating an accepted win is idempotent. The API
accepts no points, money, reward or banking outcome from the device.

OFF and inactive chapters are absent. Beta is a presentation hint for testing
builds, not a privileged bank entitlement. Customer results exclude staff audit
attribution. Both routes require customer authentication; the edge's engagement
M2M grant contains only game read/complete actions and excludes administration.

Unavailable engagement returns 503; malformed upstream catalogues return 502.
Clients retain scoped cached locks and queue wins while offline; an unavailable
response must not be converted into a successful empty catalogue. Responses are
not cached by shared intermediaries. Deploy engagement first, then this edge and
the admin console, then the mobile client. See `docs/game/lion-quest-admin.md`.

Service authority, chapter path and query values are constructed separately using
the shared structured upstream client. Host-bearing paths, query/fragment injection
and redirects to another authority cannot change the completion destination.

## Authored course capability

Editor-capable applications send `contentSchema=1` on the catalogue GET. The edge
forwards only this supported capability and projects the optional `content` beside
the existing flat chapter fields. The party still comes from the verified JWT and
acting-for context. Capability negotiation changes game presentation, never customer
identity, unlock authority, awards or banking permissions.

The content uses the app's existing version-1 LevelSpec format: terrain, platforms,
collectibles, enemies, bridge/rooftop objects and finish/boss positions. Clients must
validate identity, theme and geometry before replacing their last known catalogue.
The native implementation rejects an invalid refresh atomically, retains the scoped
offline cache and permits the bundled starting chapter without a usable cache.
Its catalogue document limit is 1 MiB.

A client omitting the capability retains the three original bundled chapters;
new authored chapter ids are absent rather than masquerading as a different bundled
layout. Older clients need not implement an editor to retain their original game.
Deploy the compatible engagement implementation before this projection and release
the native consumer before publishing newly authored content beyond testing builds.
