<!-- SPDX-License-Identifier: Apache-2.0 -->
# SCA lifecycle conflicts

Consumption succeeds only once for a completed, unexpired challenge whose party and dynamic-linking
fields match the requested operation. The exact expiry instant is outside its validity period.

On a concurrent lifecycle conflict, read the current challenge before deciding whether to retry.
A consumed challenge must never be reset or reused. A lost HTTP response can follow a committed
consumption; reconcile the downstream operation through its own idempotent identity before creating
another approval. A new approval is a new customer decision, not a retry token for the previous one.

The first signature-verified device decision owns the challenge's Redis decision key until expiry.
A competing decision receives a conflict and cannot shorten its TTL or replace DENIED with APPROVED.
This transient store is not durable evidentiary retention.

Deploy the additive version migration first and drain old lifecycle writers before relying on the
new concurrency guard. Retain the version column on binary rollback. Returning to old writers while
challenges remain actionable restores replay and stale-write risks; expire/reconcile those challenges
and verify the deployment's authorization and notification paths before reopening traffic.
