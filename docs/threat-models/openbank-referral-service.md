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

The sandbox deployment adds three explicit trust boundaries. Requests enter with Keycloak OIDC
tokens and the client secret is projected from Vault; a missing projection prevents the container
from starting. Referral state is isolated in its own CloudNativePG database, with IAM-bound backup
access and no ledger credentials. Generated NetworkPolicies admit application traffic only from the
admin UI namespace, health/metrics traffic from the platform observers, and same-namespace database
traffic. The initial GitOps declaration keeps the workload at zero replicas until the image is
signed and attested; enabling a live replica is a separate reviewed change with rollout evidence.
