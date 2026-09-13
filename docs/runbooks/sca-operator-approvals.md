# SCA operator approvals

SCA exposes `GET /api/v1/sca/approvals` and `PATCH /api/v1/sca/approvals/{id}` to operators
and administrators. The list returns pending entries, oldest first, with a bounded `limit`.
The checker sends `{"approve": true}` or `{"approve": false}`. The maker cannot decide
their own request; the same identity representation is used in both checks.

When `authz.enforce` and `authz.four-eyes.enforce` are enabled and OPA requires approval,
the original operation returns 202 with `approvalId` before its business write. After a
different checker approves, the maker retries the identical operation with `X-Approval-Id`.
Revocation binds `partyId@deviceId`. Enrollment binds the party and the SHA-256 fingerprint
of length-prefixed UTF-8 credential id, public key and algorithm. The checker must compare
the target and fingerprint with the intended request through a trusted review channel.
Raw key material is not stored in the approval resource. Challenge consumption binds its
challenge id; the service still validates party and immutable dynamic-linking fields.

An altered target, request, maker, or a reused approval creates a new pending approval.
Existing identity-specific policy exemptions keep automated customer ceremonies working.
The service has no blanket exemption for operator identities.

## Deployment

`AUTHZ_FOUR_EYES_ENFORCE` defaults to false. This change prepares the API and binding;
it does not turn on the production manifest. Before enabling it, complete the admin review
flow, exercise the real identity provider and Redis permissions, and run an enforced
maker/checker drill. Review both successful service-account ceremonies and refused human
requests. All writers must have the atomic approval-store implementation; see
[the shared upgrade precautions](atomic-four-eyes-approvals.md).

Pending approvals made with the earlier party-only binding cannot authorize the new
resource format. Obtain fresh approvals after upgrading. Do not roll back to the older
party-only binding while approval traffic is active. Pause approval traffic during a rollback
and drain approvals; retain credential revocation and decision evidence migrations.

## Evidence and failure handling

An approval marked EXECUTED means its one-use authorization was claimed, not that the
database operation committed. If a response is lost, inspect the device or challenge and
its outbox evidence before obtaining a fresh approval. Never replay an authorization by
editing Redis. The TTL-bounded approval queue is not a durable audit of the checker;
durable authorization evidence is a separate production requirement.

`ScaFourEyesFlowIT` uses real HTTP, PostgreSQL, Redis and the generated deployment OPA
bundle. Its policy image and bundle are declared Gradle inputs. It proves local enforcement,
ownership parsing, maker/checker separation, field binding and existing service exemptions.
It does not prove production identity wiring, live rollout or administrative UI readiness.
