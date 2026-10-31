# Approval maker provenance — Clearing

Four-eyes approval responses from this service include `makerActorKind`, captured when the pending request is created and kept with its approval record. The value can be `HUMAN`, `AI_AGENT`, `SERVICE_ACCOUNT`, `CUSTOMER_PARTY`, or `UNKNOWN`; older or unclassified records may be `UNKNOWN`.

This field helps an operator understand who initiated a request. It is not an authorization decision: the server still enforces the distinct maker and checker identities. The Admin inbox must show an unknown kind honestly instead of guessing from the maker's display name or ID.
