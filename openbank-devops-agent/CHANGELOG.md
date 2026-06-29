# Changelog

## [0.3.0](https://github.com/JiRaska/open-bank/compare/devops-agent-v0.2.0...devops-agent-v0.3.0) (2026-06-29)


### Features

* **devops:** activate the D1 + D5 detectors via the GitHub API (ADR-0119) ([#2309](https://github.com/JiRaska/open-bank/issues/2309)) ([9071a5f](https://github.com/JiRaska/open-bank/commit/9071a5fb698f98b717f4c87114b88fcaf33191a5))
* **devops:** add AI DevOps agent for SSDLC/DORA monitoring (ADR-0119) ([#2295](https://github.com/JiRaska/open-bank/issues/2295)) ([e811e4f](https://github.com/JiRaska/open-bank/commit/e811e4fd9510517f2e6fd099a2b0b7556f85b2aa))
* **devops:** HITL approve/reject for findings (ADR-0119) ([#2308](https://github.com/JiRaska/open-bank/issues/2308)) ([b42a4c3](https://github.com/JiRaska/open-bank/commit/b42a4c3f4d75b0ff2199fc5fa9336f6fc45c21b8))
* **devops:** open remediation-proposal PRs on GitHub (ADR-0119) ([#2307](https://github.com/JiRaska/open-bank/issues/2307)) ([82a658e](https://github.com/JiRaska/open-bank/commit/82a658e3f62d3f29f5f30427af62ac3767fd51e4))
* **devops:** persist findings in Postgres (ADR-0119) ([#2306](https://github.com/JiRaska/open-bank/issues/2306)) ([23ad901](https://github.com/JiRaska/open-bank/commit/23ad901750c9e2f02064ce8855012f3068e74522))
* **devops:** wire live DeepSeek LLM diagnosis for the devops-agent (ADR-0119) ([#2303](https://github.com/JiRaska/open-bank/issues/2303)) ([ef69886](https://github.com/JiRaska/open-bank/commit/ef6988652b7fabea43bdcbd9cf37569b4dc9f1d2))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank/issues/2342)

## [0.2.0](https://github.com/JiRaska/open-bank/compare/devops-agent-v0.1.0...devops-agent-v0.2.0) (2026-06-27)


### Features

* **devops:** activate the D1 + D5 detectors via the GitHub API (ADR-0119) ([#2309](https://github.com/JiRaska/open-bank/issues/2309)) ([eeb12b8](https://github.com/JiRaska/open-bank/commit/eeb12b85de59cea8c8c320b04b4c4b0be3684fae))
* **devops:** add AI DevOps agent for SSDLC/DORA monitoring (ADR-0119) ([#2295](https://github.com/JiRaska/open-bank/issues/2295)) ([c0ede0a](https://github.com/JiRaska/open-bank/commit/c0ede0a075be0be7381637476d0944c0a7ac9792))
* **devops:** HITL approve/reject for findings (ADR-0119) ([#2308](https://github.com/JiRaska/open-bank/issues/2308)) ([0e21c8c](https://github.com/JiRaska/open-bank/commit/0e21c8c3e5d8a855fec9a25204d5b16e3559804f))
* **devops:** open remediation-proposal PRs on GitHub (ADR-0119) ([#2307](https://github.com/JiRaska/open-bank/issues/2307)) ([5fe2836](https://github.com/JiRaska/open-bank/commit/5fe2836a970b5c08b74889e05d20d2e2c15c778d))
* **devops:** persist findings in Postgres (ADR-0119) ([#2306](https://github.com/JiRaska/open-bank/issues/2306)) ([502883f](https://github.com/JiRaska/open-bank/commit/502883f49be1583a6353d2d9019482a7eb53e82c))
* **devops:** wire live DeepSeek LLM diagnosis for the devops-agent (ADR-0119) ([#2303](https://github.com/JiRaska/open-bank/issues/2303)) ([f35293f](https://github.com/JiRaska/open-bank/commit/f35293f111bb6f7899a17390b1142511ca197726))
