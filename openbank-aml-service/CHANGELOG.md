# Changelog

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.10.0...aml-service-v0.10.1) (2026-09-03)


### Bug Fixes

* **aml:** toCreatedEvent requires the caller's clock, not an EPOCH default ([#8379](https://github.com/JiRaska/open-bank-oss/issues/8379)) ([01a46e8](https://github.com/JiRaska/open-bank-oss/commit/01a46e8543284b489a59be634a231ca45f786bd7)), closes [#8357](https://github.com/JiRaska/open-bank-oss/issues/8357)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.9.2...aml-service-v0.10.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.9.2](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.9.1...aml-service-v0.9.2) (2026-08-22)


### Bug Fixes

* **aml:** stamp the real decision time on every AML case transition, and decide openbank.aml.auto-clear ([#5837](https://github.com/JiRaska/open-bank-oss/issues/5837)) ([#6029](https://github.com/JiRaska/open-bank-oss/issues/6029)) ([60e9450](https://github.com/JiRaska/open-bank-oss/commit/60e9450313db736ed32d26c3477c973dc719bf9b))
* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.9.0...aml-service-v0.9.1) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.8.3...aml-service-v0.9.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.8.2...aml-service-v0.8.3) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.8.1...aml-service-v0.8.2) (2026-08-09)


### Bug Fixes

* **jaxrs-params:** answer 400, not 500, for a missing required query/header parameter ([#4375](https://github.com/JiRaska/open-bank-oss/issues/4375)) ([32ab2a2](https://github.com/JiRaska/open-bank-oss/commit/32ab2a2dfe0d208ae5ba865758c774ec47a92d09)), closes [#4175](https://github.com/JiRaska/open-bank-oss/issues/4175)

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.8.0...aml-service-v0.8.1) (2026-08-09)


### Bug Fixes

* **libs:** stamp ApiError.timestamp at construction instead of serving 1970 ([#3880](https://github.com/JiRaska/open-bank-oss/issues/3880)) ([b3e6672](https://github.com/JiRaska/open-bank-oss/commit/b3e6672c9e13470fc6353ad8a5483e4075875b1f))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.7.10...aml-service-v0.8.0) (2026-08-07)


### Features

* **scheduler:** register workflow liveness for five non-money-path jobs (ADR-0237) ([#3735](https://github.com/JiRaska/open-bank-oss/issues/3735)) ([90801b2](https://github.com/JiRaska/open-bank-oss/commit/90801b203c4eec229180321e869c926be3a99ee8))

## [0.7.10](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.7.9...aml-service-v0.7.10) (2026-08-02)


### Bug Fixes

* **aml:** resolve the party an AML case is really about, instead of storing the account id ([#3529](https://github.com/JiRaska/open-bank-oss/issues/3529)) ([f0843dd](https://github.com/JiRaska/open-bank-oss/commit/f0843dd42cf208499259955e51fc31c05b513612)), closes [#3413](https://github.com/JiRaska/open-bank-oss/issues/3413)

## [0.7.9](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.7.8...aml-service-v0.7.9) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.7.8](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.7.7...aml-service-v0.7.8) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.7.7](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.7.6...aml-service-v0.7.7) (2026-07-25)


### Bug Fixes

* **aml:** state the four constraints the gate previously forced into prose ([#2373](https://github.com/JiRaska/open-bank-oss/issues/2373)) ([67508f4](https://github.com/JiRaska/open-bank-oss/commit/67508f452534cf2ad13872c295e66c485e4c93a1))

## [0.7.6](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.7.5...aml-service-v0.7.6) (2026-07-25)


### Bug Fixes

* **aml:** describe the implemented API in openapi.yaml and correct the lineage ([#2317](https://github.com/JiRaska/open-bank-oss/issues/2317)) ([c0e1993](https://github.com/JiRaska/open-bank-oss/commit/c0e199372bed7229380e84c1d2488a266ee61aa5)), closes [#2312](https://github.com/JiRaska/open-bank-oss/issues/2312)

## [0.7.5](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.7.4...aml-service-v0.7.5) (2026-07-17)


### Bug Fixes

* **aml:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1472](https://github.com/JiRaska/open-bank-oss/issues/1472)) ([afa8925](https://github.com/JiRaska/open-bank-oss/commit/afa89257572ede250b23db9fbd910f57deadab8a))

## [0.7.4](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.7.3...aml-service-v0.7.4) (2026-07-11)


### Security

* **libs-testing,aml,ledger:** add shared authz conformance kit, fix live AML gap ([#467](https://github.com/JiRaska/open-bank-oss/issues/467)) ([#757](https://github.com/JiRaska/open-bank-oss/issues/757)) ([94e9c6d](https://github.com/JiRaska/open-bank-oss/commit/94e9c6d9a20cb2b1bf972bf60dfed1ff90e2443c))

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.7.2...aml-service-v0.7.3) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.7.1...aml-service-v0.7.2) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.7.0...aml-service-v0.7.1) (2026-06-30)


### Security

* **aml:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2753](https://github.com/JiRaska/open-bank-oss/issues/2753)) ([75694a7](https://github.com/JiRaska/open-bank-oss/commit/75694a7e2c3b573f0c4699046416c99df32059a3))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.6.1...aml-service-v0.7.0) (2026-06-29)


### Features

* **aml:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2124](https://github.com/JiRaska/open-bank-oss/issues/2124)) ([a9de07e](https://github.com/JiRaska/open-bank-oss/commit/a9de07ee3b6f6639fe667c53a6fd592fbeb92978))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **aml:** GDPR Art. 17 — anonymise PII on PARTY_ERASED ([#2448](https://github.com/JiRaska/open-bank-oss/issues/2448)) ([7e9ca88](https://github.com/JiRaska/open-bank-oss/commit/7e9ca8804f214004cf152bfef2c99a75dff7daa2))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.6.0...aml-service-v0.6.1) (2026-06-29)


### Bug Fixes

* **aml:** GDPR Art. 17 — anonymise PII on PARTY_ERASED ([#2448](https://github.com/JiRaska/open-bank-oss/issues/2448)) ([5a90f5f](https://github.com/JiRaska/open-bank-oss/commit/5a90f5f1fb535ea214dcf523ff6c082e641cd097))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.5.1...aml-service-v0.6.0) (2026-06-27)


### Features

* **aml:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2124](https://github.com/JiRaska/open-bank-oss/issues/2124)) ([b292145](https://github.com/JiRaska/open-bank-oss/commit/b29214592a622fb9f003685e5c9f7b8d984d2f41))
* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank-oss/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank-oss/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))
* **sanctions,aml,psd2,card-issuance:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2118](https://github.com/JiRaska/open-bank-oss/issues/2118)) ([bbd0da0](https://github.com/JiRaska/open-bank-oss/commit/bbd0da0dfd269d3c5ac5af8d5bac9d754c48a2d0))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.5.0...aml-service-v0.5.1) (2026-06-25)


### Bug Fixes

* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank-oss/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank-oss/commit/92dc2d636d857b526c8276e2647de440c540577b))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.4.0...aml-service-v0.5.0) (2026-06-21)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank-oss/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank-oss/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.3.0...aml-service-v0.4.0) (2026-06-12)


### Features

* **aml:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#801](https://github.com/JiRaska/open-bank-oss/issues/801)) ([f0dd2ab](https://github.com/JiRaska/open-bank-oss/commit/f0dd2ab2f5e9f75606ae46f0421fe8c07e1f57b9))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.2.0...aml-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **aml:** deploy aml-service + onboarding auto-screen (ADR-0073 phase 3) ([#540](https://github.com/JiRaska/open-bank-oss/issues/540)) ([cb5c472](https://github.com/JiRaska/open-bank-oss/commit/cb5c4727b055f31e951ad6954711ddda5c7f4766))


### Bug Fixes

* **aml:** deploy Redis idempotency store (createCase 500) ([#542](https://github.com/JiRaska/open-bank-oss/issues/542)) ([8b18d61](https://github.com/JiRaska/open-bank-oss/commit/8b18d6170809091df6d4b4b7b9730a49bb226576))
* **aml:** wrap reads in Panache.withSession (createCase 422) ([#545](https://github.com/JiRaska/open-bank-oss/issues/545)) ([8bda9ac](https://github.com/JiRaska/open-bank-oss/commit/8bda9acbd34b70c15078c5ed418e8e3e4e25823a))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/aml-service-v0.1.2...aml-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))


### Security

* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank-oss/issues/342)) ([e368296](https://github.com/JiRaska/open-bank-oss/commit/e3682965a4f7df3b7328e8a741e4809604706390))
