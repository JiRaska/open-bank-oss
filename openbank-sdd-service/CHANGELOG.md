# Changelog

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.11.0...sdd-service-v0.12.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.10.4...sdd-service-v0.11.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.10.4](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.10.3...sdd-service-v0.10.4) (2026-08-22)


### Bug Fixes

* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.10.3](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.10.2...sdd-service-v0.10.3) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.10.2](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.10.1...sdd-service-v0.10.2) (2026-08-18)


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.10.0...sdd-service-v0.10.1) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.9.0...sdd-service-v0.10.0) (2026-08-07)


### Features

* **sdd:** register workflow liveness on mandate expiry scheduler (ADR-0237) ([#3706](https://github.com/JiRaska/open-bank-oss/issues/3706)) ([584c72b](https://github.com/JiRaska/open-bank-oss/commit/584c72b97dd0a9d0db3c396636ee005f31727f88))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.8.3...sdd-service-v0.9.0) (2026-07-31)


### Features

* **sdd:** backoffice mandate queue — GET /api/v1/sdd/mandates/recent (ADR-0230 D3) ([#2803](https://github.com/JiRaska/open-bank-oss/issues/2803)) ([55dfdb9](https://github.com/JiRaska/open-bank-oss/commit/55dfdb9ea392d6e7c12c9cf7274d1ea7a1108cfc))

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.8.2...sdd-service-v0.8.3) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.8.1...sdd-service-v0.8.2) (2026-07-24)


### Bug Fixes

* **sdd:** @WithTransaction on write endpoints (outbox append had no session) ([#2061](https://github.com/JiRaska/open-bank-oss/issues/2061)) ([0436516](https://github.com/JiRaska/open-bank-oss/commit/04365161f5a6676d2959205b98e29c29c09ed155))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.8.0...sdd-service-v0.8.1) (2026-07-17)


### Bug Fixes

* **sdd:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1460](https://github.com/JiRaska/open-bank-oss/issues/1460)) ([a4faec3](https://github.com/JiRaska/open-bank-oss/commit/a4faec33cb021cd863503a2d99bddcfc51ce0101))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.7.1...sdd-service-v0.8.0) (2026-07-14)


### Features

* **sdd:** book the debtor debit for an authorised SDD collection ([#1000](https://github.com/JiRaska/open-bank-oss/issues/1000)) ([#1027](https://github.com/JiRaska/open-bank-oss/issues/1027)) ([58869a4](https://github.com/JiRaska/open-bank-oss/commit/58869a43096a705cde93c54d6d9f52020c79198e))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.7.0...sdd-service-v0.7.1) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.6.2...sdd-service-v0.7.0) (2026-07-06)


### Features

* **sdd:** scale-to-zero to T1 via HTTPScaledObject (ADR-0057) ([#251](https://github.com/JiRaska/open-bank-oss/issues/251)) ([5135252](https://github.com/JiRaska/open-bank-oss/commit/5135252f2d5d5cdf4d8ba8c866a7860857a15d82)), closes [#230](https://github.com/JiRaska/open-bank-oss/issues/230)

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.6.1...sdd-service-v0.6.2) (2026-06-30)


### Security

* **card-issuance,sdd:** Kafka mTLS code-side prep — SSL defaults + RBAC pre-registration (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2765](https://github.com/JiRaska/open-bank-oss/issues/2765)) ([4ae04fd](https://github.com/JiRaska/open-bank-oss/commit/4ae04fd8bbaf771ca696732b5bea6fd72048c5c6))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.6.0...sdd-service-v0.6.1) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.5.0...sdd-service-v0.6.0) (2026-06-27)


### Features

* **tpp-registry,statement,onboarding,agent,settlement,sdd:** inject Clock (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2138](https://github.com/JiRaska/open-bank-oss/issues/2138)) ([baa0d03](https://github.com/JiRaska/open-bank-oss/commit/baa0d03bcef7a1cd48cb7e115410ab625a26acde))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.4.0...sdd-service-v0.5.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.3.0...sdd-service-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))


### Bug Fixes

* **sdd:** align openapi info.version with version.txt (0.3.0) ([#791](https://github.com/JiRaska/open-bank-oss/issues/791)) ([d128449](https://github.com/JiRaska/open-bank-oss/commit/d128449c4d66bbaeb33b5e23de690e3daaefd218))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.2.0...sdd-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **libs:** add party-self-service and operator-read-any OPA rules for device.list ([#418](https://github.com/JiRaska/open-bank-oss/issues/418)) ([a4499b6](https://github.com/JiRaska/open-bank-oss/commit/a4499b605d640caa1b6b269ffb0388bf07fd98a8))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/sdd-service-v0.1.2...sdd-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
