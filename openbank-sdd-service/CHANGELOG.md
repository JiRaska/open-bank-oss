# Changelog

## [0.6.0](https://github.com/JiRaska/open-bank/compare/sdd-service-v0.5.0...sdd-service-v0.6.0) (2026-06-27)


### Features

* **tpp-registry,statement,onboarding,agent,settlement,sdd:** inject Clock (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2138](https://github.com/JiRaska/open-bank/issues/2138)) ([baa0d03](https://github.com/JiRaska/open-bank/commit/baa0d03bcef7a1cd48cb7e115410ab625a26acde))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/sdd-service-v0.4.0...sdd-service-v0.5.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank/commit/ad26ca7d58e62c8822e11f66f346926acc453058))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/sdd-service-v0.3.0...sdd-service-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))


### Bug Fixes

* **sdd:** align openapi info.version with version.txt (0.3.0) ([#791](https://github.com/JiRaska/open-bank/issues/791)) ([d128449](https://github.com/JiRaska/open-bank/commit/d128449c4d66bbaeb33b5e23de690e3daaefd218))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/sdd-service-v0.2.0...sdd-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **libs:** add party-self-service and operator-read-any OPA rules for device.list ([#418](https://github.com/JiRaska/open-bank/issues/418)) ([a4499b6](https://github.com/JiRaska/open-bank/commit/a4499b605d640caa1b6b269ffb0388bf07fd98a8))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/sdd-service-v0.1.2...sdd-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
