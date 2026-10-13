# OpenBank Delegation Service API

Customer-to-party delegated access (ADR-0232): granular, SCA-bound grants over products (accounts, savings goals, cards) and single objects (payment, statement, document).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Consumer contract verification

Consumer Pact tests exercise delegation-service's outbound REST interactions, including calls to SCA, PID, account, and card-issuance services. Their expected paths are literal values kept separate from the client annotations used to form requests, so an incorrect client route can fail the contract test. Run them with `./gradlew :openbank-delegation-service:consumerPactTest`.
