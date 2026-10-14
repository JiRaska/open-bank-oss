# OpenBank Delegation Service API

Customer-to-party delegated access (ADR-0232): granular, SCA-bound grants over products (accounts, savings goals, cards) and single objects (payment, statement, document).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Customer recertification caller

Recertification confirmation accepts only the customer-edge workload acting for the matching grantor party. The workload must present its own verified service-account JWT (`azp`, subject and matching Keycloak username); a matching principal name or `ROLE_API` alone is insufficient. The grantor equality check also applies to an already recorded idempotent confirmation.
