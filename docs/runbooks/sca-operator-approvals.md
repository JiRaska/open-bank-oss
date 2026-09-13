# SCA operator approvals

SCA exposes `GET /api/v1/sca/approvals` and `PATCH /api/v1/sca/approvals/{id}` to operators
and administrators. The list returns pending entries, oldest first, with a bounded `limit`.
`GET /api/v1/sca/approvals/{id}` reads one record even after a decision or authorization claim,
until its TTL expires. A missing record does not prove the associated operation never happened.
The checker sends `{"approve": true}` or `{"approve": false}`. The maker cannot decide
their own request; the same identity representation is used in both checks.

The admin approval inbox includes SCA and links to `/approvals/sca/{id}`. The workbench
shows the maker and exact bound target. Approval requires an explicit target review; for
enrollment the checker must also supply the matching fingerprint from the verified request.
A separate confirmation records the decision. If its response is uncertain, reload the
record before another decision. The workbench never retries the business operation for the maker.

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
it does not turn on the production manifest. Before enabling it, exercise the admin review
flow with the real identity provider and Redis permissions, and run an enforced
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
The admin component and BFF tests cover review, bearer relay, redacted errors, duplicate clicks
and uncertain responses. Playwright checks the signed-in mobile review and confirmation using
a mocked service response. These checks do not prove production identity wiring or live rollout.
