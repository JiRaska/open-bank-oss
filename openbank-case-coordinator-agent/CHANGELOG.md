# Changelog

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
