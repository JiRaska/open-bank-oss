# Changelog

## [0.6.0](https://github.com/JiRaska/open-bank/compare/notification-service-v0.5.0...notification-service-v0.6.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank/commit/ad26ca7d58e62c8822e11f66f346926acc453058))


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank/commit/4ea273195d038704acc6341f684c0f1cb039ce82))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/notification-service-v0.4.0...notification-service-v0.5.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **notification:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#805](https://github.com/JiRaska/open-bank/issues/805)) ([a76f35e](https://github.com/JiRaska/open-bank/commit/a76f35eebc4e6a74d4e8035934f1b8b823c396a7))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/notification-service-v0.3.0...notification-service-v0.4.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **notification:** anonymized Slack/Teams oversight webhooks (ADR-0059) ([#288](https://github.com/JiRaska/open-bank/issues/288)) ([2bbe76c](https://github.com/JiRaska/open-bank/commit/2bbe76c623f69f7382f66db4d8d4e898d5492ed7))
* **notification:** PUSH delivery via FCM/APNs + device token registry ([#535](https://github.com/JiRaska/open-bank/issues/535)) ([73c4ebd](https://github.com/JiRaska/open-bank/commit/73c4ebdac4dc96fffc6c60823df00d88a07f1c78))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **notifications:** page + count the feed in a single reactive session ([#567](https://github.com/JiRaska/open-bank/issues/567)) ([204e22d](https://github.com/JiRaska/open-bank/commit/204e22d1b703c3cb691b16cf9cf486b7fba4ce2b))
