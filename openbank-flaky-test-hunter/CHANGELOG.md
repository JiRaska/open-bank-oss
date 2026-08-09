# Changelog

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/flaky-test-hunter-v0.5.0...flaky-test-hunter-v0.6.0) (2026-08-06)


### Features

* **flaky-test-hunter:** register prompt registry and wire LlmDiagnosisAdapter to shared gateway ([#3810](https://github.com/JiRaska/open-bank-oss/issues/3810)) ([8b94785](https://github.com/JiRaska/open-bank-oss/commit/8b9478502942572d819d78f4ef32c8b8a2d16224))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/flaky-test-hunter-v0.4.0...flaky-test-hunter-v0.5.0) (2026-08-05)


### Features

* **scheduler:** register workflow liveness for second non-money-path batch (ADR-0237) ([#3739](https://github.com/JiRaska/open-bank-oss/issues/3739)) ([735e8bd](https://github.com/JiRaska/open-bank-oss/commit/735e8bdc12fbf541464aeb4f15ce767cb7866e78))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/flaky-test-hunter-v0.3.2...flaky-test-hunter-v0.4.0) (2026-08-02)


### Features

* **governance-auditor,release-steward,docs-truth-agent,authz-policy-auditor,flaky-test-hunter:** run the periodic sweep on a schedule, not only when asked ([#3500](https://github.com/JiRaska/open-bank-oss/issues/3500)) ([b2697d6](https://github.com/JiRaska/open-bank-oss/commit/b2697d6e85ef017b088e7cf2c9ba137702e1f2ce))

## [0.3.2](https://github.com/JiRaska/open-bank-oss/compare/flaky-test-hunter-v0.3.1...flaky-test-hunter-v0.3.2) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/flaky-test-hunter-v0.3.0...flaky-test-hunter-v0.3.1) (2026-07-25)


### Bug Fixes

* **authz:** realm-issued role names across 9 services + enforce @RolesAllowed parity ([#2404](https://github.com/JiRaska/open-bank-oss/issues/2404)) ([#2418](https://github.com/JiRaska/open-bank-oss/issues/2418)) ([64a1f9b](https://github.com/JiRaska/open-bank-oss/commit/64a1f9be47bedbda5ffad876bb0394f404503821))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/flaky-test-hunter-v0.2.0...flaky-test-hunter-v0.3.0) (2026-07-16)


### Features

* **vop:** add Verification of Payee backend; give control-plane agents episodic memory ([#1195](https://github.com/JiRaska/open-bank-oss/issues/1195)) ([91460fc](https://github.com/JiRaska/open-bank-oss/commit/91460fcc62bb72f4a99953e51e90374597dda9c3))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/flaky-test-hunter-v0.1.0...flaky-test-hunter-v0.2.0) (2026-07-14)


### Features

* **flaky-test-hunter:** add ADR-0168 fleet-wide silent-test-failure detector agent ([#1040](https://github.com/JiRaska/open-bank-oss/issues/1040)) ([fca88f6](https://github.com/JiRaska/open-bank-oss/commit/fca88f6258319e4cc95630b5660b82e6489db2a5))


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)
* **infra:** add the 6 new control/governance agents to auto-deploy fleet ([#1091](https://github.com/JiRaska/open-bank-oss/issues/1091)) ([ca311e0](https://github.com/JiRaska/open-bank-oss/commit/ca311e04e013862f91249b92709de61a0a09ae7c))

## Changelog
