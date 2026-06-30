# Changelog

## [0.8.1](https://github.com/JiRaska/open-bank/compare/audit-service-v0.8.0...audit-service-v0.8.1) (2026-06-30)


### Security

* **audit:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank/issues/2665) Tier 2c) ([#2749](https://github.com/JiRaska/open-bank/issues/2749)) ([ddba9c1](https://github.com/JiRaska/open-bank/commit/ddba9c17ff22624b17e4b28e60837b29352ad384))

## [0.8.0](https://github.com/JiRaska/open-bank/compare/audit-service-v0.7.0...audit-service-v0.8.0) (2026-06-29)


### Features

* **audit:** externally-signed tamper-evidence anchors over the audit hash chain (ADR-0031 D5) ([#2383](https://github.com/JiRaska/open-bank/issues/2383)) ([464892a](https://github.com/JiRaska/open-bank/commit/464892ae72ca0468cd0173d73e15209627918ed0))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank/issues/2342)

## [0.7.0](https://github.com/JiRaska/open-bank/compare/audit-service-v0.6.2...audit-service-v0.7.0) (2026-06-28)


### Features

* **audit:** externally-signed tamper-evidence anchors over the audit hash chain (ADR-0031 D5) ([#2383](https://github.com/JiRaska/open-bank/issues/2383)) ([29a4427](https://github.com/JiRaska/open-bank/commit/29a4427b3694ffc2cfb3e5c325ef62fa3e522b0c))

## [0.6.2](https://github.com/JiRaska/open-bank/compare/audit-service-v0.6.1...audit-service-v0.6.2) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))

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
