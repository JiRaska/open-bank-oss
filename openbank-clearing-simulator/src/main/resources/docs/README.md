# Clearing Simulator API

NON-PRODUCTION scheme/clearing counterparty (ADR-0104 D2).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Bank-own credit transfers (pacs.009)

`POST /api/v1/clearing/fi-credit-transfers/{route}` accepts a bank-own financial-institution credit
transfer on the CERTIS, TARGET2 or SWIFT route. A repeated UETR is deduplicated and answers the
original acceptance. A transfer accepted for deferred settlement is settled later by the simulator.
`GET /api/v1/clearing/fi-credit-transfers/status/{uetr}` answers the current status; an unknown UETR
returns a typed not-found body that names the original message, so a caller can tell "never
received" from "not settled yet".

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
