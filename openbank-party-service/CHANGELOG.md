# Changelog

## [0.5.0](https://github.com/JiRaska/open-bank/compare/party-service-v0.4.1...party-service-v0.5.0) (2026-06-27)


### Features

* **notification,party,standing-order:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2115](https://github.com/JiRaska/open-bank/issues/2115)) ([596924a](https://github.com/JiRaska/open-bank/commit/596924a5ea3f05e8722767c66fe89638aeaaeb87))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))

## [0.4.1](https://github.com/JiRaska/open-bank/compare/party-service-v0.4.0...party-service-v0.4.1) (2026-06-25)


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **ci:** pre-warm TC image cache + inmemory blob descriptors — eliminates NAT burst on CI sweeps ([#1675](https://github.com/JiRaska/open-bank/issues/1675)) ([0c42e1f](https://github.com/JiRaska/open-bank/commit/0c42e1ffc2c805fa84f029b45e70408281eb976b))
* **party:** map PgException(23505) on rc_blind_index to 409 CONFLICT ([#1547](https://github.com/JiRaska/open-bank/issues/1547)) ([77c6b31](https://github.com/JiRaska/open-bank/commit/77c6b31b3bfd96641e92ac4a0876d1c789c17fa0)), closes [#1417](https://github.com/JiRaska/open-bank/issues/1417)

## [0.4.0](https://github.com/JiRaska/open-bank/compare/party-service-v0.3.0...party-service-v0.4.0) (2026-06-15)


### Features

* **onboarding:** self-service E2E — savings account, sub-bound party, own-account transfers ([#894](https://github.com/JiRaska/open-bank/issues/894)) ([91dd603](https://github.com/JiRaska/open-bank/commit/91dd603e62f8f9038a0c71d1a0187ce0303442ce))


### Bug Fixes

* **accounts:** wire account-service to sanctions-service (unblocks onboarding accounts) ([#932](https://github.com/JiRaska/open-bank/issues/932)) ([e78d03c](https://github.com/JiRaska/open-bank/commit/e78d03cd354744d602101455045884a4bb9bbc11))
* **infra:** revert EC2NodeClass userData — breaks AL2023 node bootstrap ([#940](https://github.com/JiRaska/open-bank/issues/940)) ([f7d128a](https://github.com/JiRaska/open-bank/commit/f7d128ae7773d4d3237af13d45d2f4cf177aa89a))
* **party:** append-only migrations + deploy (unblocks party.id == sub onboarding) ([#930](https://github.com/JiRaska/open-bank/issues/930)) ([5e4d18e](https://github.com/JiRaska/open-bank/commit/5e4d18e8d14174a119b027597c8af8d77cac86aa))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/party-service-v0.2.0...party-service-v0.3.0) (2026-06-12)


### Features

* **account,party,onboarding:** sprint 2 — sanctions persistence, GDPR erasure, doc download, AbandonedCleaner fix ([#475](https://github.com/JiRaska/open-bank/issues/475)) ([05b20d7](https://github.com/JiRaska/open-bank/commit/05b20d764a6373d0ffd96ca84ab5a9a6ed54291f))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **party:** party lifecycle metrics + outbox backlog gauge (ADR-0077/0079) ([#793](https://github.com/JiRaska/open-bank/issues/793)) ([cbd8a7f](https://github.com/JiRaska/open-bank/commit/cbd8a7f03f08ccd7f3cb523012b3244b0d42f119))


### Bug Fixes

* **party:** resolve duplicate Flyway V8 (party-service could not boot) ([#771](https://github.com/JiRaska/open-bank/issues/771)) ([759bd09](https://github.com/JiRaska/open-bank/commit/759bd09607fbd349a64ddbd0c4468f2b6452d303)), closes [#699](https://github.com/JiRaska/open-bank/issues/699)

## [0.2.0](https://github.com/JiRaska/open-bank/compare/party-service-v0.1.1...party-service-v0.2.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **libs:** add party-self-service and operator-read-any OPA rules for device.list ([#418](https://github.com/JiRaska/open-bank/issues/418)) ([a4499b6](https://github.com/JiRaska/open-bank/commit/a4499b605d640caa1b6b269ffb0388bf07fd98a8))
* **party,kyc:** add ?status= filter to list endpoints for onboarding cockpit ([#420](https://github.com/JiRaska/open-bank/issues/420)) ([3c3cee8](https://github.com/JiRaska/open-bank/commit/3c3cee8cec1ca4896f9e30a1da5ff1c180e2a05f))
* **party:** bounded name search (ADR-0055) ([#410](https://github.com/JiRaska/open-bank/issues/410)) ([a03dc60](https://github.com/JiRaska/open-bank/commit/a03dc605344703ed9f77ed27fc78d5cb613e535d))
* **party:** gate /parties/search behind @FeatureFlag (ADR-0067 phase 2) ([aa17239](https://github.com/JiRaska/open-bank/commit/aa172393570c427afdef0523f03125493fbcf479))
* **party:** include legalName in party events (onboarding cockpit NAME column) ([#529](https://github.com/JiRaska/open-bank/issues/529)) ([5b8aa03](https://github.com/JiRaska/open-bank/commit/5b8aa03c62d0ab47e106bf89b2274ad1d2a89d0a))
* **party:** KYC + AML two-key activation gate (ADR-0073 phase 2) ([#537](https://github.com/JiRaska/open-bank/issues/537)) ([2a8d1e0](https://github.com/JiRaska/open-bank/commit/2a8d1e07797a2006751f1851b9fd316217b67998))
* **party:** pilot live feature-flag evaluation via flagd sidecar (ADR-0067) ([#456](https://github.com/JiRaska/open-bank/issues/456)) ([f2ffdbb](https://github.com/JiRaska/open-bank/commit/f2ffdbb399802e419d134d17305138b5d1ed92c3))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **party:** make email a required field in the create-party contract ([#525](https://github.com/JiRaska/open-bank/issues/525)) ([dc8a2ad](https://github.com/JiRaska/open-bank/commit/dc8a2adb71923e89fbb1b858b49fe458e65ead13))
