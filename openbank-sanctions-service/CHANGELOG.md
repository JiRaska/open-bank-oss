# Changelog

## [0.5.1](https://github.com/JiRaska/open-bank/compare/sanctions-service-v0.5.0...sanctions-service-v0.5.1) (2026-06-28)


### Bug Fixes

* **sanctions:** enable outbox dispatch so screening events publish ([#2315](https://github.com/JiRaska/open-bank/issues/2315)) ([fcfd3f9](https://github.com/JiRaska/open-bank/commit/fcfd3f949a042db32b6fe7f40c074d295e64db03))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/sanctions-service-v0.4.0...sanctions-service-v0.5.0) (2026-06-27)


### Features

* **sanctions,aml,psd2,card-issuance:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2118](https://github.com/JiRaska/open-bank/issues/2118)) ([bbd0da0](https://github.com/JiRaska/open-bank/commit/bbd0da0dfd269d3c5ac5af8d5bac9d754c48a2d0))
* **sanctions:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2125](https://github.com/JiRaska/open-bank/issues/2125)) ([61da7c2](https://github.com/JiRaska/open-bank/commit/61da7c24c384a4ec70674f95ba95f401a4817c18))


### Bug Fixes

* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))
* **sanctions:** raise HIT_THRESHOLD 0.55→0.85, POTENTIAL_HIT 0.35→0.65 ([#2226](https://github.com/JiRaska/open-bank/issues/2226)) ([990a311](https://github.com/JiRaska/open-bank/commit/990a3112030f28d22f7b00d1a4447c289795f576))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/sanctions-service-v0.3.0...sanctions-service-v0.4.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank/commit/ad26ca7d58e62c8822e11f66f346926acc453058))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))


### Bug Fixes

* **sanctions:** align openapi info.version major to API v1 (ADR-0048) ([#1399](https://github.com/JiRaska/open-bank/issues/1399)) ([bfb0048](https://github.com/JiRaska/open-bank/commit/bfb0048c6931fa7c85c671034432387b9a1d9e1b))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/sanctions-service-v0.2.0...sanctions-service-v0.3.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **sanctions:** outbox backlog gauge + countProcessable override (ADR-0077/0079) ([#820](https://github.com/JiRaska/open-bank/issues/820)) ([88198cb](https://github.com/JiRaska/open-bank/commit/88198cb0e56c0b60bd0c0e90ff5336bc59fcf54b))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/sanctions-service-v0.1.0...sanctions-service-v0.2.0) (2026-06-08)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **admin-ui:** quality dashboard — Pact contract tests, pitest mutation, composite score (ADR-0063) ([#360](https://github.com/JiRaska/open-bank/issues/360)) ([00b25bc](https://github.com/JiRaska/open-bank/commit/00b25bcc934fea8728bb4b404166cd21c273495b))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **sanctions:** list-scope selector in manual screening ([a2560dd](https://github.com/JiRaska/open-bank/commit/a2560dd2f23fecf6cbcc50c5b750b36a92e1ac2a))
* **sanctions:** real entry storage + pg_trgm screening + import service ([dbe8bc1](https://github.com/JiRaska/open-bank/commit/dbe8bc1f7d2203ba699761f1803406088259bb71))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **sanctions:** add PEP mock data with diacritic normalization ([429e1ed](https://github.com/JiRaska/open-bank/commit/429e1ed4f3754daa438fe9d7116880d8c58c2f75))
* **sanctions:** add PEP_GLOBAL to SanctionsListType enum ([5c7ecc5](https://github.com/JiRaska/open-bank/commit/5c7ecc5a68b4bc51ac7b661cf6f718bed4f864d1))
* **sanctions:** SAX streaming OFAC + OpenSanctions CSV import for EU/UN/HM/PEP ([#479](https://github.com/JiRaska/open-bank/issues/479)) ([1fb09de](https://github.com/JiRaska/open-bank/commit/1fb09dea5df07437fb6c1febd8191d6f3ca4e5cb))
* **sanctions:** use word_similarity for pg_trgm search ([eeb13c0](https://github.com/JiRaska/open-bank/commit/eeb13c035cdd2bc8d2418c3a5618407eab354066))


### Security

* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank/issues/342)) ([e368296](https://github.com/JiRaska/open-bank/commit/e3682965a4f7df3b7328e8a741e4809604706390))
