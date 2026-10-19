# OpenBank Delegation Service API

Customer-to-party delegated access (ADR-0232): granular, SCA-bound grants over products (accounts, savings goals, cards) and single objects (payment, statement, document).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Expired approval signatures / Podpisy po expiraci

Signing an expired approval request returns HTTP 409 with code `EXPIRED`, including after the expiry sweep has stored its `EXPIRED` status. An attempt refused by the initial expired-request guard does not consume the co-signer's SCA challenge or add a signature or event. SCA consumption before the later locked-state recheck is unchanged; this is not an atomic guarantee for concurrent state changes. Other closed states, including `REJECTED`, continue to return `NOT_PENDING` when signing is attempted after their deadline.

Podpis expirovaného požadavku na schválení vrací HTTP 409 s kódem `EXPIRED`, a to i poté, co úloha expirace uložila stav `EXPIRED`. Pokus odmítnutý úvodní kontrolou již expirovaného požadavku nespotřebuje SCA výzvu spolupodepisujícího ani nepřidá podpis nebo událost. Spotřeba SCA před následnou kontrolou zamčeného stavu se nemění; nejde o atomickou záruku pro souběžné změny stavu. Ostatní uzavřené stavy, včetně `REJECTED`, při pokusu o podpis po uplynutí lhůty nadále vracejí `NOT_PENDING`.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Customer recertification caller

Recertification confirmation accepts only the customer-edge workload acting for the matching grantor party. The workload must present its own verified service-account JWT (`azp`, subject and matching Keycloak username); a matching principal name or `ROLE_API` alone is insufficient. The grantor equality check also applies to an already recorded idempotent confirmation.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
