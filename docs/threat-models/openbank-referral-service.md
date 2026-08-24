# Referral service threat model (MGM first slice)

This service owns referral attribution and fixed-reward requests, not money. It stores only a
SHA-256 invite-token hash, opaque party identifiers, and an auditable lifecycle. Publication is
four-eyes; invite issuance, attribution and qualification are idempotent and protected by unique
constraints and conditional state transitions. Self-referrals, expired invites, duplicate events,
replay and concurrent attribution are rejected. The ledger is an external boundary: this slice
emits `REWARD_REQUESTED` and accepts `ACCEPTED|REJECTED|REVERSED` outcomes, but has no ledger
credentials and cannot post monetary entries. A separate money-path ADR and threat model are
required before any monetary posting is enabled. Audit data follows the 13-month retention policy;
raw invite tokens are never logged or persisted.
