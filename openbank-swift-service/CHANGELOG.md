# Changelog

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.10.0...swift-service-v0.11.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.9.0...swift-service-v0.10.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.8.0...swift-service-v0.9.0) (2026-08-19)


### Features

* **swift:** expose pending four-eyes approvals via approval inbox ([#5679](https://github.com/JiRaska/open-bank-oss/issues/5679)) ([#5696](https://github.com/JiRaska/open-bank-oss/issues/5696)) ([9824471](https://github.com/JiRaska/open-bank-oss/commit/98244714c936eae8f713cbe6c3b462dc19e5b573))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.7.6...swift-service-v0.8.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.7.6](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.7.5...swift-service-v0.7.6) (2026-08-18)


### Bug Fixes

* **swift:** add sourceService for AuditConsumer attribution ([#5349](https://github.com/JiRaska/open-bank-oss/issues/5349)) ([2c6975c](https://github.com/JiRaska/open-bank-oss/commit/2c6975c09a13c8a5d3454fc6ef5ecf277f30000a)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [0.7.5](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.7.4...swift-service-v0.7.5) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.7.4](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.7.3...swift-service-v0.7.4) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.7.2...swift-service-v0.7.3) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))
* **money-path:** a null JSON body returned 500 on 12 handlers ([#3038](https://github.com/JiRaska/open-bank-oss/issues/3038)) ([#3050](https://github.com/JiRaska/open-bank-oss/issues/3050)) ([7af4d19](https://github.com/JiRaska/open-bank-oss/commit/7af4d19aac4a0d75e221fbc64a1a24196e61ce8f))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.7.1...swift-service-v0.7.2) (2026-07-17)


### Bug Fixes

* **swift:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1557](https://github.com/JiRaska/open-bank-oss/issues/1557)) ([a3bc10e](https://github.com/JiRaska/open-bank-oss/commit/a3bc10e8323ac627a94d3895b7ea272104965a26))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.7.0...swift-service-v0.7.1) (2026-07-12)


### Bug Fixes

* **swift:** fail-closed crash bug + add clearing-simulator pact coverage ([#468](https://github.com/JiRaska/open-bank-oss/issues/468)) ([#871](https://github.com/JiRaska/open-bank-oss/issues/871)) ([175c693](https://github.com/JiRaska/open-bank-oss/commit/175c69307a53da7eeac2639ee6ccff7fa5071a4e))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.6.9...swift-service-v0.7.0) (2026-07-08)


### Features

* **swift:** wire four-eyes enforcement mechanism (ADR-0155) ([#564](https://github.com/JiRaska/open-bank-oss/issues/564)) ([b295685](https://github.com/JiRaska/open-bank-oss/commit/b295685ff4af639fd14e1de76f7057ddae6e6f60)), closes [#413](https://github.com/JiRaska/open-bank-oss/issues/413)


### Security

* **swift:** add missing @RolesAllowed guard on the send endpoint ([#568](https://github.com/JiRaska/open-bank-oss/issues/568)) ([e0eb197](https://github.com/JiRaska/open-bank-oss/commit/e0eb1978406d6a568e206e944b212e61ba9a7043))

## [0.6.9](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.6.8...swift-service-v0.6.9) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.6.8](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.6.7...swift-service-v0.6.8) (2026-07-07)


### Security

* **swift:** enforce OPA authorization on SWIFT endpoints (ADR-0034 Phase 5) ([#418](https://github.com/JiRaska/open-bank-oss/issues/418)) ([cf4f060](https://github.com/JiRaska/open-bank-oss/commit/cf4f06089ec728020ad8db545b8b1a78c26066e8))

## [0.6.7](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.6.6...swift-service-v0.6.7) (2026-07-03)


### Bug Fixes

* **libs-domain:** carry interbank settlement date through to pacs.008 (IntrBkSttlmDt) ([#195](https://github.com/JiRaska/open-bank-oss/issues/195)) ([62eef5e](https://github.com/JiRaska/open-bank-oss/commit/62eef5ef21626a56099ccfe9ebc6f6e5387a85b6))

## [0.6.6](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.6.5...swift-service-v0.6.6) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.6.5](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.6.4...swift-service-v0.6.5) (2026-06-29)


### Security

* **kafka:** mTLS + ACLs for payment.scheme-accepted, no global gate flip (ADR-0137) ([#2602](https://github.com/JiRaska/open-bank-oss/issues/2602)) ([b143022](https://github.com/JiRaska/open-bank-oss/commit/b143022f6ab76c4ff817ddbd4467fc578b8ee193))

## [0.6.4](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.6.3...swift-service-v0.6.4) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
* **swift:** disable Quarkus JUnit FacadeClassLoader in CI ([#2404](https://github.com/JiRaska/open-bank-oss/issues/2404)) ([#2506](https://github.com/JiRaska/open-bank-oss/issues/2506)) ([7a6294e](https://github.com/JiRaska/open-bank-oss/commit/7a6294e2dd1dd0470d5d3468dc170b7299089d6b))
* **swift:** stop fleet-CI hang — CI-skip boot smoke + disable Kafka devservices ([#2415](https://github.com/JiRaska/open-bank-oss/issues/2415)) ([fb7127e](https://github.com/JiRaska/open-bank-oss/commit/fb7127e456a49f11bdc0b276e9293c4e3fc1c13e))

## [0.6.3](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.6.2...swift-service-v0.6.3) (2026-06-29)


### Bug Fixes

* **swift:** disable Quarkus JUnit FacadeClassLoader in CI ([#2404](https://github.com/JiRaska/open-bank-oss/issues/2404)) ([#2506](https://github.com/JiRaska/open-bank-oss/issues/2506)) ([573cf7b](https://github.com/JiRaska/open-bank-oss/commit/573cf7ba86f619269b53f23567cc21b3a776d358))

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.6.1...swift-service-v0.6.2) (2026-06-29)


### Bug Fixes

* **swift:** stop fleet-CI hang — CI-skip boot smoke + disable Kafka devservices ([#2415](https://github.com/JiRaska/open-bank-oss/issues/2415)) ([6eeb8c9](https://github.com/JiRaska/open-bank-oss/commit/6eeb8c9ba802bee975ad454ac40f50f35d2bff37))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.6.0...swift-service-v0.6.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.5.0...swift-service-v0.6.0) (2026-06-25)


### Features

* **swift:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2095](https://github.com/JiRaska/open-bank-oss/issues/2095)) ([192d157](https://github.com/JiRaska/open-bank-oss/commit/192d1573c4b3e9bbeb7ad58f90554dddecf68dfe))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.4.0...swift-service-v0.5.0) (2026-06-25)


### Features

* **domestic-payment,swift,libs:** ADR-0104 D4 — SchemeGateway fan-out to domestic & swift rails ([331e7dd](https://github.com/JiRaska/open-bank-oss/commit/331e7ddb148c021d951521f570cc39c75aec5a3c))
* **fraud,swift:** add Pact consumer contracts for transaction + SWIFT events (ADR-0092, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2024](https://github.com/JiRaska/open-bank-oss/issues/2024)) ([2cb470b](https://github.com/JiRaska/open-bank-oss/commit/2cb470bb18db463d9707ca85ed3913ef9c03712e))
* **swift-service:** trigger transaction-service settlement after scheme ACSC (ADR-0108) ([#1872](https://github.com/JiRaska/open-bank-oss/issues/1872)) ([d3fcf2c](https://github.com/JiRaska/open-bank-oss/commit/d3fcf2c18b8771f105481016f4ea241d01aea4ab))
* **swift,transaction:** add Pact provider verification for message contracts (ADR-0092) ([#2063](https://github.com/JiRaska/open-bank-oss/issues/2063)) ([9d0ead6](https://github.com/JiRaska/open-bank-oss/commit/9d0ead608fb576b78cd17f93da5c35232f328d64))
* **transaction,payment:** ADR-0103 D2 — stamp rail + instructionType at settlement (transaction-service + 4 rails) ([#1940](https://github.com/JiRaska/open-bank-oss/issues/1940)) ([785ca02](https://github.com/JiRaska/open-bank-oss/commit/785ca024d434c845dadade0190551fdd18da17a9))


### Bug Fixes

* **swift-service,domestic-payment,sepa-instant:** ADR-0104 D4 remaining — port extraction, repo fix, tests, threat models ([fbce147](https://github.com/JiRaska/open-bank-oss/commit/fbce1475004a90d816aeadf5f049783ffc086e04))
* **swift-service:** add @Version optimistic lock to SwiftMessageEntity (issue [#1833](https://github.com/JiRaska/open-bank-oss/issues/1833)) ([a023932](https://github.com/JiRaska/open-bank-oss/commit/a023932abd351e5150a501826e3d05e4862c885e))
* **swift-service:** migrate value_date to date type and validate YYYYMMDD format ([#1857](https://github.com/JiRaska/open-bank-oss/issues/1857)) ([e4549e3](https://github.com/JiRaska/open-bank-oss/commit/e4549e33b675b91f451e1204409e5a767d4ea291))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.3.0...swift-service-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **swift:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#808](https://github.com/JiRaska/open-bank-oss/issues/808)) ([d3e5a26](https://github.com/JiRaska/open-bank-oss/commit/d3e5a2675e3a23a5e76f43bec54cbcc011940ddf))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.2.0...swift-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/swift-service-v0.1.0...swift-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
