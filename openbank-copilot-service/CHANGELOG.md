# Changelog

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.8.0...copilot-service-v0.8.1) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.7.4...copilot-service-v0.8.0) (2026-08-01)


### Features

* **observability:** make fleet LLM spend and reliability observable in Prometheus ([#3043](https://github.com/JiRaska/open-bank-oss/issues/3043)) ([000ba2a](https://github.com/JiRaska/open-bank-oss/commit/000ba2a516069ba4c65b50015a76b4086b229b30))

## [0.7.4](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.7.3...copilot-service-v0.7.4) (2026-07-25)


### Bug Fixes

* **copilot:** stop asking Jackson to parse a balance array as an object ([#2458](https://github.com/JiRaska/open-bank-oss/issues/2458)) ([717a121](https://github.com/JiRaska/open-bank-oss/commit/717a121e29d7b44f57e66e9907bd6271e9480029)), closes [#2322](https://github.com/JiRaska/open-bank-oss/issues/2322)

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.7.2...copilot-service-v0.7.3) (2026-07-25)


### Bug Fixes

* **copilot:** document themeSpec and drop the 501 the stream cannot return ([#2353](https://github.com/JiRaska/open-bank-oss/issues/2353)) ([84f1bd4](https://github.com/JiRaska/open-bank-oss/commit/84f1bd4d0f4b31ed63beda0af38b39c4ddeb6df7)), closes [#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.7.1...copilot-service-v0.7.2) (2026-07-25)


### Bug Fixes

* **copilot:** document the action-confirm endpoint and guard the route set ([#2323](https://github.com/JiRaska/open-bank-oss/issues/2323)) ([eafd368](https://github.com/JiRaska/open-bank-oss/commit/eafd368ac93312065a944e56339493fe97dd6b84)), closes [#2255](https://github.com/JiRaska/open-bank-oss/issues/2255)

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.7.0...copilot-service-v0.7.1) (2026-07-25)


### Bug Fixes

* **copilot:** declare the datastore copilot actually has, which is none ([#2268](https://github.com/JiRaska/open-bank-oss/issues/2268)) ([a5a615e](https://github.com/JiRaska/open-bank-oss/commit/a5a615ef74e6346e76c8e2a6c279b5ab1cb3837e)), closes [#2255](https://github.com/JiRaska/open-bank-oss/issues/2255)

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.6.0...copilot-service-v0.7.0) (2026-07-24)


### Features

* **theme:** edge theme prefs + copilot theme designer (ADR-0191) ([#2076](https://github.com/JiRaska/open-bank-oss/issues/2076)) ([91b9ac7](https://github.com/JiRaska/open-bank-oss/commit/91b9ac77238214e1363bec87b5f932dfd9becb42))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.5.3...copilot-service-v0.6.0) (2026-07-19)


### Features

* **copilot:** short-lived conversation memory across turns ([#1778](https://github.com/JiRaska/open-bank-oss/issues/1778)) ([4e7d1b2](https://github.com/JiRaska/open-bank-oss/commit/4e7d1b2013cedf8129e957e65b1ef84d62f682da)), closes [#1777](https://github.com/JiRaska/open-bank-oss/issues/1777)

## [0.5.3](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.5.2...copilot-service-v0.5.3) (2026-07-16)


### Bug Fixes

* **copilot,product-catalog:** enable the Quarkus management interface so probes hit a real port ([#1165](https://github.com/JiRaska/open-bank-oss/issues/1165)) ([7c8fa2b](https://github.com/JiRaska/open-bank-oss/commit/7c8fa2b76efb9d1d979eb03212f4f5dc42711767))

## [0.5.2](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.5.1...copilot-service-v0.5.2) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.5.0...copilot-service-v0.5.1) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.4.2...copilot-service-v0.5.0) (2026-06-30)


### Features

* **copilot:** D4 router + narrator — OPA gate wiring + rego name sync (ADR-0089) ([#2798](https://github.com/JiRaska/open-bank-oss/issues/2798)) ([7d5b1e0](https://github.com/JiRaska/open-bank-oss/commit/7d5b1e00a2bb929636979d57ba3d7271849b38af))

## [0.4.2](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.4.1...copilot-service-v0.4.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.4.0...copilot-service-v0.4.1) (2026-06-28)


### Bug Fixes

* **sepa-instant,balance,audit,security-scanner,copilot,customer-edge,sca:** inject Clock via CDI (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2145](https://github.com/JiRaska/open-bank-oss/issues/2145)) ([d680007](https://github.com/JiRaska/open-bank-oss/commit/d68000775625cc423c95d8a27db29ff25a708f9f))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.3.0...copilot-service-v0.4.0) (2026-06-25)


### Features

* **copilot:** emit proposal sentinel over SSE stream (ADR-0089 D2) ([#1709](https://github.com/JiRaska/open-bank-oss/issues/1709)) ([34dd7ba](https://github.com/JiRaska/open-bank-oss/commit/34dd7ba2117c6ed1babf2ceaec81da63cc62aa3c))


### Bug Fixes

* **copilot:** disable opaque token introspection, clean up role-claim-path warning ([#1665](https://github.com/JiRaska/open-bank-oss/issues/1665)) ([9a2baca](https://github.com/JiRaska/open-bank-oss/commit/9a2baca7e876415b7cd219f101842226682bb797))
* **copilot:** fix OIDC JWT validation — remove credentials.secret, pin token.issuer ([#1660](https://github.com/JiRaska/open-bank-oss/issues/1660)) ([a324968](https://github.com/JiRaska/open-bank-oss/commit/a324968705a7be75520df1bb1b6e7e65748f5e14))
* **copilot:** replace SseEventSink+@Blocking with Multi&lt;String&gt; SSE ([#1678](https://github.com/JiRaska/open-bank-oss/issues/1678)) ([71c47f5](https://github.com/JiRaska/open-bank-oss/commit/71c47f5741253988ac61b0e4162d05e4dde3d82c))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.2.0...copilot-service-v0.3.0) (2026-06-21)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))
* **copilot:** add @Authorize resource-gate on REST endpoints (ADR-0034 D3) ([#1356](https://github.com/JiRaska/open-bank-oss/issues/1356)) ([0931e2a](https://github.com/JiRaska/open-bank-oss/commit/0931e2a0c8152dc40c715cde6c84208c32142d66))
* **copilot:** emit PROGRESS signals during tool-call rounds ([#1155](https://github.com/JiRaska/open-bank-oss/issues/1155)) ([fb23d29](https://github.com/JiRaska/open-bank-oss/commit/fb23d291da3157e7567913fb16a3a3223fb895d7))
* **copilot:** expand AI assistant tools — FX rates, cards, scheduled payments, statements, FX conversion ([#1203](https://github.com/JiRaska/open-bank-oss/issues/1203)) ([3466d1e](https://github.com/JiRaska/open-bank-oss/commit/3466d1ea9acd2ed83f40f0068cf39488c53350ab))
* **copilot:** Redis ProposalTokenStore and action-confirm endpoint ([#1442](https://github.com/JiRaska/open-bank-oss/issues/1442)) ([df1ce6c](https://github.com/JiRaska/open-bank-oss/commit/df1ce6cd1974a644534f2db10338d63e2d06af07))
* **copilot:** SSE streaming + get_my_balances tool to cut response latency ([#1135](https://github.com/JiRaska/open-bank-oss/issues/1135)) ([c415783](https://github.com/JiRaska/open-bank-oss/commit/c415783e89e772a339d22d8fd4747d9e7e48d1f6))
* **copilot:** wire OPA tool-dispatch gate and proposal-token flow for Track A ([#1437](https://github.com/JiRaska/open-bank-oss/issues/1437)) ([c73c688](https://github.com/JiRaska/open-bank-oss/commit/c73c68864a9ffd14906ff7422b6d6cf6f263f8ad))
* **infra:** C8 observability sweep — PodMonitor namespaces + MeterRegistry on 4 services ([#1410](https://github.com/JiRaska/open-bank-oss/issues/1410)) ([9201493](https://github.com/JiRaska/open-bank-oss/commit/920149368ea630e0117a4a480684c16d0a5517e2))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank-oss/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank-oss/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))


### Bug Fixes

* **admin-ui:** freshness round 2 — service counts, BPMN slugs, copilot dataDomain ([#1376](https://github.com/JiRaska/open-bank-oss/issues/1376)) ([5e9160c](https://github.com/JiRaska/open-bank-oss/commit/5e9160c3219194cfa8ba85ed5a98b2317a849b20))
* **copilot:** align openapi.yaml info.version to 1.0.0 (ADR-0048) ([#1404](https://github.com/JiRaska/open-bank-oss/issues/1404)) ([a339e41](https://github.com/JiRaska/open-bank-oss/commit/a339e419e84789f134a1c8ad4ee1a728949ab5d4)), closes [#842](https://github.com/JiRaska/open-bank-oss/issues/842)

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/copilot-service-v0.1.0...copilot-service-v0.2.0) (2026-06-15)


### Features

* **copilot:** add get_my_accounts tool + pin Czech replies (ADR-0089) ([#1090](https://github.com/JiRaska/open-bank-oss/issues/1090)) ([e81965f](https://github.com/JiRaska/open-bank-oss/commit/e81965fa450b2ea390ec3fba0845b96cdf9e5a21))
* **copilot:** card-freeze + dispute action proposals (ADR-0089 Phase 2) ([#1040](https://github.com/JiRaska/open-bank-oss/issues/1040)) ([9650d9d](https://github.com/JiRaska/open-bank-oss/commit/9650d9de621940647d46454e9e1f0c75573be7fd))
* **copilot:** env-driven model id; default to runnable mistral-nemotron ([#1086](https://github.com/JiRaska/open-bank-oss/issues/1086)) ([5f4b6ad](https://github.com/JiRaska/open-bank-oss/commit/5f4b6ad94704ce5b93492325319e844151d977b5))
* **copilot:** money-path action proposals — propose_payment (ADR-0089 Phase 2) ([#1034](https://github.com/JiRaska/open-bank-oss/issues/1034)) ([e206273](https://github.com/JiRaska/open-bank-oss/commit/e206273ac510564bb58f9ea6d87e625769551a0f))
* **copilot:** OpenAI-compatible provider + NVIDIA Nemotron-70B ([#1076](https://github.com/JiRaska/open-bank-oss/issues/1076)) ([09018fa](https://github.com/JiRaska/open-bank-oss/commit/09018fa6094030a337133324b1e3389d5f103c01))
* **copilot:** scaffold customer-facing AI assistant service (ADR-0089) ([#1000](https://github.com/JiRaska/open-bank-oss/issues/1000)) ([161f5f5](https://github.com/JiRaska/open-bank-oss/commit/161f5f58ffc4a55c54dd05c14037112b39efb1bc))
* **customer-edge:** GET /customer/v1/banks — CNB bank-code registry proxy ([#1096](https://github.com/JiRaska/open-bank-oss/issues/1096)) ([a2de025](https://github.com/JiRaska/open-bank-oss/commit/a2de0251dd96c6929f11ac6e7ecb76e8d6ec16d1))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))


### Bug Fixes

* **copilot:** lazy-resolve model api-key so an un-seeded key doesn't CrashLoop boot ([#1084](https://github.com/JiRaska/open-bank-oss/issues/1084)) ([770be1d](https://github.com/JiRaska/open-bank-oss/commit/770be1d8bfbb1f389d6a4de56e6aff4a78899bab))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
