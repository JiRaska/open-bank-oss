# Changelog

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.11.0...billing-service-v0.12.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.10.2...billing-service-v0.11.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.10.2](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.10.1...billing-service-v0.10.2) (2026-08-22)


### Bug Fixes

* **card-issuance:** alert on dead-lettered outbox rows and add an operator requeue path ([#4308](https://github.com/JiRaska/open-bank-oss/issues/4308)) ([c666a4d](https://github.com/JiRaska/open-bank-oss/commit/c666a4deae12d1025722647e4813e9ccd0d86944))

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.10.0...billing-service-v0.10.1) (2026-08-19)


### Bug Fixes

* **billing:** escape billing_outbox JSON payloads via Jackson, not string concat ([#5642](https://github.com/JiRaska/open-bank-oss/issues/5642)) ([acb86e6](https://github.com/JiRaska/open-bank-oss/commit/acb86e6a72a3fd59a87066846d781e10f140e206)), closes [#4701](https://github.com/JiRaska/open-bank-oss/issues/4701)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.9.1...billing-service-v0.10.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.9.0...billing-service-v0.9.1) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.8.3...billing-service-v0.9.0) (2026-08-16)


### Features

* **billing-service:** add annual fee-summary aggregation for PAD Art. 5 statements ([#4129](https://github.com/JiRaska/open-bank-oss/issues/4129)) ([4bd9985](https://github.com/JiRaska/open-bank-oss/commit/4bd99857de524920066b99201c216476d1255408))

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.8.2...billing-service-v0.8.3) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.8.1...billing-service-v0.8.2) (2026-08-02)


### Bug Fixes

* **billing:** validate fee query params instead of letting the schema reject them ([#3064](https://github.com/JiRaska/open-bank-oss/issues/3064)) ([0be78ce](https://github.com/JiRaska/open-bank-oss/commit/0be78ce4b5bbd8177715e3d409377355d7f2a3c7))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.8.0...billing-service-v0.8.1) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.7.0...billing-service-v0.8.0) (2026-07-31)


### Features

* **finops:** route all four product-catalog callers through the KEDA interceptor ([#2699](https://github.com/JiRaska/open-bank-oss/issues/2699)) ([f603f4d](https://github.com/JiRaska/open-bank-oss/commit/f603f4d7d590200bb03bf97d83e36880ac74c862))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.6.6...billing-service-v0.7.0) (2026-07-26)


### Features

* **ledger:** register workflow-liveness gauges on the money-path schedulers ([#2488](https://github.com/JiRaska/open-bank-oss/issues/2488)) ([d0332b4](https://github.com/JiRaska/open-bank-oss/commit/d0332b4201aa965504c9f2494a5b8e1639c25ec5)), closes [#2239](https://github.com/JiRaska/open-bank-oss/issues/2239)

## [0.6.6](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.6.5...billing-service-v0.6.6) (2026-07-25)


### Bug Fixes

* **billing:** run the monthly cycle sweep on a Vert.x context ([#2187](https://github.com/JiRaska/open-bank-oss/issues/2187)) ([#2194](https://github.com/JiRaska/open-bank-oss/issues/2194)) ([8477733](https://github.com/JiRaska/open-bank-oss/commit/847773329cb2b2ddfb7c7999d1f348e087bcf176))

## [0.6.5](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.6.4...billing-service-v0.6.5) (2026-07-19)


### Bug Fixes

* **billing:** self-heal the reactive pg pool so a DB blip can't wedge the service ([#1683](https://github.com/JiRaska/open-bank-oss/issues/1683)) ([3dde5bf](https://github.com/JiRaska/open-bank-oss/commit/3dde5bf5304e68cdfcdee0e8be8b4301ed89da1a))

## [0.6.4](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.6.3...billing-service-v0.6.4) (2026-07-18)


### Bug Fixes

* **billing:** add missing quarkus-opentelemetry extension ([#1668](https://github.com/JiRaska/open-bank-oss/issues/1668)) ([12ccaa6](https://github.com/JiRaska/open-bank-oss/commit/12ccaa69daad6dba1f94e101eabc61f02ff3bc34)), closes [#669](https://github.com/JiRaska/open-bank-oss/issues/669)

## [0.6.3](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.6.2...billing-service-v0.6.3) (2026-07-17)


### Bug Fixes

* **billing:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1462](https://github.com/JiRaska/open-bank-oss/issues/1462)) ([f92d245](https://github.com/JiRaska/open-bank-oss/commit/f92d2458f9835a2147c467bf4229a0be57ade351))

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.6.1...billing-service-v0.6.2) (2026-07-14)


### Bug Fixes

* **billing:** fail closed when product-catalog read fails during fee assessment ([#1002](https://github.com/JiRaska/open-bank-oss/issues/1002)) ([1b85f39](https://github.com/JiRaska/open-bank-oss/commit/1b85f39d20458f210e720f00f5cf9ff3c79581f4))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.6.0...billing-service-v0.6.1) (2026-07-12)


### Bug Fixes

* **billing,ledger:** unusable fee GL accounts + add ledger pact coverage ([#468](https://github.com/JiRaska/open-bank-oss/issues/468)) ([#859](https://github.com/JiRaska/open-bank-oss/issues/859)) ([3583372](https://github.com/JiRaska/open-bank-oss/commit/3583372f76f5093516289108aa5f248dd481d35e))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.5.3...billing-service-v0.6.0) (2026-07-11)


### Features

* **billing:** discover the billing-cycle account batch from account-service (ADR-0143) ([#736](https://github.com/JiRaska/open-bank-oss/issues/736)) ([a7df550](https://github.com/JiRaska/open-bank-oss/commit/a7df55001281edeabb2a6754bb163c653439c61a)), closes [#548](https://github.com/JiRaska/open-bank-oss/issues/548)
* **billing:** propagate a service token to product-catalog ([#401](https://github.com/JiRaska/open-bank-oss/issues/401) rollout) ([#744](https://github.com/JiRaska/open-bank-oss/issues/744)) ([20293ee](https://github.com/JiRaska/open-bank-oss/commit/20293ee05c8f3becc0bba8ad6b6ad4f31d47bfee))

## [0.5.3](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.5.2...billing-service-v0.5.3) (2026-07-09)


### Bug Fixes

* **simulation:** re-validate balance across interleaved DST scenarios ([#672](https://github.com/JiRaska/open-bank-oss/issues/672)) ([1a868b1](https://github.com/JiRaska/open-bank-oss/commit/1a868b121473f3838fd294538267d26ff1a2ccac))

## [0.5.2](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.5.1...billing-service-v0.5.2) (2026-07-08)


### Security

* **billing:** inline log sanitization so CodeQL actually recognizes it ([#597](https://github.com/JiRaska/open-bank-oss/issues/597)) ([f28d932](https://github.com/JiRaska/open-bank-oss/commit/f28d9320fdd0ca0c2ebabae86dedadb8b87be048))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.5.0...billing-service-v0.5.1) (2026-07-08)


### Security

* **billing:** sanitize logged values (CodeQL java/log-injection) ([#588](https://github.com/JiRaska/open-bank-oss/issues/588)) ([060b5f6](https://github.com/JiRaska/open-bank-oss/commit/060b5f697ee4ed3d3aa22f2072b1388d2a3d6c6b))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.4.2...billing-service-v0.5.0) (2026-07-08)


### Features

* **billing:** ledger fee posting via outbox, four-eyes, DST invariant ([#549](https://github.com/JiRaska/open-bank-oss/issues/549)) ([3eb8fc1](https://github.com/JiRaska/open-bank-oss/commit/3eb8fc191f31649434e786b0bae3afe6e4008fbe))

## [0.4.2](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.4.1...billing-service-v0.4.2) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.4.0...billing-service-v0.4.1) (2026-07-07)


### Security

* **billing:** enforce OPA authorization on billing endpoints (ADR-0034 Phase 5) ([#391](https://github.com/JiRaska/open-bank-oss/issues/391)) ([525be76](https://github.com/JiRaska/open-bank-oss/commit/525be76cd8fbbe136e2f6f72e676fd420988cc43)), closes [#266](https://github.com/JiRaska/open-bank-oss/issues/266)

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.3.0...billing-service-v0.4.0) (2026-07-03)


### Features

* **billing:** wire OPA authorization onto the fee-assessment endpoint ([#179](https://github.com/JiRaska/open-bank-oss/issues/179)) ([6879d9a](https://github.com/JiRaska/open-bank-oss/commit/6879d9a26e33d9aa4d2158f302c4db02828e431a))


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.2.0...billing-service-v0.3.0) (2026-06-30)


### Features

* **billing:** Dockerfile + GitOps manifests — sandbox deploy (ADR-0143 phase 2c) ([#2813](https://github.com/JiRaska/open-bank-oss/issues/2813)) ([8c00ef9](https://github.com/JiRaska/open-bank-oss/commit/8c00ef9c4e806ead755bc59c42080a9c870541a4))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/billing-service-v0.1.0...billing-service-v0.2.0) (2026-06-30)


### Features

* **billing-service:** ADR-0143 phase 2b — fee assessment skeleton (money-path) ([#2756](https://github.com/JiRaska/open-bank-oss/issues/2756)) ([1250001](https://github.com/JiRaska/open-bank-oss/commit/1250001d86628b7600d23416084eb41062cde813))
* **billing-service:** ADR-0143 phase 2c (read path) — live fee assessment, dry-run ([#2770](https://github.com/JiRaska/open-bank-oss/issues/2770)) ([958b302](https://github.com/JiRaska/open-bank-oss/commit/958b302f28ad9779136af7bb25c6e8d484081561))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
