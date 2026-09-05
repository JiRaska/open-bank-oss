# Changelog

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.11.0...psd2-service-v0.11.1) (2026-09-03)


### Bug Fixes

* **psd2,sanctions:** a null JSON array element is a 400, not a 500 ([#7867](https://github.com/JiRaska/open-bank-oss/issues/7867)) ([#8003](https://github.com/JiRaska/open-bank-oss/issues/8003)) ([28eb8a3](https://github.com/JiRaska/open-bank-oss/commit/28eb8a3dee81237b6f07b5bf9e6e0f4dbe4e8f0f))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.10.0...psd2-service-v0.11.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.9.0...psd2-service-v0.10.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.14...psd2-service-v0.9.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.8.14](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.13...psd2-service-v0.8.14) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.8.13](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.12...psd2-service-v0.8.13) (2026-08-07)


### Bug Fixes

* **psd2:** answer 400, not 500, for a missing required query/header parameter ([#3658](https://github.com/JiRaska/open-bank-oss/issues/3658)) ([0abed33](https://github.com/JiRaska/open-bank-oss/commit/0abed33edb9d9748058573d3352c0a2daf825d74))

## [0.8.12](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.11...psd2-service-v0.8.12) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.8.11](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.10...psd2-service-v0.8.11) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.8.10](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.9...psd2-service-v0.8.10) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.8.9](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.8...psd2-service-v0.8.9) (2026-07-23)


### Bug Fixes

* **psd2:** back the consent gate with a real consent-service client ([#1500](https://github.com/JiRaska/open-bank-oss/issues/1500)) ([#1897](https://github.com/JiRaska/open-bank-oss/issues/1897)) ([34fbfc5](https://github.com/JiRaska/open-bank-oss/commit/34fbfc5db89e1eccd0b01d63be6936ba902e089f))

## [0.8.8](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.7...psd2-service-v0.8.8) (2026-07-17)


### Bug Fixes

* **psd2:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1461](https://github.com/JiRaska/open-bank-oss/issues/1461)) ([3dd2f0b](https://github.com/JiRaska/open-bank-oss/commit/3dd2f0b391c68301255e5076438ed55887029a9c))

## [0.8.7](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.6...psd2-service-v0.8.7) (2026-07-11)


### Bug Fixes

* **ledger,psd2:** dedicated exception types, no more mapper collision ([#526](https://github.com/JiRaska/open-bank-oss/issues/526)) ([#752](https://github.com/JiRaska/open-bank-oss/issues/752)) ([e15464c](https://github.com/JiRaska/open-bank-oss/commit/e15464c58a43c514f3a33c67751c3a75de667e1a))

## [0.8.6](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.5...psd2-service-v0.8.6) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.8.5](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.4...psd2-service-v0.8.5) (2026-07-06)


### Bug Fixes

* set real koverVerify floors for sanctions/finrep/psd2-service ([#288](https://github.com/JiRaska/open-bank-oss/issues/288)) ([b49c139](https://github.com/JiRaska/open-bank-oss/commit/b49c13968c67b34352512b6d10690bab772b2d67))

## [0.8.4](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.3...psd2-service-v0.8.4) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))
* **psd2:** sanitize request-derived values before logging (CodeQL java/log-injection) ([#108](https://github.com/JiRaska/open-bank-oss/issues/108)) ([71e6211](https://github.com/JiRaska/open-bank-oss/commit/71e6211d37f5823285aba0c1235fd703a9f9fdb4))

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.2...psd2-service-v0.8.3) (2026-06-30)


### Security

* **psd2:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2760](https://github.com/JiRaska/open-bank-oss/issues/2760)) ([5dda851](https://github.com/JiRaska/open-bank-oss/commit/5dda851fa5443998f9f17581c1ec193479d673fa))

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.1...psd2-service-v0.8.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([ce39af7](https://github.com/JiRaska/open-bank-oss/commit/ce39af7c5b02ff3fef226f34e78c7e726b149ebd))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **psd2:** suppress LongMethod and LongParameterList detekt violations ([#2274](https://github.com/JiRaska/open-bank-oss/issues/2274)) ([4828b2d](https://github.com/JiRaska/open-bank-oss/commit/4828b2d7e603a15f38e1767e29fbf91ae3776a67)), closes [#2084](https://github.com/JiRaska/open-bank-oss/issues/2084)
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.8.0...psd2-service-v0.8.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank-oss/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))
* **psd2:** suppress LongMethod and LongParameterList detekt violations ([#2274](https://github.com/JiRaska/open-bank-oss/issues/2274)) ([96ffa0e](https://github.com/JiRaska/open-bank-oss/commit/96ffa0e4d6dce4eed55e412f360f0280eff8ab63)), closes [#2084](https://github.com/JiRaska/open-bank-oss/issues/2084)

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.7.0...psd2-service-v0.8.0) (2026-06-25)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank-oss/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank-oss/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.6.0...psd2-service-v0.7.0) (2026-06-25)


### Features

* **psd2:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2089](https://github.com/JiRaska/open-bank-oss/issues/2089)) ([5740caf](https://github.com/JiRaska/open-bank-oss/commit/5740caf8d1e59c4d40b72c5ec5fcd82d67395f44)), closes [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.5.0...psd2-service-v0.6.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))


### Bug Fixes

* **customer-edge:** fix SCA challenge 400 — switch body from String to JsonNode ([213e577](https://github.com/JiRaska/open-bank-oss/commit/213e577b9fb53a83f2fe8a28d294c5380381c0bf))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.4.0...psd2-service-v0.5.0) (2026-06-15)


### Features

* **psd2:** Berlin Group XS2A consent + AIS endpoints (ADR-0090 P1) ([#1117](https://github.com/JiRaska/open-bank-oss/issues/1117)) ([970c0c7](https://github.com/JiRaska/open-bank-oss/commit/970c0c71a3803e0fb819c142c3e9c60fd8f48df2))
* **psd2:** Berlin Group XS2A payment initiation (ADR-0090 P2) ([#1120](https://github.com/JiRaska/open-bank-oss/issues/1120)) ([20e1b3b](https://github.com/JiRaska/open-bank-oss/commit/20e1b3b4ebecfc25aaa710654ecf75f92f0e690d)), closes [#1118](https://github.com/JiRaska/open-bank-oss/issues/1118)
* **psd2:** ČOBS Czech payment products on Berlin /v1 (ADR-0090 P3) ([#1121](https://github.com/JiRaska/open-bank-oss/issues/1121)) ([43ad9b8](https://github.com/JiRaska/open-bank-oss/commit/43ad9b8047df2249750ddd6f1da14fcd4488c30a)), closes [#1118](https://github.com/JiRaska/open-bank-oss/issues/1118)
* **psd2:** eIDAS QSEAL message signing + deprecate bespoke API (ADR-0090 P4) ([#1123](https://github.com/JiRaska/open-bank-oss/issues/1123)) ([7c901dc](https://github.com/JiRaska/open-bank-oss/commit/7c901dc3bb22b5663548dc87c69bb93a53363999)), closes [#1118](https://github.com/JiRaska/open-bank-oss/issues/1118)

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.3.0...psd2-service-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **psd2:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#807](https://github.com/JiRaska/open-bank-oss/issues/807)) ([428c661](https://github.com/JiRaska/open-bank-oss/commit/428c6617efb65b3b9d584420c666a4a3b4407001))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.2.0...psd2-service-v0.3.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/psd2-service-v0.1.0...psd2-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))


### Security

* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank-oss/issues/342)) ([e368296](https://github.com/JiRaska/open-bank-oss/commit/e3682965a4f7df3b7328e8a741e4809604706390))
