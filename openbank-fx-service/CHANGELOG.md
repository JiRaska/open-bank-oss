# Changelog

## [0.7.1](https://github.com/JiRaska/open-bank/compare/fx-service-v0.7.0...fx-service-v0.7.1) (2026-06-28)


### Bug Fixes

* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([35c6a63](https://github.com/JiRaska/open-bank/commit/35c6a6309e10c0a56558d93de5c494a89da72ceb))

## [0.7.0](https://github.com/JiRaska/open-bank/compare/fx-service-v0.6.0...fx-service-v0.7.0) (2026-06-27)


### Features

* **fx:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2100](https://github.com/JiRaska/open-bank/issues/2100)) ([c524c09](https://github.com/JiRaska/open-bank/commit/c524c09dcedcf27c4749281fd868344ac99b7c86))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **sepa-payment,analytics,clearing-simulator,finrep,fx,customer-edge,security-scanner:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2174](https://github.com/JiRaska/open-bank/issues/2174)) ([51a872e](https://github.com/JiRaska/open-bank/commit/51a872ec0ce0b9f888226ca94ffcfb9f392174c2))

## [0.6.0](https://github.com/JiRaska/open-bank/compare/fx-service-v0.5.0...fx-service-v0.6.0) (2026-06-25)


### Features

* **fx:** ADR-0101 P4 — Temporal durable workflow for FX conversions ([#1530](https://github.com/JiRaska/open-bank/issues/1530)) ([abf148f](https://github.com/JiRaska/open-bank/commit/abf148fe85fdcef45b1a7c7fba3b6da4c42cd53b))


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **fx-service:** configure the OIDC client so service tokens are minted (ADR-0104 D3) ([#1787](https://github.com/JiRaska/open-bank/issues/1787)) ([2cb37ca](https://github.com/JiRaska/open-bank/commit/2cb37cab689e1b54651e1be8f2cb28ef87c4fc9d))
* **fx-service:** wrap Temporal activities in VertxContextSupport (issue [#1739](https://github.com/JiRaska/open-bank/issues/1739)) ([#1773](https://github.com/JiRaska/open-bank/issues/1773)) ([602137f](https://github.com/JiRaska/open-bank/commit/602137f8b165fcd59eb88c619eb8e0f819730806))
* **temporal:** remove non-existent @WorkflowImpl annotation from fx+statement ([#1538](https://github.com/JiRaska/open-bank/issues/1538)) ([0d73e0d](https://github.com/JiRaska/open-bank/commit/0d73e0d7548d2953536975b257e646de6272cff4))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/fx-service-v0.4.0...fx-service-v0.5.0) (2026-06-15)


### Features

* **fx:** GET /api/v1/fx/rates/{base}/{quote}/history endpoint ([f0ae112](https://github.com/JiRaska/open-bank/commit/f0ae1126d6a1939f542931372adb1205e6d8a7c8))


### Bug Fixes

* explicit @Column(name = "bid_rate" | "ask_rate" | "applied_rate"). ([4808dcc](https://github.com/JiRaska/open-bank/commit/4808dccc2cc8fbda025820e437f7f17b6bfb8916))
* **fx,customer-edge:** FX history prázdná + chybí ECB odchylka ([#1115](https://github.com/JiRaska/open-bank/issues/1115)) ([2a5d872](https://github.com/JiRaska/open-bank/commit/2a5d872c9e3f68be2afa6860ae7a3b363ac43908))
* **fx:** add camelCase→snake_case Hibernate naming strategy ([#1043](https://github.com/JiRaska/open-bank/issues/1043)) ([869ac4b](https://github.com/JiRaska/open-bank/commit/869ac4bac2d0676ab8987035f9d64a7624ffa84c))
* **fx:** map bid/ask/applied rate columns explicitly — GET /rates was 500 ([#1031](https://github.com/JiRaska/open-bank/issues/1031)) ([4808dcc](https://github.com/JiRaska/open-bank/commit/4808dccc2cc8fbda025820e437f7f17b6bfb8916))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/fx-service-v0.3.0...fx-service-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **fx-service:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#686](https://github.com/JiRaska/open-bank/issues/686)) ([00fa201](https://github.com/JiRaska/open-bank/commit/00fa201f9608e7e29272f05d6cce251ec5d8f54e))
* **fx:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#799](https://github.com/JiRaska/open-bank/issues/799)) ([da92804](https://github.com/JiRaska/open-bank/commit/da928049714c2bb99278b0e96db1578cf92a2aef))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/fx-service-v0.2.0...fx-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/fx-service-v0.1.0...fx-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
