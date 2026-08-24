# Changelog

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.13.2...fraud-service-v0.14.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.13.2](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.13.1...fraud-service-v0.13.2) (2026-08-22)


### Bug Fixes

* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.13.1](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.13.0...fraud-service-v0.13.1) (2026-08-21)


### Bug Fixes

* **fraud:** dedupe both aggregates against the applied-id set, not a last-writer marker ([#5789](https://github.com/JiRaska/open-bank-oss/issues/5789)) ([#6040](https://github.com/JiRaska/open-bank-oss/issues/6040)) ([6df930b](https://github.com/JiRaska/open-bank-oss/commit/6df930bdb17826a0110891a24241cd1fee88c339))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.12.1...fraud-service-v0.13.0) (2026-08-20)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **fraud:** dedupe velocity_aggregates on redelivery ([#5716](https://github.com/JiRaska/open-bank-oss/issues/5716)) ([#5786](https://github.com/JiRaska/open-bank-oss/issues/5786)) ([1fd2170](https://github.com/JiRaska/open-bank-oss/commit/1fd21708c799c625447923c3bd1ac2d574005d2c))
* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.12.1](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.12.0...fraud-service-v0.12.1) (2026-08-20)


### Bug Fixes

* **fraud:** dedupe velocity_aggregates on redelivery ([#5716](https://github.com/JiRaska/open-bank-oss/issues/5716)) ([#5786](https://github.com/JiRaska/open-bank-oss/issues/5786)) ([1fd2170](https://github.com/JiRaska/open-bank-oss/commit/1fd21708c799c625447923c3bd1ac2d574005d2c))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.11.2...fraud-service-v0.12.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.11.2](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.11.1...fraud-service-v0.11.2) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.11.0...fraud-service-v0.11.1) (2026-08-09)


### Bug Fixes

* **fraud:** register the ADR-0237 liveness heartbeat on the hold-expiry sweep ([#4286](https://github.com/JiRaska/open-bank-oss/issues/4286)) ([a9ed56a](https://github.com/JiRaska/open-bank-oss/commit/a9ed56ac866f76c896b975e9742bccb6e2168d30)), closes [#3345](https://github.com/JiRaska/open-bank-oss/issues/3345)

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.10.2...fraud-service-v0.11.0) (2026-08-09)


### Features

* **fraud:** raise a marketing-suppression fraud-hold signal (ADR-0220 D3.5, [#2749](https://github.com/JiRaska/open-bank-oss/issues/2749)) ([#4252](https://github.com/JiRaska/open-bank-oss/issues/4252)) ([26486a0](https://github.com/JiRaska/open-bank-oss/commit/26486a014c2df3b32b6523fd494d7071d76406f4))

## [0.10.2](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.10.1...fraud-service-v0.10.2) (2026-08-07)


### Bug Fixes

* **fraud:** a wrongly-typed currency answered 500, not 400 ([#3923](https://github.com/JiRaska/open-bank-oss/issues/3923)) ([4d7f7b7](https://github.com/JiRaska/open-bank-oss/commit/4d7f7b78cc87834b4506ba68321edde7a4cb40c1))

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.10.0...fraud-service-v0.10.1) (2026-08-02)


### Bug Fixes

* **fraud:** an ONNX native-load Error 500'd the whole resource, not just scoring ([#3376](https://github.com/JiRaska/open-bank-oss/issues/3376)) ([330707c](https://github.com/JiRaska/open-bank-oss/commit/330707ce77347ee8d15bbbbfc8d66d732d920ab2)), closes [#3354](https://github.com/JiRaska/open-bank-oss/issues/3354)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.9.1...fraud-service-v0.10.0) (2026-07-31)


### Features

* **fraud:** analyst review queue — GET /api/v1/fraud/review-queue (ADR-0230 D2) ([#2798](https://github.com/JiRaska/open-bank-oss/issues/2798)) ([2a765b2](https://github.com/JiRaska/open-bank-oss/commit/2a765b258ebd63cdc4ea1ef93c0788fb4484bdcd))

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.9.0...fraud-service-v0.9.1) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.8.1...fraud-service-v0.9.0) (2026-07-24)


### Features

* **fraud:** verify the ONNX model against a signed model card before serving (ADR-0141) ([#2016](https://github.com/JiRaska/open-bank-oss/issues/2016)) ([d6fa173](https://github.com/JiRaska/open-bank-oss/commit/d6fa1733e4c3b692e5e85e9cbf020bd9752e2752))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.8.0...fraud-service-v0.8.1) (2026-07-16)


### Bug Fixes

* **libs:** delete the hand-rolled UUIDv7 Ids that ADR-0106 forbids ([#1244](https://github.com/JiRaska/open-bank-oss/issues/1244)) ([3b73dc2](https://github.com/JiRaska/open-bank-oss/commit/3b73dc2d8552463a9b56d5b67e3b9f7f8fc92ee9)), closes [#1242](https://github.com/JiRaska/open-bank-oss/issues/1242)

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.7.2...fraud-service-v0.8.0) (2026-07-12)


### Features

* **fraud:** add in-process ONNX Runtime adapter for shadow scoring (ADR-0139 phase-1b) ([#956](https://github.com/JiRaska/open-bank-oss/issues/956)) ([dae215b](https://github.com/JiRaska/open-bank-oss/commit/dae215b5bf996338411239a2bb807328ba165e06))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.7.1...fraud-service-v0.7.2) (2026-07-12)


### Bug Fixes

* **fraud:** use Admin.listGroups instead of deprecated listConsumerGroups ([#869](https://github.com/JiRaska/open-bank-oss/issues/869)) ([9d85115](https://github.com/JiRaska/open-bank-oss/commit/9d85115b71c20ae8a2decb64e9cec5e89ff79a07)), closes [#865](https://github.com/JiRaska/open-bank-oss/issues/865)

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.7.0...fraud-service-v0.7.1) (2026-07-11)


### Bug Fixes

* **fraud-service:** provision Redis for the online feature store ([#706](https://github.com/JiRaska/open-bank-oss/issues/706)) ([a407c3a](https://github.com/JiRaska/open-bank-oss/commit/a407c3ae46fa3662ca5dfc4dfb8aa8b965255143))
* **fraud-service:** replace env-var Kafka overrides with a properties file (startup-crash risk) ([#698](https://github.com/JiRaska/open-bank-oss/issues/698)) ([771528c](https://github.com/JiRaska/open-bank-oss/commit/771528c5f36ab45ed9914afbd39df4092b082146))
* **fraud:** set Kafka group.id via env var, not a dotted YAML key ([#685](https://github.com/JiRaska/open-bank-oss/issues/685)) ([16d2556](https://github.com/JiRaska/open-bank-oss/commit/16d2556934fe5238fd98ad33da8472e257a1f014))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.6.1...fraud-service-v0.7.0) (2026-07-09)


### Features

* **fraud:** add new-payee + high-amount REVIEW rule (v4) ([#635](https://github.com/JiRaska/open-bank-oss/issues/635)) ([ebc5dc1](https://github.com/JiRaska/open-bank-oss/commit/ebc5dc12d255a1dc46231b8c2a704b0839c556f1))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.6.0...fraud-service-v0.6.1) (2026-07-08)


### Bug Fixes

* **fraud:** per-currency amount thresholds, fail closed on unmapped currency ([#565](https://github.com/JiRaska/open-bank-oss/issues/565)) ([8af2672](https://github.com/JiRaska/open-bank-oss/commit/8af2672aa6a89c00591086b8656b00bf07351df1))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.5.2...fraud-service-v0.6.0) (2026-07-08)


### Features

* **fraud:** expand rule engine to v3 with amount-based rules ([#546](https://github.com/JiRaska/open-bank-oss/issues/546)) ([3f8f8be](https://github.com/JiRaska/open-bank-oss/commit/3f8f8be91db9584c9e873367f16a17a1fb735d78)), closes [#529](https://github.com/JiRaska/open-bank-oss/issues/529)

## [0.5.2](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.5.1...fraud-service-v0.5.2) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.5.0...fraud-service-v0.5.1) (2026-07-07)


### Security

* **fraud:** enforce OPA authorization on fraud endpoints (ADR-0034 Phase 5) ([#388](https://github.com/JiRaska/open-bank-oss/issues/388)) ([7703fa9](https://github.com/JiRaska/open-bank-oss/commit/7703fa97961206acae4899b30d197296b7b418f2))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.4.3...fraud-service-v0.5.0) (2026-06-30)


### Features

* **fraud:** online feature store + shadow ML scoring (ADR-0139/0140 phase 1) ([#2738](https://github.com/JiRaska/open-bank-oss/issues/2738)) ([3e280ea](https://github.com/JiRaska/open-bank-oss/commit/3e280ea18cc5073256816ab66f88ed96aa3628d6))


### Bug Fixes

* **fraud:** derive quarkus.application.version from version.txt ([#2717](https://github.com/JiRaska/open-bank-oss/issues/2717)) ([9e003f8](https://github.com/JiRaska/open-bank-oss/commit/9e003f8729f2ffa2492cacf6b084d3a4215e8636))

## [0.4.3](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.4.2...fraud-service-v0.4.3) (2026-06-29)


### Bug Fixes

* **fraud:** reconcile quarkus.application.version with version.txt ([#2669](https://github.com/JiRaska/open-bank-oss/issues/2669)) ([b9f84bd](https://github.com/JiRaska/open-bank-oss/commit/b9f84bd26d4ed42a3d25d29fab77a93b694acb49))

## [0.4.2](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.4.1...fraud-service-v0.4.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.4.0...fraud-service-v0.4.1) (2026-06-25)


### Bug Fixes

* **fraud:** repair toEntity clock scope + ktlint class-signature (ADR-0100, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2088](https://github.com/JiRaska/open-bank-oss/issues/2088)) ([e13ed1c](https://github.com/JiRaska/open-bank-oss/commit/e13ed1c6c9420928075a528bea34717b2353ca1d))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.3.0...fraud-service-v0.4.0) (2026-06-25)


### Features

* **fraud,swift:** add Pact consumer contracts for transaction + SWIFT events (ADR-0092, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2024](https://github.com/JiRaska/open-bank-oss/issues/2024)) ([2cb470b](https://github.com/JiRaska/open-bank-oss/commit/2cb470bb18db463d9707ca85ed3913ef9c03712e))


### Bug Fixes

* **balance,sepa-payment,fraud:** @Dependent scope on ClockProducer + inject Clock into fraud persistence (ADR-0100) ([#2081](https://github.com/JiRaska/open-bank-oss/issues/2081)) ([fc1a129](https://github.com/JiRaska/open-bank-oss/commit/fc1a129cbfee4b5db41dbf4334f3dbe9d5e621c8))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.2.0...fraud-service-v0.3.0) (2026-06-25)


### Features

* **fraud:** ADR-0084 §2 velocity counters + Kafka transaction signal consumer ([#1876](https://github.com/JiRaska/open-bank-oss/issues/1876)) ([ffea993](https://github.com/JiRaska/open-bank-oss/commit/ffea993b87144a935cf18ac9121657b714298ff4))
* **libs:** add Ids.newId() — UUIDv7 helper (ADR-0106 Tier 1) ([#1945](https://github.com/JiRaska/open-bank-oss/issues/1945)) ([da77a9b](https://github.com/JiRaska/open-bank-oss/commit/da77a9b526e3b4ffc0106863cca174c7ed59ebb7))


### Bug Fixes

* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank-oss/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank-oss/commit/92dc2d636d857b526c8276e2647de440c540577b))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/fraud-service-v0.1.0...fraud-service-v0.2.0) (2026-06-15)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **fraud:** stand up openbank-fraud-service skeleton (ADR-0084 Phase 1) ([#999](https://github.com/JiRaska/open-bank-oss/issues/999)) ([0af9fbe](https://github.com/JiRaska/open-bank-oss/commit/0af9fbef06c8a5370f08a4cd847a37a73ddad560)), closes [#850](https://github.com/JiRaska/open-bank-oss/issues/850)
* **fraud:** verdict-tagged scoring metric (ADR-0084 §1) ([#1101](https://github.com/JiRaska/open-bank-oss/issues/1101)) ([307c919](https://github.com/JiRaska/open-bank-oss/commit/307c919f9ae7c572bb70bdb02dc1b866ff18bed7)), closes [#850](https://github.com/JiRaska/open-bank-oss/issues/850)


### Bug Fixes

* **fraud:** assign unique HTTP port 8133 (resolve collision with sepa-payment) ([#1032](https://github.com/JiRaska/open-bank-oss/issues/1032)) ([4f32d87](https://github.com/JiRaska/open-bank-oss/commit/4f32d8762fd437c293b095228756274dbc5b06d3))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
