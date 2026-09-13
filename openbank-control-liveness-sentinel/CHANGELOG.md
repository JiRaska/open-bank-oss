# Changelog

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.7.2...control-liveness-sentinel-v0.7.3) (2026-08-27)


### Performance

* **flaky-test-hunter:** add read-path smoke coverage ([#7374](https://github.com/JiRaska/open-bank-oss/issues/7374)) ([b93d00d](https://github.com/JiRaska/open-bank-oss/commit/b93d00d3ab19f3705b87212ab0ce60cfd1119f6f))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.7.1...control-liveness-sentinel-v0.7.2) (2026-08-09)


### Bug Fixes

* **libs:** seed the workflow-liveness age gauge at registration, not at Instant.EPOCH ([#4208](https://github.com/JiRaska/open-bank-oss/issues/4208)) ([73d5f0d](https://github.com/JiRaska/open-bank-oss/commit/73d5f0dc4eabb2349ddb9ca02da9c8a3e81453f2))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.7.0...control-liveness-sentinel-v0.7.1) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.6.1...control-liveness-sentinel-v0.7.0) (2026-08-02)


### Features

* **control-liveness-sentinel:** run the daily check on a schedule, not only when asked ([#3339](https://github.com/JiRaska/open-bank-oss/issues/3339)) ([0dad251](https://github.com/JiRaska/open-bank-oss/commit/0dad2515909de6df8657230edddd7a5bbe5f0d88)), closes [#2239](https://github.com/JiRaska/open-bank-oss/issues/2239)

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.6.0...control-liveness-sentinel-v0.6.1) (2026-08-02)


### Bug Fixes

* **liveness-sentinel:** the assert was right — the prompt never said whether refusing may quote ([#3245](https://github.com/JiRaska/open-bank-oss/issues/3245)) ([29ed626](https://github.com/JiRaska/open-bank-oss/commit/29ed6263a230c685edf6d05a753534a7f7163762))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.5.2...control-liveness-sentinel-v0.6.0) (2026-08-01)


### Features

* **observability:** make fleet LLM spend and reliability observable in Prometheus ([#3043](https://github.com/JiRaska/open-bank-oss/issues/3043)) ([000ba2a](https://github.com/JiRaska/open-bank-oss/commit/000ba2a516069ba4c65b50015a76b4086b229b30))

## [0.5.2](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.5.1...control-liveness-sentinel-v0.5.2) (2026-07-25)


### Bug Fixes

* **authz:** realm-issued role names across 9 services + enforce @RolesAllowed parity ([#2404](https://github.com/JiRaska/open-bank-oss/issues/2404)) ([#2418](https://github.com/JiRaska/open-bank-oss/issues/2418)) ([64a1f9b](https://github.com/JiRaska/open-bank-oss/commit/64a1f9be47bedbda5ffad876bb0394f404503821))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.5.0...control-liveness-sentinel-v0.5.1) (2026-07-25)


### Bug Fixes

* **control-liveness-sentinel:** query the workflow-liveness gauge name that is actually emitted ([#2187](https://github.com/JiRaska/open-bank-oss/issues/2187)) ([#2238](https://github.com/JiRaska/open-bank-oss/issues/2238)) ([407488a](https://github.com/JiRaska/open-bank-oss/commit/407488a44ed214098d6ed0c702c25b72167d8e38))


### Security

* **liveness:** strengthen the control-liveness-sentinel prompt against injected remediation commands ([#1918](https://github.com/JiRaska/open-bank-oss/issues/1918)) ([#2321](https://github.com/JiRaska/open-bank-oss/issues/2321)) ([cffc5ff](https://github.com/JiRaska/open-bank-oss/commit/cffc5ff933be268d3ee675de96382607cad9877b))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.4.0...control-liveness-sentinel-v0.5.0) (2026-07-25)


### Features

* **control-liveness-sentinel:** load prompt from the registry + route via the LlmGatewayPort seam ([#1918](https://github.com/JiRaska/open-bank-oss/issues/1918)) ([#2259](https://github.com/JiRaska/open-bank-oss/issues/2259)) ([0c2a1a1](https://github.com/JiRaska/open-bank-oss/commit/0c2a1a15f2d1b5591d85f455dc87c546e480a626))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.3.1...control-liveness-sentinel-v0.4.0) (2026-07-16)


### Features

* **vop:** add Verification of Payee backend; give control-plane agents episodic memory ([#1195](https://github.com/JiRaska/open-bank-oss/issues/1195)) ([91460fc](https://github.com/JiRaska/open-bank-oss/commit/91460fcc62bb72f4a99953e51e90374597dda9c3))

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.3.0...control-liveness-sentinel-v0.3.1) (2026-07-14)


### Bug Fixes

* **control-liveness-sentinel:** boot crash on unmapped config keys ([#1124](https://github.com/JiRaska/open-bank-oss/issues/1124)) ([2b858b3](https://github.com/JiRaska/open-bank-oss/commit/2b858b3f1bbd36b289c35adc0d406b20c2c6c91d))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.2.0...control-liveness-sentinel-v0.3.0) (2026-07-14)


### Features

* **control-liveness-sentinel:** wire real LLM diagnosis and GitHub proposal creation ([#1087](https://github.com/JiRaska/open-bank-oss/issues/1087)) ([59ecdfe](https://github.com/JiRaska/open-bank-oss/commit/59ecdfe02d905221e9f62d709949943589bb3f76))


### Bug Fixes

* **infra:** add the 6 new control/governance agents to auto-deploy fleet ([#1091](https://github.com/JiRaska/open-bank-oss/issues/1091)) ([ca311e0](https://github.com/JiRaska/open-bank-oss/commit/ca311e04e013862f91249b92709de61a0a09ae7c))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/control-liveness-sentinel-v0.1.0...control-liveness-sentinel-v0.2.0) (2026-07-14)


### Features

* **control-liveness-sentinel:** add ADR-0163 fleet-wide correlator agent for ADR-0160 mechanisms ([#1026](https://github.com/JiRaska/open-bank-oss/issues/1026)) ([dc13ec0](https://github.com/JiRaska/open-bank-oss/commit/dc13ec0813f2bde2b9cdfbb73a1c3551d899f431))


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## Changelog
