# Changelog

## [0.6.0](https://github.com/JiRaska/open-bank/compare/interest-service-v0.5.0...interest-service-v0.6.0) (2026-06-25)


### Features

* **interest:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2079](https://github.com/JiRaska/open-bank/issues/2079)) ([1b2e909](https://github.com/JiRaska/open-bank/commit/1b2e9091086f032e60b06d4d20915f394e17738f))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/interest-service-v0.4.0...interest-service-v0.5.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **interest:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#802](https://github.com/JiRaska/open-bank/issues/802)) ([eaef7f3](https://github.com/JiRaska/open-bank/commit/eaef7f3b0cc180995d03e915d13abd6545caafc5))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/interest-service-v0.3.0...interest-service-v0.4.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/interest-service-v0.2.2...interest-service-v0.3.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
