# Changelog

## [1.11.1](https://github.com/JiRaska/open-bank/compare/agent-service-v1.11.0...agent-service-v1.11.1) (2026-06-29)


### Security

* **agent:** bind asserted X-Agent-Id to verified operator roles (ADR-0031 D3a) ([#2403](https://github.com/JiRaska/open-bank/issues/2403)) ([5b94bc3](https://github.com/JiRaska/open-bank/commit/5b94bc3423c9a808e3c859ee8e485cff6dd97a37))
* **agent:** mint per-run pki-agent SVID in the BFF + base64 cert transport (ADR-0031 D3b) ([#2439](https://github.com/JiRaska/open-bank/issues/2439)) ([e8d5c92](https://github.com/JiRaska/open-bank/commit/e8d5c927d6f19d84c8c5eef5dc0df9a20718a011))
* **agent:** verify a PoP-signed pki-agent cert as the per-run agent identity (ADR-0031 D3b) ([#2412](https://github.com/JiRaska/open-bank/issues/2412)) ([f2e3537](https://github.com/JiRaska/open-bank/commit/f2e353757fd3255f0a3ffd2453be226a52546cb0))

## [1.11.0](https://github.com/JiRaska/open-bank/compare/agent-service-v1.10.1...agent-service-v1.11.0) (2026-06-28)


### Features

* **agent:** per-run OTel span for governed agent runs (ADR-0031 D7) ([#2385](https://github.com/JiRaska/open-bank/issues/2385)) ([baa1ce6](https://github.com/JiRaska/open-bank/commit/baa1ce68683e030bf62d2e5c2cd7b89d14161478))

## [1.10.1](https://github.com/JiRaska/open-bank/compare/agent-service-v1.10.0...agent-service-v1.10.1) (2026-06-27)


### Bug Fixes

* **agent:** suppress UnusedParameter in OversightEventConsumer ([#2232](https://github.com/JiRaska/open-bank/issues/2232)) ([f8ff3d2](https://github.com/JiRaska/open-bank/commit/f8ff3d29503c4be0e4905fbca516aebc3efb3586)), closes [#2084](https://github.com/JiRaska/open-bank/issues/2084)
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [1.10.0](https://github.com/JiRaska/open-bank/compare/agent-service-v1.9.0...agent-service-v1.10.0) (2026-06-25)


### Features

* **tpp-registry,statement,onboarding,agent,settlement,sdd:** inject Clock (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2138](https://github.com/JiRaska/open-bank/issues/2138)) ([baa0d03](https://github.com/JiRaska/open-bank/commit/baa0d03bcef7a1cd48cb7e115410ab625a26acde))

## [1.9.0](https://github.com/JiRaska/open-bank/compare/agent-service-v1.8.0...agent-service-v1.9.0) (2026-06-25)


### Features

* **agent,admin-ui:** tag MCP tools with their service so the coverage grid is complete ([#744](https://github.com/JiRaska/open-bank/issues/744)) ([#1860](https://github.com/JiRaska/open-bank/issues/1860)) ([50ac1d3](https://github.com/JiRaska/open-bank/commit/50ac1d33bbf9e5ab4b21ea0f1c7c7fdc458b3721))
* **agent:** add query.gl.readonly capability for GL-sensitive tools ([#1966](https://github.com/JiRaska/open-bank/issues/1966)) ([5b32941](https://github.com/JiRaska/open-bank/commit/5b32941c8c5cf0b12c4460ebc56adfa705cd53f3))
* **agent:** Kafka trigger for compliance-officer oversight sweep (ADR-0031 D9 P2) ([#1965](https://github.com/JiRaska/open-bank/issues/1965)) ([9ec01cc](https://github.com/JiRaska/open-bank/commit/9ec01cc8f34ff482e662cf07f1edf6a824633988))
* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank/commit/ad26ca7d58e62c8822e11f66f346926acc453058))


### Bug Fixes

* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank/commit/92dc2d636d857b526c8276e2647de440c540577b))

## [1.8.0](https://github.com/JiRaska/open-bank/compare/agent-service-v1.7.0...agent-service-v1.8.0) (2026-06-15)


### Features

* **agent:** autonomous compliance-officer oversight sweeps (ADR-0031 D9 phase 2) ([#844](https://github.com/JiRaska/open-bank/issues/844)) ([7716c7c](https://github.com/JiRaska/open-bank/commit/7716c7ccfe29cd6da79a2a097616ca4eeb61ebbd)), closes [#840](https://github.com/JiRaska/open-bank/issues/840)
* **agent:** emit run-level AI attribution + proposal lifecycle audit (ADR-0031 D5) ([#837](https://github.com/JiRaska/open-bank/issues/837)) ([943bff1](https://github.com/JiRaska/open-bank/commit/943bff1de41a7ae94636f0c3307e54f962ea403b))
* **agent:** kill switch — config baseline + runtime break-glass (ADR-0031 D7) ([#987](https://github.com/JiRaska/open-bank/issues/987)) ([39dbeea](https://github.com/JiRaska/open-bank/commit/39dbeead5288c209ea532adcf186db3e7fbe582e))
* **agent:** observability read tools (Prometheus/Loki/Alertmanager) for compliance-officer ([#970](https://github.com/JiRaska/open-bank/issues/970)) ([2e856f0](https://github.com/JiRaska/open-bank/commit/2e856f05e648908cfb45195d5d65058a8b8e552c))
* **agent:** OIDC-authenticate the agent surface (ADR-0031 D3) ([#997](https://github.com/JiRaska/open-bank/issues/997)) ([0fc6b52](https://github.com/JiRaska/open-bank/commit/0fc6b52179c194e152bdfe73f7e74235024966f0))


### Bug Fixes

* **agent,balance,product-catalog:** unblock main CI — capability rename sync + /q/metrics registries ([#751](https://github.com/JiRaska/open-bank/issues/751)) ([a561b91](https://github.com/JiRaska/open-bank/commit/a561b91ee2f06ed71b23086a3a62d7db00a8c7ff))
* **dispute:** assign unique HTTP port 8135 (resolve collision with lending) ([#1045](https://github.com/JiRaska/open-bank/issues/1045)) ([b219e93](https://github.com/JiRaska/open-bank/commit/b219e93ac044ee2d7c4b8e352bbba17265c624dc))


### Security

* **agent:** prompt-injection guardrail in front of the reasoning loop (ADR-0031 D6) ([#845](https://github.com/JiRaska/open-bank/issues/845)) ([c7e30f4](https://github.com/JiRaska/open-bank/commit/c7e30f4ed1e8fabe574ec74d8c4c3316196b922e))

## [1.7.0](https://github.com/JiRaska/open-bank/compare/agent-service-v1.6.0...agent-service-v1.7.0) (2026-06-12)


### Features

* **agent:** add HITL proposal queue and MCP tool-execution audit (ADR-0031 D4/D8) ([#653](https://github.com/JiRaska/open-bank/issues/653)) ([d169a24](https://github.com/JiRaska/open-bank/commit/d169a244da39d8b304cce8ae89bf8df6d29ad028))
* **agent:** AI-attributed audit on MCP tool execution (ADR-0031 D5) ([#706](https://github.com/JiRaska/open-bank/issues/706)) ([4787e8a](https://github.com/JiRaska/open-bank/commit/4787e8ac88320b01b88d5f457cba6e77d4a397de))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **infra,agent:** feature-flag flip enforcement — CI gate + MCP tool (ADR-0067 / issue [#419](https://github.com/JiRaska/open-bank/issues/419)) ([#758](https://github.com/JiRaska/open-bank/issues/758)) ([96bfb7d](https://github.com/JiRaska/open-bank/commit/96bfb7d506c9e2da22cde563ef8d676d77699019))


### Bug Fixes

* **security:** pentest P0 — auth internal endpoints + least-privilege agent (ADR-0078) ([#700](https://github.com/JiRaska/open-bank/issues/700)) ([6d6866c](https://github.com/JiRaska/open-bank/commit/6d6866c48f7692b8478b19ec5915392dd41675fd))
* **security:** pentest P2 — prompt-leak hardening, 1h session, AI-proposal warning (ADR-0078) ([#746](https://github.com/JiRaska/open-bank/issues/746)) ([6c35555](https://github.com/JiRaska/open-bank/commit/6c355558b10498efefbfeebabb40b36aeb0895cb))

## [1.6.0](https://github.com/JiRaska/open-bank/compare/agent-service-v1.5.0...agent-service-v1.6.0) (2026-06-10)


### Features

* **agent:** HITL proposal queue — draft_ticket tool + admin-ui approvals (ADR-0031 D4) ([#657](https://github.com/JiRaska/open-bank/issues/657)) ([ba90e1b](https://github.com/JiRaska/open-bank/commit/ba90e1bde400f3641630743f4779db1c17f5659e))
* **agent:** MCP read tools for aml/sanctions/fx/clearing/interest/dispute/sepa-instant ([#639](https://github.com/JiRaska/open-bank/issues/639)) ([47e389c](https://github.com/JiRaska/open-bank/commit/47e389c82bdb589761da2b7c9fa0b284dcfd0427))

## [1.5.0](https://github.com/JiRaska/open-bank/compare/agent-service-v1.4.0...agent-service-v1.5.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **agent:** D9 enforcement block mode + D2 charter rate-limiting + D4 proposal detection ([#311](https://github.com/JiRaska/open-bank/issues/311)) ([1085787](https://github.com/JiRaska/open-bank/commit/1085787a40a512033348c2d21d0fbbd052aa1eb3))
* **agent:** real-data assistant — service-to-service auth, fleet tools, free-tier hardening ([#290](https://github.com/JiRaska/open-bank/issues/290)) ([a5548b6](https://github.com/JiRaska/open-bank/commit/a5548b6182c6167f15aa1ee6a48862c1b5703b02))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
