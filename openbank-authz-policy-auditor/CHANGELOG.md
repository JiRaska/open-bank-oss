# Changelog

## [0.6.2](https://github.com/JiRaska/open-bank-oss/compare/authz-policy-auditor-v0.6.1...authz-policy-auditor-v0.6.2) (2026-09-07)


### Bug Fixes

* **authz-policy-auditor:** refuse instead of returning a fabricated proposal URL ([#5906](https://github.com/JiRaska/open-bank-oss/issues/5906)) ([0b476ab](https://github.com/JiRaska/open-bank-oss/commit/0b476ab6c9083eae5c8926dcd0e194afc940846b)), closes [#5897](https://github.com/JiRaska/open-bank-oss/issues/5897)

## [0.6.1](https://github.com/JiRaska/open-bank-oss/compare/authz-policy-auditor-v0.6.0...authz-policy-auditor-v0.6.1) (2026-08-27)


### Performance

* **flaky-test-hunter:** add read-path smoke coverage ([#7374](https://github.com/JiRaska/open-bank-oss/issues/7374)) ([b93d00d](https://github.com/JiRaska/open-bank-oss/commit/b93d00d3ab19f3705b87212ab0ce60cfd1119f6f))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/authz-policy-auditor-v0.5.0...authz-policy-auditor-v0.6.0) (2026-08-08)


### Features

* **authz-policy-auditor:** wire ADR-0148 prompt registry and LLM gateway (ADR-0167) ([#3775](https://github.com/JiRaska/open-bank-oss/issues/3775)) ([06aedd6](https://github.com/JiRaska/open-bank-oss/commit/06aedd6dd2176934298b997808d6ad23566d88ae))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/authz-policy-auditor-v0.4.0...authz-policy-auditor-v0.5.0) (2026-08-07)


### Features

* **scheduler:** register workflow liveness for five non-money-path jobs (ADR-0237) ([#3735](https://github.com/JiRaska/open-bank-oss/issues/3735)) ([90801b2](https://github.com/JiRaska/open-bank-oss/commit/90801b203c4eec229180321e869c926be3a99ee8))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/authz-policy-auditor-v0.3.2...authz-policy-auditor-v0.4.0) (2026-08-02)


### Features

* **governance-auditor,release-steward,docs-truth-agent,authz-policy-auditor,flaky-test-hunter:** run the periodic sweep on a schedule, not only when asked ([#3500](https://github.com/JiRaska/open-bank-oss/issues/3500)) ([b2697d6](https://github.com/JiRaska/open-bank-oss/commit/b2697d6e85ef017b088e7cf2c9ba137702e1f2ce))

## [0.3.2](https://github.com/JiRaska/open-bank-oss/compare/authz-policy-auditor-v0.3.1...authz-policy-auditor-v0.3.2) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/authz-policy-auditor-v0.3.0...authz-policy-auditor-v0.3.1) (2026-07-25)


### Bug Fixes

* **authz:** realm-issued role names across 9 services + enforce @RolesAllowed parity ([#2404](https://github.com/JiRaska/open-bank-oss/issues/2404)) ([#2418](https://github.com/JiRaska/open-bank-oss/issues/2418)) ([64a1f9b](https://github.com/JiRaska/open-bank-oss/commit/64a1f9be47bedbda5ffad876bb0394f404503821))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/authz-policy-auditor-v0.2.0...authz-policy-auditor-v0.3.0) (2026-07-16)


### Features

* **vop:** add Verification of Payee backend; give control-plane agents episodic memory ([#1195](https://github.com/JiRaska/open-bank-oss/issues/1195)) ([91460fc](https://github.com/JiRaska/open-bank-oss/commit/91460fcc62bb72f4a99953e51e90374597dda9c3))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/authz-policy-auditor-v0.1.0...authz-policy-auditor-v0.2.0) (2026-07-14)


### Features

* **authz-policy-auditor:** add ADR-0167 OPA/Rego static-policy auditor agent ([#1038](https://github.com/JiRaska/open-bank-oss/issues/1038)) ([0d752d6](https://github.com/JiRaska/open-bank-oss/commit/0d752d6db018bd0baf80e0e3f49d6f4e799c9373))


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)
* **infra:** add the 6 new control/governance agents to auto-deploy fleet ([#1091](https://github.com/JiRaska/open-bank-oss/issues/1091)) ([ca311e0](https://github.com/JiRaska/open-bank-oss/commit/ca311e04e013862f91249b92709de61a0a09ae7c))

## Changelog
