# Changelog

## [0.20.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.19.1...domestic-payment-v0.20.0) (2026-09-07)


### Features

* **domestic-payment:** carry the synthetic taint across the Kafka hop ([#8640](https://github.com/JiRaska/open-bank-oss/issues/8640)) ([462b634](https://github.com/JiRaska/open-bank-oss/commit/462b634fe7c34fa8384d7bb160a01a879b51fd83))
* **payments:** tell the customer when a domestic payment is rejected ([#8508](https://github.com/JiRaska/open-bank-oss/issues/8508)) ([d64653d](https://github.com/JiRaska/open-bank-oss/commit/d64653dcec71a20b2906336baca85fdf8504bee2))


### Bug Fixes

* **domestic-payment:** disambiguate SOURCE_SERVICE const collisions + gate budget repair ([#8823](https://github.com/JiRaska/open-bank-oss/issues/8823)) ([2e92438](https://github.com/JiRaska/open-bank-oss/commit/2e9243887b7311b46cd4365c5112b8be1cd21c8c))

## [0.19.1](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.19.0...domestic-payment-v0.19.1) (2026-09-03)


### Security

* **domestic-payment:** bind delegated spend atomically ([#8252](https://github.com/JiRaska/open-bank-oss/issues/8252)) ([c763440](https://github.com/JiRaska/open-bank-oss/commit/c7634401bf8e5cc8690b24015acd537c19def591))

## [0.19.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.18.0...domestic-payment-v0.19.0) (2026-08-26)


### Features

* **domestic-payment:** persist trusted synthetic taint ([#7155](https://github.com/JiRaska/open-bank-oss/issues/7155)) ([536c84e](https://github.com/JiRaska/open-bank-oss/commit/536c84e741687963af055e23590a7c685004b675))

## [0.18.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.17.0...domestic-payment-v0.18.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.17.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.16.1...domestic-payment-v0.17.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.16.1](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.16.0...domestic-payment-v0.16.1) (2026-08-22)


### Bug Fixes

* **docs:** repair the 7 .mmd diagrams that do not parse ([#6496](https://github.com/JiRaska/open-bank-oss/issues/6496)) ([c1e6ad7](https://github.com/JiRaska/open-bank-oss/commit/c1e6ad7b14887db70ec3365747f2ed06d9ec02db))

## [0.16.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.15.0...domestic-payment-v0.16.0) (2026-08-19)


### Features

* **domestic-payment:** expose pending four-eyes approvals via approval inbox ([#5679](https://github.com/JiRaska/open-bank-oss/issues/5679)) ([#5692](https://github.com/JiRaska/open-bank-oss/issues/5692)) ([73b584e](https://github.com/JiRaska/open-bank-oss/commit/73b584e972ddb73270cb0af9ab1722ac66996eb7))

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.14.2...domestic-payment-v0.15.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)


### Security

* **deps:** resolve micrometer-core CVE-2026-40984 without a platform bump ([#5495](https://github.com/JiRaska/open-bank-oss/issues/5495)) ([b8b8d7a](https://github.com/JiRaska/open-bank-oss/commit/b8b8d7a2f28375c29949674059613e4ed8867a09))

## [0.14.2](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.14.1...domestic-payment-v0.14.2) (2026-08-18)


### Bug Fixes

* **domestic-payment:** add eventType/sourceService to audit-consumed events ([#5255](https://github.com/JiRaska/open-bank-oss/issues/5255)) ([3b3326c](https://github.com/JiRaska/open-bank-oss/commit/3b3326caaeb1a898ce75ef5547da73d812d67ebb))

## [0.14.1](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.14.0...domestic-payment-v0.14.1) (2026-08-17)


### Bug Fixes

* **domestic-payment:** make a synthetic fraud verdict distinguishable from a real one ([#4221](https://github.com/JiRaska/open-bank-oss/issues/4221) layers 2+3) ([#4411](https://github.com/JiRaska/open-bank-oss/issues/4411)) ([6265ea8](https://github.com/JiRaska/open-bank-oss/commit/6265ea869275f6722b937860f5dcd03d3674d5d7))
* **domestic-payment:** put the authenticated actor on the wire for domestic.payment.created ([#4997](https://github.com/JiRaska/open-bank-oss/issues/4997)) ([1a333f0](https://github.com/JiRaska/open-bank-oss/commit/1a333f047781ecb360d9c5bc53920e94f4b37c04)), closes [#3994](https://github.com/JiRaska/open-bank-oss/issues/3994)
* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.13.6...domestic-payment-v0.14.0) (2026-08-16)


### Features

* **libs-runtime:** wire outboxDispatched/outboxDead metrics into AbstractOutboxDispatcher ([#5071](https://github.com/JiRaska/open-bank-oss/issues/5071)) ([8da83b0](https://github.com/JiRaska/open-bank-oss/commit/8da83b073b07052316c56425290579ff162dcbff)), closes [#5049](https://github.com/JiRaska/open-bank-oss/issues/5049)

## [0.13.6](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.13.5...domestic-payment-v0.13.6) (2026-08-16)


### Bug Fixes

* **sepa-payment,domestic-payment:** emit sanctions screening/hit metrics ([#5079](https://github.com/JiRaska/open-bank-oss/issues/5079)) ([ef730ff](https://github.com/JiRaska/open-bank-oss/commit/ef730ffffebad6165a29e8715b993b1a273060fc)), closes [#5049](https://github.com/JiRaska/open-bank-oss/issues/5049)

## [0.13.5](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.13.4...domestic-payment-v0.13.5) (2026-08-16)


### Bug Fixes

* **domestic-payment:** emit paymentCompleted/paymentProcessingDuration on terminal transitions ([#5068](https://github.com/JiRaska/open-bank-oss/issues/5068)) ([ae4af48](https://github.com/JiRaska/open-bank-oss/commit/ae4af48107c953623ccef39563394a86c40989c7)), closes [#5049](https://github.com/JiRaska/open-bank-oss/issues/5049)

## [0.13.4](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.13.3...domestic-payment-v0.13.4) (2026-08-10)


### Bug Fixes

* **domestic-payment:** fail settlePayment on a settlement outage instead of completing the workflow on SENT_TO_CLEARING ([#4300](https://github.com/JiRaska/open-bank-oss/issues/4300)) ([ece5819](https://github.com/JiRaska/open-bank-oss/commit/ece581989b85ee4d62931897f73439e8c892de92))

## [0.13.3](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.13.2...domestic-payment-v0.13.3) (2026-08-09)


### Bug Fixes

* **domestic-payment:** never submit a payment to the clearing scheme twice ([#4218](https://github.com/JiRaska/open-bank-oss/issues/4218)) ([#4275](https://github.com/JiRaska/open-bank-oss/issues/4275)) ([11be465](https://github.com/JiRaska/open-bank-oss/commit/11be4658b2ac4224fdd9256925419c4b90ae3306))

## [0.13.2](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.13.1...domestic-payment-v0.13.2) (2026-08-09)


### Bug Fixes

* **libs:** stamp ApiError.timestamp at construction instead of serving 1970 ([#3880](https://github.com/JiRaska/open-bank-oss/issues/3880)) ([b3e6672](https://github.com/JiRaska/open-bank-oss/commit/b3e6672c9e13470fc6353ad8a5483e4075875b1f))

## [0.13.1](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.13.0...domestic-payment-v0.13.1) (2026-08-08)


### Bug Fixes

* **domestic-payment:** make validatePayment re-entrant so a stranded payment can be re-driven ([#4200](https://github.com/JiRaska/open-bank-oss/issues/4200)) ([305b98c](https://github.com/JiRaska/open-bank-oss/commit/305b98c75e209505dc52b4432b51498d8133bfa6)), closes [#4182](https://github.com/JiRaska/open-bank-oss/issues/4182)

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.12.0...domestic-payment-v0.13.0) (2026-08-08)


### Features

* **domestic-payment:** add customer-facing payment confirmation download endpoint ([#4126](https://github.com/JiRaska/open-bank-oss/issues/4126)) ([3f6d80c](https://github.com/JiRaska/open-bank-oss/commit/3f6d80c9ad0ec083de1d8f0c8c5762fbf26863b0))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.11.4...domestic-payment-v0.12.0) (2026-08-07)


### Features

* **domestic-payment:** register workflow liveness on stranded-gauge and screening-redrive (ADR-0237) ([#3704](https://github.com/JiRaska/open-bank-oss/issues/3704)) ([7db0e56](https://github.com/JiRaska/open-bank-oss/commit/7db0e56efb819439965aebb602dc63ed92b20681))

## [0.11.4](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.11.3...domestic-payment-v0.11.4) (2026-08-02)


### Bug Fixes

* **domestic-payment:** give a payment held on an unavailable screen a way out ([#3518](https://github.com/JiRaska/open-bank-oss/issues/3518)) ([1ebcda4](https://github.com/JiRaska/open-bank-oss/commit/1ebcda415bf9b8348f354c6bc176c933480a7ea7)), closes [#3266](https://github.com/JiRaska/open-bank-oss/issues/3266)

## [0.11.3](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.11.2...domestic-payment-v0.11.3) (2026-08-02)


### Bug Fixes

* **libs,domestic:** surface the transport fault a resilient call masks ([#3377](https://github.com/JiRaska/open-bank-oss/issues/3377)) ([c7a78ff](https://github.com/JiRaska/open-bank-oss/commit/c7a78ff10bd99abd4af37434f0351e396d1ad85a))

## [0.11.2](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.11.1...domestic-payment-v0.11.2) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.11.0...domestic-payment-v0.11.1) (2026-08-02)


### Bug Fixes

* **domestic:** resolve the debtor's party for an AML case instead of sending the account id ([#3404](https://github.com/JiRaska/open-bank-oss/issues/3404)) ([d949ce9](https://github.com/JiRaska/open-bank-oss/commit/d949ce9ef5ba09e0961159f403fd672fc0e5ca81))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.10.6...domestic-payment-v0.11.0) (2026-08-02)


### Features

* **domestic-payment:** alert on payments that stop progressing, not just on the service being down ([#3379](https://github.com/JiRaska/open-bank-oss/issues/3379)) ([02fd8fc](https://github.com/JiRaska/open-bank-oss/commit/02fd8fcd67cca1cbbd0f8ddf0f2220727f696682)), closes [#3273](https://github.com/JiRaska/open-bank-oss/issues/3273) [#3271](https://github.com/JiRaska/open-bank-oss/issues/3271)

## [0.10.6](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.10.5...domestic-payment-v0.10.6) (2026-08-02)


### Bug Fixes

* **lending,domestic:** align the entities with the DDL their own migrations create ([#3211](https://github.com/JiRaska/open-bank-oss/issues/3211)) ([0fbd745](https://github.com/JiRaska/open-bank-oss/commit/0fbd74595bd7b1401de5e67838b3cb0e7eed0722))

## [0.10.5](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.10.4...domestic-payment-v0.10.5) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))

## [0.10.4](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.10.3...domestic-payment-v0.10.4) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.10.3](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.10.2...domestic-payment-v0.10.3) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.10.2](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.10.1...domestic-payment-v0.10.2) (2026-07-24)


### Bug Fixes

* **domestic:** make Temporal the sole orchestrator, retire the legacy in-service flow (ADR-0120 Phase 6, [#1917](https://github.com/JiRaska/open-bank-oss/issues/1917) — 3/3) ([#2122](https://github.com/JiRaska/open-bank-oss/issues/2122)) ([189fe7b](https://github.com/JiRaska/open-bank-oss/commit/189fe7b034a277ceb20d686de725c68992b60b7f))

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.10.0...domestic-payment-v0.10.1) (2026-07-17)


### Bug Fixes

* **domestic-payment:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1520](https://github.com/JiRaska/open-bank-oss/issues/1520)) ([6f550b6](https://github.com/JiRaska/open-bank-oss/commit/6f550b69ad2741187099814a924c8a2576064f9a))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.9.0...domestic-payment-v0.10.0) (2026-07-09)


### Features

* **domestic-payment:** enforce fraud verdict on cleared payments (ADR-0084 §4.2) ([#675](https://github.com/JiRaska/open-bank-oss/issues/675)) ([9778a11](https://github.com/JiRaska/open-bank-oss/commit/9778a113e574a5808c80a1724580ab755bb5c434)), closes [#667](https://github.com/JiRaska/open-bank-oss/issues/667)

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.8.10...domestic-payment-v0.9.0) (2026-07-08)


### Features

* **domestic-payment:** wire four-eyes enforcement mechanism (ADR-0155) ([#560](https://github.com/JiRaska/open-bank-oss/issues/560)) ([1f5e900](https://github.com/JiRaska/open-bank-oss/commit/1f5e9003ca5ee7c26d75f8d174daf4c5fc0dce86)), closes [#413](https://github.com/JiRaska/open-bank-oss/issues/413)

## [0.8.10](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.8.9...domestic-payment-v0.8.10) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.8.9](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.8.8...domestic-payment-v0.8.9) (2026-07-07)


### Security

* **domestic-payment:** enforce OPA authorization on payment endpoints (ADR-0034 Phase 5) ([#393](https://github.com/JiRaska/open-bank-oss/issues/393)) ([7e38bf5](https://github.com/JiRaska/open-bank-oss/commit/7e38bf533b44da36c3d5723f9eae2658566d23a0))

## [0.8.8](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.8.7...domestic-payment-v0.8.8) (2026-07-03)


### Bug Fixes

* **libs-domain:** carry interbank settlement date through to pacs.008 (IntrBkSttlmDt) ([#195](https://github.com/JiRaska/open-bank-oss/issues/195)) ([62eef5e](https://github.com/JiRaska/open-bank-oss/commit/62eef5ef21626a56099ccfe9ebc6f6e5387a85b6))

## [0.8.7](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.8.6...domestic-payment-v0.8.7) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.8.6](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.8.5...domestic-payment-v0.8.6) (2026-06-30)


### Bug Fixes

* **domestic:** label in-house transfers correctly, not "CERTIS" ([#2737](https://github.com/JiRaska/open-bank-oss/issues/2737)) ([d00e4de](https://github.com/JiRaska/open-bank-oss/commit/d00e4dee9b6cff4147261722a360c443cea82b1c))

## [0.8.5](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.8.4...domestic-payment-v0.8.5) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** credit the payee on internal transfers (two-sided settlement) ([#2682](https://github.com/JiRaska/open-bank-oss/issues/2682)) ([4d3163e](https://github.com/JiRaska/open-bank-oss/commit/4d3163ef319d8c01c85ca3d8c3ea02386c886732))
* **domestic-payment:** derive transferScope server-side and apply AMLD4 SDD screening ([#2261](https://github.com/JiRaska/open-bank-oss/issues/2261)) ([79dac9b](https://github.com/JiRaska/open-bank-oss/commit/79dac9bc2d661e3059fed3cffdb0863435355095))
* **domestic-payment:** extract HTTP_NOT_FOUND constant to resolve detekt MagicNumber ([#2258](https://github.com/JiRaska/open-bank-oss/issues/2258)) ([79cb762](https://github.com/JiRaska/open-bank-oss/commit/79cb762dbeed76d937ff4cb401b820e963d216d7)), closes [#2084](https://github.com/JiRaska/open-bank-oss/issues/2084)
* **domestic-payment:** resolve ktlint violations in AccountServiceClient and IdempotencyConfig ([#2260](https://github.com/JiRaska/open-bank-oss/issues/2260)) ([a49e042](https://github.com/JiRaska/open-bank-oss/commit/a49e04285fe08f6d9aeea1966b760ec1c76bdab0))
* **domestic-payment:** scale settlement amount to currency minor units ([#2654](https://github.com/JiRaska/open-bank-oss/issues/2654)) ([265039e](https://github.com/JiRaska/open-bank-oss/commit/265039e5c14f3c0429dc34c32fd0ffdfe395a184))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([c61fcaf](https://github.com/JiRaska/open-bank-oss/commit/c61fcafde76be08f716c710462be70752073aba1))


### Security

* **kafka:** mTLS + ACLs for payment.scheme-accepted, no global gate flip (ADR-0137) ([#2602](https://github.com/JiRaska/open-bank-oss/issues/2602)) ([b143022](https://github.com/JiRaska/open-bank-oss/commit/b143022f6ab76c4ff817ddbd4467fc578b8ee193))

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.8.2...domestic-payment-v0.8.3) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** derive transferScope server-side and apply AMLD4 SDD screening ([#2261](https://github.com/JiRaska/open-bank-oss/issues/2261)) ([79dac9b](https://github.com/JiRaska/open-bank-oss/commit/79dac9bc2d661e3059fed3cffdb0863435355095))
* **domestic-payment:** extract HTTP_NOT_FOUND constant to resolve detekt MagicNumber ([#2258](https://github.com/JiRaska/open-bank-oss/issues/2258)) ([79cb762](https://github.com/JiRaska/open-bank-oss/commit/79cb762dbeed76d937ff4cb401b820e963d216d7)), closes [#2084](https://github.com/JiRaska/open-bank-oss/issues/2084)
* **domestic-payment:** resolve ktlint violations in AccountServiceClient and IdempotencyConfig ([#2260](https://github.com/JiRaska/open-bank-oss/issues/2260)) ([a49e042](https://github.com/JiRaska/open-bank-oss/commit/a49e04285fe08f6d9aeea1966b760ec1c76bdab0))
* **domestic-payment:** scale settlement amount to currency minor units ([#2654](https://github.com/JiRaska/open-bank-oss/issues/2654)) ([265039e](https://github.com/JiRaska/open-bank-oss/commit/265039e5c14f3c0429dc34c32fd0ffdfe395a184))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([c61fcaf](https://github.com/JiRaska/open-bank-oss/commit/c61fcafde76be08f716c710462be70752073aba1))


### Security

* **kafka:** mTLS + ACLs for payment.scheme-accepted, no global gate flip (ADR-0137) ([#2602](https://github.com/JiRaska/open-bank-oss/issues/2602)) ([b143022](https://github.com/JiRaska/open-bank-oss/commit/b143022f6ab76c4ff817ddbd4467fc578b8ee193))

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.8.1...domestic-payment-v0.8.2) (2026-06-28)


### Bug Fixes

* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([35c6a63](https://github.com/JiRaska/open-bank-oss/commit/35c6a6309e10c0a56558d93de5c494a89da72ceb))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.8.0...domestic-payment-v0.8.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** derive transferScope server-side and apply AMLD4 SDD screening ([#2261](https://github.com/JiRaska/open-bank-oss/issues/2261)) ([0d36834](https://github.com/JiRaska/open-bank-oss/commit/0d368341e39f70ed3a87ff31af0d061113cf07f2))
* **domestic-payment:** extract HTTP_NOT_FOUND constant to resolve detekt MagicNumber ([#2258](https://github.com/JiRaska/open-bank-oss/issues/2258)) ([5c7769b](https://github.com/JiRaska/open-bank-oss/commit/5c7769b027986683365e0cfab01fd979af2df0c2)), closes [#2084](https://github.com/JiRaska/open-bank-oss/issues/2084)
* **domestic-payment:** resolve ktlint violations in AccountServiceClient and IdempotencyConfig ([#2260](https://github.com/JiRaska/open-bank-oss/issues/2260)) ([6895569](https://github.com/JiRaska/open-bank-oss/commit/689556955b67ae66fdaad5e40bf176a4270cf06e))
* **domestic-payment:** serialize amount as String in response DTO ([#2222](https://github.com/JiRaska/open-bank-oss/issues/2222)) ([403d738](https://github.com/JiRaska/open-bank-oss/commit/403d738f1ffc236ab1a3714faf6064982b3be427))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.7.0...domestic-payment-v0.8.0) (2026-06-25)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank-oss/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank-oss/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.6.0...domestic-payment-v0.7.0) (2026-06-25)


### Features

* **domestic-payment,sepa-payment:** inject Clock into SettlementAdapter and Kafka publisher (ADR-0100) ([#2064](https://github.com/JiRaska/open-bank-oss/issues/2064)) ([85d890e](https://github.com/JiRaska/open-bank-oss/commit/85d890e8bcf0c1e5d79b76134834cb19bf90ee84)), closes [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)


### Bug Fixes

* **domestic-payment:** use @Dependent scope for ClockProducer + fix test import ordering ([#2067](https://github.com/JiRaska/open-bank-oss/issues/2067)) ([872fb1b](https://github.com/JiRaska/open-bank-oss/commit/872fb1b45d11de161ed3e46d121769b07c62598a)), closes [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.5.0...domestic-payment-v0.6.0) (2026-06-25)


### Features

* **domestic-payment,customer-edge:** add INSTANT priority; forward priority from app body ([e683832](https://github.com/JiRaska/open-bank-oss/commit/e683832c0f71a69531d2a8e53bbca94da22b2749))
* **domestic-payment,customer-edge:** add INSTANT priority; forward priority from app body ([9fddff9](https://github.com/JiRaska/open-bank-oss/commit/9fddff995d15fe94b6db4ae9eb05732a99938cff))


### Bug Fixes

* **domestic-payment:** inject Clock into domain/application layers (ADR-0100, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2011](https://github.com/JiRaska/open-bank-oss/issues/2011)) ([5df146d](https://github.com/JiRaska/open-bank-oss/commit/5df146d91829fda1e2521c91f89f522f5b23e7a9))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.4.0...domestic-payment-v0.5.0) (2026-06-25)


### Features

* **domestic-payment,swift,libs:** ADR-0104 D4 — SchemeGateway fan-out to domestic & swift rails ([331e7dd](https://github.com/JiRaska/open-bank-oss/commit/331e7ddb148c021d951521f570cc39c75aec5a3c))
* **domestic-payment:** add Temporal durable workflow for ČOBS payment orchestration (ADR-0101 P2) ([#1470](https://github.com/JiRaska/open-bank-oss/issues/1470)) ([6372d29](https://github.com/JiRaska/open-bank-oss/commit/6372d290155146456ded815c582d190e7e37eb0d))
* **domestic-payment:** trigger transaction-service settlement after scheme ACSC (ADR-0108) ([#1870](https://github.com/JiRaska/open-bank-oss/issues/1870)) ([cecc93b](https://github.com/JiRaska/open-bank-oss/commit/cecc93bf82638438fd7f1177b2a8dc65f381a8fb))
* **transaction,payment:** ADR-0103 D2 — stamp rail + instructionType at settlement (transaction-service + 4 rails) ([#1940](https://github.com/JiRaska/open-bank-oss/issues/1940)) ([785ca02](https://github.com/JiRaska/open-bank-oss/commit/785ca024d434c845dadade0190551fdd18da17a9))


### Bug Fixes

* **admin-ui:** governance data freshness — ADR-0082 renumber, MONEY_PATH fraud-service, STATIC_CANDIDATES ([f69ba3b](https://github.com/JiRaska/open-bank-oss/commit/f69ba3baaac7ab91b25a0c6a4f7945574f0f53b5))
* **domestic-payment:** clear pre-existing detekt/ktlint/test drift ([#1696](https://github.com/JiRaska/open-bank-oss/issues/1696)) ([1fd72fa](https://github.com/JiRaska/open-bank-oss/commit/1fd72faca6648617ab06bdf11d1789f97fb6142d))
* **domestic-payment:** configure the OIDC client so service tokens are minted (ADR-0104 D3) ([#1784](https://github.com/JiRaska/open-bank-oss/issues/1784)) ([f5a1cf0](https://github.com/JiRaska/open-bank-oss/commit/f5a1cf09475559a895a72ae552dccfc16e8e6750))
* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank-oss/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank-oss/commit/92dc2d636d857b526c8276e2647de440c540577b))
* **sdd:** add sdd_outbox_seq to V2 migration + fix outbox IT Vert.x context ([fceadf4](https://github.com/JiRaska/open-bank-oss/commit/fceadf4a679eddc7cbde749839846cc83bf8d5d5)), closes [#1360](https://github.com/JiRaska/open-bank-oss/issues/1360)
* **sepa-payment:** remove blank line before closing brace (ktlint) ([#1362](https://github.com/JiRaska/open-bank-oss/issues/1362)) ([65a698d](https://github.com/JiRaska/open-bank-oss/commit/65a698d6a92e2772b4d21406cf97bed983f8cc8f))
* **swift-service,domestic-payment,sepa-instant:** ADR-0104 D4 remaining — port extraction, repo fix, tests, threat models ([fbce147](https://github.com/JiRaska/open-bank-oss/commit/fbce1475004a90d816aeadf5f049783ffc086e04))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.3.0...domestic-payment-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#814](https://github.com/JiRaska/open-bank-oss/issues/814)) ([5710f35](https://github.com/JiRaska/open-bank-oss/commit/5710f35c413b577883309789c963732f66927729))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **sepa-instant:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#685](https://github.com/JiRaska/open-bank-oss/issues/685)) ([de3124e](https://github.com/JiRaska/open-bank-oss/commit/de3124eb125755c33a35d21c8bdee3208b539c69))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.2.0...domestic-payment-v0.3.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/domestic-payment-v0.1.2...domestic-payment-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **payments:** remove dead incoming Kafka channels — no @Incoming consumer ([#377](https://github.com/JiRaska/open-bank-oss/issues/377)) ([5fbdda7](https://github.com/JiRaska/open-bank-oss/commit/5fbdda796d4201b9ef1f57d41c76a00b18a5216b))
* **payments:** use property expression in Kafka channel bootstrap.servers ([#373](https://github.com/JiRaska/open-bank-oss/issues/373)) ([32507ee](https://github.com/JiRaska/open-bank-oss/commit/32507eeda72bec17c92f85169d72759ed02f1c4a))
