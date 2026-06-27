# Changelog

## [0.6.1](https://github.com/JiRaska/open-bank/compare/clearing-service-v0.6.0...clearing-service-v0.6.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.6.0](https://github.com/JiRaska/open-bank/compare/clearing-service-v0.5.0...clearing-service-v0.6.0) (2026-06-25)


### Features

* **clearing:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2094](https://github.com/JiRaska/open-bank/issues/2094)) ([a238043](https://github.com/JiRaska/open-bank/commit/a238043dda4e48738cab8f21a72aff770b08524b))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/clearing-service-v0.4.2...clearing-service-v0.5.0) (2026-06-25)


### Features

* **clearing:** inject Clock into application layer (ADR-0100) ([#2061](https://github.com/JiRaska/open-bank/issues/2061)) ([91693f1](https://github.com/JiRaska/open-bank/commit/91693f10c6b40c5177a1265a6f194164070c5f94))
* **clearing:** remove OffsetDateTime.now() defaults from domain (ADR-0100) ([#2076](https://github.com/JiRaska/open-bank/issues/2076)) ([74ce0da](https://github.com/JiRaska/open-bank/commit/74ce0dab7b2fb85019de33233a6090945e2f4200)), closes [#1612](https://github.com/JiRaska/open-bank/issues/1612)

## [0.4.2](https://github.com/JiRaska/open-bank/compare/clearing-service-v0.4.1...clearing-service-v0.4.2) (2026-06-25)


### Bug Fixes

* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank/commit/92dc2d636d857b526c8276e2647de440c540577b))

## [0.4.1](https://github.com/JiRaska/open-bank/compare/clearing-service-v0.4.0...clearing-service-v0.4.1) (2026-06-15)


### Bug Fixes

* **clearing:** seed sentinel batch + first ApiIT (batch aggregation + RBAC) ([#783](https://github.com/JiRaska/open-bank/issues/783)) ([38c4e6f](https://github.com/JiRaska/open-bank/commit/38c4e6f5b59379dff5281ca9bfc3ccf0ab54aeab))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/clearing-service-v0.3.0...clearing-service-v0.4.0) (2026-06-12)


### Features

* **clearing:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#796](https://github.com/JiRaska/open-bank/issues/796)) ([e3dd9d9](https://github.com/JiRaska/open-bank/commit/e3dd9d986b3b8911ca229d35640399091d883d05))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/clearing-service-v0.2.0...clearing-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/clearing-service-v0.1.1...clearing-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))


### Security

* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank/issues/342)) ([e368296](https://github.com/JiRaska/open-bank/commit/e3682965a4f7df3b7328e8a741e4809604706390))
