# Changelog

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/devops-agent-v0.5.1...devops-agent-v0.6.0) (2026-08-01)


### Features

* **observability:** make fleet LLM spend and reliability observable in Prometheus ([#3043](https://github.com/JiRaska/open-bank-oss/issues/3043)) ([000ba2a](https://github.com/JiRaska/open-bank-oss/commit/000ba2a516069ba4c65b50015a76b4086b229b30))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/devops-agent-v0.5.0...devops-agent-v0.5.1) (2026-07-25)


### Bug Fixes

* **authz:** realm-issued role names across 9 services + enforce @RolesAllowed parity ([#2404](https://github.com/JiRaska/open-bank-oss/issues/2404)) ([#2418](https://github.com/JiRaska/open-bank-oss/issues/2418)) ([64a1f9b](https://github.com/JiRaska/open-bank-oss/commit/64a1f9be47bedbda5ffad876bb0394f404503821))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/devops-agent-v0.4.0...devops-agent-v0.5.0) (2026-07-25)


### Features

* **devops-agent:** load prompts from the registry + route via the LlmGatewayPort seam ([#1918](https://github.com/JiRaska/open-bank-oss/issues/1918)) ([#2240](https://github.com/JiRaska/open-bank-oss/issues/2240)) ([fb44dd7](https://github.com/JiRaska/open-bank-oss/commit/fb44dd79d40b9f64a076e1fe03848aa84db7d89d))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/devops-agent-v0.3.2...devops-agent-v0.4.0) (2026-07-17)


### Features

* **vop:** add Verification of Payee backend; give control-plane agents episodic memory ([#1195](https://github.com/JiRaska/open-bank-oss/issues/1195)) ([91460fc](https://github.com/JiRaska/open-bank-oss/commit/91460fcc62bb72f4a99953e51e90374597dda9c3))

## [0.3.2](https://github.com/JiRaska/open-bank-oss/compare/devops-agent-v0.3.1...devops-agent-v0.3.2) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/devops-agent-v0.3.0...devops-agent-v0.3.1) (2026-07-02)


### Security

* **devops-agent:** sanitize finding id before logging (CodeQL java/log-injection) ([#152](https://github.com/JiRaska/open-bank-oss/issues/152)) ([363b7b4](https://github.com/JiRaska/open-bank-oss/commit/363b7b454820304d8997c5e6ffa2251fa197c96b))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/devops-agent-v0.2.0...devops-agent-v0.3.0) (2026-06-29)


### Features

* **devops:** activate the D1 + D5 detectors via the GitHub API (ADR-0119) ([#2309](https://github.com/JiRaska/open-bank-oss/issues/2309)) ([9071a5f](https://github.com/JiRaska/open-bank-oss/commit/9071a5fb698f98b717f4c87114b88fcaf33191a5))
* **devops:** add AI DevOps agent for SSDLC/DORA monitoring (ADR-0119) ([#2295](https://github.com/JiRaska/open-bank-oss/issues/2295)) ([e811e4f](https://github.com/JiRaska/open-bank-oss/commit/e811e4fd9510517f2e6fd099a2b0b7556f85b2aa))
* **devops:** HITL approve/reject for findings (ADR-0119) ([#2308](https://github.com/JiRaska/open-bank-oss/issues/2308)) ([b42a4c3](https://github.com/JiRaska/open-bank-oss/commit/b42a4c3f4d75b0ff2199fc5fa9336f6fc45c21b8))
* **devops:** open remediation-proposal PRs on GitHub (ADR-0119) ([#2307](https://github.com/JiRaska/open-bank-oss/issues/2307)) ([82a658e](https://github.com/JiRaska/open-bank-oss/commit/82a658e3f62d3f29f5f30427af62ac3767fd51e4))
* **devops:** persist findings in Postgres (ADR-0119) ([#2306](https://github.com/JiRaska/open-bank-oss/issues/2306)) ([23ad901](https://github.com/JiRaska/open-bank-oss/commit/23ad901750c9e2f02064ce8855012f3068e74522))
* **devops:** wire live DeepSeek LLM diagnosis for the devops-agent (ADR-0119) ([#2303](https://github.com/JiRaska/open-bank-oss/issues/2303)) ([ef69886](https://github.com/JiRaska/open-bank-oss/commit/ef6988652b7fabea43bdcbd9cf37569b4dc9f1d2))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/devops-agent-v0.1.0...devops-agent-v0.2.0) (2026-06-27)


### Features

* **devops:** activate the D1 + D5 detectors via the GitHub API (ADR-0119) ([#2309](https://github.com/JiRaska/open-bank-oss/issues/2309)) ([eeb12b8](https://github.com/JiRaska/open-bank-oss/commit/eeb12b85de59cea8c8c320b04b4c4b0be3684fae))
* **devops:** add AI DevOps agent for SSDLC/DORA monitoring (ADR-0119) ([#2295](https://github.com/JiRaska/open-bank-oss/issues/2295)) ([c0ede0a](https://github.com/JiRaska/open-bank-oss/commit/c0ede0a075be0be7381637476d0944c0a7ac9792))
* **devops:** HITL approve/reject for findings (ADR-0119) ([#2308](https://github.com/JiRaska/open-bank-oss/issues/2308)) ([0e21c8c](https://github.com/JiRaska/open-bank-oss/commit/0e21c8c3e5d8a855fec9a25204d5b16e3559804f))
* **devops:** open remediation-proposal PRs on GitHub (ADR-0119) ([#2307](https://github.com/JiRaska/open-bank-oss/issues/2307)) ([5fe2836](https://github.com/JiRaska/open-bank-oss/commit/5fe2836a970b5c08b74889e05d20d2e2c15c778d))
* **devops:** persist findings in Postgres (ADR-0119) ([#2306](https://github.com/JiRaska/open-bank-oss/issues/2306)) ([502883f](https://github.com/JiRaska/open-bank-oss/commit/502883f49be1583a6353d2d9019482a7eb53e82c))
* **devops:** wire live DeepSeek LLM diagnosis for the devops-agent (ADR-0119) ([#2303](https://github.com/JiRaska/open-bank-oss/issues/2303)) ([f35293f](https://github.com/JiRaska/open-bank-oss/commit/f35293f111bb6f7899a17390b1142511ca197726))
