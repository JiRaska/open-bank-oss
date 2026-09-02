# Changelog

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
