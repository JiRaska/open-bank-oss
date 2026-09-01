# Changelog

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.9.0...sepa-instant-v0.10.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.8.1...sepa-instant-v0.9.0) (2026-08-21)


### Features

* **sepa-instant:** expose pending four-eyes approvals via approval inbox ([#5694](https://github.com/JiRaska/open-bank-oss/issues/5694)) ([89c5631](https://github.com/JiRaska/open-bank-oss/commit/89c5631f9b07a4ea63383979e5ad950e1a337deb))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.8.0...sepa-instant-v0.8.1) (2026-08-18)


### Bug Fixes

* **sepa-instant:** add sourceService to published events for audit attribution ([#5389](https://github.com/JiRaska/open-bank-oss/issues/5389)) ([6069ce0](https://github.com/JiRaska/open-bank-oss/commit/6069ce0fd301e4aba5d36ad1fd418e2d027225b5))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.7.7...sepa-instant-v0.8.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))

## [0.7.7](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.7.6...sepa-instant-v0.7.7) (2026-08-17)


### Bug Fixes

* **domestic-payment:** make a synthetic fraud verdict distinguishable from a real one ([#4221](https://github.com/JiRaska/open-bank-oss/issues/4221) layers 2+3) ([#4411](https://github.com/JiRaska/open-bank-oss/issues/4411)) ([6265ea8](https://github.com/JiRaska/open-bank-oss/commit/6265ea869275f6722b937860f5dcd03d3674d5d7))
* **sepa-instant:** emit paymentProcessingDuration on terminal transitions ([#5213](https://github.com/JiRaska/open-bank-oss/issues/5213)) ([6c04bec](https://github.com/JiRaska/open-bank-oss/commit/6c04bec1bfaf5b35cbeff011a8a62c8d5423add4)), closes [#5049](https://github.com/JiRaska/open-bank-oss/issues/5049)

## [0.7.6](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.7.5...sepa-instant-v0.7.6) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.7.5](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.7.4...sepa-instant-v0.7.5) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))
* **money-path:** a null JSON body returned 500 on 12 handlers ([#3038](https://github.com/JiRaska/open-bank-oss/issues/3038)) ([#3050](https://github.com/JiRaska/open-bank-oss/issues/3050)) ([7af4d19](https://github.com/JiRaska/open-bank-oss/commit/7af4d19aac4a0d75e221fbc64a1a24196e61ce8f))

## [0.7.4](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.7.3...sepa-instant-v0.7.4) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.7.2...sepa-instant-v0.7.3) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.7.1...sepa-instant-v0.7.2) (2026-07-17)


### Bug Fixes

* **sepa-instant:** remove dead SctInstOutboxPort pipeline ([#1364](https://github.com/JiRaska/open-bank-oss/issues/1364)) ([7f65116](https://github.com/JiRaska/open-bank-oss/commit/7f651163f377636b8400d8c43b822231818eeac7)), closes [#1034](https://github.com/JiRaska/open-bank-oss/issues/1034)

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.7.0...sepa-instant-v0.7.1) (2026-07-12)


### Bug Fixes

* **sepa-instant:** valueDate format bug + add transaction-service pact coverage ([#468](https://github.com/JiRaska/open-bank-oss/issues/468)) ([#840](https://github.com/JiRaska/open-bank-oss/issues/840)) ([96ab862](https://github.com/JiRaska/open-bank-oss/commit/96ab862846f734ce521c01d2b93ca8f6db0cbcf3))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.6.6...sepa-instant-v0.7.0) (2026-07-08)


### Features

* **sepa-instant:** wire four-eyes enforcement mechanism (ADR-0155) ([#561](https://github.com/JiRaska/open-bank-oss/issues/561)) ([5889388](https://github.com/JiRaska/open-bank-oss/commit/58893884f12a9eb0c17a431caa24acb0408148dd)), closes [#413](https://github.com/JiRaska/open-bank-oss/issues/413)

## [0.6.6](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.6.5...sepa-instant-v0.6.6) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.6.5](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.6.4...sepa-instant-v0.6.5) (2026-07-07)


### Security

* **sepa-instant:** enforce OPA authorization on payment endpoints (ADR-0034 Phase 5) ([#410](https://github.com/JiRaska/open-bank-oss/issues/410)) ([a207575](https://github.com/JiRaska/open-bank-oss/commit/a2075759a8f1326a30aaf18a6a0bb72dc18e1087)), closes [#266](https://github.com/JiRaska/open-bank-oss/issues/266)

## [0.6.4](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.6.3...sepa-instant-v0.6.4) (2026-07-03)


### Bug Fixes

* **libs-domain:** carry interbank settlement date through to pacs.008 (IntrBkSttlmDt) ([#195](https://github.com/JiRaska/open-bank-oss/issues/195)) ([62eef5e](https://github.com/JiRaska/open-bank-oss/commit/62eef5ef21626a56099ccfe9ebc6f6e5387a85b6))

## [0.6.3](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.6.2...sepa-instant-v0.6.3) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.6.1...sepa-instant-v0.6.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)


### Security

* **kafka:** mTLS + ACLs for payment.scheme-accepted, no global gate flip (ADR-0137) ([#2602](https://github.com/JiRaska/open-bank-oss/issues/2602)) ([b143022](https://github.com/JiRaska/open-bank-oss/commit/b143022f6ab76c4ff817ddbd4467fc578b8ee193))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.6.0...sepa-instant-v0.6.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **sepa-instant,balance,audit,security-scanner,copilot,customer-edge,sca:** inject Clock via CDI (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2145](https://github.com/JiRaska/open-bank-oss/issues/2145)) ([d680007](https://github.com/JiRaska/open-bank-oss/commit/d68000775625cc423c95d8a27db29ff25a708f9f))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.5.0...sepa-instant-v0.6.0) (2026-06-25)


### Features

* **sepa-instant:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2087](https://github.com/JiRaska/open-bank-oss/issues/2087)) ([7db610a](https://github.com/JiRaska/open-bank-oss/commit/7db610a70e1b74b6b64dac09760c1160acdeb57b)), closes [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.4.0...sepa-instant-v0.5.0) (2026-06-25)


### Features

* **domestic-payment,swift,libs:** ADR-0104 D4 — SchemeGateway fan-out to domestic & swift rails ([331e7dd](https://github.com/JiRaska/open-bank-oss/commit/331e7ddb148c021d951521f570cc39c75aec5a3c))
* **sepa-instant:** submit real pacs.008 to the scheme gateway (ADR-0104 D4) ([#1732](https://github.com/JiRaska/open-bank-oss/issues/1732)) ([c4f96d0](https://github.com/JiRaska/open-bank-oss/commit/c4f96d08b29bc450bcfb06c644c4f73e845db4ef))
* **sepa-instant:** trigger transaction-service settlement after scheme ACSC (ADR-0108) ([#1923](https://github.com/JiRaska/open-bank-oss/issues/1923)) ([58cdfd0](https://github.com/JiRaska/open-bank-oss/commit/58cdfd0b738d94edf6c1586044f30c44cab50b24))
* **transaction,payment:** ADR-0103 D2 — stamp rail + instructionType at settlement (transaction-service + 4 rails) ([#1940](https://github.com/JiRaska/open-bank-oss/issues/1940)) ([785ca02](https://github.com/JiRaska/open-bank-oss/commit/785ca024d434c845dadade0190551fdd18da17a9))


### Bug Fixes

* **sepa-instant:** configure the OIDC client so service tokens are minted (ADR-0104 D3) ([#1823](https://github.com/JiRaska/open-bank-oss/issues/1823)) ([0c85ffb](https://github.com/JiRaska/open-bank-oss/commit/0c85ffb5387310b04c7523a5df6a1b633aebcecb))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.3.0...sepa-instant-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **sepa-instant:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#815](https://github.com/JiRaska/open-bank-oss/issues/815)) ([0d16e7b](https://github.com/JiRaska/open-bank-oss/commit/0d16e7ba83ec42d27ec83746917ca99bf0f2112f))
* **sepa-instant:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#685](https://github.com/JiRaska/open-bank-oss/issues/685)) ([de3124e](https://github.com/JiRaska/open-bank-oss/commit/de3124eb125755c33a35d21c8bdee3208b539c69))


### Bug Fixes

* **sepa-instant:** add @RolesAllowed guards to SCT Inst endpoints ([#785](https://github.com/JiRaska/open-bank-oss/issues/785)) ([57e6ec0](https://github.com/JiRaska/open-bank-oss/commit/57e6ec0fe7efcfd1c17bd54ca80790622b4bb6ae))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.2.0...sepa-instant-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/sepa-instant-v0.1.0...sepa-instant-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **payments:** remove dead incoming Kafka channels — no @Incoming consumer ([#377](https://github.com/JiRaska/open-bank-oss/issues/377)) ([5fbdda7](https://github.com/JiRaska/open-bank-oss/commit/5fbdda796d4201b9ef1f57d41c76a00b18a5216b))
* **payments:** use property expression in Kafka channel bootstrap.servers ([#373](https://github.com/JiRaska/open-bank-oss/issues/373)) ([32507ee](https://github.com/JiRaska/open-bank-oss/commit/32507eeda72bec17c92f85169d72759ed02f1c4a))
