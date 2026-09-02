# Changelog

## [0.32.1](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.32.0...lending-service-v0.32.1) (2026-09-01)


### Bug Fixes

* **lending:** distinguish an unconfigured court register from a clear one ([#7595](https://github.com/JiRaska/open-bank-oss/issues/7595)) ([3144e07](https://github.com/JiRaska/open-bank-oss/commit/3144e073e9dc5391190861402c8944257a9a9ae7))

## [0.32.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.31.0...lending-service-v0.32.0) (2026-08-27)


### Features

* **lending:** ADR-0269 platform — quotes, credit profile, AI levels, consent surface, financial health, funnel ([#6235](https://github.com/JiRaska/open-bank-oss/issues/6235)) ([3b62a4a](https://github.com/JiRaska/open-bank-oss/commit/3b62a4a5d42a80d0726c8018ca1af58599fb371b))

## [0.31.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.30.0...lending-service-v0.31.0) (2026-08-26)


### Features

* **admin-ui:** enrich operator cockpit ([#5905](https://github.com/JiRaska/open-bank-oss/issues/5905)) ([9a2207a](https://github.com/JiRaska/open-bank-oss/commit/9a2207aa5e66797f7f33789df2846da049113a9d))

## [0.30.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.29.0...lending-service-v0.30.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.29.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.28.0...lending-service-v0.29.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.28.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.27.0...lending-service-v0.28.0) (2026-08-22)


### Features

* **lending:** ADR-0269 slice 1 — one credit journey, three product shapes, customer-readable projection ([#6230](https://github.com/JiRaska/open-bank-oss/issues/6230)) ([a969810](https://github.com/JiRaska/open-bank-oss/commit/a969810df5541832f63580dfa828efaec81a3ba4))

## [0.27.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.26.1...lending-service-v0.27.0) (2026-08-21)


### Features

* **lending:** ADR-0269 slice 0 — credit-offer consent and the distress suppression floor ([#6226](https://github.com/JiRaska/open-bank-oss/issues/6226)) ([bf87d31](https://github.com/JiRaska/open-bank-oss/commit/bf87d314745d72eae965a256e6f68f34e8bf01b2))

## [0.26.1](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.26.0...lending-service-v0.26.1) (2026-08-21)


### Bug Fixes

* **lending:** bind the real GL-posting and credit adapters, and refuse to boot when they are not ([#6081](https://github.com/JiRaska/open-bank-oss/issues/6081)) ([2875938](https://github.com/JiRaska/open-bank-oss/commit/2875938dc5e50a872fd60a952bdf0439ec198ac0))

## [0.26.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.25.0...lending-service-v0.26.0) (2026-08-20)


### Features

* **product-catalog:** govern downstream product terms ([#5841](https://github.com/JiRaska/open-bank-oss/issues/5841)) ([932d639](https://github.com/JiRaska/open-bank-oss/commit/932d63921fb3b8a8c63741deaeb4214a6e8fa142))

## [0.25.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.24.5...lending-service-v0.25.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **lending:** add sourceService to remaining event types for audit attribution ([#5399](https://github.com/JiRaska/open-bank-oss/issues/5399)) ([1de3bff](https://github.com/JiRaska/open-bank-oss/commit/1de3bffdbacc242e13cf5635a2637995a822584a)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)
* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.24.5](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.24.4...lending-service-v0.24.5) (2026-08-17)


### Bug Fixes

* **lending:** actually pay the borrower on disbursement ([#3931](https://github.com/JiRaska/open-bank-oss/issues/3931)) ([#5231](https://github.com/JiRaska/open-bank-oss/issues/5231)) ([0d664f0](https://github.com/JiRaska/open-bank-oss/commit/0d664f078df84a07be659ea50659bc68b340c51f))

## [0.24.4](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.24.3...lending-service-v0.24.4) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.24.3](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.24.2...lending-service-v0.24.3) (2026-08-13)


### Bug Fixes

* **lending:** converge the compliance-pack registry across replicas ([#3644](https://github.com/JiRaska/open-bank-oss/issues/3644)) ([9e8fa86](https://github.com/JiRaska/open-bank-oss/commit/9e8fa8683f4d24311bd8e744084daffb952755df))

## [0.24.2](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.24.1...lending-service-v0.24.2) (2026-08-10)


### Bug Fixes

* **lending:** stamp business event time on the money-path audit producers ([#4412](https://github.com/JiRaska/open-bank-oss/issues/4412)) ([6e43ccc](https://github.com/JiRaska/open-bank-oss/commit/6e43ccc78ac8cd4f4a8af63743f6a530056a7510)), closes [#3914](https://github.com/JiRaska/open-bank-oss/issues/3914)

## [0.24.1](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.24.0...lending-service-v0.24.1) (2026-08-09)


### Bug Fixes

* **lending:** make origination state transitions atomic ([#3876](https://github.com/JiRaska/open-bank-oss/issues/3876)) ([cff2570](https://github.com/JiRaska/open-bank-oss/commit/cff2570e52c5d5281005c73f752f6a2f39162aef)), closes [#3850](https://github.com/JiRaska/open-bank-oss/issues/3850)

## [0.24.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.23.1...lending-service-v0.24.0) (2026-08-08)


### Features

* **engagement:** materialise ARREARS + ERASURE_REQUESTED for the D3.5 targeting exclusion ([#4106](https://github.com/JiRaska/open-bank-oss/issues/4106)) ([fb5c455](https://github.com/JiRaska/open-bank-oss/commit/fb5c455bc69fc5749c05c2798d7bcf9358a1b7fd))

## [0.23.1](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.23.0...lending-service-v0.23.1) (2026-08-07)


### Bug Fixes

* **lending:** make the compliance-pack four-eyes decision atomic ([#3837](https://github.com/JiRaska/open-bank-oss/issues/3837)) ([51b07b0](https://github.com/JiRaska/open-bank-oss/commit/51b07b08230194ee4524659c4c423a7351bd2648))

## [0.23.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.22.2...lending-service-v0.23.0) (2026-08-07)


### Features

* **lending:** register workflow liveness on the accrual and provisioning schedulers (ADR-0237) ([#3647](https://github.com/JiRaska/open-bank-oss/issues/3647)) ([ed03676](https://github.com/JiRaska/open-bank-oss/commit/ed036760800efdd5db8ee444989aaa547dd23d98)), closes [#3345](https://github.com/JiRaska/open-bank-oss/issues/3345)

## [0.22.2](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.22.1...lending-service-v0.22.2) (2026-08-03)


### Bug Fixes

* **lending:** customer intake was never on — @ConfigProperty ignored on 93 constructor params fleet-wide ([#3534](https://github.com/JiRaska/open-bank-oss/issues/3534)) ([e5796ea](https://github.com/JiRaska/open-bank-oss/commit/e5796ea72496b170f302958fb6e5ca6ef8553367))

## [0.22.1](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.22.0...lending-service-v0.22.1) (2026-08-02)


### Bug Fixes

* **lending:** compliance-pack activation never worked — three defects behind one 403 ([#3448](https://github.com/JiRaska/open-bank-oss/issues/3448)) ([ffabe8b](https://github.com/JiRaska/open-bank-oss/commit/ffabe8b65c697e8866ebbfa5ba907b0a103803be))

## [0.22.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.21.0...lending-service-v0.22.0) (2026-08-02)


### Features

* **lending:** per-state and per-status totals for the whole book ([#3309](https://github.com/JiRaska/open-bank-oss/issues/3309)) ([c50795a](https://github.com/JiRaska/open-bank-oss/commit/c50795a62be54ceac6ed375a5d62b559d10928ac))

## [0.21.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.20.3...lending-service-v0.21.0) (2026-08-02)


### Features

* **lending:** customer self-service loan application intake (ADR-0211) ([#3197](https://github.com/JiRaska/open-bank-oss/issues/3197)) ([3848b5c](https://github.com/JiRaska/open-bank-oss/commit/3848b5c25ab638765c355414fd04c773997d43c5))

## [0.20.3](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.20.2...lending-service-v0.20.3) (2026-08-02)


### Bug Fixes

* **lending,domestic:** align the entities with the DDL their own migrations create ([#3211](https://github.com/JiRaska/open-bank-oss/issues/3211)) ([0fbd745](https://github.com/JiRaska/open-bank-oss/commit/0fbd74595bd7b1401de5e67838b3cb0e7eed0722))

## [0.20.2](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.20.1...lending-service-v0.20.2) (2026-08-01)


### Bug Fixes

* **lending:** document the fail-closed input contract of the decision evaluation ([#3168](https://github.com/JiRaska/open-bank-oss/issues/3168)) ([9052f5f](https://github.com/JiRaska/open-bank-oss/commit/9052f5f17477d72da595660a25cfa569a5c69b95))

## [0.20.1](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.20.0...lending-service-v0.20.1) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))

## [0.20.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.19.0...lending-service-v0.20.0) (2026-07-31)


### Features

* **lending:** deterministic decision engine wired into ASSESSMENT (ADR-0213) ([fa61b32](https://github.com/JiRaska/open-bank-oss/commit/fa61b32cfa78058b5fc3d94ddcf25403830d4338))

## [0.19.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.18.0...lending-service-v0.19.0) (2026-07-31)


### Features

* **lending:** termination and early-exit lifecycle (ADR-0215) ([816a409](https://github.com/JiRaska/open-bank-oss/commit/816a409f8f5c33bdeaa3bbc529dc826196ffabcd))

## [0.18.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.17.0...lending-service-v0.18.0) (2026-07-31)


### Features

* **lending:** credit evidence emission into the audit chain (ADR-0214) ([2759397](https://github.com/JiRaska/open-bank-oss/commit/2759397b629f42f9893f2d44724d488cfd34456a))

## [0.17.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.16.0...lending-service-v0.17.0) (2026-07-31)


### Features

* **lending:** Temporal durable origination timers (ADR-0211 D2) ([35980f4](https://github.com/JiRaska/open-bank-oss/commit/35980f429cee132664eec58fa0ecf1354ebb2134))

## [0.16.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.15.0...lending-service-v0.16.0) (2026-07-31)


### Features

* **lending:** canonical origination graph wired into apply/decide/disburse (ADR-0211) ([2b6cf67](https://github.com/JiRaska/open-bank-oss/commit/2b6cf676e25a75bfec3b0e82cfb6fcf0db013c2d))

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.14.0...lending-service-v0.15.0) (2026-07-31)


### Features

* **lending:** CZ reference compliance pack + activation runbook (ADR-0212 bootstrap) ([86d07e6](https://github.com/JiRaska/open-bank-oss/commit/86d07e688e965e87b8d910d8db5d3c1f30a61c72))

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.13.0...lending-service-v0.14.0) (2026-07-31)


### Features

* **lending:** compliance pack four-eyes activation + fail-closed origination guard (ADR-0212) ([d97b3c5](https://github.com/JiRaska/open-bank-oss/commit/d97b3c57a2376ceb5c1c5009fe49854b086722e5))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.12.0...lending-service-v0.13.0) (2026-07-31)


### Features

* **lending:** backoffice read queues — recent applications + active loans (ADR-0230 D1) ([#2793](https://github.com/JiRaska/open-bank-oss/issues/2793)) ([0544f37](https://github.com/JiRaska/open-bank-oss/commit/0544f3770312d222fee32594a61dc7e0661db9b1))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.11.5...lending-service-v0.12.0) (2026-07-31)


### Features

* **lending:** pending-approvals list endpoint — first inbox federation source (ADR-0227 D2) ([#2791](https://github.com/JiRaska/open-bank-oss/issues/2791)) ([36e5d3a](https://github.com/JiRaska/open-bank-oss/commit/36e5d3a8ab97eb5db81c1e840f5df67bb77c53d0))

## [0.11.5](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.11.4...lending-service-v0.11.5) (2026-07-23)


### Bug Fixes

* **lending:** select GL accounts by loan currency, seed EUR/USD/GBP ([#1275](https://github.com/JiRaska/open-bank-oss/issues/1275)) ([#1898](https://github.com/JiRaska/open-bank-oss/issues/1898)) ([768a6f7](https://github.com/JiRaska/open-bank-oss/commit/768a6f736ff578b90196628e300205fa4d8982ce))

## [0.11.4](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.11.3...lending-service-v0.11.4) (2026-07-19)


### Bug Fixes

* **lending:** point funding-clearing at the account ledger actually seeded ([#1731](https://github.com/JiRaska/open-bank-oss/issues/1731)) ([a5f6acc](https://github.com/JiRaska/open-bank-oss/commit/a5f6acc8a051462aeaa03067625f799aa039ecab)), closes [#1720](https://github.com/JiRaska/open-bank-oss/issues/1720)

## [0.11.3](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.11.2...lending-service-v0.11.3) (2026-07-19)


### Bug Fixes

* **lending:** let the customer-edge read a customer's own loans ([#1694](https://github.com/JiRaska/open-bank-oss/issues/1694)) ([3add309](https://github.com/JiRaska/open-bank-oss/commit/3add309f2092bce6c158467fd5909c19fff0ed4b))

## [0.11.2](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.11.1...lending-service-v0.11.2) (2026-07-17)


### Bug Fixes

* **lending:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1469](https://github.com/JiRaska/open-bank-oss/issues/1469)) ([6b02825](https://github.com/JiRaska/open-bank-oss/commit/6b02825cbccd5d11f0788c3574e1f51ba4a4141e))

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.11.0...lending-service-v0.11.1) (2026-07-16)


### Bug Fixes

* **lending:** capitalize accrued interest on reschedule, derecognize it on write-off ([#1253](https://github.com/JiRaska/open-bank-oss/issues/1253)) ([5e14908](https://github.com/JiRaska/open-bank-oss/commit/5e14908a9440eb8614a0c33da0eff4a116e9e666))
* **lending:** reverse accrued interest on reschedule, derecognize it on write-off ([#1236](https://github.com/JiRaska/open-bank-oss/issues/1236)) ([b18c74e](https://github.com/JiRaska/open-bank-oss/commit/b18c74e8bd3e3d13f11f1d0225c7757d91f2b733)), closes [#470](https://github.com/JiRaska/open-bank-oss/issues/470)

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.10.1...lending-service-v0.11.0) (2026-07-11)


### Features

* **lending:** loan rescheduling/restructuring with optional forgiveness (issue [#667](https://github.com/JiRaska/open-bank-oss/issues/667)/[#668](https://github.com/JiRaska/open-bank-oss/issues/668)) ([#711](https://github.com/JiRaska/open-bank-oss/issues/711)) ([202f5c4](https://github.com/JiRaska/open-bank-oss/commit/202f5c4c0cc348898a0d77d155293c9a6991a6cb))

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.10.0...lending-service-v0.10.1) (2026-07-09)


### Bug Fixes

* **lending:** wire real outbox-writing adapter for loan domain events ([#652](https://github.com/JiRaska/open-bank-oss/issues/652)) ([59a6a48](https://github.com/JiRaska/open-bank-oss/commit/59a6a48db77a1236803513c271390087109f8817)), closes [#651](https://github.com/JiRaska/open-bank-oss/issues/651)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.9.0...lending-service-v0.10.0) (2026-07-09)


### Features

* **lending:** four-eyes gate for collateral registration (ADR-0028 follow-up) ([#631](https://github.com/JiRaska/open-bank-oss/issues/631)) ([0b2ddab](https://github.com/JiRaska/open-bank-oss/commit/0b2ddabea0afcc184b6c8845c22d7022ae0e3c30)), closes [#621](https://github.com/JiRaska/open-bank-oss/issues/621)

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.8.0...lending-service-v0.9.0) (2026-07-09)


### Features

* **lending, anacredit:** loan.stage_changed event integration ([#642](https://github.com/JiRaska/open-bank-oss/issues/642)) ([d456578](https://github.com/JiRaska/open-bank-oss/commit/d456578a94dcad64ccf11ba36dc1d3886cc7cbc0))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.7.0...lending-service-v0.8.0) (2026-07-08)


### Features

* **lending:** collateral-adjusted LGD in IFRS 9 ECL (ADR-0028 D1) ([#607](https://github.com/JiRaska/open-bank-oss/issues/607)) ([7a5b639](https://github.com/JiRaska/open-bank-oss/commit/7a5b63925c07f460c13dc9285c09578801bb88c4)), closes [#604](https://github.com/JiRaska/open-bank-oss/issues/604)
* **lending:** IFRS 9 provisioning first increment — stage bucketing, delta ECL, ledger posting ([#535](https://github.com/JiRaska/open-bank-oss/issues/535)) ([31c2490](https://github.com/JiRaska/open-bank-oss/commit/31c249055de124e628c445c372c2dca798aed51f)), closes [#532](https://github.com/JiRaska/open-bank-oss/issues/532)

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.6.6...lending-service-v0.7.0) (2026-07-08)


### Features

* **lending:** wire four-eyes enforcement mechanism (ADR-0155) ([#563](https://github.com/JiRaska/open-bank-oss/issues/563)) ([df5e2ce](https://github.com/JiRaska/open-bank-oss/commit/df5e2cee5c63194553b7a7865a5f44b35d63d9cb)), closes [#413](https://github.com/JiRaska/open-bank-oss/issues/413)

## [0.6.6](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.6.5...lending-service-v0.6.6) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.6.5](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.6.4...lending-service-v0.6.5) (2026-07-07)


### Security

* **lending:** enforce OPA authorization on lending endpoints (ADR-0034 Phase 5) ([#392](https://github.com/JiRaska/open-bank-oss/issues/392)) ([a3ab024](https://github.com/JiRaska/open-bank-oss/commit/a3ab02462e32f95b63b1427032f0b9bcb20af056))

## [0.6.4](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.6.3...lending-service-v0.6.4) (2026-06-30)


### Security

* **lending:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2a) ([#2746](https://github.com/JiRaska/open-bank-oss/issues/2746)) ([b96cc8a](https://github.com/JiRaska/open-bank-oss/commit/b96cc8a139f786c9e426122934d4a68293565c03))

## [0.6.3](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.6.2...lending-service-v0.6.3) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.6.1...lending-service-v0.6.2) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.6.0...lending-service-v0.6.1) (2026-06-25)


### Bug Fixes

* **interest,dispute,lending:** complete Clock injection (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2136](https://github.com/JiRaska/open-bank-oss/issues/2136)) ([41a2921](https://github.com/JiRaska/open-bank-oss/commit/41a2921b9b89cc06025cc71a4b428cb019fb499f))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.5.0...lending-service-v0.6.0) (2026-06-25)


### Features

* **lending:** inject Clock for DST determinism (ADR-0100 Layer 1) ([#2086](https://github.com/JiRaska/open-bank-oss/issues/2086)) ([dc118ec](https://github.com/JiRaska/open-bank-oss/commit/dc118ecefe2f9bd36d86cf2461c0f9715b128c0d))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.4.0...lending-service-v0.5.0) (2026-06-25)


### Features

* **lending:** add @Authorize resource-gate on REST endpoints (ADR-0034 D3) ([#1278](https://github.com/JiRaska/open-bank-oss/issues/1278)) ([cfafdb4](https://github.com/JiRaska/open-bank-oss/commit/cfafdb4cc9b8c2b887cf0ee7dfb4b5b748e885bb))


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank-oss/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **lending:** align openapi info.version major to API v1 (ADR-0048) ([#1397](https://github.com/JiRaska/open-bank-oss/issues/1397)) ([9713355](https://github.com/JiRaska/open-bank-oss/commit/97133558d854c3a79ebb523b264359b82151beb4))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.3.0...lending-service-v0.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **lending:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#804](https://github.com/JiRaska/open-bank-oss/issues/804)) ([df39f9a](https://github.com/JiRaska/open-bank-oss/commit/df39f9a15db7a5aea4b0f8f8dc7642c98a22e6b1))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.2.0...lending-service-v0.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/lending-service-v0.1.0...lending-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))


### Security

* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank-oss/issues/342)) ([e368296](https://github.com/JiRaska/open-bank-oss/commit/e3682965a4f7df3b7328e8a741e4809604706390))
