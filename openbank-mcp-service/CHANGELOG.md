# Changelog

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
