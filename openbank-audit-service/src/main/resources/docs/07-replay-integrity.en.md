# Append and replay integrity

The audit append path takes a transaction-scoped PostgreSQL advisory lock before checking an event ID, reading the chain head and inserting its next link. This serializes append across pods. An at-least-once retry with the same event ID is a no-op only when payload, event type, aggregate type and ID, actor ID and source service match the stored entry. A conflicting reuse fails instead of silently accepting different evidence; it must be investigated at the producer. The audit chain is not a substitute for a producer's original event artifact.
