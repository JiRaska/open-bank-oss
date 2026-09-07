# Changelog

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/docs-truth-agent-v0.6.0...docs-truth-agent-v0.6.1) (2026-09-07)


### Bug Fixes

* **authz-policy-auditor:** refuse instead of returning a fabricated proposal URL ([#5906](https://github.com/JiRaska/open-bank-oss/issues/5906)) ([0b476ab](https://github.com/JiRaska/open-bank-oss/commit/0b476ab6c9083eae5c8926dcd0e194afc940846b)), closes [#5897](https://github.com/JiRaska/open-bank-oss/issues/5897)

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/docs-truth-agent-v0.5.0...docs-truth-agent-v0.6.0) (2026-08-07)


### Features

* **scheduler:** register workflow liveness for five non-money-path jobs (ADR-0237) ([#3735](https://github.com/JiRaska/open-bank-oss/issues/3735)) ([90801b2](https://github.com/JiRaska/open-bank-oss/commit/90801b203c4eec229180321e869c926be3a99ee8))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/docs-truth-agent-v0.4.0...docs-truth-agent-v0.5.0) (2026-08-06)


### Features

* **docs-truth-agent:** wire prompt registry and LLM gateway ([#3787](https://github.com/JiRaska/open-bank-oss/issues/3787)) ([1d00fdd](https://github.com/JiRaska/open-bank-oss/commit/1d00fddf7c67c76f2c020bae669a61c15503b947)), closes [#3786](https://github.com/JiRaska/open-bank-oss/issues/3786)

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/docs-truth-agent-v0.3.3...docs-truth-agent-v0.4.0) (2026-08-02)


### Features

* **governance-auditor,release-steward,docs-truth-agent,authz-policy-auditor,flaky-test-hunter:** run the periodic sweep on a schedule, not only when asked ([#3500](https://github.com/JiRaska/open-bank-oss/issues/3500)) ([b2697d6](https://github.com/JiRaska/open-bank-oss/commit/b2697d6e85ef017b088e7cf2c9ba137702e1f2ce))

## [0.3.3](https://github.com/JiRaska/open-bank-oss/compare/docs-truth-agent-v0.3.2...docs-truth-agent-v0.3.3) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.3.2](https://github.com/JiRaska/open-bank-oss/compare/docs-truth-agent-v0.3.1...docs-truth-agent-v0.3.2) (2026-07-25)


### Bug Fixes

* **authz:** realm-issued role names across 9 services + enforce @RolesAllowed parity ([#2404](https://github.com/JiRaska/open-bank-oss/issues/2404)) ([#2418](https://github.com/JiRaska/open-bank-oss/issues/2418)) ([64a1f9b](https://github.com/JiRaska/open-bank-oss/commit/64a1f9be47bedbda5ffad876bb0394f404503821))

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/docs-truth-agent-v0.3.0...docs-truth-agent-v0.3.1) (2026-07-22)


### Bug Fixes

* **libs:** bump pgjdbc to 42.7.12, fix ADR delivery-status front-matter parsing ([#1886](https://github.com/JiRaska/open-bank-oss/issues/1886)) ([31c0fb6](https://github.com/JiRaska/open-bank-oss/commit/31c0fb620e59b4c156fa138a6a1b622ebe5f924e))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/docs-truth-agent-v0.2.1...docs-truth-agent-v0.3.0) (2026-07-16)


### Features

* **vop:** add Verification of Payee backend; give control-plane agents episodic memory ([#1195](https://github.com/JiRaska/open-bank-oss/issues/1195)) ([91460fc](https://github.com/JiRaska/open-bank-oss/commit/91460fcc62bb72f4a99953e51e90374597dda9c3))

## [0.2.1](https://github.com/JiRaska/open-bank-oss/compare/docs-truth-agent-v0.2.0...docs-truth-agent-v0.2.1) (2026-07-14)


### Bug Fixes

* **infra:** add the 6 new control/governance agents to auto-deploy fleet ([#1091](https://github.com/JiRaska/open-bank-oss/issues/1091)) ([ca311e0](https://github.com/JiRaska/open-bank-oss/commit/ca311e04e013862f91249b92709de61a0a09ae7c))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/docs-truth-agent-v0.1.0...docs-truth-agent-v0.2.0) (2026-07-14)


### Features

* **docs-truth-agent:** add ADR-0166 ADR-status-vs-code drift detector agent ([#1036](https://github.com/JiRaska/open-bank-oss/issues/1036)) ([4829699](https://github.com/JiRaska/open-bank-oss/commit/4829699def680117c402d8a73adefd1df26eaf7e))


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## Changelog
