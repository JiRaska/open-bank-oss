# Banking Context Service API

Purpose-bound, case-assigned graph views for authorized investigations.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Verified identity on the internal policy boundary

The request adapters preserve verified bearer classification and the bound client ID when
constructing the investigator used by the second, purpose/assignment-bound OPA check. The
identity comes from `SecurityIdentity` through the shared `ServiceAccountIdentity` checks;
request parameters, display names and role presence cannot establish a verified person or
named client. Missing proof retains machine classification and no client binding.

The policy input retains the existing `type: HUMAN` value for compatibility; this type is
an attribution category, not proof of a human. `service_account` is the authoritative verified
classification for Phase 2 policy, and `client_id` carries the independent named-client proof.
Assignment, root scope, purpose checks, disclosure audit and policy decisions remain in place.

This producer change does not activate Phase 2 policy. Deploy and verify the reviewed context
image before activating #12537: an image built before this correction still sends the default
machine flag for its custom read checks even if it contains the earlier shared PEP update.
