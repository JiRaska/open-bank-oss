# Changelog

## [0.13.2](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.13.1...sepa-payment-v0.13.2) (2026-09-07)


### Bug Fixes

* **sepa-payment:** add sourceService to the Temporal-path event payloads ([#5888](https://github.com/JiRaska/open-bank-oss/issues/5888)) ([b81eb66](https://github.com/JiRaska/open-bank-oss/commit/b81eb66bc0e9b5697ad32601171e56101e3c11c8)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [0.13.1](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.13.0...sepa-payment-v0.13.1) (2026-09-04)


### Bug Fixes

* **sepa-payment:** resolve AML case party_id via account-service ([#8505](https://github.com/JiRaska/open-bank-oss/issues/8505)) ([#8631](https://github.com/JiRaska/open-bank-oss/issues/8631)) ([2ea7bcc](https://github.com/JiRaska/open-bank-oss/commit/2ea7bcc98365ea005ab3ed77be7994cac75024c2))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.12.0...sepa-payment-v0.13.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.11.1...sepa-payment-v0.12.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.11.0...sepa-payment-v0.11.1) (2026-08-21)


### Bug Fixes

* **sepa-payment:** record who processed a pacs.004 return, durably ([#6072](https://github.com/JiRaska/open-bank-oss/issues/6072)) ([f953707](https://github.com/JiRaska/open-bank-oss/commit/f9537074d808c833dc7222e10678f31d3122c516)), closes [#6056](https://github.com/JiRaska/open-bank-oss/issues/6056)

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.10.1...sepa-payment-v0.11.0) (2026-08-20)


### Features

* **sepa-payment:** expose pending four-eyes approvals via approval inbox ([#5679](https://github.com/JiRaska/open-bank-oss/issues/5679)) ([#5691](https://github.com/JiRaska/open-bank-oss/issues/5691)) ([dc737f5](https://github.com/JiRaska/open-bank-oss/commit/dc737f572a2dc368231d255ec4741137e6cd5531))

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.10.0...sepa-payment-v0.10.1) (2026-08-18)


### Bug Fixes

* **sepa-payment:** add sourceService to outbox events for audit attribution ([#5388](https://github.com/JiRaska/open-bank-oss/issues/5388)) ([de59515](https://github.com/JiRaska/open-bank-oss/commit/de59515cfe02746b9af2de75472b7826feb5cae9)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.9.4...sepa-payment-v0.10.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.9.4](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.9.3...sepa-payment-v0.9.4) (2026-08-17)


### Bug Fixes

* **domestic-payment:** make a synthetic fraud verdict distinguishable from a real one ([#4221](https://github.com/JiRaska/open-bank-oss/issues/4221) layers 2+3) ([#4411](https://github.com/JiRaska/open-bank-oss/issues/4411)) ([6265ea8](https://github.com/JiRaska/open-bank-oss/commit/6265ea869275f6722b937860f5dcd03d3674d5d7))
* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.9.3](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.9.2...sepa-payment-v0.9.3) (2026-08-16)


### Bug Fixes

* **sepa-payment,domestic-payment:** emit sanctions screening/hit metrics ([#5079](https://github.com/JiRaska/open-bank-oss/issues/5079)) ([ef730ff](https://github.com/JiRaska/open-bank-oss/commit/ef730ffffebad6165a29e8715b993b1a273060fc)), closes [#5049](https://github.com/JiRaska/open-bank-oss/issues/5049)

## [0.9.2](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.9.1...sepa-payment-v0.9.2) (2026-08-10)


### Bug Fixes

* **lending:** stamp business event time on the money-path audit producers ([#4412](https://github.com/JiRaska/open-bank-oss/issues/4412)) ([6e43ccc](https://github.com/JiRaska/open-bank-oss/commit/6e43ccc78ac8cd4f4a8af63743f6a530056a7510)), closes [#3914](https://github.com/JiRaska/open-bank-oss/issues/3914)

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.9.0...sepa-payment-v0.9.1) (2026-08-09)


### Bug Fixes

* **libs:** stamp ApiError.timestamp at construction instead of serving 1970 ([#3880](https://github.com/JiRaska/open-bank-oss/issues/3880)) ([b3e6672](https://github.com/JiRaska/open-bank-oss/commit/b3e6672c9e13470fc6353ad8a5483e4075875b1f))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.8.9...sepa-payment-v0.9.0) (2026-08-08)


### Features

* **sepa-payment:** add customer-facing payment confirmation download endpoint ([#4131](https://github.com/JiRaska/open-bank-oss/issues/4131)) ([676a6f2](https://github.com/JiRaska/open-bank-oss/commit/676a6f2db8147dbb245497cfc6483bfc4983ec43))

## [0.8.9](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.8.8...sepa-payment-v0.8.9) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.8.8](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.8.7...sepa-payment-v0.8.8) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))

## [0.8.7](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.8.6...sepa-payment-v0.8.7) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.8.6](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.8.5...sepa-payment-v0.8.6) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.8.5](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.8.4...sepa-payment-v0.8.5) (2026-07-24)


### Bug Fixes

* **sepa:** invoke shadowFraudScore in the payment workflow (ADR-0120 Phase 6 prerequisite, [#1917](https://github.com/JiRaska/open-bank-oss/issues/1917)) ([#2068](https://github.com/JiRaska/open-bank-oss/issues/2068)) ([95f5ffc](https://github.com/JiRaska/open-bank-oss/commit/95f5ffcd9bd4e931d7d5725fe2e468a201b93c2d))
* **sepa:** make Temporal the sole orchestrator, retire the legacy in-service flow (ADR-0120 Phase 6, [#1917](https://github.com/JiRaska/open-bank-oss/issues/1917) — 2/2) ([#2110](https://github.com/JiRaska/open-bank-oss/issues/2110)) ([81f981a](https://github.com/JiRaska/open-bank-oss/commit/81f981adafc8a979dc149a437289baa404ccd84a)), closes [#2068](https://github.com/JiRaska/open-bank-oss/issues/2068)

## [0.8.4](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.8.3...sepa-payment-v0.8.4) (2026-07-17)


### Bug Fixes

* **sepa-payment:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1471](https://github.com/JiRaska/open-bank-oss/issues/1471)) ([566ac07](https://github.com/JiRaska/open-bank-oss/commit/566ac07dd7f359fbaccec37c3e82e805a3100de1))

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.8.2...sepa-payment-v0.8.3) (2026-07-14)


### Bug Fixes

* **sepa-payment:** correct invalid TransactionType in the transaction-service pact ([#937](https://github.com/JiRaska/open-bank-oss/issues/937)) ([ecfb4b5](https://github.com/JiRaska/open-bank-oss/commit/ecfb4b5dc98b4500552374c1059e5e022d841ac6))

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.8.1...sepa-payment-v0.8.2) (2026-07-12)


### Bug Fixes

* **sepa-payment:** send valueDate in the format transaction-service actually parses ([#844](https://github.com/JiRaska/open-bank-oss/issues/844)) ([c692624](https://github.com/JiRaska/open-bank-oss/commit/c692624f67b4b95f875ceb31e1376276faaf5eea))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.8.0...sepa-payment-v0.8.1) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.7.6...sepa-payment-v0.8.0) (2026-07-07)


### Features

* **libs:** add opt-in four-eyes enforcement, pilot on sepa-payment ([#408](https://github.com/JiRaska/open-bank-oss/issues/408)) ([e64bfaa](https://github.com/JiRaska/open-bank-oss/commit/e64bfaa174e17a299d3a1639c4e531f57ecc0152))


### Security

* **sepa-payment:** enforce OPA authorization on payment endpoints (ADR-0034 Phase 5) ([#394](https://github.com/JiRaska/open-bank-oss/issues/394)) ([d4e88ad](https://github.com/JiRaska/open-bank-oss/commit/d4e88adbd90b6e7656db7963fa1cb436be378697))

## [0.7.6](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.7.5...sepa-payment-v0.7.6) (2026-07-03)


### Bug Fixes

* **libs-domain:** carry interbank settlement date through to pacs.008 (IntrBkSttlmDt) ([#195](https://github.com/JiRaska/open-bank-oss/issues/195)) ([62eef5e](https://github.com/JiRaska/open-bank-oss/commit/62eef5ef21626a56099ccfe9ebc6f6e5387a85b6))

## [0.7.5](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.7.4...sepa-payment-v0.7.5) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.7.4](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.7.3...sepa-payment-v0.7.4) (2026-06-29)


### Security

* **kafka:** mTLS + ACLs for payment.scheme-accepted, no global gate flip (ADR-0137) ([#2602](https://github.com/JiRaska/open-bank-oss/issues/2602)) ([b143022](https://github.com/JiRaska/open-bank-oss/commit/b143022f6ab76c4ff817ddbd4467fc578b8ee193))

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.7.2...sepa-payment-v0.7.3) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([ce39af7](https://github.com/JiRaska/open-bank-oss/commit/ce39af7c5b02ff3fef226f34e78c7e726b149ebd))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([c61fcaf](https://github.com/JiRaska/open-bank-oss/commit/c61fcafde76be08f716c710462be70752073aba1))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.7.1...sepa-payment-v0.7.2) (2026-06-28)


### Bug Fixes

* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([35c6a63](https://github.com/JiRaska/open-bank-oss/commit/35c6a6309e10c0a56558d93de5c494a89da72ceb))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.7.0...sepa-payment-v0.7.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank-oss/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.6.0...sepa-payment-v0.7.0) (2026-06-25)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank-oss/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank-oss/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.5.1...sepa-payment-v0.6.0) (2026-06-25)


### Features

* **domestic-payment,sepa-payment:** inject Clock into SettlementAdapter and Kafka publisher (ADR-0100) ([#2064](https://github.com/JiRaska/open-bank-oss/issues/2064)) ([85d890e](https://github.com/JiRaska/open-bank-oss/commit/85d890e8bcf0c1e5d79b76134834cb19bf90ee84)), closes [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)


### Bug Fixes

* **balance,sepa-payment,fraud:** @Dependent scope on ClockProducer + inject Clock into fraud persistence (ADR-0100) ([#2081](https://github.com/JiRaska/open-bank-oss/issues/2081)) ([fc1a129](https://github.com/JiRaska/open-bank-oss/commit/fc1a129cbfee4b5db41dbf4334f3dbe9d5e621c8))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.5.0...sepa-payment-v0.5.1) (2026-06-25)


### Bug Fixes

* **sepa-payment:** inject Clock into domain/application layers (ADR-0100, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2009](https://github.com/JiRaska/open-bank-oss/issues/2009)) ([c2f7a0c](https://github.com/JiRaska/open-bank-oss/commit/c2f7a0c5904437df6d12b20b8c8a9538d1e29144))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.4.0...sepa-payment-v0.5.0) (2026-06-25)


### Features

* **sepa-payment:** handle inbound pacs.004 returns — PROCESSING→RETURNED + ledger reversal (ADR-0109) ([#1931](https://github.com/JiRaska/open-bank-oss/issues/1931)) ([7b80be7](https://github.com/JiRaska/open-bank-oss/commit/7b80be71ef0405b3d6ce482c1ef16e1bdf46259c))
* **sepa-payment:** SEPA SCT pilot — submit real pacs.008 to the scheme gateway (ADR-0104 D3) ([#1722](https://github.com/JiRaska/open-bank-oss/issues/1722)) ([a8ae6b9](https://github.com/JiRaska/open-bank-oss/commit/a8ae6b93a0c0b1a60c10edb4a9ae0b4a4abf85d4))
* **sepa-payment:** submit real pacs.008 to the scheme gateway (ADR-0104 D3) ([#1723](https://github.com/JiRaska/open-bank-oss/issues/1723)) ([855ca3d](https://github.com/JiRaska/open-bank-oss/commit/855ca3d973a284f24c0ef6a3996a3c98b7d880c2))
* **sepa-payment:** Temporal durable workflow — ADR-0101 P1 ([#1449](https://github.com/JiRaska/open-bank-oss/issues/1449)) ([f718066](https://github.com/JiRaska/open-bank-oss/commit/f718066c0a90ae62c6f9e36554917a198b1832fb))
* **sepa-payment:** trigger transaction-service settlement after scheme ACSC (ADR-0108) ([#1869](https://github.com/JiRaska/open-bank-oss/issues/1869)) ([a3d4925](https://github.com/JiRaska/open-bank-oss/commit/a3d492560b01259643f7be0543186a82a2011f07))
* **transaction,payment:** ADR-0103 D2 — stamp rail + instructionType at settlement (transaction-service + 4 rails) ([#1940](https://github.com/JiRaska/open-bank-oss/issues/1940)) ([785ca02](https://github.com/JiRaska/open-bank-oss/commit/785ca024d434c845dadade0190551fdd18da17a9))


### Bug Fixes

* **admin-ui:** governance data freshness — ADR-0082 renumber, MONEY_PATH fraud-service, STATIC_CANDIDATES ([f69ba3b](https://github.com/JiRaska/open-bank-oss/commit/f69ba3baaac7ab91b25a0c6a4f7945574f0f53b5))
* **gitops:** correct transform processor OTTL syntax — rum-gateway CrashLoopBackOff ([#1935](https://github.com/JiRaska/open-bank-oss/issues/1935)) ([10fecc2](https://github.com/JiRaska/open-bank-oss/commit/10fecc2ff6d8e6353ec14c4a9e1a0b344294f687))
* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank-oss/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank-oss/commit/92dc2d636d857b526c8276e2647de440c540577b))
* **sdd:** add sdd_outbox_seq to V2 migration + fix outbox IT Vert.x context ([fceadf4](https://github.com/JiRaska/open-bank-oss/commit/fceadf4a679eddc7cbde749839846cc83bf8d5d5)), closes [#1360](https://github.com/JiRaska/open-bank-oss/issues/1360)
* **sepa-payment:** attach OIDC bearer explicitly on scheme submission (ADR-0104 BUG [#3](https://github.com/JiRaska/open-bank-oss/issues/3)) ([#1779](https://github.com/JiRaska/open-bank-oss/issues/1779)) ([4bd05e6](https://github.com/JiRaska/open-bank-oss/commit/4bd05e661499cc1e7c1ddf3cb4819cb127aa014f))
* **sepa-payment:** configure the OIDC client so service tokens are minted (ADR-0104 D3 Bug [#3](https://github.com/JiRaska/open-bank-oss/issues/3)) ([#1782](https://github.com/JiRaska/open-bank-oss/issues/1782)) ([b0177d4](https://github.com/JiRaska/open-bank-oss/commit/b0177d46bebfa69f858d901306b997b5d3a74041))
* **sepa-payment:** fail closed on an unparseable pacs.002 (ADR-0104 D3) ([#1735](https://github.com/JiRaska/open-bank-oss/issues/1735)) ([50331a0](https://github.com/JiRaska/open-bank-oss/commit/50331a0de412f7f14cc31c15a0bc4194cf84511d))
* **sepa-payment:** remove blank line before closing brace (ktlint) ([#1362](https://github.com/JiRaska/open-bank-oss/issues/1362)) ([65a698d](https://github.com/JiRaska/open-bank-oss/commit/65a698d6a92e2772b4d21406cf97bed983f8cc8f))
* **sepa-payment:** run Temporal screening activities on a Vert.x context (ADR-0104 D3) ([#1738](https://github.com/JiRaska/open-bank-oss/issues/1738)) ([d4f787e](https://github.com/JiRaska/open-bank-oss/commit/d4f787eacacf8444c5b52d67ee8e740bee6eb361))
* **sepa-payment:** submit pacs.008 to the scheme from the Temporal workflow (ADR-0104 D3) ([#1774](https://github.com/JiRaska/open-bank-oss/issues/1774)) ([049cb95](https://github.com/JiRaska/open-bank-oss/commit/049cb95a9284bfa2476a1bbad1117f6ff3bafffc))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.3.0...sepa-payment-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **observability:** add DomainMetrics façade and wire into sepa-payment (ADR-0077 Phase 2) ([#677](https://github.com/JiRaska/open-bank-oss/issues/677)) ([7c09a12](https://github.com/JiRaska/open-bank-oss/commit/7c09a1216d7e04d84f461279ae141d9198a63ad1))
* **sepa-payment:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#812](https://github.com/JiRaska/open-bank-oss/issues/812)) ([0957f5d](https://github.com/JiRaska/open-bank-oss/commit/0957f5d89dfb1a024de5a63f364b1f8849602d92))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.2.0...sepa-payment-v0.3.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-payment-v0.1.2...sepa-payment-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **payments:** remove dead incoming Kafka channels — no @Incoming consumer ([#377](https://github.com/JiRaska/open-bank-oss/issues/377)) ([5fbdda7](https://github.com/JiRaska/open-bank-oss/commit/5fbdda796d4201b9ef1f57d41c76a00b18a5216b))
* **payments:** use property expression in Kafka channel bootstrap.servers ([#373](https://github.com/JiRaska/open-bank-oss/issues/373)) ([32507ee](https://github.com/JiRaska/open-bank-oss/commit/32507eeda72bec17c92f85169d72759ed02f1c4a))
