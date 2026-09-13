# Notification service threat model

## Scope

This lightweight STRIDE review covers customer notification ingestion, persistence and delivery,
including delegated-access lifecycle notifications consumed from `openbank.delegation.events`.
Notification-service does not authorize delegated access: the product service remains the final
authority and the app must re-fetch the authenticated grant after a notification tap.

## Trust boundaries and controls

| Threat | Control |
|---|---|
| A producer injects an arbitrary mobile URL | `MobileDeepLink` admits only closed bank routes. Delegation detail routes require a canonical UUID and reject query strings, fragments, traversal and non-bank schemes. |
| A lock-screen notification discloses customer or account data | Push data contains only template id, notification id, the allow-listed app route and an opaque correlation reference. Lifecycle copy carries only the resource type; no party name, balance, account number or capability set is placed on the wire. |
| A forged or stale notification becomes authorization | A deep link is navigation metadata only. The destination must authenticate the party and load the grant from customer-edge; notification content never grants access or supplies an authority decision. |
| A lifecycle change is invisible to one affected party | Offered, activated, declined, revoked and renounced transitions notify the party that did not initiate the action. Bank suspension/reinstatement and expiry notify both parties because authority changes independently of either customer. Security-category preferences cannot mute these messages. |
| An unknown future event is routed to the wrong customer | Event types use a closed reviewed recipient map. Unknown types are acknowledged without notification until recipient semantics are explicitly added and tested. Malformed identifiers are rejected before dispatch. |
| Event redelivery creates duplicate messages | Delivery remains at-least-once and duplicates are possible. `aggregateId` is retained as correlation evidence; the app must render notification state idempotently. A durable producer-event deduplication key is a residual improvement and must not be simulated with an in-memory cache. |

## Rollout and rollback

The change is additive: deploy notification-service after the delegation event contract is present.
No producer rollout or database migration is required. Rollback restores the previous consumer and
stops the three newly handled event types; it does not alter grants or authorization state. Before
rollback, confirm the consumer group has no lag so an old image cannot silently acknowledge queued
suspend, reinstate or renounce events without notifying customers.

## Residual risks

- The customer mobile application lives in a separate repository. Its `openbank://delegations/{id}`
  route must authenticate, handle a missing/revoked grant without an existence oracle, and show a
  safe generic state until that client contract is available and verified end to end.
- At-least-once delivery may create duplicate notification rows. This is visible but not an
  authorization or money-movement defect; durable deduplication remains preferable for UX quality.
