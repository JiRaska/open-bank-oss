# Changelog

## [0.4.1](https://github.com/JiRaska/open-bank/compare/anacredit-service-v0.4.0...anacredit-service-v0.4.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/anacredit-service-v0.3.1...anacredit-service-v0.4.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank/commit/ad26ca7d58e62c8822e11f66f346926acc453058))

## [0.3.1](https://github.com/JiRaska/open-bank/compare/anacredit-service-v0.3.0...anacredit-service-v0.3.1) (2026-06-15)


### Bug Fixes

* **anacredit:** assign unique HTTP port 8137 (resolve collision with onboarding) ([#1041](https://github.com/JiRaska/open-bank/issues/1041)) ([fb88443](https://github.com/JiRaska/open-bank/commit/fb88443b5f1c84e56ca4650265d2787e18de9287))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/anacredit-service-v0.2.0...anacredit-service-v0.3.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/anacredit-service-v0.1.1...anacredit-service-v0.2.0) (2026-06-08)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
