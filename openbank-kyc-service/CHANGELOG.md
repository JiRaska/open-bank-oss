# Changelog

## [0.5.1](https://github.com/JiRaska/open-bank/compare/kyc-service-v0.5.0...kyc-service-v0.5.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/kyc-service-v0.4.1...kyc-service-v0.5.0) (2026-06-25)


### Features

* **kyc:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2133](https://github.com/JiRaska/open-bank/issues/2133)) ([2c0e68c](https://github.com/JiRaska/open-bank/commit/2c0e68cfcd1b68dd7162b31b362b295dea140307))

## [0.4.1](https://github.com/JiRaska/open-bank/compare/kyc-service-v0.4.0...kyc-service-v0.4.1) (2026-06-23)


### Bug Fixes

* **infra:** commit swift-service-db Pod Identity association for WAL backups (ADR-0104 D4) ([#1793](https://github.com/JiRaska/open-bank/issues/1793)) ([49fc6dd](https://github.com/JiRaska/open-bank/commit/49fc6ddf988952f6281b4689f8c7eee1670a03f9))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/kyc-service-v0.3.1...kyc-service-v0.4.0) (2026-06-21)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank/commit/ad26ca7d58e62c8822e11f66f346926acc453058))
* **kyc:** approve/reject with SecurityContext identity and mandatory reason (ADR-0068) ([#1292](https://github.com/JiRaska/open-bank/issues/1292)) ([12cd679](https://github.com/JiRaska/open-bank/commit/12cd67970386db722555c2d7a8488392c0fad00c))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))

## [0.3.1](https://github.com/JiRaska/open-bank/compare/kyc-service-v0.3.0...kyc-service-v0.3.1) (2026-06-15)


### Bug Fixes

* **kyc:** 409 on duplicate active case + active-status lookup + reuse log ([#972](https://github.com/JiRaska/open-bank/issues/972)) ([5846fee](https://github.com/JiRaska/open-bank/commit/5846feeea6396e9229377d4fd7dbda087afae8c1)), closes [#536](https://github.com/JiRaska/open-bank/issues/536)
* **kyc:** remove duplicate V5 Flyway migration (crashed startup) ([#1069](https://github.com/JiRaska/open-bank/issues/1069)) ([c28f690](https://github.com/JiRaska/open-bank/commit/c28f690416ba811f8a20ff1b93fb8d4a057d33d7))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/kyc-service-v0.2.0...kyc-service-v0.3.0) (2026-06-12)


### Features

* **account,party,onboarding:** sprint 2 — sanctions persistence, GDPR erasure, doc download, AbandonedCleaner fix ([#475](https://github.com/JiRaska/open-bank/issues/475)) ([05b20d7](https://github.com/JiRaska/open-bank/commit/05b20d764a6373d0ffd96ca84ab5a9a6ed54291f))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **kyc:** KYC metrics + outbox backlog gauge (ADR-0077/0079) ([#792](https://github.com/JiRaska/open-bank/issues/792)) ([1a4e119](https://github.com/JiRaska/open-bank/commit/1a4e11987aa02a1aa4d568e0fc744ce1b059b494))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/kyc-service-v0.1.1...kyc-service-v0.2.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **kyc:** auto-open a KYC case on PARTY_CREATED ([#534](https://github.com/JiRaska/open-bank/issues/534)) ([0935320](https://github.com/JiRaska/open-bank/commit/09353204d58b819a64b388c3fb4f61ce9d3c9658))
* **kyc:** sandbox straight-through auto-approve on PARTY_CREATED (ADR-0073 ph3, re-do) ([#541](https://github.com/JiRaska/open-bank/issues/541)) ([f8c5f1f](https://github.com/JiRaska/open-bank/commit/f8c5f1fee24c982fedf4e39d12f526ec399761aa))
* **party,kyc:** add ?status= filter to list endpoints for onboarding cockpit ([#420](https://github.com/JiRaska/open-bank/issues/420)) ([3c3cee8](https://github.com/JiRaska/open-bank/commit/3c3cee8cec1ca4896f9e30a1da5ff1c180e2a05f))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
