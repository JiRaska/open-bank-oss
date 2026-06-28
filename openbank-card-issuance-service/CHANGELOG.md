# Changelog

## [0.6.2](https://github.com/JiRaska/open-bank/compare/card-issuance-service-v0.6.1...card-issuance-service-v0.6.2) (2026-06-28)


### Bug Fixes

* **card-issuance:** handle PARTY_ERASED event to anonymise cardholder PII (GDPR Art. 17) ([#2268](https://github.com/JiRaska/open-bank/issues/2268)) ([9f098fd](https://github.com/JiRaska/open-bank/commit/9f098fd07a3eed8c8a3e803d15eef4b3cb975afe))

## [0.6.1](https://github.com/JiRaska/open-bank/compare/card-issuance-service-v0.6.0...card-issuance-service-v0.6.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))

## [0.6.0](https://github.com/JiRaska/open-bank/compare/card-issuance-service-v0.5.0...card-issuance-service-v0.6.0) (2026-06-25)


### Features

* **sanctions,aml,psd2,card-issuance:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2118](https://github.com/JiRaska/open-bank/issues/2118)) ([bbd0da0](https://github.com/JiRaska/open-bank/commit/bbd0da0dfd269d3c5ac5af8d5bac9d754c48a2d0))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/card-issuance-service-v0.4.0...card-issuance-service-v0.5.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank/commit/ad26ca7d58e62c8822e11f66f346926acc453058))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/card-issuance-service-v0.3.0...card-issuance-service-v0.4.0) (2026-06-12)


### Features

* **card-issuance:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#800](https://github.com/JiRaska/open-bank/issues/800)) ([63bd246](https://github.com/JiRaska/open-bank/commit/63bd2460f3c0f158901d548178e81c501f0f8d76))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/card-issuance-service-v0.2.0...card-issuance-service-v0.3.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/card-issuance-service-v0.1.1...card-issuance-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
