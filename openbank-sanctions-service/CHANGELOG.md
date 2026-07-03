# Changelog

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/sanctions-service-v0.6.1...sanctions-service-v0.6.2) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/sanctions-service-v0.6.0...sanctions-service-v0.6.1) (2026-06-30)


### Security

* **sanctions:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2761](https://github.com/JiRaska/open-bank-oss/issues/2761)) ([9e7ee7b](https://github.com/JiRaska/open-bank-oss/commit/9e7ee7b2b79182019aa005bc2c7df3aa2a82fcd2))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/sanctions-service-v0.5.1...sanctions-service-v0.6.0) (2026-06-29)


### Features

* **sanctions:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2125](https://github.com/JiRaska/open-bank-oss/issues/2125)) ([9613742](https://github.com/JiRaska/open-bank-oss/commit/96137420563a0cfb732b1874eab07de65b7bc7cc))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([ce39af7](https://github.com/JiRaska/open-bank-oss/commit/ce39af7c5b02ff3fef226f34e78c7e726b149ebd))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
* **sanctions:** enable outbox dispatch so screening events publish ([#2315](https://github.com/JiRaska/open-bank-oss/issues/2315)) ([2b81792](https://github.com/JiRaska/open-bank-oss/commit/2b8179232fccc2ef403ff5001f7d417f6931f8d1))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/sanctions-service-v0.5.0...sanctions-service-v0.5.1) (2026-06-28)


### Bug Fixes

* **sanctions:** enable outbox dispatch so screening events publish ([#2315](https://github.com/JiRaska/open-bank-oss/issues/2315)) ([fcfd3f9](https://github.com/JiRaska/open-bank-oss/commit/fcfd3f949a042db32b6fe7f40c074d295e64db03))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/sanctions-service-v0.4.0...sanctions-service-v0.5.0) (2026-06-27)


### Features

* **sanctions,aml,psd2,card-issuance:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2118](https://github.com/JiRaska/open-bank-oss/issues/2118)) ([bbd0da0](https://github.com/JiRaska/open-bank-oss/commit/bbd0da0dfd269d3c5ac5af8d5bac9d754c48a2d0))
* **sanctions:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2125](https://github.com/JiRaska/open-bank-oss/issues/2125)) ([61da7c2](https://github.com/JiRaska/open-bank-oss/commit/61da7c24c384a4ec70674f95ba95f401a4817c18))


### Bug Fixes

* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank-oss/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))
* **sanctions:** raise HIT_THRESHOLD 0.55→0.85, POTENTIAL_HIT 0.35→0.65 ([#2226](https://github.com/JiRaska/open-bank-oss/issues/2226)) ([990a311](https://github.com/JiRaska/open-bank-oss/commit/990a3112030f28d22f7b00d1a4447c289795f576))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/sanctions-service-v0.3.0...sanctions-service-v0.4.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank-oss/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank-oss/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))


### Bug Fixes

* **sanctions:** align openapi info.version major to API v1 (ADR-0048) ([#1399](https://github.com/JiRaska/open-bank-oss/issues/1399)) ([bfb0048](https://github.com/JiRaska/open-bank-oss/commit/bfb0048c6931fa7c85c671034432387b9a1d9e1b))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/sanctions-service-v0.2.0...sanctions-service-v0.3.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **sanctions:** outbox backlog gauge + countProcessable override (ADR-0077/0079) ([#820](https://github.com/JiRaska/open-bank-oss/issues/820)) ([88198cb](https://github.com/JiRaska/open-bank-oss/commit/88198cb0e56c0b60bd0c0e90ff5336bc59fcf54b))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/sanctions-service-v0.1.0...sanctions-service-v0.2.0) (2026-06-08)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **admin-ui:** quality dashboard — Pact contract tests, pitest mutation, composite score (ADR-0063) ([#360](https://github.com/JiRaska/open-bank-oss/issues/360)) ([00b25bc](https://github.com/JiRaska/open-bank-oss/commit/00b25bcc934fea8728bb4b404166cd21c273495b))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **sanctions:** list-scope selector in manual screening ([a2560dd](https://github.com/JiRaska/open-bank-oss/commit/a2560dd2f23fecf6cbcc50c5b750b36a92e1ac2a))
* **sanctions:** real entry storage + pg_trgm screening + import service ([dbe8bc1](https://github.com/JiRaska/open-bank-oss/commit/dbe8bc1f7d2203ba699761f1803406088259bb71))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **sanctions:** add PEP mock data with diacritic normalization ([429e1ed](https://github.com/JiRaska/open-bank-oss/commit/429e1ed4f3754daa438fe9d7116880d8c58c2f75))
* **sanctions:** add PEP_GLOBAL to SanctionsListType enum ([5c7ecc5](https://github.com/JiRaska/open-bank-oss/commit/5c7ecc5a68b4bc51ac7b661cf6f718bed4f864d1))
* **sanctions:** SAX streaming OFAC + OpenSanctions CSV import for EU/UN/HM/PEP ([#479](https://github.com/JiRaska/open-bank-oss/issues/479)) ([1fb09de](https://github.com/JiRaska/open-bank-oss/commit/1fb09dea5df07437fb6c1febd8191d6f3ca4e5cb))
* **sanctions:** use word_similarity for pg_trgm search ([eeb13c0](https://github.com/JiRaska/open-bank-oss/commit/eeb13c035cdd2bc8d2418c3a5618407eab354066))


### Security

* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank-oss/issues/342)) ([e368296](https://github.com/JiRaska/open-bank-oss/commit/e3682965a4f7df3b7328e8a741e4809604706390))
