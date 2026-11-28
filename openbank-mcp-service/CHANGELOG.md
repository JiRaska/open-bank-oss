# Changelog

## [0.22.2](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.22.1...mcp-service-v0.22.2) (2026-11-28)


### Bug Fixes

* **mcp-service:** keep Keycloak refresh credentials opaque ([#17484](https://github.com/JiRaska/open-bank-oss/issues/17484)) ([292f1e2](https://github.com/JiRaska/open-bank-oss/commit/292f1e2ee210e0bb57cd523d5a71bc30c0a50b28))

## [0.22.1](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.22.0...mcp-service-v0.22.1) (2026-11-26)


### Bug Fixes

* **mcp-service:** admit scoped ChatGPT OAuth registration ([#17377](https://github.com/JiRaska/open-bank-oss/issues/17377)) ([3c22575](https://github.com/JiRaska/open-bank-oss/commit/3c225753d9ee64f8b0a8ecadf2b856d78b91759e))

## [0.22.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.21.0...mcp-service-v0.22.0) (2026-11-26)


### Features

* **mcp-service:** bridge hosted OAuth to native bank approval ([#17364](https://github.com/JiRaska/open-bank-oss/issues/17364)) ([125f689](https://github.com/JiRaska/open-bank-oss/commit/125f6898c3738fcab6fda6a1c71b296582551047))

## [0.21.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.20.0...mcp-service-v0.21.0) (2026-11-26)


### Features

* **infra:** connect hosted MCP OAuth through the native bank passkey ([#17327](https://github.com/JiRaska/open-bank-oss/issues/17327)) ([ceb01bb](https://github.com/JiRaska/open-bank-oss/commit/ceb01bb689e593447527363b86b16f5a4439709b))

## [0.20.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.19.0...mcp-service-v0.20.0) (2026-11-25)


### Features

* **mcp-service:** declare hosted ChatGPT and Claude registrations ([#17242](https://github.com/JiRaska/open-bank-oss/issues/17242)) ([8b6dcdb](https://github.com/JiRaska/open-bank-oss/commit/8b6dcdbf7b0e90a20decc645fa2a365a065ce7a6))

## [0.19.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.18.7...mcp-service-v0.19.0) (2026-11-24)


### Features

* **mcp-service:** support hosted Tier H balance authorization ([#17232](https://github.com/JiRaska/open-bank-oss/issues/17232)) ([619f58b](https://github.com/JiRaska/open-bank-oss/commit/619f58bb7c49fd6a3aaf39d501f66acbc54d0a8e))

## [0.18.7](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.18.6...mcp-service-v0.18.7) (2026-11-24)


### Security

* **mcp-service:** customer propose_payment records a PENDING proposal in the delegation store ([#16864](https://github.com/JiRaska/open-bank-oss/issues/16864)) ([fbd7c36](https://github.com/JiRaska/open-bank-oss/commit/fbd7c36b10e02983ca8100132fa121f80f55ae4b))

## [0.18.6](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.18.5...mcp-service-v0.18.6) (2026-11-23)


### Security

* **mcp-service:** caller-scoped opaque accountRef for MCP tool chaining ([#16863](https://github.com/JiRaska/open-bank-oss/issues/16863)) ([cdd8413](https://github.com/JiRaska/open-bank-oss/commit/cdd8413840c923f2d5f56a80f5c9615b55a94ab9))

## [0.18.5](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.18.4...mcp-service-v0.18.5) (2026-11-23)


### Security

* **mcp-service:** read customer accounts only under an active MCP_CLIENT grant ([#16800](https://github.com/JiRaska/open-bank-oss/issues/16800)) ([0a30252](https://github.com/JiRaska/open-bank-oss/commit/0a302528fcb2bb609ba297218b978d841335d001))

## [0.18.4](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.18.3...mcp-service-v0.18.4) (2026-11-22)


### Security

* **mcp-service:** shared fail-closed Valkey DPoP replay store and budgets ([#16791](https://github.com/JiRaska/open-bank-oss/issues/16791)) ([49b0b6c](https://github.com/JiRaska/open-bank-oss/commit/49b0b6cb70d888ef9cd468d203fb309f676b8789))

## [0.18.3](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.18.2...mcp-service-v0.18.3) (2026-11-22)


### Security

* **mcp-service:** refuse customer credentials on the staff root ([#17086](https://github.com/JiRaska/open-bank-oss/issues/17086)) ([30cb2eb](https://github.com/JiRaska/open-bank-oss/commit/30cb2eb1bbeee575d9a8c21396edd718ae5b16b8))

## [0.18.2](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.18.1...mcp-service-v0.18.2) (2026-11-22)


### Security

* **mcp:** sanitise free text in tool results (ADR-0383 D7) ([#16637](https://github.com/JiRaska/open-bank-oss/issues/16637)) ([5afbbc4](https://github.com/JiRaska/open-bank-oss/commit/5afbbc4268b7554a615d5bcef9689a5759f997e1))

## [0.18.1](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.18.0...mcp-service-v0.18.1) (2026-11-22)


### Security

* **mcp-service:** cap subject-less audit keys per network allocation ([#17022](https://github.com/JiRaska/open-bank-oss/issues/17022)) ([54d3b13](https://github.com/JiRaska/open-bank-oss/commit/54d3b13485c5c80dde182664809845958749f99d))
* **mcp:** admit MCP clients by allowlist tier in the customers realm ([#16664](https://github.com/JiRaska/open-bank-oss/issues/16664)) ([5fb6813](https://github.com/JiRaska/open-bank-oss/commit/5fb681320dc1bd4c411e9a4493c287c256c1d8d4))
* **mcp:** edge trusted by mTLS identity; customer channel on its own DPoP-only root ([#17004](https://github.com/JiRaska/open-bank-oss/issues/17004)) ([2606221](https://github.com/JiRaska/open-bank-oss/commit/2606221c33b356b4e9d946d6b5af94916e794998))

## [0.18.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.17.11...mcp-service-v0.18.0) (2026-11-21)


### Features

* **mcp-service:** ADR-0384 slice 1 — customer MCP channel metrics, alerts and admin view ([#16779](https://github.com/JiRaska/open-bank-oss/issues/16779)) ([c421931](https://github.com/JiRaska/open-bank-oss/commit/c4219319f7fa372e8899c4a775ce3fa99c626299))

## [0.17.11](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.17.10...mcp-service-v0.17.11) (2026-11-20)


### Security

* **mcp-service:** admit customer-realm tokens on a DPoP-bound channel ([#16721](https://github.com/JiRaska/open-bank-oss/issues/16721)) ([1223f79](https://github.com/JiRaska/open-bank-oss/commit/1223f7981e477f48e1c57c911a597c4645009c37))

## [0.17.10](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.17.9...mcp-service-v0.17.10) (2026-11-18)


### Security

* **mcp:** bound JSON-RPC input before dispatch, audit and downstream calls ([#16644](https://github.com/JiRaska/open-bank-oss/issues/16644)) ([6c37533](https://github.com/JiRaska/open-bank-oss/commit/6c3753381a47674a0c9b2971fd5f1b6a23e157da))

## [0.17.9](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.17.8...mcp-service-v0.17.9) (2026-11-17)


### Security

* **mcp:** require this server's audience on every /mcp token ([#16626](https://github.com/JiRaska/open-bank-oss/issues/16626)) ([800e795](https://github.com/JiRaska/open-bank-oss/commit/800e795e2292ea193bd1cad63862d024aaba716e)), closes [#16622](https://github.com/JiRaska/open-bank-oss/issues/16622) [#16590](https://github.com/JiRaska/open-bank-oss/issues/16590)

## [0.17.8](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.17.7...mcp-service-v0.17.8) (2026-11-17)


### Bug Fixes

* **mcp:** throttle before policy for call and list, bound flood audit, cap body size ([#16596](https://github.com/JiRaska/open-bank-oss/issues/16596)) ([fb6600c](https://github.com/JiRaska/open-bank-oss/commit/fb6600cb1abe6c01bebdc485c9a1de6521d4d6fa))

## [0.17.7](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.17.6...mcp-service-v0.17.7) (2026-11-07)


### Bug Fixes

* **admin-ui,mcp-service:** align remaining domain consumers ([#15040](https://github.com/JiRaska/open-bank-oss/issues/15040)) ([7e5b6d5](https://github.com/JiRaska/open-bank-oss/commit/7e5b6d552d94dd952dc1e5e0a549439e87896a18))

## [0.17.6](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.17.5...mcp-service-v0.17.6) (2026-10-16)


### Security

* **opa:** authorize machines by the verified service_account flag (phase 2 of 2, gated on [#12538](https://github.com/JiRaska/open-bank-oss/issues/12538) rollout) ([#12537](https://github.com/JiRaska/open-bank-oss/issues/12537)) ([abf3aff](https://github.com/JiRaska/open-bank-oss/commit/abf3aff4f24fd170a6e1641b3008f9e4648a9572))

## [0.17.5](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.17.4...mcp-service-v0.17.5) (2026-10-14)


### Bug Fixes

* audit follow-ups 4-12 — live rules.yaml reads, timeout/pool gates, ADR staleness gate, coverage floors ([#12773](https://github.com/JiRaska/open-bank-oss/issues/12773)) ([09377e6](https://github.com/JiRaska/open-bank-oss/commit/09377e6fe51a06df94b5ad8040c5cc1478c3d416))

## [0.17.4](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.17.3...mcp-service-v0.17.4) (2026-10-14)


### Security

* **libs-runtime:** send OPA a verified service_account flag (phase 1 of 2) ([#12538](https://github.com/JiRaska/open-bank-oss/issues/12538)) ([3a8493a](https://github.com/JiRaska/open-bank-oss/commit/3a8493a7d8640b0baf252a879c1a908bd19587e2))

## [0.17.3](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.17.2...mcp-service-v0.17.3) (2026-09-29)


### Security

* **agent:** own M2M identities for the reads that still needed ROLE_OPERATOR ([#10486](https://github.com/JiRaska/open-bank-oss/issues/10486) batch 8) ([#11454](https://github.com/JiRaska/open-bank-oss/issues/11454)) ([9d20502](https://github.com/JiRaska/open-bank-oss/commit/9d205029f5f71b15427134a8e7140201f63ec13d))

## [0.17.2](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.17.1...mcp-service-v0.17.2) (2026-09-29)


### Security

* **fleet:** scope OIDC TLS verification=none to %dev and gate it ([#10870](https://github.com/JiRaska/open-bank-oss/issues/10870)) ([cf7aa55](https://github.com/JiRaska/open-bank-oss/commit/cf7aa5528809917ccb7e4a94d80f25b2d136c7b4))

## [0.17.1](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.17.0...mcp-service-v0.17.1) (2026-09-23)


### Security

* **agent:** own M2M identities for the AI-agent reads and the statement search ([#10486](https://github.com/JiRaska/open-bank-oss/issues/10486) batch 7) ([#10551](https://github.com/JiRaska/open-bank-oss/issues/10551)) ([5a25631](https://github.com/JiRaska/open-bank-oss/commit/5a25631fffd69f9f5ea653d36b5b5718bb35e951))

## [0.17.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.16.0...mcp-service-v0.17.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.16.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.15.1...mcp-service-v0.16.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))

## [0.15.1](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.15.0...mcp-service-v0.15.1) (2026-08-13)


### Security

* **mcp-service:** record CVE-2025-14969 VEX verdict as affected ([#4536](https://github.com/JiRaska/open-bank-oss/issues/4536)) ([40f01ad](https://github.com/JiRaska/open-bank-oss/commit/40f01ad45539bd81a0de5f46758aa2b34f1a85bd)), closes [#4443](https://github.com/JiRaska/open-bank-oss/issues/4443) [#4533](https://github.com/JiRaska/open-bank-oss/issues/4533)

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.14.5...mcp-service-v0.15.0) (2026-08-07)


### Features

* **mcp-service:** add statement and payment confirmation query tools ([#4127](https://github.com/JiRaska/open-bank-oss/issues/4127)) ([98841b4](https://github.com/JiRaska/open-bank-oss/commit/98841b41dafd900429141744f3f3f57b1f0fa1b6))

## [0.14.5](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.14.4...mcp-service-v0.14.5) (2026-08-06)


### Bug Fixes

* **mcp:** refuse propose_payment instead of answering PROPOSED for a proposal nobody recorded ([#3900](https://github.com/JiRaska/open-bank-oss/issues/3900)) ([dcefe12](https://github.com/JiRaska/open-bank-oss/commit/dcefe12e284c29d2f230d0b8c11d92cd475c3ee2)), closes [#2414](https://github.com/JiRaska/open-bank-oss/issues/2414)

## [0.14.4](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.14.3...mcp-service-v0.14.4) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.14.3](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.14.2...mcp-service-v0.14.3) (2026-08-02)


### Bug Fixes

* **mcp:** forward attributes through the rest.rego agent-charter bridge ([#3315](https://github.com/JiRaska/open-bank-oss/issues/3315)) ([4d1ef53](https://github.com/JiRaska/open-bank-oss/commit/4d1ef53ce25e705fa215fcab83ac627d4ee449b0))

## [0.14.2](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.14.1...mcp-service-v0.14.2) (2026-08-02)


### Bug Fixes

* **mcp:** key an agent session by the token's sub, not by a name an admin can change ([#3242](https://github.com/JiRaska/open-bank-oss/issues/3242)) ([7ef9ed1](https://github.com/JiRaska/open-bank-oss/commit/7ef9ed198a58ab192742c7ca7ef662bf87325662)), closes [#3182](https://github.com/JiRaska/open-bank-oss/issues/3182)

## [0.14.1](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.14.0...mcp-service-v0.14.1) (2026-07-31)


### Bug Fixes

* **mcp:** resolve the staff OBO identity end-to-end (ADR-0224, E2E [#2750](https://github.com/JiRaska/open-bank-oss/issues/2750)) ([#2946](https://github.com/JiRaska/open-bank-oss/issues/2946)) ([ff154eb](https://github.com/JiRaska/open-bank-oss/commit/ff154ebdb9310400f53a9aee5cf44c8d6ad92c69))

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.13.0...mcp-service-v0.14.0) (2026-07-31)


### Features

* **mcp:** agent sessions become stateful — store, lifecycle, live OBO validation (ADR-0224 D2) ([#2843](https://github.com/JiRaska/open-bank-oss/issues/2843)) ([45b7d87](https://github.com/JiRaska/open-bank-oss/commit/45b7d870a752fbd9a3b590b10a5f27f49715d4fb))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.12.0...mcp-service-v0.13.0) (2026-07-31)


### Features

* **mcp:** accept staff OBO tokens behind mcp.obo.enabled (ADR-0224 phase 1b) ([#2763](https://github.com/JiRaska/open-bank-oss/issues/2763)) ([64c2d5e](https://github.com/JiRaska/open-bank-oss/commit/64c2d5e78b687a5ae707ae8dbc15fae90c69533d))
* **mcp:** stamp every MCP audit event with channel, act chain and session id (ADR-0226 D2) ([#2760](https://github.com/JiRaska/open-bank-oss/issues/2760)) ([020ac77](https://github.com/JiRaska/open-bank-oss/commit/020ac773a1ccf8ac021f18aed4eb42a2ed8c89b7))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.11.0...mcp-service-v0.12.0) (2026-07-31)


### Features

* **mcp:** policy-filtered tools/list — capability-shaped discovery (ADR-0225) ([#2755](https://github.com/JiRaska/open-bank-oss/issues/2755)) ([8ff6911](https://github.com/JiRaska/open-bank-oss/commit/8ff69119df6d4515ffada73c41e6f8df81326ac1))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.10.0...mcp-service-v0.11.0) (2026-07-26)


### Features

* **mcp:** validate propose_payment's arguments server-side (T-T2) ([#2649](https://github.com/JiRaska/open-bank-oss/issues/2649)) ([08814c0](https://github.com/JiRaska/open-bank-oss/commit/08814c0511754a38a1a3e1ba4a545f5d5c8fe9f7)), closes [#2414](https://github.com/JiRaska/open-bank-oss/issues/2414)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.9.2...mcp-service-v0.10.0) (2026-07-26)


### Features

* **mcp:** register the campaign-copilot's read-only reach tool, denied until granted ([#2639](https://github.com/JiRaska/open-bank-oss/issues/2639)) ([d405124](https://github.com/JiRaska/open-bank-oss/commit/d4051244b1f00729c2aa7746feb4d1dca3e49175)), closes [#2574](https://github.com/JiRaska/open-bank-oss/issues/2574)

## [0.9.2](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.9.1...mcp-service-v0.9.2) (2026-07-26)


### Security

* **mcp:** mark tool results as untrusted data, and bind the charter to the code ([#2610](https://github.com/JiRaska/open-bank-oss/issues/2610)) ([04ad12b](https://github.com/JiRaska/open-bank-oss/commit/04ad12b75b0f0014b549bf806cd337581d9977c9)), closes [#2412](https://github.com/JiRaska/open-bank-oss/issues/2412)

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.9.0...mcp-service-v0.9.1) (2026-07-26)


### Security

* **mcp:** enforce PROPOSED-only on the call path, not in whichever ProposalPort is bound ([#2498](https://github.com/JiRaska/open-bank-oss/issues/2498)) ([dc941f5](https://github.com/JiRaska/open-bank-oss/commit/dc941f5654c0b81ded3bca6d29534ae523cc70f7))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.8.0...mcp-service-v0.9.0) (2026-07-26)


### Features

* **mcp:** enforce the charter's data_scope.pii masking on every tool result ([#2481](https://github.com/JiRaska/open-bank-oss/issues/2481)) ([517a88f](https://github.com/JiRaska/open-bank-oss/commit/517a88f8e7f079ff95d7b97ee752ba1c3032e19b))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.7.0...mcp-service-v0.8.0) (2026-07-26)


### Features

* **mcp:** rate-limit tools/call per acting agent ([#2484](https://github.com/JiRaska/open-bank-oss/issues/2484)) ([152131d](https://github.com/JiRaska/open-bank-oss/commit/152131d1f5ff676eb51d28ae747be5b9aaa79dd0)), closes [#2409](https://github.com/JiRaska/open-bank-oss/issues/2409)

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.6.0...mcp-service-v0.7.0) (2026-07-25)


### Features

* **mcp:** remove the phase-1 placeholder identity and wire real read ports (ADR-0195 step 4) ([#2316](https://github.com/JiRaska/open-bank-oss/issues/2316)) ([9dc2089](https://github.com/JiRaska/open-bank-oss/commit/9dc2089bfcaaf012a2cabf8b20d0ccc790ba9992))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.5.0...mcp-service-v0.6.0) (2026-07-25)


### Features

* **mcp:** instrument tool-call outcomes, JSON-RPC methods and caller identity ([#2285](https://github.com/JiRaska/open-bank-oss/issues/2285)) ([889c4eb](https://github.com/JiRaska/open-bank-oss/commit/889c4eba204ff1997e783931803404c3563e2316)), closes [#2255](https://github.com/JiRaska/open-bank-oss/issues/2255)
* **mcp:** M2M OIDC client + downstream URLs for consent-validated read ports (ADR-0195 step 3) ([#2278](https://github.com/JiRaska/open-bank-oss/issues/2278)) ([8b0341c](https://github.com/JiRaska/open-bank-oss/commit/8b0341c4d788cb11855179d51ccef9eca99436be))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.4.0...mcp-service-v0.5.0) (2026-07-25)


### Features

* **mcp:** real consent-validated read ports, code-complete but not wired (ADR-0195 step 2) ([#2262](https://github.com/JiRaska/open-bank-oss/issues/2262)) ([ebe497f](https://github.com/JiRaska/open-bank-oss/commit/ebe497f60eb53540e43bcf846d1dde1904e9f82a))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.3.0...mcp-service-v0.4.0) (2026-07-25)


### Features

* **mcp:** resolve the acting agent + consent from the caller's OAuth token (ADR-0195 step 1) ([#2253](https://github.com/JiRaska/open-bank-oss/issues/2253)) ([3108b3a](https://github.com/JiRaska/open-bank-oss/commit/3108b3a81772599bd51378b4cb00479155804aac))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.2.1...mcp-service-v0.3.0) (2026-07-25)


### Features

* **mcp:** emit an AI-attributed audit event for every tools/call (ADR-0031 D5) ([#2222](https://github.com/JiRaska/open-bank-oss/issues/2222)) ([332a9a1](https://github.com/JiRaska/open-bank-oss/commit/332a9a17f97ba341436ff006eb4f0ef882935e77)), closes [#2207](https://github.com/JiRaska/open-bank-oss/issues/2207)

## [0.2.1](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.2.0...mcp-service-v0.2.1) (2026-07-25)


### Bug Fixes

* **mcp:** pin USER to numeric uid 100 so runAsNonRoot admits the pod ([#2137](https://github.com/JiRaska/open-bank-oss/issues/2137)) ([7623c9d](https://github.com/JiRaska/open-bank-oss/commit/7623c9d1f6f4ad4b6eb2af5999e3eb574553d54f))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/mcp-service-v0.1.0...mcp-service-v0.2.0) (2026-07-24)


### Features

* **mcp:** MCP server phase 1 — curated PSD2 tools behind the ADR-0034 PDP ([#1922](https://github.com/JiRaska/open-bank-oss/issues/1922)) ([#2104](https://github.com/JiRaska/open-bank-oss/issues/2104)) ([c04790d](https://github.com/JiRaska/open-bank-oss/commit/c04790d2a874820ab60fc2257bef455c8451d035))
