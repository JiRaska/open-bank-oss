# Changelog

## [0.26.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.25.0...card-issuance-service-v0.26.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.25.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.24.4...card-issuance-service-v0.25.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.24.4](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.24.3...card-issuance-service-v0.24.4) (2026-08-22)


### Bug Fixes

* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.24.3](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.24.2...card-issuance-service-v0.24.3) (2026-08-22)


### Bug Fixes

* **card-issuance:** alert on dead-lettered outbox rows and add an operator requeue path ([#4308](https://github.com/JiRaska/open-bank-oss/issues/4308)) ([c666a4d](https://github.com/JiRaska/open-bank-oss/commit/c666a4deae12d1025722647e4813e9ccd0d86944))

## [0.24.2](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.24.1...card-issuance-service-v0.24.2) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.24.1](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.24.0...card-issuance-service-v0.24.1) (2026-08-18)


### Bug Fixes

* **card-issuance:** add sourceService to card domain events ([#5382](https://github.com/JiRaska/open-bank-oss/issues/5382)) ([ed655a0](https://github.com/JiRaska/open-bank-oss/commit/ed655a0595190e23ac16e7f4f08b6b7f6d060f49)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [0.24.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.23.0...card-issuance-service-v0.24.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.23.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.22.0...card-issuance-service-v0.23.0) (2026-08-17)


### Features

* **card-issuance:** re-encrypt batch job for a rotated OpenBao DEK ([#5347](https://github.com/JiRaska/open-bank-oss/issues/5347)) ([13fedea](https://github.com/JiRaska/open-bank-oss/commit/13fedea50cfc44aa00b930acbb9bca290a2c72c7))

## [0.22.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.21.0...card-issuance-service-v0.22.0) (2026-08-17)


### Features

* **card-issuance:** envelope encryption for PAN vault via OpenBao Transit ([#5224](https://github.com/JiRaska/open-bank-oss/issues/5224)) ([7817150](https://github.com/JiRaska/open-bank-oss/commit/78171506bf435797142827b7f60ec6ab90d8a4bd))


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.21.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.20.0...card-issuance-service-v0.21.0) (2026-08-16)


### Features

* **libs-runtime:** wire outboxDispatched/outboxDead metrics into AbstractOutboxDispatcher ([#5071](https://github.com/JiRaska/open-bank-oss/issues/5071)) ([8da83b0](https://github.com/JiRaska/open-bank-oss/commit/8da83b073b07052316c56425290579ff162dcbff)), closes [#5049](https://github.com/JiRaska/open-bank-oss/issues/5049)

## [0.20.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.19.0...card-issuance-service-v0.20.0) (2026-08-14)


### Features

* **scheduler:** register workflow liveness on four retention and cleanup jobs (ADR-0237) ([#4739](https://github.com/JiRaska/open-bank-oss/issues/4739)) ([c2a2fa4](https://github.com/JiRaska/open-bank-oss/commit/c2a2fa4b788a172ef85c8babb439cecd10fbfe23)), closes [#3345](https://github.com/JiRaska/open-bank-oss/issues/3345)

## [0.19.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.18.1...card-issuance-service-v0.19.0) (2026-08-13)


### Features

* **cards:** additional cardholder — a card issued to someone else, with its own limits ([#4194](https://github.com/JiRaska/open-bank-oss/issues/4194)) ([25bd631](https://github.com/JiRaska/open-bank-oss/commit/25bd63177daa65dae31a2958cae80cdab2def3b9))

## [0.18.1](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.18.0...card-issuance-service-v0.18.1) (2026-08-09)


### Bug Fixes

* **libs:** stamp ApiError.timestamp at construction instead of serving 1970 ([#3880](https://github.com/JiRaska/open-bank-oss/issues/3880)) ([b3e6672](https://github.com/JiRaska/open-bank-oss/commit/b3e6672c9e13470fc6353ad8a5483e4075875b1f))

## [0.18.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.17.1...card-issuance-service-v0.18.0) (2026-08-09)


### Features

* **card-issuance:** model the single-use card lifecycle (D1 server preparation) ([#4039](https://github.com/JiRaska/open-bank-oss/issues/4039)) ([3dcaa48](https://github.com/JiRaska/open-bank-oss/commit/3dcaa480ff6b6079b3ebffc4896fe911f4927fe0))

## [0.17.1](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.17.0...card-issuance-service-v0.17.1) (2026-08-08)


### Bug Fixes

* **libs:** an open circuit breaker must not drive outbox rows to terminal DEAD ([#4163](https://github.com/JiRaska/open-bank-oss/issues/4163)) ([ce0ef79](https://github.com/JiRaska/open-bank-oss/commit/ce0ef7954df5a0e543810828ff0487abda062b7a))

## [0.17.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.16.0...card-issuance-service-v0.17.0) (2026-08-08)


### Features

* **ci:** check gitops workload env hostnames, and fix the four it finds ([#3974](https://github.com/JiRaska/open-bank-oss/issues/3974)) ([123633f](https://github.com/JiRaska/open-bank-oss/commit/123633fcdb7ce6bfa5b949bd1610196618e36108))

## [0.16.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.15.2...card-issuance-service-v0.16.0) (2026-08-07)


### Features

* **card-issuance:** make the card controls real, and add category rules (D3) ([#4020](https://github.com/JiRaska/open-bank-oss/issues/4020)) ([3d3ce14](https://github.com/JiRaska/open-bank-oss/commit/3d3ce1428f0c2a6ca5969bbf0b84bea1698eee64))

## [0.15.2](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.15.1...card-issuance-service-v0.15.2) (2026-08-07)


### Bug Fixes

* **psd2:** answer 400, not 500, for a missing required query/header parameter ([#3658](https://github.com/JiRaska/open-bank-oss/issues/3658)) ([0abed33](https://github.com/JiRaska/open-bank-oss/commit/0abed33edb9d9748058573d3352c0a2daf825d74))

## [0.15.1](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.15.0...card-issuance-service-v0.15.1) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.14.0...card-issuance-service-v0.15.0) (2026-08-02)


### Features

* **card-issuance:** delegation-grant enforcement projection for cards (ADR-0232 D3) ([#3105](https://github.com/JiRaska/open-bank-oss/issues/3105)) ([9851447](https://github.com/JiRaska/open-bank-oss/commit/9851447f95fb3b4cdafa61d6b5a9811e8581f474))

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.13.1...card-issuance-service-v0.14.0) (2026-07-30)


### Features

* **finops:** route all four product-catalog callers through the KEDA interceptor ([#2699](https://github.com/JiRaska/open-bank-oss/issues/2699)) ([f603f4d](https://github.com/JiRaska/open-bank-oss/commit/f603f4d7d590200bb03bf97d83e36880ac74c862))


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.13.1](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.13.0...card-issuance-service-v0.13.1) (2026-07-25)


### Bug Fixes

* **cards:** issue virtual cards ACTIVE and backfill the vault for pre-ADR-0194 cards ([#2214](https://github.com/JiRaska/open-bank-oss/issues/2214)) ([bc21905](https://github.com/JiRaska/open-bank-oss/commit/bc21905c004a24fb79bc4000a3ffb1bc2867c27b))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.12.0...card-issuance-service-v0.13.0) (2026-07-25)


### Features

* **cards:** card lifecycle, synthetic PAN vault and SCA-gated card operations (ADR-0194) ([#2135](https://github.com/JiRaska/open-bank-oss/issues/2135)) ([991cd92](https://github.com/JiRaska/open-bank-oss/commit/991cd928a9ea8a267aeb5aa82c33ae5a32aa3887))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.11.0...card-issuance-service-v0.12.0) (2026-07-23)


### Features

* **cards:** channel controls (contactless / online / ATM / abroad) ([#1981](https://github.com/JiRaska/open-bank-oss/issues/1981)) ([fef2bda](https://github.com/JiRaska/open-bank-oss/commit/fef2bdadf18b2fcafab6b7d19ab82de0f0b33b8d)), closes [#1980](https://github.com/JiRaska/open-bank-oss/issues/1980)

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.10.3...card-issuance-service-v0.11.0) (2026-07-21)


### Features

* **cards:** customer-settable daily/monthly spending limits ([#1863](https://github.com/JiRaska/open-bank-oss/issues/1863)) ([1a37f1d](https://github.com/JiRaska/open-bank-oss/commit/1a37f1d2c0009b127efd6c00f6274b9376937e30)), closes [#1862](https://github.com/JiRaska/open-bank-oss/issues/1862)

## [0.10.3](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.10.2...card-issuance-service-v0.10.3) (2026-07-17)


### Bug Fixes

* **card-issuance:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1516](https://github.com/JiRaska/open-bank-oss/issues/1516)) ([04f7b33](https://github.com/JiRaska/open-bank-oss/commit/04f7b33aca215c33b46a135736e21616908f6cb4))

## [0.10.2](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.10.1...card-issuance-service-v0.10.2) (2026-07-09)


### Bug Fixes

* **card-issuance-service:** provision Kafka mTLS identity + fix dotted group.id keys ([#697](https://github.com/JiRaska/open-bank-oss/issues/697)) ([331a336](https://github.com/JiRaska/open-bank-oss/commit/331a33647d5c54cc544861fcfebf3b836cf9ce11))

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.10.0...card-issuance-service-v0.10.1) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.9.0...card-issuance-service-v0.10.0) (2026-07-07)


### Features

* **gdpr:** add kyc/card export coverage and disabled-by-default session-log retention ([#356](https://github.com/JiRaska/open-bank-oss/issues/356)) ([d627e0a](https://github.com/JiRaska/open-bank-oss/commit/d627e0a0d9c7514f65b53f8d253c2ae5394e5386))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.8.2...card-issuance-service-v0.9.0) (2026-07-06)


### Features

* **card-issuance:** scale-to-zero to T1 via HTTPScaledObject (ADR-0057) ([#249](https://github.com/JiRaska/open-bank-oss/issues/249)) ([14556c8](https://github.com/JiRaska/open-bank-oss/commit/14556c87b6db5df07bd362d31eaec945d0af0c65)), closes [#230](https://github.com/JiRaska/open-bank-oss/issues/230)

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.8.1...card-issuance-service-v0.8.2) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.8.0...card-issuance-service-v0.8.1) (2026-06-30)


### Security

* **card-issuance,sdd:** Kafka mTLS code-side prep — SSL defaults + RBAC pre-registration (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2765](https://github.com/JiRaska/open-bank-oss/issues/2765)) ([4ae04fd](https://github.com/JiRaska/open-bank-oss/commit/4ae04fd8bbaf771ca696732b5bea6fd72048c5c6))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.7.1...card-issuance-service-v0.8.0) (2026-06-29)


### Features

* **card-issuance:** GDPR Art.5 card PII retention expiry (ADR-0118 §5) ([#2479](https://github.com/JiRaska/open-bank-oss/issues/2479)) ([74ddd37](https://github.com/JiRaska/open-bank-oss/commit/74ddd370c6ea7f15d0711bdfa318b8aa6494f657))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **card-issuance:** handle PARTY_ERASED event to anonymise cardholder PII (GDPR Art. 17) ([#2268](https://github.com/JiRaska/open-bank-oss/issues/2268)) ([16db236](https://github.com/JiRaska/open-bank-oss/commit/16db236f009fa842c91660e8d59f6cbf061b366d))
* **card-issuance:** use &lt;= boundary in anonymizeExpiredCardPii (GDPR compliance) ([#2525](https://github.com/JiRaska/open-bank-oss/issues/2525)) ([b46e45b](https://github.com/JiRaska/open-bank-oss/commit/b46e45bd694461a908e61d63bc4f5ddf924f39b5))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([ce39af7](https://github.com/JiRaska/open-bank-oss/commit/ce39af7c5b02ff3fef226f34e78c7e726b149ebd))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.7.0...card-issuance-service-v0.7.1) (2026-06-29)


### Bug Fixes

* **card-issuance:** use &lt;= boundary in anonymizeExpiredCardPii (GDPR compliance) ([#2525](https://github.com/JiRaska/open-bank-oss/issues/2525)) ([2fe35f6](https://github.com/JiRaska/open-bank-oss/commit/2fe35f628ff46868ead5da4ea119cf314d1908d1))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.6.2...card-issuance-service-v0.7.0) (2026-06-29)


### Features

* **card-issuance:** GDPR Art.5 card PII retention expiry (ADR-0118 §5) ([#2479](https://github.com/JiRaska/open-bank-oss/issues/2479)) ([b87f815](https://github.com/JiRaska/open-bank-oss/commit/b87f8156d8ef2e14458b82cb20b03bc97cb714bf))

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.6.1...card-issuance-service-v0.6.2) (2026-06-28)


### Bug Fixes

* **card-issuance:** handle PARTY_ERASED event to anonymise cardholder PII (GDPR Art. 17) ([#2268](https://github.com/JiRaska/open-bank-oss/issues/2268)) ([9f098fd](https://github.com/JiRaska/open-bank-oss/commit/9f098fd07a3eed8c8a3e803d15eef4b3cb975afe))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.6.0...card-issuance-service-v0.6.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank-oss/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.5.0...card-issuance-service-v0.6.0) (2026-06-25)


### Features

* **sanctions,aml,psd2,card-issuance:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2118](https://github.com/JiRaska/open-bank-oss/issues/2118)) ([bbd0da0](https://github.com/JiRaska/open-bank-oss/commit/bbd0da0dfd269d3c5ac5af8d5bac9d754c48a2d0))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.4.0...card-issuance-service-v0.5.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.3.0...card-issuance-service-v0.4.0) (2026-06-12)


### Features

* **card-issuance:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#800](https://github.com/JiRaska/open-bank-oss/issues/800)) ([63bd246](https://github.com/JiRaska/open-bank-oss/commit/63bd2460f3c0f158901d548178e81c501f0f8d76))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.2.0...card-issuance-service-v0.3.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/card-issuance-service-v0.1.1...card-issuance-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
