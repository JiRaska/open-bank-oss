# Changelog

## [0.19.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.18.0...interest-service-v0.19.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.18.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.17.2...interest-service-v0.18.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.17.2](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.17.1...interest-service-v0.17.2) (2026-08-22)


### Bug Fixes

* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.17.1](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.17.0...interest-service-v0.17.1) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.17.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.16.0...interest-service-v0.17.0) (2026-08-20)


### Features

* **product-catalog:** govern downstream product terms ([#5841](https://github.com/JiRaska/open-bank-oss/issues/5841)) ([932d639](https://github.com/JiRaska/open-bank-oss/commit/932d63921fb3b8a8c63741deaeb4214a6e8fa142))

## [0.16.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.15.1...interest-service-v0.16.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.15.1](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.15.0...interest-service-v0.15.1) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.14.7...interest-service-v0.15.0) (2026-08-07)


### Features

* **interest:** register workflow liveness on accrual and capitalization schedulers (ADR-0237) ([#3705](https://github.com/JiRaska/open-bank-oss/issues/3705)) ([d5f47b9](https://github.com/JiRaska/open-bank-oss/commit/d5f47b910bac985bed0a215a361bc410340d6585))

## [0.14.7](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.14.6...interest-service-v0.14.7) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.14.6](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.14.5...interest-service-v0.14.6) (2026-08-01)


### Bug Fixes

* **api:** stop reporting client errors as 500 — unmapped DateTimeException and catch-all recovers ([#3038](https://github.com/JiRaska/open-bank-oss/issues/3038)) ([#3057](https://github.com/JiRaska/open-bank-oss/issues/3057)) ([fafe119](https://github.com/JiRaska/open-bank-oss/commit/fafe1194c56224c98de40be8f4e9dcba018c2f91))

## [0.14.5](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.14.4...interest-service-v0.14.5) (2026-08-01)


### Bug Fixes

* **money-path:** a null JSON body returned 500 on 12 handlers ([#3038](https://github.com/JiRaska/open-bank-oss/issues/3038)) ([#3050](https://github.com/JiRaska/open-bank-oss/issues/3050)) ([7af4d19](https://github.com/JiRaska/open-bank-oss/commit/7af4d19aac4a0d75e221fbc64a1a24196e61ce8f))

## [0.14.4](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.14.3...interest-service-v0.14.4) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.14.3](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.14.2...interest-service-v0.14.3) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.14.2](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.14.1...interest-service-v0.14.2) (2026-07-24)


### Bug Fixes

* **interest:** freeze the tax profile at claim time so a capitalize() retry can't diverge row-vs-GL ([#2043](https://github.com/JiRaska/open-bank-oss/issues/2043)) ([ae03f3a](https://github.com/JiRaska/open-bank-oss/commit/ae03f3a728ae01fd9b5e4a1ba5c6eace25251a90)), closes [#1355](https://github.com/JiRaska/open-bank-oss/issues/1355) [#1316](https://github.com/JiRaska/open-bank-oss/issues/1316)

## [0.14.1](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.14.0...interest-service-v0.14.1) (2026-07-24)


### Bug Fixes

* **interest:** bind currency to the rate config so a mixed-currency accrual set can't wedge capitalize ([#2037](https://github.com/JiRaska/open-bank-oss/issues/2037)) ([72ebcf2](https://github.com/JiRaska/open-bank-oss/commit/72ebcf22a1c533e746aa5d325c1127ccad089892)), closes [#1265](https://github.com/JiRaska/open-bank-oss/issues/1265)

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.13.0...interest-service-v0.14.0) (2026-07-23)


### Features

* **interest:** run monthly capitalization so withholding is assembled ([#999](https://github.com/JiRaska/open-bank-oss/issues/999)) ([#1900](https://github.com/JiRaska/open-bank-oss/issues/1900)) ([a438d8d](https://github.com/JiRaska/open-bank-oss/commit/a438d8dd45b9daae1e718910acf754df03014828))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.12.0...interest-service-v0.13.0) (2026-07-18)


### Features

* **interest:** per-account rate override; CURRENT defaults to zero interest ([#1618](https://github.com/JiRaska/open-bank-oss/issues/1618)) ([#1645](https://github.com/JiRaska/open-bank-oss/issues/1645)) ([743090b](https://github.com/JiRaska/open-bank-oss/commit/743090b38ab59a27becd05ff3665045c18c2f793))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.11.0...interest-service-v0.12.0) (2026-07-18)


### Features

* **interest:** make the CURRENT account interest-bearing too, not just SAVINGS ([#1618](https://github.com/JiRaska/open-bank-oss/issues/1618)) ([#1642](https://github.com/JiRaska/open-bank-oss/issues/1642)) ([9ea756c](https://github.com/JiRaska/open-bank-oss/commit/9ea756c6d832cd181d2d4cd116b89e6eeb3536dd))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.10.1...interest-service-v0.11.0) (2026-07-18)


### Features

* **interest:** wire the daily accrual engine (accrueAll was a stub) + seed savings rate ([#1614](https://github.com/JiRaska/open-bank-oss/issues/1614)) ([06e361d](https://github.com/JiRaska/open-bank-oss/commit/06e361d981c9b780cd1c1a50bda3b8ed65f4f42e))

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.10.0...interest-service-v0.10.1) (2026-07-17)


### Bug Fixes

* **interest:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1468](https://github.com/JiRaska/open-bank-oss/issues/1468)) ([2b75d68](https://github.com/JiRaska/open-bank-oss/commit/2b75d68f2c72a38d6679836d4b0b4f2aac67de75))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.9.1...interest-service-v0.10.0) (2026-07-17)


### Features

* **interest:** post the ADR-0033 split so capitalization actually credits the customer ([#1316](https://github.com/JiRaska/open-bank-oss/issues/1316)) ([b2ae411](https://github.com/JiRaska/open-bank-oss/commit/b2ae4117f6995b63f16e53a3004fdabee4ed223d))

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.9.0...interest-service-v0.9.1) (2026-07-16)


### Bug Fixes

* **interest:** settle a zero-tax remittance batch instead of wedging it ([#1264](https://github.com/JiRaska/open-bank-oss/issues/1264)) ([8d64381](https://github.com/JiRaska/open-bank-oss/commit/8d64381218c271d325d528950c300ac1168d3a8c))
* **interest:** settle withholding remittance, make capitalization atomic and per-product ([#1246](https://github.com/JiRaska/open-bank-oss/issues/1246)) ([a798e8b](https://github.com/JiRaska/open-bank-oss/commit/a798e8bbe909dbb8f06c75f249cf9cd4fe4c0ca4)), closes [#999](https://github.com/JiRaska/open-bank-oss/issues/999)

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.8.0...interest-service-v0.9.0) (2026-07-14)


### Features

* **interest:** book the withholding-tax remittance cash leg to the finanční úřad ([#999](https://github.com/JiRaska/open-bank-oss/issues/999)) ([#1039](https://github.com/JiRaska/open-bank-oss/issues/1039)) ([4417e2b](https://github.com/JiRaska/open-bank-oss/commit/4417e2bd636661eb40485eea0457fa7ead7afbc1))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.7.1...interest-service-v0.8.0) (2026-07-13)


### Features

* **governance:** bootstrap OPA enforcement for anacredit/card-issuance/interest ([#938](https://github.com/JiRaska/open-bank-oss/issues/938)) ([#962](https://github.com/JiRaska/open-bank-oss/issues/962)) ([8a35e3a](https://github.com/JiRaska/open-bank-oss/commit/8a35e3adfd4202339209aa67237082475dc7018d))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.7.0...interest-service-v0.7.1) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.6.4...interest-service-v0.7.0) (2026-07-06)


### Features

* **interest:** scale-to-zero to T1 via HTTPScaledObject (ADR-0057) ([#248](https://github.com/JiRaska/open-bank-oss/issues/248)) ([f06c908](https://github.com/JiRaska/open-bank-oss/commit/f06c90847ee5777d66e0c1652a77c9c88cdc4d48)), closes [#230](https://github.com/JiRaska/open-bank-oss/issues/230)

## [0.6.4](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.6.3...interest-service-v0.6.4) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.6.3](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.6.2...interest-service-v0.6.3) (2026-06-30)


### Security

* **interest:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2759](https://github.com/JiRaska/open-bank-oss/issues/2759)) ([9abc058](https://github.com/JiRaska/open-bank-oss/commit/9abc058f9a7f2309df5d633057c87feb9f798ef9))

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.6.1...interest-service-v0.6.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.6.0...interest-service-v0.6.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **interest,dispute,lending:** complete Clock injection (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2136](https://github.com/JiRaska/open-bank-oss/issues/2136)) ([41a2921](https://github.com/JiRaska/open-bank-oss/commit/41a2921b9b89cc06025cc71a4b428cb019fb499f))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.5.0...interest-service-v0.6.0) (2026-06-25)


### Features

* **interest:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2079](https://github.com/JiRaska/open-bank-oss/issues/2079)) ([1b2e909](https://github.com/JiRaska/open-bank-oss/commit/1b2e9091086f032e60b06d4d20915f394e17738f))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.4.0...interest-service-v0.5.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **interest:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#802](https://github.com/JiRaska/open-bank-oss/issues/802)) ([eaef7f3](https://github.com/JiRaska/open-bank-oss/commit/eaef7f3b0cc180995d03e915d13abd6545caafc5))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.3.0...interest-service-v0.4.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/interest-service-v0.2.2...interest-service-v0.3.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
