# Changelog

## [0.6.5](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.6.4...lending-service-v0.6.5) (2026-07-07)


### Security

* **lending:** enforce OPA authorization on lending endpoints (ADR-0034 Phase 5) ([#392](https://github.com/JiRaska/open-bank-oss/issues/392)) ([a3ab024](https://github.com/JiRaska/open-bank-oss/commit/a3ab02462e32f95b63b1427032f0b9bcb20af056))

## [0.6.4](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.6.3...lending-service-v0.6.4) (2026-06-30)


### Security

* **lending:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2a) ([#2746](https://github.com/JiRaska/open-bank-oss/issues/2746)) ([b96cc8a](https://github.com/JiRaska/open-bank-oss/commit/b96cc8a139f786c9e426122934d4a68293565c03))

## [0.6.3](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.6.2...lending-service-v0.6.3) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.6.1...lending-service-v0.6.2) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.6.0...lending-service-v0.6.1) (2026-06-25)


### Bug Fixes

* **interest,dispute,lending:** complete Clock injection (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2136](https://github.com/JiRaska/open-bank-oss/issues/2136)) ([41a2921](https://github.com/JiRaska/open-bank-oss/commit/41a2921b9b89cc06025cc71a4b428cb019fb499f))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.5.0...lending-service-v0.6.0) (2026-06-25)


### Features

* **lending:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2086](https://github.com/JiRaska/open-bank-oss/issues/2086)) ([dc118ec](https://github.com/JiRaska/open-bank-oss/commit/dc118ecefe2f9bd36d86cf2461c0f9715b128c0d))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.4.0...lending-service-v0.5.0) (2026-06-25)


### Features

* **lending:** add @Authorize resource-gate on REST endpoints (ADR-0034 D3) ([#1278](https://github.com/JiRaska/open-bank-oss/issues/1278)) ([cfafdb4](https://github.com/JiRaska/open-bank-oss/commit/cfafdb4cc9b8c2b887cf0ee7dfb4b5b748e885bb))


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank-oss/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **lending:** align openapi info.version major to API v1 (ADR-0048) ([#1397](https://github.com/JiRaska/open-bank-oss/issues/1397)) ([9713355](https://github.com/JiRaska/open-bank-oss/commit/97133558d854c3a79ebb523b264359b82151beb4))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.3.0...lending-service-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **lending:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#804](https://github.com/JiRaska/open-bank-oss/issues/804)) ([df39f9a](https://github.com/JiRaska/open-bank-oss/commit/df39f9a15db7a5aea4b0f8f8dc7642c98a22e6b1))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.2.0...lending-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.1.0...lending-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))


### Security

* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank-oss/issues/342)) ([e368296](https://github.com/JiRaska/open-bank-oss/commit/e3682965a4f7df3b7328e8a741e4809604706390))
