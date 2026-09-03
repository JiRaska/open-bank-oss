# Changelog

## [0.27.3](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.27.2...account-service-v0.27.3) (2026-09-03)


### Bug Fixes

* **account:** revoking an unknown authorization answers 404, not 500 ([#8466](https://github.com/JiRaska/open-bank-oss/issues/8466)) ([909dee5](https://github.com/JiRaska/open-bank-oss/commit/909dee547a0bf602792adf60b9df91c28abe55be))

## [0.27.2](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.27.1...account-service-v0.27.2) (2026-09-03)


### Bug Fixes

* **account:** dead-letter into an explicit topic, not the shared implicit one ([#8304](https://github.com/JiRaska/open-bank-oss/issues/8304)) ([4faf359](https://github.com/JiRaska/open-bank-oss/commit/4faf359eb20c990b0a54c9ef79d013ffed4b59c8)), closes [#5752](https://github.com/JiRaska/open-bank-oss/issues/5752)

## [0.27.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.27.0...account-service-v0.27.1) (2026-09-02)


### Security

* **delegation:** reject stale lifecycle projections ([#8220](https://github.com/JiRaska/open-bank-oss/issues/8220)) ([6537342](https://github.com/JiRaska/open-bank-oss/commit/65373422f3e3c38e266e411f219beaceab407005))

## [0.27.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.26.0...account-service-v0.27.0) (2026-08-31)


### Features

* **account:** expose pending approvals in unified inbox ([#7028](https://github.com/JiRaska/open-bank-oss/issues/7028)) ([82512a4](https://github.com/JiRaska/open-bank-oss/commit/82512a459701f9cbc9742a9b06fb5e7e94e5be37))

## [0.26.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.25.0...account-service-v0.26.0) (2026-08-26)


### Features

* **customer-edge:** add term deposit journey ([#6838](https://github.com/JiRaska/open-bank-oss/issues/6838)) ([c99828e](https://github.com/JiRaska/open-bank-oss/commit/c99828e110223ceebe63befda471bd9232720fad))

## [0.25.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.24.0...account-service-v0.25.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.24.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.23.1...account-service-v0.24.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.23.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.23.0...account-service-v0.23.1) (2026-08-22)


### Bug Fixes

* **docs:** repair the 7 .mmd diagrams that do not parse ([#6496](https://github.com/JiRaska/open-bank-oss/issues/6496)) ([c1e6ad7](https://github.com/JiRaska/open-bank-oss/commit/c1e6ad7b14887db70ec3365747f2ed06d9ec02db))
* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.23.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.22.0...account-service-v0.23.0) (2026-08-20)


### Features

* **product-catalog:** govern downstream product terms ([#5841](https://github.com/JiRaska/open-bank-oss/issues/5841)) ([932d639](https://github.com/JiRaska/open-bank-oss/commit/932d63921fb3b8a8c63741deaeb4214a6e8fa142))

## [0.22.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.21.3...account-service-v0.22.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.21.3](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.21.2...account-service-v0.21.3) (2026-08-18)


### Bug Fixes

* **account,party:** add sourceService to audit-consumed events ([#5267](https://github.com/JiRaska/open-bank-oss/issues/5267)) ([2aeefeb](https://github.com/JiRaska/open-bank-oss/commit/2aeefebc6275c45068049aa40869e84a1efd58c4))

## [0.21.2](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.21.1...account-service-v0.21.2) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.21.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.21.0...account-service-v0.21.1) (2026-08-16)


### Bug Fixes

* **accounts:** refuse to close an account that still holds money ([#5072](https://github.com/JiRaska/open-bank-oss/issues/5072)) ([d7be3a3](https://github.com/JiRaska/open-bank-oss/commit/d7be3a3f82f29b190160e8cd6ebaa3dddcfc96ca))

## [0.21.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.20.3...account-service-v0.21.0) (2026-08-16)


### Features

* **accounts:** let a customer rename an account (TOP-10 [#10](https://github.com/JiRaska/open-bank-oss/issues/10), part 1) ([#5002](https://github.com/JiRaska/open-bank-oss/issues/5002)) ([b9b3fc6](https://github.com/JiRaska/open-bank-oss/commit/b9b3fc675da7c5920d1d8fd4562fb001eb04635d))

## [0.20.3](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.20.2...account-service-v0.20.3) (2026-08-14)


### Bug Fixes

* **account:** make the ADR-0232 dual-run's store disagreement observable ([#4634](https://github.com/JiRaska/open-bank-oss/issues/4634)) ([bca632f](https://github.com/JiRaska/open-bank-oss/commit/bca632fa2edcbc9bff0bc12174b61bb986f8b0a8)), closes [#2993](https://github.com/JiRaska/open-bank-oss/issues/2993)

## [0.20.2](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.20.1...account-service-v0.20.2) (2026-08-09)


### Bug Fixes

* **balance:** stop a not-yet-effective credit being spendable before its value date ([#3916](https://github.com/JiRaska/open-bank-oss/issues/3916)) ([67ef850](https://github.com/JiRaska/open-bank-oss/commit/67ef8503ae469fd2fc95a97174b2f36ae1dba000))

## [0.20.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.20.0...account-service-v0.20.1) (2026-08-09)


### Bug Fixes

* **libs:** stamp ApiError.timestamp at construction instead of serving 1970 ([#3880](https://github.com/JiRaska/open-bank-oss/issues/3880)) ([b3e6672](https://github.com/JiRaska/open-bank-oss/commit/b3e6672c9e13470fc6353ad8a5483e4075875b1f))

## [0.20.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.19.1...account-service-v0.20.0) (2026-08-08)


### Features

* **ci:** check gitops workload env hostnames, and fix the four it finds ([#3974](https://github.com/JiRaska/open-bank-oss/issues/3974)) ([123633f](https://github.com/JiRaska/open-bank-oss/commit/123633fcdb7ce6bfa5b949bd1610196618e36108))

## [0.19.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.19.0...account-service-v0.19.1) (2026-08-07)


### Bug Fixes

* **account:** the propose-only savings withdrawal approval could never succeed ([#3632](https://github.com/JiRaska/open-bank-oss/issues/3632)) ([4209407](https://github.com/JiRaska/open-bank-oss/commit/4209407e79ffd7a11f048ff5d7daa9fdc6fdfa30))

## [0.19.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.18.1...account-service-v0.19.0) (2026-08-07)


### Features

* let a delegate pay from a shared account, and audit it as delegated ([#3633](https://github.com/JiRaska/open-bank-oss/issues/3633)) ([568686b](https://github.com/JiRaska/open-bank-oss/commit/568686bfc3ba15e824252f3502b0fddc856c7d37))

## [0.18.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.18.0...account-service-v0.18.1) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.18.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.17.0...account-service-v0.18.0) (2026-08-02)


### Features

* **account:** savings-goal delegation + propose-only withdrawal flow (ADR-0232 D3/D8) ([#3143](https://github.com/JiRaska/open-bank-oss/issues/3143)) ([18bcac7](https://github.com/JiRaska/open-bank-oss/commit/18bcac75a2b3ea8fccee6f831043607b862586ae))

## [0.17.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.16.2...account-service-v0.17.0) (2026-08-02)


### Features

* **account:** delegation-grant enforcement projection (ADR-0232 D3) ([#3058](https://github.com/JiRaska/open-bank-oss/issues/3058)) ([d28a787](https://github.com/JiRaska/open-bank-oss/commit/d28a787c842d07e9e7d3c5f2267274e773c6ef7c))

## [0.16.2](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.16.1...account-service-v0.16.2) (2026-08-02)


### Bug Fixes

* **infra:** give the five money-path services the JDBC datasource Flyway migrates ([#3192](https://github.com/JiRaska/open-bank-oss/issues/3192)) ([d9b31d5](https://github.com/JiRaska/open-bank-oss/commit/d9b31d5d2cccd169ec6ce7e8e971d5853ef952f1)), closes [#3080](https://github.com/JiRaska/open-bank-oss/issues/3080)

## [0.16.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.16.0...account-service-v0.16.1) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))

## [0.16.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.15.6...account-service-v0.16.0) (2026-07-30)


### Features

* **finops:** route all four product-catalog callers through the KEDA interceptor ([#2699](https://github.com/JiRaska/open-bank-oss/issues/2699)) ([f603f4d](https://github.com/JiRaska/open-bank-oss/commit/f603f4d7d590200bb03bf97d83e36880ac74c862))

## [0.15.6](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.15.5...account-service-v0.15.6) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.15.5](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.15.4...account-service-v0.15.5) (2026-07-18)


### Bug Fixes

* **account:** make account authorizations persist; merge + session on the repo ([#1602](https://github.com/JiRaska/open-bank-oss/issues/1602)) ([b6b039f](https://github.com/JiRaska/open-bank-oss/commit/b6b039f0361b8ef381e4e41aa103aaf3e81a39a6)), closes [#1600](https://github.com/JiRaska/open-bank-oss/issues/1600)

## [0.15.4](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.15.3...account-service-v0.15.4) (2026-07-17)


### Bug Fixes

* **account-service:** stop acking-and-dropping party events that only failed transiently ([#1533](https://github.com/JiRaska/open-bank-oss/issues/1533)) ([10733be](https://github.com/JiRaska/open-bank-oss/commit/10733be22d701ba424829b8f6c0c4981d10c283f)), closes [#1497](https://github.com/JiRaska/open-bank-oss/issues/1497)

## [0.15.3](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.15.2...account-service-v0.15.3) (2026-07-17)


### Bug Fixes

* **account:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1470](https://github.com/JiRaska/open-bank-oss/issues/1470)) ([42e80c8](https://github.com/JiRaska/open-bank-oss/commit/42e80c8559aa255014cb2f13df52b3857d940414))

## [0.15.2](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.15.1...account-service-v0.15.2) (2026-07-16)


### Bug Fixes

* **party-service:** restore @PactBroker on provider verification (unblocks auto-deploy) ([#1166](https://github.com/JiRaska/open-bank-oss/issues/1166)) ([f9f28e5](https://github.com/JiRaska/open-bank-oss/commit/f9f28e5c700d5e98df59416aba4ac669e62e47a3))

## [0.15.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.15.0...account-service-v0.15.1) (2026-07-11)


### Bug Fixes

* **account:** propagate a service token to product-catalog ([#401](https://github.com/JiRaska/open-bank-oss/issues/401) rollout) ([#835](https://github.com/JiRaska/open-bank-oss/issues/835)) ([1444456](https://github.com/JiRaska/open-bank-oss/commit/144445677944189139752e313db68f132ed10d8e))

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.14.0...account-service-v0.15.0) (2026-07-11)


### Features

* **perf:** money-path write benchmark + docker-compose local-dev fixes (issue [#669](https://github.com/JiRaska/open-bank-oss/issues/669)) ([#734](https://github.com/JiRaska/open-bank-oss/issues/734)) ([b0870e2](https://github.com/JiRaska/open-bank-oss/commit/b0870e27c5dfe789c47ff8e2843915de60c98d03))

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.13.0...account-service-v0.14.0) (2026-07-11)


### Features

* **account:** list ACTIVE accounts fleet-wide for billing discovery (ADR-0143) ([#735](https://github.com/JiRaska/open-bank-oss/issues/735)) ([ccff069](https://github.com/JiRaska/open-bank-oss/commit/ccff06984e3900b10f1fe08df90347426054ae2e))
* **account:** validate account opening against product-catalog (ADR-0158, issue [#668](https://github.com/JiRaska/open-bank-oss/issues/668)) ([#727](https://github.com/JiRaska/open-bank-oss/issues/727)) ([2d56058](https://github.com/JiRaska/open-bank-oss/commit/2d56058a0d1ab2d8b6afcf79fbfee69bf792285c))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.12.3...account-service-v0.13.0) (2026-07-08)


### Features

* **account:** wire four-eyes enforcement mechanism (ADR-0155) ([#559](https://github.com/JiRaska/open-bank-oss/issues/559)) ([958f9a5](https://github.com/JiRaska/open-bank-oss/commit/958f9a5365c5a5e273fb64a68cddbf1221734f66)), closes [#413](https://github.com/JiRaska/open-bank-oss/issues/413)

## [0.12.3](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.12.2...account-service-v0.12.3) (2026-07-08)


### Bug Fixes

* **account:** stamp audit timestamps from the injected Clock instead of EPOCH ([#540](https://github.com/JiRaska/open-bank-oss/issues/540)) ([39c296c](https://github.com/JiRaska/open-bank-oss/commit/39c296c0ec88b7209cac4f42f1a053aed6f41f0a))
* **account:** stamp audit timestamps on the transactional open path ([#562](https://github.com/JiRaska/open-bank-oss/issues/562)) ([5738301](https://github.com/JiRaska/open-bank-oss/commit/573830144703e5146f5cd7df112f286b9c9cea37)), closes [#533](https://github.com/JiRaska/open-bank-oss/issues/533)
* **account:** transactional idempotent opening + version-guarded lifecycle updates ([#541](https://github.com/JiRaska/open-bank-oss/issues/541)) ([7612ee7](https://github.com/JiRaska/open-bank-oss/commit/7612ee7bd8a65116c6d4305d184a6f2873324a37))

## [0.12.2](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.12.1...account-service-v0.12.2) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)
* **account:** wrap over-long argument lists in AccountServiceLifecycleTest (latent ktlint violation) ([#476](https://github.com/JiRaska/open-bank-oss/issues/476)) ([5f1a606](https://github.com/JiRaska/open-bank-oss/commit/5f1a6065f2509a8414f1266328a7702f72c801a1)), closes [#321](https://github.com/JiRaska/open-bank-oss/issues/321)

## [0.12.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.12.0...account-service-v0.12.1) (2026-07-07)


### Security

* **account:** enforce OPA authorization on account endpoints (ADR-0034 Phase 5) ([#412](https://github.com/JiRaska/open-bank-oss/issues/412)) ([0398bfc](https://github.com/JiRaska/open-bank-oss/commit/0398bfcb0a0f6cebc6779c5748b5b221ce31c243)), closes [#266](https://github.com/JiRaska/open-bank-oss/issues/266)

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.11.4...account-service-v0.12.0) (2026-07-04)


### Features

* **account,customer-edge:** implement ADR-0153 — savings goal metadata ([#219](https://github.com/JiRaska/open-bank-oss/issues/219)) ([05f73fd](https://github.com/JiRaska/open-bank-oss/commit/05f73fdd14d9fd9f7a9d30c4ffae50d15d5dfe07))

## [0.11.4](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.11.3...account-service-v0.11.4) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.11.3](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.11.2...account-service-v0.11.3) (2026-06-30)


### Security

* **account:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 1) ([#2729](https://github.com/JiRaska/open-bank-oss/issues/2729)) ([d192966](https://github.com/JiRaska/open-bank-oss/commit/d192966f8488389fc2a1f5706789d2a3160a4781))

## [0.11.2](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.11.1...account-service-v0.11.2) (2026-06-29)


### Bug Fixes

* **libs,account,consent,ledger,pid,transaction:** make DomainEvent.occurredAt explicit ([#2137](https://github.com/JiRaska/open-bank-oss/issues/2137)) ([#2662](https://github.com/JiRaska/open-bank-oss/issues/2662)) ([9e0c2ea](https://github.com/JiRaska/open-bank-oss/commit/9e0c2ea14a65aec227df333b83b0b7283b6c16a5))

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.11.0...account-service-v0.11.1) (2026-06-29)


### Bug Fixes

* **account:** retire non-atomic pocket-exchange DEBIT/CREDIT pair (ADR-0110 §3) ([#2651](https://github.com/JiRaska/open-bank-oss/issues/2651)) ([09e479a](https://github.com/JiRaska/open-bank-oss/commit/09e479a6d58e89f12e86818fd409523708658ad1))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.10.0...account-service-v0.11.0) (2026-06-29)


### Features

* **account:** same-account FX pocket exchange (ADR-0110) ([#2425](https://github.com/JiRaska/open-bank-oss/issues/2425)) ([e90fb73](https://github.com/JiRaska/open-bank-oss/commit/e90fb73978b5dcaf1c167ee693de1634780d4dfe))


### Bug Fixes

* **account:** GDPR Art. 17 — handle PARTY_ERASED to nullify legalName (ADR-0118) ([#2443](https://github.com/JiRaska/open-bank-oss/issues/2443)) ([9b52ac1](https://github.com/JiRaska/open-bank-oss/commit/9b52ac1afaf8f0fe4e6afdf2248bb6256f639cb0))
* **account:** sort accounts CURRENT-first in findByPartyId ([#2257](https://github.com/JiRaska/open-bank-oss/issues/2257)) ([8176d43](https://github.com/JiRaska/open-bank-oss/commit/8176d43f447387e3d3d8041a9bd400d569164530))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([ce39af7](https://github.com/JiRaska/open-bank-oss/commit/ce39af7c5b02ff3fef226f34e78c7e726b149ebd))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.9.1...account-service-v0.10.0) (2026-06-29)


### Features

* **account:** same-account FX pocket exchange (ADR-0110) ([#2425](https://github.com/JiRaska/open-bank-oss/issues/2425)) ([3757160](https://github.com/JiRaska/open-bank-oss/commit/3757160e55aaa3d1201685e95f258694b45facb2))


### Bug Fixes

* **account:** GDPR Art. 17 — handle PARTY_ERASED to nullify legalName (ADR-0118) ([#2443](https://github.com/JiRaska/open-bank-oss/issues/2443)) ([131fb7e](https://github.com/JiRaska/open-bank-oss/commit/131fb7e3adc20f6f4164d28460a320f6c62bfa9c))

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.9.0...account-service-v0.9.1) (2026-06-27)


### Bug Fixes

* **account:** sort accounts CURRENT-first in findByPartyId ([#2257](https://github.com/JiRaska/open-bank-oss/issues/2257)) ([3493c4c](https://github.com/JiRaska/open-bank-oss/commit/3493c4cd3e0c1675e8de33fd41c84dcc1ca6ad06))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank-oss/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.8.0...account-service-v0.9.0) (2026-06-25)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank-oss/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank-oss/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.7.1...account-service-v0.8.0) (2026-06-25)


### Features

* **account:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2098](https://github.com/JiRaska/open-bank-oss/issues/2098)) ([1c33954](https://github.com/JiRaska/open-bank-oss/commit/1c33954f61fc4c5a7c5f74467c0d5e1bd89e906a))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.7.0...account-service-v0.7.1) (2026-06-25)


### Bug Fixes

* **account:** inject Clock into domain/application layers (ADR-0100, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2008](https://github.com/JiRaska/open-bank-oss/issues/2008)) ([148b489](https://github.com/JiRaska/open-bank-oss/commit/148b4893522206eaa0174115bfa875d3354cf5c1))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.6.0...account-service-v0.7.0) (2026-06-25)


### Features

* **customer-edge:** ADR-0104 P1 — expose currency-pocket lifecycle to customers ([#1683](https://github.com/JiRaska/open-bank-oss/issues/1683)) ([24b3530](https://github.com/JiRaska/open-bank-oss/commit/24b35308f61cd2dfdc5ac4a6f040955a5abf237a))


### Bug Fixes

* **account:** add customer ownership guard to listPockets + resolvePocket (pentest A1) ([#1422](https://github.com/JiRaska/open-bank-oss/issues/1422)) ([95f65f6](https://github.com/JiRaska/open-bank-oss/commit/95f65f66f7d604b248734b0064f4b185ea26775d))
* **account:** align openapi info.version major to API v1 (ADR-0048) ([#1398](https://github.com/JiRaska/open-bank-oss/issues/1398)) ([604b5eb](https://github.com/JiRaska/open-bank-oss/commit/604b5eb717f45d3b0b29fe20c02adcdece3841b0))
* **account:** sync openapi.yaml info.version to lockstep 0.6.0 ([#1379](https://github.com/JiRaska/open-bank-oss/issues/1379)) ([a693c50](https://github.com/JiRaska/open-bank-oss/commit/a693c5053febdf004813eaf9ef7aba3b49cd3462))
* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank-oss/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **ci:** latent ktlint violations in openbank-libs + account-service ([fc4a63e](https://github.com/JiRaska/open-bank-oss/commit/fc4a63ece44019d85c9047b960f93380acbc6c5b))
* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank-oss/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank-oss/commit/92dc2d636d857b526c8276e2647de440c540577b))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.5.0...account-service-v0.6.0) (2026-06-15)


### Features

* **onboarding:** self-service E2E — savings account, sub-bound party, own-account transfers ([#894](https://github.com/JiRaska/open-bank-oss/issues/894)) ([91dd603](https://github.com/JiRaska/open-bank-oss/commit/91dd603e62f8f9038a0c71d1a0187ce0303442ce))


### Bug Fixes

* **account:** default sandbox bank code 0000 instead of Fio's 2010 ([#1094](https://github.com/JiRaska/open-bank-oss/issues/1094)) ([395b3dd](https://github.com/JiRaska/open-bank-oss/commit/395b3ddb9040a87f387c150abaf6a53cfd87e23b))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.4.1...account-service-v0.5.0) (2026-06-12)


### Features

* **account,party,onboarding:** sprint 2 — sanctions persistence, GDPR erasure, doc download, AbandonedCleaner fix ([#475](https://github.com/JiRaska/open-bank-oss/issues/475)) ([05b20d7](https://github.com/JiRaska/open-bank-oss/commit/05b20d764a6373d0ffd96ca84ab5a9a6ed54291f))
* **account:** account lifecycle metrics + outbox backlog gauge (ADR-0077/0079) ([#790](https://github.com/JiRaska/open-bank-oss/issues/790)) ([5bb1b3a](https://github.com/JiRaska/open-bank-oss/commit/5bb1b3a75d42ab302aee2c07b87e743194f1fa65))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **infra,agent:** feature-flag flip enforcement — CI gate + MCP tool (ADR-0067 / issue [#419](https://github.com/JiRaska/open-bank-oss/issues/419)) ([#758](https://github.com/JiRaska/open-bank-oss/issues/758)) ([96bfb7d](https://github.com/JiRaska/open-bank-oss/commit/96bfb7d506c9e2da22cde563ef8d676d77699019))


### Bug Fixes

* **account:** consume the deployed party-service event contract (onboarding→account) ([#764](https://github.com/JiRaska/open-bank-oss/issues/764)) ([71006e1](https://github.com/JiRaska/open-bank-oss/commit/71006e1987e571fd9a145ae740b5e2440e263d09))

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.4.0...account-service-v0.4.1) (2026-06-10)


### Security

* **account:** enforce X-Customer-Party-Id ownership on reads (IDOR defense-in-depth) ([#632](https://github.com/JiRaska/open-bank-oss/issues/632)) ([cd81cd5](https://github.com/JiRaska/open-bank-oss/commit/cd81cd5c0f3490f870eb613d2cfce11dbc4405c4))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.3.0...account-service-v0.4.0) (2026-06-09)


### Features

* **account:** auto-grant welcome bonus on account activation ([#555](https://github.com/JiRaska/open-bank-oss/issues/555)) ([7ea7548](https://github.com/JiRaska/open-bank-oss/commit/7ea75483aab5e8d1148ca08fbd521ee0934ad8b8))
* **account:** open PENDING_ACTIVATION account on onboarding (ADR-0073 phase 1) ([#533](https://github.com/JiRaska/open-bank-oss/issues/533)) ([df6f48c](https://github.com/JiRaska/open-bank-oss/commit/df6f48c920098436b47277878bc180f42db3ec2b))
* **account:** welcome-bonus notification + edge notification feed ([#565](https://github.com/JiRaska/open-bank-oss/issues/565)) ([a7c8d8f](https://github.com/JiRaska/open-bank-oss/commit/a7c8d8ff3e6b43c792727fb4e6eb71b1608def52))
* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **account:** make onboarding balance init event-driven (no balance row bug) ([#550](https://github.com/JiRaska/open-bank-oss/issues/550)) ([c3757aa](https://github.com/JiRaska/open-bank-oss/commit/c3757aa9432ec8e6a30d2cb9656b9bf52ace28d8))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/account-service-v0.2.0...account-service-v0.3.0) (2026-06-06)


### Features

* **account:** trigram IBAN-fragment search endpoint (money-path) ([#268](https://github.com/JiRaska/open-bank-oss/issues/268)) ([6c5a7da](https://github.com/JiRaska/open-bank-oss/commit/6c5a7daf59ccb31a914e1fdc1b667949bacd89d1))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **account:** generate nationally-valid Czech BBAN (ČNB mod-11), not just mod-97 ([#230](https://github.com/JiRaska/open-bank-oss/issues/230)) ([fbf1595](https://github.com/JiRaska/open-bank-oss/commit/fbf15953f765e5f3a4296e951800327f526a6aff)), closes [#66](https://github.com/JiRaska/open-bank-oss/issues/66)
* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
