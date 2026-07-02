# Changelog

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.8.1...card-issuance-service-v0.8.2) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.8.0...card-issuance-service-v0.8.1) (2026-06-30)


### Security

* **card-issuance,sdd:** Kafka mTLS code-side prep — SSL defaults + RBAC pre-registration (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2765](https://github.com/JiRaska/open-bank-oss/issues/2765)) ([4ae04fd](https://github.com/JiRaska/open-bank-oss/commit/4ae04fd8bbaf771ca696732b5bea6fd72048c5c6))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.7.1...card-issuance-service-v0.8.0) (2026-06-29)


### Features

* **card-issuance:** GDPR Art.5 card PII retention expiry (ADR-0118 §5) ([#2479](https://github.com/JiRaska/open-bank-oss/issues/2479)) ([74ddd37](https://github.com/JiRaska/open-bank-oss/commit/74ddd370c6ea7f15d0711bdfa318b8aa6494f657))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **card-issuance:** handle PARTY_ERASED event to anonymise cardholder PII (GDPR Art. 17) ([#2268](https://github.com/JiRaska/open-bank-oss/issues/2268)) ([16db236](https://github.com/JiRaska/open-bank-oss/commit/16db236f009fa842c91660e8d59f6cbf061b366d))
* **card-issuance:** use &lt;= boundary in anonymizeExpiredCardPii (GDPR compliance) ([#2525](https://github.com/JiRaska/open-bank-oss/issues/2525)) ([b46e45b](https://github.com/JiRaska/open-bank-oss/commit/b46e45bd694461a908e61d63bc4f5ddf924f39b5))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([ce39af7](https://github.com/JiRaska/open-bank-oss/commit/ce39af7c5b02ff3fef226f34e78c7e726b149ebd))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.7.0...card-issuance-service-v0.7.1) (2026-06-29)


### Bug Fixes

* **card-issuance:** use &lt;= boundary in anonymizeExpiredCardPii (GDPR compliance) ([#2525](https://github.com/JiRaska/open-bank-oss/issues/2525)) ([2fe35f6](https://github.com/JiRaska/open-bank-oss/commit/2fe35f628ff46868ead5da4ea119cf314d1908d1))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.6.2...card-issuance-service-v0.7.0) (2026-06-29)


### Features

* **card-issuance:** GDPR Art.5 card PII retention expiry (ADR-0118 §5) ([#2479](https://github.com/JiRaska/open-bank-oss/issues/2479)) ([b87f815](https://github.com/JiRaska/open-bank-oss/commit/b87f8156d8ef2e14458b82cb20b03bc97cb714bf))

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.6.1...card-issuance-service-v0.6.2) (2026-06-28)


### Bug Fixes

* **card-issuance:** handle PARTY_ERASED event to anonymise cardholder PII (GDPR Art. 17) ([#2268](https://github.com/JiRaska/open-bank-oss/issues/2268)) ([9f098fd](https://github.com/JiRaska/open-bank-oss/commit/9f098fd07a3eed8c8a3e803d15eef4b3cb975afe))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.6.0...card-issuance-service-v0.6.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank-oss/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.5.0...card-issuance-service-v0.6.0) (2026-06-25)


### Features

* **sanctions,aml,psd2,card-issuance:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2118](https://github.com/JiRaska/open-bank-oss/issues/2118)) ([bbd0da0](https://github.com/JiRaska/open-bank-oss/commit/bbd0da0dfd269d3c5ac5af8d5bac9d754c48a2d0))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.4.0...card-issuance-service-v0.5.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.3.0...card-issuance-service-v0.4.0) (2026-06-12)


### Features

* **card-issuance:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#800](https://github.com/JiRaska/open-bank-oss/issues/800)) ([63bd246](https://github.com/JiRaska/open-bank-oss/commit/63bd2460f3c0f158901d548178e81c501f0f8d76))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.2.0...card-issuance-service-v0.3.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.1.1...card-issuance-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
