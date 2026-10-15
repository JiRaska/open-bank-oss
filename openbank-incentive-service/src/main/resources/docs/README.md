# OpenBank Incentive Service

Governed fixed-benefit offers and opaque promo-code reservations.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Customer and offer callers

Customer reservation and release endpoints accept the configured customer-edge service account only when the verified JWT client id, subject and Keycloak service-account username match. The offer read admits the campaign service account under the same binding. An operator role admits a verified interactive user session, but cannot make an unrelated machine token an offer reader.
