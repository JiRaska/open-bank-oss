# Changelog

## [0.6.1](https://github.com/JiRaska/open-bank/compare/aml-service-v0.6.0...aml-service-v0.6.1) (2026-06-29)


### Bug Fixes

* **aml:** GDPR Art. 17 — anonymise PII on PARTY_ERASED ([#2448](https://github.com/JiRaska/open-bank/issues/2448)) ([5a90f5f](https://github.com/JiRaska/open-bank/commit/5a90f5f1fb535ea214dcf523ff6c082e641cd097))

## [0.6.0](https://github.com/JiRaska/open-bank/compare/aml-service-v0.5.1...aml-service-v0.6.0) (2026-06-27)


### Features

* **aml:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2124](https://github.com/JiRaska/open-bank/issues/2124)) ([b292145](https://github.com/JiRaska/open-bank/commit/b29214592a622fb9f003685e5c9f7b8d984d2f41))
* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))
* **sanctions,aml,psd2,card-issuance:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2118](https://github.com/JiRaska/open-bank/issues/2118)) ([bbd0da0](https://github.com/JiRaska/open-bank/commit/bbd0da0dfd269d3c5ac5af8d5bac9d754c48a2d0))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.5.1](https://github.com/JiRaska/open-bank/compare/aml-service-v0.5.0...aml-service-v0.5.1) (2026-06-25)


### Bug Fixes

* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank/commit/92dc2d636d857b526c8276e2647de440c540577b))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/aml-service-v0.4.0...aml-service-v0.5.0) (2026-06-21)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank/commit/ad26ca7d58e62c8822e11f66f346926acc453058))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/aml-service-v0.3.0...aml-service-v0.4.0) (2026-06-12)


### Features

* **aml:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#801](https://github.com/JiRaska/open-bank/issues/801)) ([f0dd2ab](https://github.com/JiRaska/open-bank/commit/f0dd2ab2f5e9f75606ae46f0421fe8c07e1f57b9))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/aml-service-v0.2.0...aml-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **aml:** deploy aml-service + onboarding auto-screen (ADR-0073 phase 3) ([#540](https://github.com/JiRaska/open-bank/issues/540)) ([cb5c472](https://github.com/JiRaska/open-bank/commit/cb5c4727b055f31e951ad6954711ddda5c7f4766))


### Bug Fixes

* **aml:** deploy Redis idempotency store (createCase 500) ([#542](https://github.com/JiRaska/open-bank/issues/542)) ([8b18d61](https://github.com/JiRaska/open-bank/commit/8b18d6170809091df6d4b4b7b9730a49bb226576))
* **aml:** wrap reads in Panache.withSession (createCase 422) ([#545](https://github.com/JiRaska/open-bank/issues/545)) ([8bda9ac](https://github.com/JiRaska/open-bank/commit/8bda9acbd34b70c15078c5ed418e8e3e4e25823a))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/aml-service-v0.1.2...aml-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))


### Security

* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank/issues/342)) ([e368296](https://github.com/JiRaska/open-bank/commit/e3682965a4f7df3b7328e8a741e4809604706390))
