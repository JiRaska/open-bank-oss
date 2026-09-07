# Changelog

## [1.17.1](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.17.0...balance-service-v1.17.1) (2026-09-07)


### Bug Fixes

* **balance,ledger:** fuzz-found 500s on date query params become 400/default ([#8835](https://github.com/JiRaska/open-bank-oss/issues/8835)) ([062c26a](https://github.com/JiRaska/open-bank-oss/commit/062c26afd615d4c973a51bfd4920618ceb5401f4))
* **balance:** write balance events to the outbox, retire the direct emitter ([#8510](https://github.com/JiRaska/open-bank-oss/issues/8510)) ([#8688](https://github.com/JiRaska/open-bank-oss/issues/8688)) ([82f0be3](https://github.com/JiRaska/open-bank-oss/commit/82f0be3409cb9cc82cfd0fd1fe2130562624c9e5))
* **kafka:** resolve the 11 baselined auto.offset.reset config lies ([#8370](https://github.com/JiRaska/open-bank-oss/issues/8370)) ([#8860](https://github.com/JiRaska/open-bank-oss/issues/8860)) ([f328ebd](https://github.com/JiRaska/open-bank-oss/commit/f328ebdf265f2dd3dd90ad3db3d2a052eb657923))

## [1.17.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.16.0...balance-service-v1.17.0) (2026-09-01)


### Features

* **balance:** expose pending approvals in unified inbox ([#7031](https://github.com/JiRaska/open-bank-oss/issues/7031)) ([541a03d](https://github.com/JiRaska/open-bank-oss/commit/541a03d739528a503725f03020e75fbd67bd5058))

## [1.16.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.15.0...balance-service-v1.16.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [1.15.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.14.3...balance-service-v1.15.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [1.14.3](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.14.2...balance-service-v1.14.3) (2026-08-22)


### Bug Fixes

* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [1.14.2](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.14.1...balance-service-v1.14.2) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [1.14.1](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.14.0...balance-service-v1.14.1) (2026-08-18)


### Bug Fixes

* **balance:** add sourceService to BalanceEvent for audit attribution ([#5374](https://github.com/JiRaska/open-bank-oss/issues/5374)) ([29683d8](https://github.com/JiRaska/open-bank-oss/commit/29683d829ebd18402495e5b31f84969e0a18e572))

## [1.14.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.13.3...balance-service-v1.14.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [1.13.3](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.13.2...balance-service-v1.13.3) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [1.13.2](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.13.1...balance-service-v1.13.2) (2026-08-10)


### Bug Fixes

* **audit:** give the unattributed producers a real actor, and a way to say nobody did it ([#4424](https://github.com/JiRaska/open-bank-oss/issues/4424)) ([0cadda3](https://github.com/JiRaska/open-bank-oss/commit/0cadda3725280119a86dba722efa692e1f783fc9))

## [1.13.1](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.13.0...balance-service-v1.13.1) (2026-08-09)


### Bug Fixes

* **balance:** stop a not-yet-effective credit being spendable before its value date ([#3916](https://github.com/JiRaska/open-bank-oss/issues/3916)) ([67ef850](https://github.com/JiRaska/open-bank-oss/commit/67ef8503ae469fd2fc95a97174b2f36ae1dba000))

## [1.13.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.12.3...balance-service-v1.13.0) (2026-08-07)


### Features

* **balance:** register workflow liveness on the reconciliation schedulers (ADR-0237) ([#3703](https://github.com/JiRaska/open-bank-oss/issues/3703)) ([ba57849](https://github.com/JiRaska/open-bank-oss/commit/ba5784998288dea5f665b97546aeafb081859ac0))

## [1.12.3](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.12.2...balance-service-v1.12.3) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [1.12.2](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.12.1...balance-service-v1.12.2) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))
* **money-path:** a null JSON body returned 500 on 12 handlers ([#3038](https://github.com/JiRaska/open-bank-oss/issues/3038)) ([#3050](https://github.com/JiRaska/open-bank-oss/issues/3050)) ([7af4d19](https://github.com/JiRaska/open-bank-oss/commit/7af4d19aac4a0d75e221fbc64a1a24196e61ce8f))

## [1.12.1](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.12.0...balance-service-v1.12.1) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [1.12.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.11.3...balance-service-v1.12.0) (2026-07-25)


### Features

* **balance:** surface the future-value-dated pipeline on the reconciliation report (ADR-0178 Phase 3) ([#2175](https://github.com/JiRaska/open-bank-oss/issues/2175)) ([0a6610e](https://github.com/JiRaska/open-bank-oss/commit/0a6610e99ff5753b1dc1a5e7629d28ba608a0154))

## [1.11.3](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.11.2...balance-service-v1.11.3) (2026-07-19)


### Bug Fixes

* **balance:** reconcile the sub-ledger on the ledger's value-date basis ([#1747](https://github.com/JiRaska/open-bank-oss/issues/1747)) ([d2423c5](https://github.com/JiRaska/open-bank-oss/commit/d2423c5d949fe546a1a2a85052dd65a737055f56))

## [1.11.2](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.11.1...balance-service-v1.11.2) (2026-07-18)


### Bug Fixes

* **balance:** cross-pod exclusion for the daily reconciliation + freshness watchdog ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1601](https://github.com/JiRaska/open-bank-oss/issues/1601)) ([aa0aa97](https://github.com/JiRaska/open-bank-oss/commit/aa0aa97e713ba0ec968e6a1fe4de79cc0cf6cf13))

## [1.11.1](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.11.0...balance-service-v1.11.1) (2026-07-17)


### Bug Fixes

* **balance:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1496](https://github.com/JiRaska/open-bank-oss/issues/1496)) ([84a8831](https://github.com/JiRaska/open-bank-oss/commit/84a8831668e30716f797b76c1f9a2dfd91f0aaec))

## [1.11.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.10.1...balance-service-v1.11.0) (2026-07-14)


### Features

* **balance,libs-runtime:** reconciliation drift-SLA via Prometheus (ADR-0160 m4) ([#1025](https://github.com/JiRaska/open-bank-oss/issues/1025)) ([b115581](https://github.com/JiRaska/open-bank-oss/commit/b1155819d4f9b31e100a8796ab55b3c3d5b1826e))
* **balance:** watchdog alerts when the daily EoD tie-out goes stale/absent (step 2 of [#855](https://github.com/JiRaska/open-bank-oss/issues/855)) ([#863](https://github.com/JiRaska/open-bank-oss/issues/863)) ([84f54ec](https://github.com/JiRaska/open-bank-oss/commit/84f54ec1766e6c59acb5be13c56ea74ac36cd0a5))
* **balance:** wire four-eyes enforcement mechanism (ADR-0155) ([#930](https://github.com/JiRaska/open-bank-oss/issues/930)) ([7825026](https://github.com/JiRaska/open-bank-oss/commit/7825026b997e5239b750ec160b02fffaafbb2dc8))


### Bug Fixes

* **balance:** bridge Pact @State handlers onto a duplicated Vert.x context ([#858](https://github.com/JiRaska/open-bank-oss/issues/858)) ([79938a1](https://github.com/JiRaska/open-bank-oss/commit/79938a1302c5563aaaea8de537726ecc988b6fba))
* **balance:** create missing balance_reconciliation_seq (daily tie-out never persisted) ([#856](https://github.com/JiRaska/open-bank-oss/issues/856)) ([9ef4905](https://github.com/JiRaska/open-bank-oss/commit/9ef4905fbc77f1bf6db823c4e2ea96aaa1421560))
* **balance:** V10 repair the booked-change replay double-count ([#939](https://github.com/JiRaska/open-bank-oss/issues/939)) ([#947](https://github.com/JiRaska/open-bank-oss/issues/947)) ([ef89022](https://github.com/JiRaska/open-bank-oss/commit/ef89022f2ebf75d08f46428a97b26045d6ecf986))

## [1.10.1](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.10.0...balance-service-v1.10.1) (2026-07-12)


### Bug Fixes

* **balance:** V10 repair the booked-change replay double-count ([#939](https://github.com/JiRaska/open-bank-oss/issues/939)) ([#947](https://github.com/JiRaska/open-bank-oss/issues/947)) ([ef89022](https://github.com/JiRaska/open-bank-oss/commit/ef89022f2ebf75d08f46428a97b26045d6ecf986))

## [1.10.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.9.0...balance-service-v1.10.0) (2026-07-12)


### Features

* **balance:** wire four-eyes enforcement mechanism (ADR-0155) ([#930](https://github.com/JiRaska/open-bank-oss/issues/930)) ([7825026](https://github.com/JiRaska/open-bank-oss/commit/7825026b997e5239b750ec160b02fffaafbb2dc8))

## [1.9.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.8.3...balance-service-v1.9.0) (2026-07-12)


### Features

* **balance:** watchdog alerts when the daily EoD tie-out goes stale/absent (step 2 of [#855](https://github.com/JiRaska/open-bank-oss/issues/855)) ([#863](https://github.com/JiRaska/open-bank-oss/issues/863)) ([84f54ec](https://github.com/JiRaska/open-bank-oss/commit/84f54ec1766e6c59acb5be13c56ea74ac36cd0a5))


### Bug Fixes

* **balance:** bridge Pact @State handlers onto a duplicated Vert.x context ([#858](https://github.com/JiRaska/open-bank-oss/issues/858)) ([79938a1](https://github.com/JiRaska/open-bank-oss/commit/79938a1302c5563aaaea8de537726ecc988b6fba))
* **balance:** create missing balance_reconciliation_seq (daily tie-out never persisted) ([#856](https://github.com/JiRaska/open-bank-oss/issues/856)) ([9ef4905](https://github.com/JiRaska/open-bank-oss/commit/9ef4905fbc77f1bf6db823c4e2ea96aaa1421560))

## [1.8.3](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.8.2...balance-service-v1.8.3) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [1.8.2](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.8.1...balance-service-v1.8.2) (2026-07-07)


### Security

* **balance:** enforce OPA authorization on balance endpoints (ADR-0034 Phase 5) ([#407](https://github.com/JiRaska/open-bank-oss/issues/407)) ([42ee4b2](https://github.com/JiRaska/open-bank-oss/commit/42ee4b233f29f044eeb8114fbe43139d2395e9ae))

## [1.8.1](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.8.0...balance-service-v1.8.1) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [1.8.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.7.3...balance-service-v1.8.0) (2026-06-30)


### Features

* **libs,ledger,balance:** ADR-0077 Tier C — ledger posting amount + balance revaluation metrics ([#2797](https://github.com/JiRaska/open-bank-oss/issues/2797)) ([609cee4](https://github.com/JiRaska/open-bank-oss/commit/609cee4975b2e7066da327c58722b8fb0f3882f4))

## [1.7.3](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.7.2...balance-service-v1.7.3) (2026-06-30)


### Security

* **balance:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 1) ([#2728](https://github.com/JiRaska/open-bank-oss/issues/2728)) ([e4cb565](https://github.com/JiRaska/open-bank-oss/commit/e4cb5657c7a9a857aae045ba9917494d18d4669d))

## [1.7.2](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.7.1...balance-service-v1.7.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [1.7.1](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.7.0...balance-service-v1.7.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **sepa-instant,balance,audit,security-scanner,copilot,customer-edge,sca:** inject Clock via CDI (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2145](https://github.com/JiRaska/open-bank-oss/issues/2145)) ([d680007](https://github.com/JiRaska/open-bank-oss/commit/d68000775625cc423c95d8a27db29ff25a708f9f))

## [1.7.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.6.0...balance-service-v1.7.0) (2026-06-25)


### Features

* **balance:** inject Clock into application and infrastructure layers (ADR-0100) ([#2065](https://github.com/JiRaska/open-bank-oss/issues/2065)) ([4adefcd](https://github.com/JiRaska/open-bank-oss/commit/4adefcdf3ba93d294f260eafb1ac26d2d5f0e98b)), closes [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)
* **balance:** inject Clock into ReconciliationResource (ADR-0100) ([#2066](https://github.com/JiRaska/open-bank-oss/issues/2066)) ([0d78f0a](https://github.com/JiRaska/open-bank-oss/commit/0d78f0aaaa073a905fbe83a5073c6f6f0b524e28)), closes [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)


### Bug Fixes

* **balance,sepa-payment,fraud:** @Dependent scope on ClockProducer + inject Clock into fraud persistence (ADR-0100) ([#2081](https://github.com/JiRaska/open-bank-oss/issues/2081)) ([fc1a129](https://github.com/JiRaska/open-bank-oss/commit/fc1a129cbfee4b5db41dbf4334f3dbe9d5e621c8))

## [1.6.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.5.2...balance-service-v1.6.0) (2026-06-25)


### Features

* **balance:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2029](https://github.com/JiRaska/open-bank-oss/issues/2029)) ([a6ab925](https://github.com/JiRaska/open-bank-oss/commit/a6ab925e56edee29adb4f265a70fc0a31e934de4))


### Bug Fixes

* **balance:** inject Clock into domain/application layers (ADR-0100, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2006](https://github.com/JiRaska/open-bank-oss/issues/2006)) ([9591d00](https://github.com/JiRaska/open-bank-oss/commit/9591d00c4f21b1188e52d599a3634c902bb865d0))

## [1.5.2](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.5.1...balance-service-v1.5.2) (2026-06-25)


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank-oss/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **libs:** wire @Authorize attributes to AuthzQuery + BearerTokenClientHeadersFactory warn log ([de3bfc1](https://github.com/JiRaska/open-bank-oss/commit/de3bfc1937681ff13205a2ddedc07334ee23b42e))
* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank-oss/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank-oss/commit/92dc2d636d857b526c8276e2647de440c540577b))


### Security

* **balance:** per-account ownership check via X-Customer-Party-Id (A1, issue [#628](https://github.com/JiRaska/open-bank-oss/issues/628)) ([f69af56](https://github.com/JiRaska/open-bank-oss/commit/f69af56979ea4b3df64d86bee1a5cd17c21caf7d))

## [1.5.1](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.5.0...balance-service-v1.5.1) (2026-06-15)


### Bug Fixes

* **agent,balance,product-catalog:** unblock main CI — capability rename sync + /q/metrics registries ([#751](https://github.com/JiRaska/open-bank-oss/issues/751)) ([a561b91](https://github.com/JiRaska/open-bank-oss/commit/a561b91ee2f06ed71b23086a3a62d7db00a8c7ff))

## [1.5.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.4.0...balance-service-v1.5.0) (2026-06-12)


### Features

* **balance:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#811](https://github.com/JiRaska/open-bank-oss/issues/811)) ([10b78f2](https://github.com/JiRaska/open-bank-oss/commit/10b78f240bae7cab91e52a1a24e7c63ebb716dbc))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [1.4.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.3.0...balance-service-v1.4.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **balance:** event-driven zero-balance init on AccountCreated (ADR-0073) ([#549](https://github.com/JiRaska/open-bank-oss/issues/549)) ([18c23aa](https://github.com/JiRaska/open-bank-oss/commit/18c23aa073ba2ea1ba9732782b74416a6a028902))
* **balance:** make direct credit/debit idempotent on referenceId ([#590](https://github.com/JiRaska/open-bank-oss/issues/590)) ([32bfa57](https://github.com/JiRaska/open-bank-oss/commit/32bfa57cbcb5276e8537628efedd3f1b4fe02eea))
* **balance:** point-in-time asOf query on GET balance ([#579](https://github.com/JiRaska/open-bank-oss/issues/579)) ([8256ee6](https://github.com/JiRaska/open-bank-oss/commit/8256ee6072a9a0d8017ee67bfef8e4cea11a1928))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [1.3.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.2.3...balance-service-v1.3.0) (2026-06-06)


### Features

* **admin-ui:** quality dashboard — Pact contract tests, pitest mutation, composite score (ADR-0063) ([#360](https://github.com/JiRaska/open-bank-oss/issues/360)) ([00b25bc](https://github.com/JiRaska/open-bank-oss/commit/00b25bcc934fea8728bb4b404166cd21c273495b))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))


### Security

* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank-oss/issues/342)) ([e368296](https://github.com/JiRaska/open-bank-oss/commit/e3682965a4f7df3b7328e8a741e4809604706390))
