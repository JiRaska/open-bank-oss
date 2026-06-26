# Changelog

## [0.6.1](https://github.com/JiRaska/open-bank/compare/audit-service-v0.6.0...audit-service-v0.6.1) (2026-06-25)


### Bug Fixes

* **sepa-instant,balance,audit,security-scanner,copilot,customer-edge,sca:** inject Clock via CDI (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2145](https://github.com/JiRaska/open-bank/issues/2145)) ([d680007](https://github.com/JiRaska/open-bank/commit/d68000775625cc423c95d8a27db29ff25a708f9f))

## [0.6.0](https://github.com/JiRaska/open-bank/compare/audit-service-v0.5.0...audit-service-v0.6.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank/commit/ad26ca7d58e62c8822e11f66f346926acc453058))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/audit-service-v0.4.0...audit-service-v0.5.0) (2026-06-15)


### Features

* **security:** customer payment non-repudiation — SCA settlement gate, identity threading, audit hash chain (ADR-0086) ([#900](https://github.com/JiRaska/open-bank/issues/900)) ([fcc1e52](https://github.com/JiRaska/open-bank/commit/fcc1e52b247b0eb61b9ee8d5332f110984a6fb33))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/audit-service-v0.3.0...audit-service-v0.4.0) (2026-06-12)


### Features

* **audit:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#795](https://github.com/JiRaska/open-bank/issues/795)) ([1d2cd18](https://github.com/JiRaska/open-bank/commit/1d2cd18c4dfd3beb85df2e0731ef12e0f2895944))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/audit-service-v0.2.0...audit-service-v0.3.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/audit-service-v0.1.0...audit-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **admin-ui:** correct security-scanner specId in API docs page ([fe954e5](https://github.com/JiRaska/open-bank/commit/fe954e51e799b399c8ebba50eeed6c784818864f))
* **audit:** align Kafka topic names + add missing KafkaTopic manifests ([#380](https://github.com/JiRaska/open-bank/issues/380)) ([fe954e5](https://github.com/JiRaska/open-bank/commit/fe954e51e799b399c8ebba50eeed6c784818864f))
* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
