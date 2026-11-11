

`OutboxTableShape.sentAtColumn` (default `sent_at`) names the delivery-time column for a table that predates the fleet shape; incentive's `incentive_outbox` uses `published_at` (#11902).

`TopicProducers` attributes the restricted `openbank.party.audit` topic to
party-service when a consumed audit event lacks its own source attribution.
