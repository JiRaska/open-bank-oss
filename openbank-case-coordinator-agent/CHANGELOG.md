# Changelog

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.7.0...case-coordinator-agent-v0.8.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.6.2...case-coordinator-agent-v0.7.0) (2026-08-22)


### Features

* **admin-ui:** add evidence-backed Agent Control Room ([#6424](https://github.com/JiRaska/open-bank-oss/issues/6424)) ([02317dd](https://github.com/JiRaska/open-bank-oss/commit/02317ddd2080efa3d6293999b39c3e1099fb009b))

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.6.1...case-coordinator-agent-v0.6.2) (2026-08-20)


### Bug Fixes

* **case-coordinator:** bump thread API contract ([#5887](https://github.com/JiRaska/open-bank-oss/issues/5887)) ([a4e80bd](https://github.com/JiRaska/open-bank-oss/commit/a4e80bd00df2ecf6f274d52a1304c30c154f7e4a))

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.6.0...case-coordinator-agent-v0.6.1) (2026-08-20)


### Bug Fixes

* **case-coordinator:** bump API contract to 1.2.0 for the shadow-pilot additions ([#5881](https://github.com/JiRaska/open-bank-oss/issues/5881)) ([c13e7f6](https://github.com/JiRaska/open-bank-oss/commit/c13e7f69287a84a40847ead6c1755ef2ab24ff09))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.5.0...case-coordinator-agent-v0.6.0) (2026-08-20)


### Features

* **case-coordinator:** isolate incident shadow pilot ([#5861](https://github.com/JiRaska/open-bank-oss/issues/5861)) ([c78a10d](https://github.com/JiRaska/open-bank-oss/commit/c78a10d78732c186c390382aed5c7e6e36d0e777))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.4.5...case-coordinator-agent-v0.5.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.4.5](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.4.4...case-coordinator-agent-v0.4.5) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.4.4](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.4.3...case-coordinator-agent-v0.4.4) (2026-08-16)


### Bug Fixes

* **case-coordinator:** authorise the asserted agent identity against the caller ([#4834](https://github.com/JiRaska/open-bank-oss/issues/4834)) ([#4985](https://github.com/JiRaska/open-bank-oss/issues/4985)) ([02fff25](https://github.com/JiRaska/open-bank-oss/commit/02fff25127fb01bea2e0bbb12134d3054ab909fe))

## [0.4.3](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.4.2...case-coordinator-agent-v0.4.3) (2026-08-15)


### Bug Fixes

* **case-coordinator:** the signal denial stops echoing the caller's agentId ([#4834](https://github.com/JiRaska/open-bank-oss/issues/4834)) ([#4863](https://github.com/JiRaska/open-bank-oss/issues/4863)) ([f3e692a](https://github.com/JiRaska/open-bank-oss/commit/f3e692aae70f3f6e2b3b53a9d225abf0e255c7eb))

## [0.4.2](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.4.1...case-coordinator-agent-v0.4.2) (2026-08-14)


### Bug Fixes

* **case-coordinator:** key the open-rate quota on the caller, not on the identity it claims ([#4834](https://github.com/JiRaska/open-bank-oss/issues/4834)) ([#4840](https://github.com/JiRaska/open-bank-oss/issues/4840)) ([6a61229](https://github.com/JiRaska/open-bank-oss/commit/6a61229fa783fd9c0c07f997fc92d4d066451ea8))

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.4.0...case-coordinator-agent-v0.4.1) (2026-08-14)


### Bug Fixes

* **case-coordinator:** stop logging and echoing an unsanitised openedBy ([#4215](https://github.com/JiRaska/open-bank-oss/issues/4215)) ([#4833](https://github.com/JiRaska/open-bank-oss/issues/4833)) ([8f5e53a](https://github.com/JiRaska/open-bank-oss/commit/8f5e53a0e94d2ef43d2f33249b92682bfba9e7ef))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.3.0...case-coordinator-agent-v0.4.0) (2026-08-09)


### Features

* **case-coordinator:** deploy the swarm case-coordinator runtime via GitOps ([#4187](https://github.com/JiRaska/open-bank-oss/issues/4187)) ([#4236](https://github.com/JiRaska/open-bank-oss/issues/4236)) ([46a8066](https://github.com/JiRaska/open-bank-oss/commit/46a8066ae0c0d91fdf37799db5e2b61b9b585532))


### Bug Fixes

* **ci:** re-align case-coordinator-agent's Dockerfile FROM with the deploy recipe ([#4392](https://github.com/JiRaska/open-bank-oss/issues/4392)) ([cd3d010](https://github.com/JiRaska/open-bank-oss/commit/cd3d010f27e1b5eca829dac390d0843e99f0f841))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.2.0...case-coordinator-agent-v0.3.0) (2026-08-08)


### Features

* **case-coordinator:** add case thread read API and admin-ui pact ([#4226](https://github.com/JiRaska/open-bank-oss/issues/4226)) ([b99a31f](https://github.com/JiRaska/open-bank-oss/commit/b99a31f45d8cfbc68a9b7c4228944b1378f405d1))
* **case-coordinator:** add Temporal CaseWorkflow swarm lifecycle ([#4181](https://github.com/JiRaska/open-bank-oss/issues/4181)) ([#4215](https://github.com/JiRaska/open-bank-oss/issues/4215)) ([4405a74](https://github.com/JiRaska/open-bank-oss/commit/4405a74fa23ac7460e3df56e1c51bb42a817a856))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/case-coordinator-agent-v0.1.0...case-coordinator-agent-v0.2.0) (2026-08-07)


### Features

* **case-coordinator-agent:** create module and register in rules.yaml (ADR-0244) ([#3772](https://github.com/JiRaska/open-bank-oss/issues/3772)) ([fbde2b6](https://github.com/JiRaska/open-bank-oss/commit/fbde2b65efed06296d96c8bb6e5cb7a2801a54a3))
