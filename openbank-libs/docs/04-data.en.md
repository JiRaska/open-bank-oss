

`OutboxTableShape.sentAtColumn` (default `sent_at`) names the delivery-time column for a table that predates the fleet shape; incentive's `incentive_outbox` uses `published_at` (#11902).

## Outbox claim ownership

A service can opt into claim fencing with `AbstractClaimFencedOutboxRepository` and
`PanacheClaimFencedOutboxEntityV2`. The service adds a nullable `claim_token UUID` column
alongside the V2 retry columns. The claim assigns a UUID, and the event ID and token must both
match before an acknowledgement changes the row. The dispatcher counts only committed
acknowledgements. Consumers still deduplicate by event ID because a delayed sender can publish
after another worker has reclaimed its row.

Deploy a schema-compatible relay before adding the column and quiesce any relay that uses
untyped acknowledgements before activating the fenced repository.
