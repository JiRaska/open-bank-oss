# Changelog

## [0.14.2](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.14.1...standing-order-service-v0.14.2) (2026-07-25)


### Bug Fixes

* **standing-order:** run the daily execution sweep on a Vert.x context ([#2148](https://github.com/JiRaska/open-bank-oss/issues/2148)) ([#2180](https://github.com/JiRaska/open-bank-oss/issues/2180)) ([9047a98](https://github.com/JiRaska/open-bank-oss/commit/9047a98023dbce622aed2ab8f7cb1bcb846c6777))

## [0.14.1](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.14.0...standing-order-service-v0.14.1) (2026-07-24)


### Bug Fixes

* **standing-order:** merge instead of persist so cancel/pause don't 500 ([#2079](https://github.com/JiRaska/open-bank-oss/issues/2079)) ([5f8c233](https://github.com/JiRaska/open-bank-oss/commit/5f8c23327d5054f8ec4227ea66fe4c42cea7cd3f)), closes [#2077](https://github.com/JiRaska/open-bank-oss/issues/2077)

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.13.1...standing-order-service-v0.14.0) (2026-07-24)


### Features

* **standing-order:** ONCE frequency (one-off future-dated payment) — [#7](https://github.com/JiRaska/open-bank-oss/issues/7) ([#2072](https://github.com/JiRaska/open-bank-oss/issues/2072)) ([eb0bcf3](https://github.com/JiRaska/open-bank-oss/commit/eb0bcf3008caa0fa81cdfab98f53f6739c9e1a81))

## [0.13.1](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.13.0...standing-order-service-v0.13.1) (2026-07-17)


### Bug Fixes

* **standing-order:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1555](https://github.com/JiRaska/open-bank-oss/issues/1555)) ([5813a0f](https://github.com/JiRaska/open-bank-oss/commit/5813a0ffcb971b4709e7750f579e0d72ab949fb7))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.12.0...standing-order-service-v0.13.0) (2026-07-13)


### Features

* **libs-runtime:** WorkflowLivenessWatchdog primitive (ADR-0160 mechanism 3) ([#1001](https://github.com/JiRaska/open-bank-oss/issues/1001)) ([6c3d9f3](https://github.com/JiRaska/open-bank-oss/commit/6c3d9f36276c40466cadad78a2a01e5cec580219))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.11.4...standing-order-service-v0.12.0) (2026-07-13)


### Features

* **standing-order:** execute due orders via the SEPA rail ([#889](https://github.com/JiRaska/open-bank-oss/issues/889)) ([#994](https://github.com/JiRaska/open-bank-oss/issues/994)) ([afc099d](https://github.com/JiRaska/open-bank-oss/commit/afc099d8d6d346bdb7bc172848dbbb0c2a05aacf))

## [0.11.4](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.11.3...standing-order-service-v0.11.4) (2026-07-11)


### Security

* **standing-order,tpp-registry:** add missing RBAC to fully-open endpoints ([#758](https://github.com/JiRaska/open-bank-oss/issues/758)) ([5ed0f4c](https://github.com/JiRaska/open-bank-oss/commit/5ed0f4cba2d47e288a8000f48d122418e878318f))

## [0.11.3](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.11.2...standing-order-service-v0.11.3) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.11.2](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.11.1...standing-order-service-v0.11.2) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.11.0...standing-order-service-v0.11.1) (2026-06-30)


### Security

* **standing-order,tpp-registry:** Kafka mTLS code-side prep — SSL defaults + RBAC pre-registration (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2764](https://github.com/JiRaska/open-bank-oss/issues/2764)) ([96189bd](https://github.com/JiRaska/open-bank-oss/commit/96189bd19e44370bbfe6584d0a7bd6bec38dac10))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.10.0...standing-order-service-v0.11.0) (2026-06-29)


### Features

* **standing-order:** add execution-callback endpoints for payment rail (record-execution / record-failure) ([#2499](https://github.com/JiRaska/open-bank-oss/issues/2499)) ([27fa5c2](https://github.com/JiRaska/open-bank-oss/commit/27fa5c206b79dcda1be994bab662876811c18431))
* **standing-order:** add failure tracking, execution callbacks + boot smoke test (ADR-0114) ([#2413](https://github.com/JiRaska/open-bank-oss/issues/2413)) ([6e82dcc](https://github.com/JiRaska/open-bank-oss/commit/6e82dccb192659512a97a4415f17359be04750d0))
* **standing-order:** daily execution scheduler emitting standing-order.due.v1 outbox events ([#2269](https://github.com/JiRaska/open-bank-oss/issues/2269)) ([c93550b](https://github.com/JiRaska/open-bank-oss/commit/c93550b4132ae6d10cef5b726e62bfe70b61cd56))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([ce39af7](https://github.com/JiRaska/open-bank-oss/commit/ce39af7c5b02ff3fef226f34e78c7e726b149ebd))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
* **standing-order:** use 6-field Quartz cron for execution scheduler ([#2346](https://github.com/JiRaska/open-bank-oss/issues/2346)) ([d188454](https://github.com/JiRaska/open-bank-oss/commit/d188454926900c6dc240d1523e39faaaedab3c70))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.9.0...standing-order-service-v0.10.0) (2026-06-29)


### Features

* **standing-order:** add execution-callback endpoints for payment rail (record-execution / record-failure) ([#2499](https://github.com/JiRaska/open-bank-oss/issues/2499)) ([e5731e7](https://github.com/JiRaska/open-bank-oss/commit/e5731e72691c406164b0eba636f60f36062ca891))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.8.1...standing-order-service-v0.9.0) (2026-06-29)


### Features

* **standing-order:** add failure tracking, execution callbacks + boot smoke test (ADR-0114) ([#2413](https://github.com/JiRaska/open-bank-oss/issues/2413)) ([aa084c4](https://github.com/JiRaska/open-bank-oss/commit/aa084c44e6b4ba89819151d17aa034b84a893f7c))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.8.0...standing-order-service-v0.8.1) (2026-06-28)


### Bug Fixes

* **standing-order:** use 6-field Quartz cron for execution scheduler ([#2346](https://github.com/JiRaska/open-bank-oss/issues/2346)) ([1c49e55](https://github.com/JiRaska/open-bank-oss/commit/1c49e5587d63b9f5b4425c8266edac79efeb4f49))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.7.0...standing-order-service-v0.8.0) (2026-06-28)


### Features

* **standing-order:** daily execution scheduler emitting standing-order.due.v1 outbox events ([#2269](https://github.com/JiRaska/open-bank-oss/issues/2269)) ([c7eb84b](https://github.com/JiRaska/open-bank-oss/commit/c7eb84b817208f511f5c724956e95103e1592907))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.6.0...standing-order-service-v0.7.0) (2026-06-27)


### Features

* **notification,party,standing-order:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2115](https://github.com/JiRaska/open-bank-oss/issues/2115)) ([596924a](https://github.com/JiRaska/open-bank-oss/commit/596924a5ea3f05e8722767c66fe89638aeaaeb87))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank-oss/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.5.0...standing-order-service-v0.6.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank-oss/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank-oss/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.4.0...standing-order-service-v0.5.0) (2026-06-15)


### Features

* standing orders + open additional account (customer self-service) ([#906](https://github.com/JiRaska/open-bank-oss/issues/906)) ([9a6c07a](https://github.com/JiRaska/open-bank-oss/commit/9a6c07aa51f7077d4e2660a0bad44bdaa39b27de))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.3.0...standing-order-service-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **standing-order:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#806](https://github.com/JiRaska/open-bank-oss/issues/806)) ([efdc8bd](https://github.com/JiRaska/open-bank-oss/commit/efdc8bdfedd3ec09854a012ccaaf43260dc79705))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.2.0...standing-order-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/standing-order-service-v0.1.0...standing-order-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
