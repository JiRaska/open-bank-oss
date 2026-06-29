# Changelog

## [0.8.0](https://github.com/JiRaska/open-bank/compare/consent-service-v0.7.1...consent-service-v0.8.0) (2026-06-29)


### Features

* **consent:** hourly expiration sweep + ADR-0126 unified consent lifecycle ([#2522](https://github.com/JiRaska/open-bank/issues/2522)) ([df1c514](https://github.com/JiRaska/open-bank/commit/df1c5145c403a8f9a1e11641642d8e10b28c1ea8))

## [0.7.1](https://github.com/JiRaska/open-bank/compare/consent-service-v0.7.0...consent-service-v0.7.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.7.0](https://github.com/JiRaska/open-bank/compare/consent-service-v0.6.0...consent-service-v0.7.0) (2026-06-25)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))

## [0.6.0](https://github.com/JiRaska/open-bank/compare/consent-service-v0.5.1...consent-service-v0.6.0) (2026-06-25)


### Features

* **consent:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2078](https://github.com/JiRaska/open-bank/issues/2078)) ([6b96506](https://github.com/JiRaska/open-bank/commit/6b9650610b1115bdbda3e40fac8fcd9e4fa21175)), closes [#1612](https://github.com/JiRaska/open-bank/issues/1612)

## [0.5.1](https://github.com/JiRaska/open-bank/compare/consent-service-v0.5.0...consent-service-v0.5.1) (2026-06-25)


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank/commit/4ea273195d038704acc6341f684c0f1cb039ce82))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/consent-service-v0.4.0...consent-service-v0.5.0) (2026-06-15)


### Features

* **consent:** add TELEMETRY_RUM consent scope (ADR-0088 D4b, RUM gateway O4) ([#1052](https://github.com/JiRaska/open-bank/issues/1052)) ([1560978](https://github.com/JiRaska/open-bank/commit/1560978437764dca1ff2b43c1be3bd4c4f733b96))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/consent-service-v0.3.0...consent-service-v0.4.0) (2026-06-12)


### Features

* **consent:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#797](https://github.com/JiRaska/open-bank/issues/797)) ([7be2671](https://github.com/JiRaska/open-bank/commit/7be26713a35f5d74bd2468ccb9a4c3a82e44329a))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/consent-service-v0.2.0...consent-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/consent-service-v0.1.1...consent-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
