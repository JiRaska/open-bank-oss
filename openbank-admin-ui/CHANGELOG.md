# Changelog

## [0.38.1](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.38.0...admin-ui-v0.38.1) (2026-06-29)


### Bug Fixes

* **admin-ui:** error-rate tile reads true 5xx ratio over 1h, not noisy 5m ([#2658](https://github.com/JiRaska/open-bank/issues/2658)) ([702eeca](https://github.com/JiRaska/open-bank/commit/702eecaf52ad80ff5afafaf0d45b0697e628fde2))

## [0.38.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.37.0...admin-ui-v0.38.0) (2026-06-29)


### Features

* **admin-ui:** mark ADR-0031 D3 built — SVID CN cross-check live and enforced ([#2543](https://github.com/JiRaska/open-bank/issues/2543)) ([6ab1534](https://github.com/JiRaska/open-bank/commit/6ab153439b972db3b4e066156c8f5a09a1d6949a))
* **admin-ui:** read live SBOM from /q/openbank/sbom, fall back to baked bundle ([#2367](https://github.com/JiRaska/open-bank/issues/2367)) ([f7c2b58](https://github.com/JiRaska/open-bank/commit/f7c2b5843bf4bff25426981aa1833c29be023530))
* **admin-ui:** surface AI-agent findings via shared AgentInsightsPanel below metrics ([#2503](https://github.com/JiRaska/open-bank/issues/2503)) ([da00df0](https://github.com/JiRaska/open-bank/commit/da00df081e63c7bafc31981bf26927d6c23f6de6))
* **admin-ui:** surface live audit-trail integrity on IAOps (ADR-0031 D5) ([#2398](https://github.com/JiRaska/open-bank/issues/2398)) ([72f97d7](https://github.com/JiRaska/open-bank/commit/72f97d745fe249f7fa4d13dc28b5c098ab2816fa))
* **devops:** add AI DevOps agent for SSDLC/DORA monitoring (ADR-0119) ([#2295](https://github.com/JiRaska/open-bank/issues/2295)) ([e811e4f](https://github.com/JiRaska/open-bank/commit/e811e4fd9510517f2e6fd099a2b0b7556f85b2aa))
* **devops:** HITL approve/reject for findings (ADR-0119) ([#2308](https://github.com/JiRaska/open-bank/issues/2308)) ([b42a4c3](https://github.com/JiRaska/open-bank/commit/b42a4c3f4d75b0ff2199fc5fa9336f6fc45c21b8))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **admin-ui:** point Temporal metrics queries at real server metric names ([a747521](https://github.com/JiRaska/open-bank/commit/a7475211094c061082f62db31e5bf7017a7b0ed8))
* **admin-ui:** reflect ADR-0031 phase-2 reality + correct stale CD note in CLAUDE.md ([#2498](https://github.com/JiRaska/open-bank/issues/2498)) ([5908881](https://github.com/JiRaska/open-bank/commit/590888124d4af7fdf3828fde06f0ea2474a4a689))
* **admin-ui:** restore AuditIntegrityTile dropped in iaops during [#2503](https://github.com/JiRaska/open-bank/issues/2503) ([#2508](https://github.com/JiRaska/open-bank/issues/2508)) ([d0ee740](https://github.com/JiRaska/open-bank/commit/d0ee74069a323be001b8dcbf781a09bf1600ddcf))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank/issues/2342)


### Security

* **agent:** mint per-run pki-agent SVID in the BFF + base64 cert transport (ADR-0031 D3b) ([#2439](https://github.com/JiRaska/open-bank/issues/2439)) ([1dce3db](https://github.com/JiRaska/open-bank/commit/1dce3dbffc8b73accb902c7bef31865f0cc16402))

## [0.37.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.36.0...admin-ui-v0.37.0) (2026-06-29)


### Features

* **admin-ui:** mark ADR-0031 D3 built — SVID CN cross-check live and enforced ([#2543](https://github.com/JiRaska/open-bank/issues/2543)) ([6539208](https://github.com/JiRaska/open-bank/commit/6539208b50855c258de2b710f66a0c57c32a5cf3))

## [0.36.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.35.1...admin-ui-v0.36.0) (2026-06-29)


### Features

* **admin-ui:** surface AI-agent findings via shared AgentInsightsPanel below metrics ([#2503](https://github.com/JiRaska/open-bank/issues/2503)) ([7c4679f](https://github.com/JiRaska/open-bank/commit/7c4679f32c37f5cdb023344de95c203ab65f57ed))


### Bug Fixes

* **admin-ui:** reflect ADR-0031 phase-2 reality + correct stale CD note in CLAUDE.md ([#2498](https://github.com/JiRaska/open-bank/issues/2498)) ([fc192c5](https://github.com/JiRaska/open-bank/commit/fc192c5166bcfd269e0dfb5e1feacb48db12cf80))
* **admin-ui:** restore AuditIntegrityTile dropped in iaops during [#2503](https://github.com/JiRaska/open-bank/issues/2503) ([#2508](https://github.com/JiRaska/open-bank/issues/2508)) ([adba25b](https://github.com/JiRaska/open-bank/commit/adba25b67a7625250214595d55bd417b2da852d2))

## [0.36.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.35.1...admin-ui-v0.36.0) (2026-06-29)


### Features

* **admin-ui:** surface AI-agent findings via shared AgentInsightsPanel below metrics ([#2503](https://github.com/JiRaska/open-bank/issues/2503)) ([7c4679f](https://github.com/JiRaska/open-bank/commit/7c4679f32c37f5cdb023344de95c203ab65f57ed))


### Bug Fixes

* **admin-ui:** reflect ADR-0031 phase-2 reality + correct stale CD note in CLAUDE.md ([#2498](https://github.com/JiRaska/open-bank/issues/2498)) ([fc192c5](https://github.com/JiRaska/open-bank/commit/fc192c5166bcfd269e0dfb5e1feacb48db12cf80))
* **admin-ui:** restore AuditIntegrityTile dropped in iaops during [#2503](https://github.com/JiRaska/open-bank/issues/2503) ([#2508](https://github.com/JiRaska/open-bank/issues/2508)) ([adba25b](https://github.com/JiRaska/open-bank/commit/adba25b67a7625250214595d55bd417b2da852d2))

## [0.35.1](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.35.0...admin-ui-v0.35.1) (2026-06-29)


### Security

* **agent:** mint per-run pki-agent SVID in the BFF + base64 cert transport (ADR-0031 D3b) ([#2439](https://github.com/JiRaska/open-bank/issues/2439)) ([e8d5c92](https://github.com/JiRaska/open-bank/commit/e8d5c927d6f19d84c8c5eef5dc0df9a20718a011))

## [0.35.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.34.2...admin-ui-v0.35.0) (2026-06-28)


### Features

* **admin-ui:** read live SBOM from /q/openbank/sbom, fall back to baked bundle ([#2367](https://github.com/JiRaska/open-bank/issues/2367)) ([20e46fa](https://github.com/JiRaska/open-bank/commit/20e46faf0bc282dd0d74c50da20696ebb38f101b))
* **admin-ui:** surface live audit-trail integrity on IAOps (ADR-0031 D5) ([#2398](https://github.com/JiRaska/open-bank/issues/2398)) ([a0c7bda](https://github.com/JiRaska/open-bank/commit/a0c7bdaeeed125b45ac2194e4c14943622d80a60))

## [0.34.2](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.34.1...admin-ui-v0.34.2) (2026-06-28)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank/issues/2344)) ([88d1d8a](https://github.com/JiRaska/open-bank/commit/88d1d8ac2f743630e1caa9be80f0a399e6e79ff2))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank/issues/2358)) ([9e47bcc](https://github.com/JiRaska/open-bank/commit/9e47bcc7e4a1a1a6c40bd2daafbd1a2b041df018))

## [0.34.1](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.34.0...admin-ui-v0.34.1) (2026-06-28)


### Bug Fixes

* **admin-ui:** point Temporal metrics queries at real server metric names ([1b99c66](https://github.com/JiRaska/open-bank/commit/1b99c665bc9f3fb55395634380e701ab88cb76cd))

## [0.34.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.33.0...admin-ui-v0.34.0) (2026-06-27)


### Features

* **devops:** add AI DevOps agent for SSDLC/DORA monitoring (ADR-0119) ([#2295](https://github.com/JiRaska/open-bank/issues/2295)) ([c0ede0a](https://github.com/JiRaska/open-bank/commit/c0ede0a075be0be7381637476d0944c0a7ac9792))
* **devops:** HITL approve/reject for findings (ADR-0119) ([#2308](https://github.com/JiRaska/open-bank/issues/2308)) ([0e21c8c](https://github.com/JiRaska/open-bank/commit/0e21c8c3e5d8a855fec9a25204d5b16e3559804f))

## [0.33.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.32.0...admin-ui-v0.33.0) (2026-06-27)


### Features

* **admin-ui:** FinOps AI costs card + IAOps anomaly timeline (ADR-0112) ([#2183](https://github.com/JiRaska/open-bank/issues/2183)) ([225cc57](https://github.com/JiRaska/open-bank/commit/225cc574b6eabc543f00aedc7871b0981ac0dbfa))


### Bug Fixes

* **admin-ui:** move MCP server registry out of ToolCard loop ([#2176](https://github.com/JiRaska/open-bank/issues/2176)) ([97f7d29](https://github.com/JiRaska/open-bank/commit/97f7d2916a0424b43e0fb688280598c7aa9164ce))
* **admin-ui:** normalise finops-agent tier-object tools in /iaops API ([#2203](https://github.com/JiRaska/open-bank/issues/2203)) ([a629f4b](https://github.com/JiRaska/open-bank/commit/a629f4b2d2bbfb2c6cdd7daf5251f264ef514d03))

## [0.32.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.31.0...admin-ui-v0.32.0) (2026-06-25)


### Features

* **admin-ui:** prod-readiness collector + CI wiring (ADR-0029) ([#2017](https://github.com/JiRaska/open-bank/issues/2017)) ([0e7af8f](https://github.com/JiRaska/open-bank/commit/0e7af8f22ab7ecf3b7cce3aa741d03ddf490b0f4)), closes [#299](https://github.com/JiRaska/open-bank/issues/299)
* **admin-ui:** wire CatalogDriftBanner to BCP, Health, and Services pages (ADR-0071) ([#2005](https://github.com/JiRaska/open-bank/issues/2005)) ([ba3bee5](https://github.com/JiRaska/open-bank/commit/ba3bee51ba1fdadf5c385c5e3010a2e090e48a2f))

## [0.31.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.30.0...admin-ui-v0.31.0) (2026-06-25)


### Features

* **admin-ui:** add HolmesGPT RCA investigation panel to IAOps page (ADR-0031 D9) ([#1926](https://github.com/JiRaska/open-bank/issues/1926)) ([8da4b53](https://github.com/JiRaska/open-bank/commit/8da4b53171b05d4497c060f4a941f6adc17ac42b))
* **admin-ui:** add Temporal durable execution monitoring page (ADR-0101) ([#1448](https://github.com/JiRaska/open-bank/issues/1448)) ([d1cc901](https://github.com/JiRaska/open-bank/commit/d1cc90119d7b70a44795a653daa191ebcf49c6a8))
* **admin-ui:** four-eyes identity verification cockpit ([#1518](https://github.com/JiRaska/open-bank/issues/1518)) ([365d088](https://github.com/JiRaska/open-bank/commit/365d08843c229134599c25c3b6a2237a08b3a5fb))
* **admin-ui:** K8s-API service discovery for System Health screen (ADR-0051 Phase 1) ([#1955](https://github.com/JiRaska/open-bank/issues/1955)) ([cc6a696](https://github.com/JiRaska/open-bank/commit/cc6a696b760d226b4d8b3b4a57d973bf5d645a08))
* **admin-ui:** render PROBABILISTIC_CANDIDATE trigger in the identity cockpit ([#1537](https://github.com/JiRaska/open-bank/issues/1537)) ([9336fb2](https://github.com/JiRaska/open-bank/commit/9336fb21d88cad3aa540cecc8282a4a7119e1e4e))
* **agent,admin-ui:** tag MCP tools with their service so the coverage grid is complete ([#744](https://github.com/JiRaska/open-bank/issues/744)) ([#1860](https://github.com/JiRaska/open-bank/issues/1860)) ([50ac1d3](https://github.com/JiRaska/open-bank/commit/50ac1d33bbf9e5ab4b21ea0f1c7c7fdc458b3721))


### Bug Fixes

* **admin-ui:** align package.json to version.txt 0.30.0 (release_invariant) ([#1444](https://github.com/JiRaska/open-bank/issues/1444)) ([38829e6](https://github.com/JiRaska/open-bank/commit/38829e681b1e4649a42c75785fc502cf578202f7))
* **admin-ui:** discover Argo Rollouts + add missing namespaces to fleet view ([#1504](https://github.com/JiRaska/open-bank/issues/1504)) ([380dcff](https://github.com/JiRaska/open-bank/commit/380dcffbefe485fe9e8b0269c29636738df3f91f))
* **admin-ui:** fix JVM heap metrics query — use container label not application ([#1430](https://github.com/JiRaska/open-bank/issues/1430)) ([66e8f5d](https://github.com/JiRaska/open-bank/commit/66e8f5d36e9e7941e9f6bc6583714a4216128d1f))
* **admin-ui:** refresh Temporal phases + wire Pact Broker verdicts into quality report ([#1700](https://github.com/JiRaska/open-bank/issues/1700)) ([8aa1823](https://github.com/JiRaska/open-bank/commit/8aa18235647deb13259c01108054272904d6a0e7))
* **admin-ui:** render OWASP Top 10 coverage grid + fix dead ternary in security route ([fdcb108](https://github.com/JiRaska/open-bank/commit/fdcb108f4c93b7af595683fb3e154da8320aaed6))
* **admin-ui:** use npm ci in Dockerfile to avoid ENOTEMPTY cache corruption ([#1461](https://github.com/JiRaska/open-bank/issues/1461)) ([3d994f6](https://github.com/JiRaska/open-bank/commit/3d994f6ae140693d66fb4f77727db62f2c1f6da6))
* **observability:** stop false-positive alert noise and bound HolmesGPT token budget ([#1734](https://github.com/JiRaska/open-bank/issues/1734)) ([489f118](https://github.com/JiRaska/open-bank/commit/489f11888702e23caac291b6aecde68a18684d60))

## [0.30.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.29.0...admin-ui-v0.30.0) (2026-06-19)


### Features

* **infra:** VPA recommender + FinOps right-sizing panel (ADR-0099 ph.3) ([#1386](https://github.com/JiRaska/open-bank/issues/1386)) ([fd10bcc](https://github.com/JiRaska/open-bank/commit/fd10bcc504f33b2a68e2950fb298cdd47a1e37f6))

## [0.29.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.28.0...admin-ui-v0.29.0) (2026-06-19)


### Features

* **admin-ui:** add Identity & Deduplication docs page with visual flow ([#1322](https://github.com/JiRaska/open-bank/issues/1322)) ([bed2682](https://github.com/JiRaska/open-bank/commit/bed2682f4de3a8cc823c48eef30a6ce6a8bd091f))
* **admin-ui:** implement MTTR baseline from Prometheus ALERTS metric ([#1384](https://github.com/JiRaska/open-bank/issues/1384)) ([b8d117e](https://github.com/JiRaska/open-bank/commit/b8d117e753ba06447c2109a8f6f788c9ed80d751))
* **admin-ui:** QRlessPay docs page — sequence, security layers, QR comparison ([#1239](https://github.com/JiRaska/open-bank/issues/1239)) ([edc479c](https://github.com/JiRaska/open-bank/commit/edc479c11946be3579a43f53d73979fda8729f79))


### Bug Fixes

* **admin-ui:** align version.txt to package.json 0.29.0 (release_invariant) ([#1189](https://github.com/JiRaska/open-bank/issues/1189)) ([2138a24](https://github.com/JiRaska/open-bank/commit/2138a24809c22505a3ca5383011246a4fc82a0a4))
* **admin-ui:** drop browser noise from GlitchTip (extensions, empty rejections) ([#1183](https://github.com/JiRaska/open-bank/issues/1183)) ([2578b69](https://github.com/JiRaska/open-bank/commit/2578b6954ce4b8ba16aa34abcbd01d75a68eea52))
* **admin-ui:** freshness round 2 — service counts, BPMN slugs, copilot dataDomain ([#1376](https://github.com/JiRaska/open-bank/issues/1376)) ([5e9160c](https://github.com/JiRaska/open-bank/commit/5e9160c3219194cfa8ba85ed5a98b2317a849b20))
* **admin-ui:** governance data freshness — ADR-0082 renumber, MONEY_PATH fraud-service, STATIC_CANDIDATES ([f69ba3b](https://github.com/JiRaska/open-bank/commit/f69ba3baaac7ab91b25a0c6a4f7945574f0f53b5))
* **admin-ui:** QRlessPay sequence diagram — correct arrow directions + visible lifelines ([#1269](https://github.com/JiRaska/open-bank/issues/1269)) ([28d99e0](https://github.com/JiRaska/open-bank/commit/28d99e0ce6ba41af8a8e031a7ea33a69fcd67246))

## [0.28.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.27.0...admin-ui-v0.28.0) (2026-06-15)


### Features

* **admin-ui:** add HolmesGPT + customer-copilot charters to AI governance (IAOps) ([#1060](https://github.com/JiRaska/open-bank/issues/1060)) ([660cafb](https://github.com/JiRaska/open-bank/commit/660cafb9793635fe6b7477aac653257ae71e00f9)), closes [#1010](https://github.com/JiRaska/open-bank/issues/1010)
* **admin-ui:** add Lending, SEPA Direct Debit, SCA Push & Accounting-Closings BPMN processes ([#941](https://github.com/JiRaska/open-bank/issues/941)) ([8130700](https://github.com/JiRaska/open-bank/commit/8130700f4830500880f7c6512142c0d9451f873f))
* **admin-ui:** bake changelog into image + link version badge to release notes ([#1006](https://github.com/JiRaska/open-bank/issues/1006)) ([94e22ec](https://github.com/JiRaska/open-bank/commit/94e22ec2fda3707a6f018d78adf901fd643db85e))
* **admin-ui:** closings view — EoM close runs + manual catch-up (ADR-0069 D3) ([#866](https://github.com/JiRaska/open-bank/issues/866)) ([0667360](https://github.com/JiRaska/open-bank/commit/06673601adfba16604c6724c727cf0a44b533a40))
* **admin-ui:** document synthetics + AI RCA on the observability stack page ([#1056](https://github.com/JiRaska/open-bank/issues/1056)) ([07150c6](https://github.com/JiRaska/open-bank/commit/07150c6a211f874a72214d02b16ec411c61178da)), closes [#1010](https://github.com/JiRaska/open-bank/issues/1010)
* **admin-ui:** GlitchTip crash/error monitoring (ADR-0075) ([#978](https://github.com/JiRaska/open-bank/issues/978)) ([9c025c1](https://github.com/JiRaska/open-bank/commit/9c025c19c1263493533993c25d6562575b5fc37b))
* **admin-ui:** make all long-form docs pages bilingual (CS/EN) ([#1011](https://github.com/JiRaska/open-bank/issues/1011)) ([f07811c](https://github.com/JiRaska/open-bank/commit/f07811ce363f91cdcc6fcd668baca7dba7a8ef2c)), closes [#1010](https://github.com/JiRaska/open-bank/issues/1010)
* **admin-ui:** migrate BPMN docs to YAML loader + first-class async event flows ([#933](https://github.com/JiRaska/open-bank/issues/933)) ([eca1a32](https://github.com/JiRaska/open-bank/commit/eca1a32e2a1ee92d283130276bdef866f462a90c))
* **admin-ui:** refresh observability stack explainer (ADR-0088/0089) ([#924](https://github.com/JiRaska/open-bank/issues/924)) ([6ca0684](https://github.com/JiRaska/open-bank/commit/6ca068406b835eb8ccf0e444196f7bd3efcbd77b))
* **admin-ui:** refresh remaining drift docs pages (BCP/service-map/cloud-arch/compliance) ([#1026](https://github.com/JiRaska/open-bank/issues/1026)) ([64ea65f](https://github.com/JiRaska/open-bank/commit/64ea65fae7d696a811cd2b14bcb699fa3917013a)), closes [#1010](https://github.com/JiRaska/open-bank/issues/1010)
* **admin-ui:** render all code-derived services in the API catalog ([#1015](https://github.com/JiRaska/open-bank/issues/1015)) ([b35480b](https://github.com/JiRaska/open-bank/commit/b35480b45db38ff6793c28c128995b08c1711e1f)), closes [#1010](https://github.com/JiRaska/open-bank/issues/1010)
* **admin-ui:** surface the expanded observability stack on Infrastructure ([#1020](https://github.com/JiRaska/open-bank/issues/1020)) ([0d2003e](https://github.com/JiRaska/open-bank/commit/0d2003e80d58f27b6ec2c56c9ac295069832fe6b)), closes [#1010](https://github.com/JiRaska/open-bank/issues/1010)
* **agent:** kill switch — config baseline + runtime break-glass (ADR-0031 D7) ([#987](https://github.com/JiRaska/open-bank/issues/987)) ([39dbeea](https://github.com/JiRaska/open-bank/commit/39dbeead5288c209ea532adcf186db3e7fbe582e))
* **agent:** OIDC-authenticate the agent surface (ADR-0031 D3) ([#997](https://github.com/JiRaska/open-bank/issues/997)) ([0fc6b52](https://github.com/JiRaska/open-bank/commit/0fc6b52179c194e152bdfe73f7e74235024966f0))


### Bug Fixes

* **admin-ui:** infra board — OpenBao + correct Valkey version ([#890](https://github.com/JiRaska/open-bank/issues/890)) ([333c363](https://github.com/JiRaska/open-bank/commit/333c36377b12d66dd28f7d44e00475cdb3306371))
* **admin-ui:** Loki infra card — real health probe + clean version ([#867](https://github.com/JiRaska/open-bank/issues/867)) ([06c3469](https://github.com/JiRaska/open-bank/commit/06c3469625bc3fac9b418e80436386011d9be2fb))
* **agent:** reconcile declared enforcement to block (ADR-0031 D8, [#743](https://github.com/JiRaska/open-bank/issues/743)) ([#1003](https://github.com/JiRaska/open-bank/issues/1003)) ([05ac3d8](https://github.com/JiRaska/open-bank/commit/05ac3d8fff9b405fe508ff761cca5c400d95233a))
* **anacredit:** assign unique HTTP port 8137 (resolve collision with onboarding) ([#1041](https://github.com/JiRaska/open-bank/issues/1041)) ([fb88443](https://github.com/JiRaska/open-bank/commit/fb88443b5f1c84e56ca4650265d2787e18de9287))
* **dispute:** assign unique HTTP port 8135 (resolve collision with lending) ([#1045](https://github.com/JiRaska/open-bank/issues/1045)) ([b219e93](https://github.com/JiRaska/open-bank/commit/b219e93ac044ee2d7c4b8e352bbba17265c624dc))
* **statement:** assign unique HTTP port 8136 (resolve collision with customer-edge) ([#1046](https://github.com/JiRaska/open-bank/issues/1046)) ([d9aa9c6](https://github.com/JiRaska/open-bank/commit/d9aa9c6538ab767118de226fddb6cfc2d0eafb53))

## [0.27.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.26.0...admin-ui-v0.27.0) (2026-06-12)


### Features

* **admin-ui,infra:** D4 proposal flag in UI + D9 block mode in sandbox ([#324](https://github.com/JiRaska/open-bank/issues/324)) ([03e2254](https://github.com/JiRaska/open-bank/commit/03e2254511c75156cff1af8642937da3d99c6f87))
* **admin-ui,onboarding:** Sprint 3 — legalName in account form, onboarding REST security (ADR-0068) ([#477](https://github.com/JiRaska/open-bank/issues/477)) ([251f2e6](https://github.com/JiRaska/open-bank/commit/251f2e6796b32063bfcde0542fab281b71ec0faa))
* **admin-ui:** add Day-end close cockpit visualizing the EoD ledger tie-out ([#464](https://github.com/JiRaska/open-bank/issues/464)) ([f633063](https://github.com/JiRaska/open-bank/commit/f633063a2ea75e119ac8a2394af5fed8e40d513a))
* **admin-ui:** add FinOps cost allocation showback (service/domain/business-flow) ([#348](https://github.com/JiRaska/open-bank/issues/348)) ([7e0ce40](https://github.com/JiRaska/open-bank/commit/7e0ce4006ee29c8acb00a4e773620d65ea9b8533))
* **admin-ui:** add onboarding cockpit page (ADR-0068 Gap 4) ([#455](https://github.com/JiRaska/open-bank/issues/455)) ([9a5797b](https://github.com/JiRaska/open-bank/commit/9a5797b9f343680434cec80e3f9ceae5b37407cc))
* **admin-ui:** Cluster & container dossier (/docs/cluster) — ADR-0081 ([#772](https://github.com/JiRaska/open-bank/issues/772)) ([9846235](https://github.com/JiRaska/open-bank/commit/984623524590b4bf4428fc754b46513214c24469))
* **admin-ui:** code-derived catalog + service-graph generators (ADR-0029 D1+D3) ([#285](https://github.com/JiRaska/open-bank/issues/285)) ([c5f8050](https://github.com/JiRaska/open-bank/commit/c5f805006f3dda015d6cb99e1fee5bb1fc1afa16))
* **admin-ui:** cost route prefers live ConfigMap, falls back to baked ([#269](https://github.com/JiRaska/open-bank/issues/269)) ([cdab7fa](https://github.com/JiRaska/open-bank/commit/cdab7fa7fcc3f421fa3ee2dceaf7aad59a934584))
* **admin-ui:** customer-app dossier + CI content-check & cross-repo transport (ADR-0072) ([#583](https://github.com/JiRaska/open-bank/issues/583)) ([c639c3c](https://github.com/JiRaska/open-bank/commit/c639c3c7247340a85a19b27f6ada31b80653ecc0))
* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **admin-ui:** DORA Deployment Frequency from a git-derived snapshot (ADR-0061) ([#328](https://github.com/JiRaska/open-bank/issues/328)) ([490cf68](https://github.com/JiRaska/open-bank/commit/490cf6891a806989074ffe855d8f924fbdd59b60))
* **admin-ui:** governance & zero-trust visualization surfaces ([#392](https://github.com/JiRaska/open-bank/issues/392)) ([5c4f40d](https://github.com/JiRaska/open-bank/commit/5c4f40de95c44d87d5e0eac831efbd5fbcc6d9d7))
* **admin-ui:** IAOps — AI governance section (ADR-0031) ([#283](https://github.com/JiRaska/open-bank/issues/283)) ([ae753a9](https://github.com/JiRaska/open-bank/commit/ae753a9587540d493d73cd75f31817045ebad642))
* **admin-ui:** quality dashboard — Pact contract tests, pitest mutation, composite score (ADR-0063) ([#360](https://github.com/JiRaska/open-bank/issues/360)) ([00b25bc](https://github.com/JiRaska/open-bank/commit/00b25bcc934fea8728bb4b404166cd21c273495b))
* **admin-ui:** read-only feature-flag registry at /docs/flags (ADR-0067) ([#461](https://github.com/JiRaska/open-bank/issues/461)) ([3a8db98](https://github.com/JiRaska/open-bank/commit/3a8db989b719fa1ebcf3a0886d407f15f9f93f5e))
* **admin-ui:** real AWS cloud cost panel in FinOps (Cost Explorer snapshot) ([#262](https://github.com/JiRaska/open-bank/issues/262)) ([8d715db](https://github.com/JiRaska/open-bank/commit/8d715dbe259c15e39f351f1941c3acac01f4fd87))
* **admin-ui:** retire manifest.ts data + CI governance gate (ADR-0071 phase 4) ([#527](https://github.com/JiRaska/open-bank/issues/527)) ([7a4db5a](https://github.com/JiRaska/open-bank/commit/7a4db5a6420ac9074d96c85fcb1b8a1980f54342))
* **admin-ui:** Service Map LEGO bricks — side view ([#608](https://github.com/JiRaska/open-bank/issues/608)) ([42ced8e](https://github.com/JiRaska/open-bank/commit/42ced8e86941f8995f130f127234257e0a97d3bd))
* **admin-ui:** Service Map redesign — LEGO bricks, clean layout, readable edges ([#606](https://github.com/JiRaska/open-bank/issues/606)) ([876adb9](https://github.com/JiRaska/open-bank/commit/876adb9589cf23e9fa06dc7b360bbe8d8591be92))
* **admin-ui:** switch governance pages to derived data + fix drift (ADR-0071 phase 3) ([#526](https://github.com/JiRaska/open-bank/issues/526)) ([7d032ed](https://github.com/JiRaska/open-bank/commit/7d032edff2cf638e279dfffb8b9db66117bb95c1))
* **admin-ui:** Trace Explorer & Compliance Control Tower ([#393](https://github.com/JiRaska/open-bank/issues/393)) ([6cad862](https://github.com/JiRaska/open-bank/commit/6cad8622018ae2babee3c1d3d191780601822c34))
* **admin-ui:** visualise serverless tiers + scale-to-zero plan on services ([#308](https://github.com/JiRaska/open-bank/issues/308)) ([7de927e](https://github.com/JiRaska/open-bank/commit/7de927e1a416db8102ce91ace2f91b732fe6d5e7))
* **admin-ui:** wire /parties/search BFF — ADR-0055 Phase 4 ([#411](https://github.com/JiRaska/open-bank/issues/411)) ([6f53864](https://github.com/JiRaska/open-bank/commit/6f5386440b1f630539961ad110ac920951f1e3a1))
* **agent:** HITL proposal queue — draft_ticket tool + admin-ui approvals (ADR-0031 D4) ([#657](https://github.com/JiRaska/open-bank/issues/657)) ([ba90e1b](https://github.com/JiRaska/open-bank/commit/ba90e1bde400f3641630743f4779db1c17f5659e))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **infra:** deploy Kafka UI to messaging namespace + fix dynamic topic list ([#389](https://github.com/JiRaska/open-bank/issues/389)) ([1db0b5e](https://github.com/JiRaska/open-bank/commit/1db0b5e5230865ac0849f151cb7f1cf94e842fd9))
* **infra:** lifecycle & vulnerability intelligence on /infrastructure (ADR-0077) ([#669](https://github.com/JiRaska/open-bank/issues/669)) ([96c3f78](https://github.com/JiRaska/open-bank/commit/96c3f788366ea98ded27001a39de000b4ef05bd8))
* **observability:** LGTM+Pyroscope correlation layer + GlitchTip on one pane (ADR-0079) ([#788](https://github.com/JiRaska/open-bank/issues/788)) ([0645c63](https://github.com/JiRaska/open-bank/commit/0645c63d4914f98b4a45fbcb0a1a441d9e97b6de))
* **sanctions:** list-scope selector in manual screening ([a2560dd](https://github.com/JiRaska/open-bank/commit/a2560dd2f23fecf6cbcc50c5b750b36a92e1ac2a))
* **security-scanner:** deploy security-scanner to GitOps + sync governance manifest ([#354](https://github.com/JiRaska/open-bank/issues/354)) ([eca7198](https://github.com/JiRaska/open-bank/commit/eca71982de5c16f4c7c827087f98b3af1f81cd97))
* **statement:** harden monthly close cadence and enable the cron ([#470](https://github.com/JiRaska/open-bank/issues/470)) ([#629](https://github.com/JiRaska/open-bank/issues/629)) ([43b1fd7](https://github.com/JiRaska/open-bank/commit/43b1fd77b0cd4cfb839fc23cdffadec83587f8d1))


### Bug Fixes

* **admin-ui:** add -service suffix candidate in SBOM path lookup + bump 0.23.0→0.23.1 ([#385](https://github.com/JiRaska/open-bank/issues/385)) ([9cd7fb3](https://github.com/JiRaska/open-bank/commit/9cd7fb3b0ff6061489f32581f16af65a09da540a))
* **admin-ui:** add /approvals layout so the page renders inside the app shell ([#660](https://github.com/JiRaska/open-bank/issues/660)) ([ad6f566](https://github.com/JiRaska/open-bank/commit/ad6f5667b61d93f7ff8a3f321ea678a5c340cc50))
* **admin-ui:** add app-level error boundary, not bare global-error ([#551](https://github.com/JiRaska/open-bank/issues/551)) ([0b24b47](https://github.com/JiRaska/open-bank/commit/0b24b474f4d174e3759cfb89dc5eed9d7a3aeb6b))
* **admin-ui:** add customer-edge and onboarding-service to governance manifest ([#491](https://github.com/JiRaska/open-bank/issues/491)) ([366fbd2](https://github.com/JiRaska/open-bank/commit/366fbd27b012efa26cd46a1515dd11a4c9ce1bf3))
* **admin-ui:** add missing namespace→group mappings and RoleBindings for discovery ([#487](https://github.com/JiRaska/open-bank/issues/487)) ([80f7786](https://github.com/JiRaska/open-bank/commit/80f77864e42c2bec6ddc9de95effd0f43c16486e))
* **admin-ui:** add sanctions+security-scanner to namespace discovery ([#370](https://github.com/JiRaska/open-bank/issues/370)) ([9e05ee6](https://github.com/JiRaska/open-bank/commit/9e05ee66408caa7040221c03d16f4b44b0536397))
* **admin-ui:** align FinOps cost bars on a fixed label column ([#325](https://github.com/JiRaska/open-bank/issues/325)) ([253146e](https://github.com/JiRaska/open-bank/commit/253146ebbb15e5d2df8b13fddd10ac6fc4bc7f74))
* **admin-ui:** correct account-search copy to match deployed capability ([#292](https://github.com/JiRaska/open-bank/issues/292)) ([2bfa4a9](https://github.com/JiRaska/open-bank/commit/2bfa4a93b5709775645ba9f457a542854b5df26d))
* **admin-ui:** correct security-scanner specId in API docs page ([fe954e5](https://github.com/JiRaska/open-bank/commit/fe954e51e799b399c8ebba50eeed6c784818864f))
* **admin-ui:** discover the kyc namespace (KYC section showed 'not deployed') ([#532](https://github.com/JiRaska/open-bank/issues/532)) ([90bc867](https://github.com/JiRaska/open-bank/commit/90bc867ab04dc9d28fa26d0d9241f01d93af1343))
* **admin-ui:** distinguish scaled-to-zero services from not-deployed ([#544](https://github.com/JiRaska/open-bank/issues/544)) ([d9e9a6f](https://github.com/JiRaska/open-bank/commit/d9e9a6ffc16e6b8b9a55ed32959118ccdd26771d))
* **admin-ui:** drive Service Documentation list from live cluster, not a static array ([#271](https://github.com/JiRaska/open-bank/issues/271)) ([30bdb2b](https://github.com/JiRaska/open-bank/commit/30bdb2b79fbbb838f608cf9d8958bfc8ec3e9172))
* **admin-ui:** dynamic Kafka topics from Kafka UI API ([#386](https://github.com/JiRaska/open-bank/issues/386)) ([33a28dc](https://github.com/JiRaska/open-bank/commit/33a28dc6892bfc4beef9ff176592b278cdc5e648))
* **admin-ui:** FinOps platform components reflect real in-cluster stack ([#260](https://github.com/JiRaska/open-bank/issues/260)) ([e1ddb25](https://github.com/JiRaska/open-bank/commit/e1ddb25944624a5cbffbcfe77d6807b928253e45))
* **admin-ui:** force dynamic rendering in RootLayout for nonce CSP ([#739](https://github.com/JiRaska/open-bank/issues/739)) ([8f21a79](https://github.com/JiRaska/open-bank/commit/8f21a7958f0968f67971f9c8a50307353c2b7031))
* **admin-ui:** read KYC check field as checkType, not type ([#543](https://github.com/JiRaska/open-bank/issues/543)) ([7171dc1](https://github.com/JiRaska/open-bank/commit/7171dc1c2bf9c2782e76dfa045b37d5ac7e946b3))
* **admin-ui:** realign version.txt + deploy refreshed dossier ([#647](https://github.com/JiRaska/open-bank/issues/647)) ([39aa0b3](https://github.com/JiRaska/open-bank/commit/39aa0b38705f3be6943732f4f83424db9f5fe076))
* **admin-ui:** refresh /iaops AI-governance statuses to current reality (ADR-0031) ([#741](https://github.com/JiRaska/open-bank/issues/741)) ([38599f1](https://github.com/JiRaska/open-bank/commit/38599f11c84733f7dc14baab380ded8b3b982071))
* **admin-ui:** refresh stale hardcoded values across pages (currency audit) ([#742](https://github.com/JiRaska/open-bank/issues/742)) ([7b9739c](https://github.com/JiRaska/open-bank/commit/7b9739cf8dd6048dda04f910e87abdd8d58e789e))
* **admin-ui:** remove redundant flags layout.tsx — double sidebar bug ([23328b0](https://github.com/JiRaska/open-bank/commit/23328b0b74032a2b73287985f6f779c3f8825054))
* **admin-ui:** Service Map — fix render crash + restore edges (graph-derived) ([#599](https://github.com/JiRaska/open-bank/issues/599)) ([a0235ea](https://github.com/JiRaska/open-bank/commit/a0235ea00b552e32eb7d0f4563a489025c903f0d))
* **admin-ui:** Service Map LEGO bricks — real proportions ([#615](https://github.com/JiRaska/open-bank/issues/615)) ([f10e1dc](https://github.com/JiRaska/open-bank/commit/f10e1dc1d0a9502fd8af436b6d4abb4df1394ab5))
* **admin-ui:** Service Map LEGO bricks — uniform height (length scales with degree) ([#613](https://github.com/JiRaska/open-bank/issues/613)) ([167faf4](https://github.com/JiRaska/open-bank/commit/167faf4f0ac7b6db4f62844c0832a9d438582b8f))
* **admin-ui:** strip glob wildcards from IBAN fragment search + show BBAN in account detail ([#396](https://github.com/JiRaska/open-bank/issues/396)) ([cea664f](https://github.com/JiRaska/open-bank/commit/cea664f43a2bad12c16c3e9f93a3b11589d92d98))
* **admin-ui:** wire account search to the trigram /search endpoint ([#277](https://github.com/JiRaska/open-bank/issues/277)) ([de50213](https://github.com/JiRaska/open-bank/commit/de50213a08ffdd521aa3bd16d3d0cad7f08e12a9))
* **admin-ui:** wire lang prop to DefenseRings aria-label (i18n guard) ([#778](https://github.com/JiRaska/open-bank/issues/778)) ([8ad68f6](https://github.com/JiRaska/open-bank/commit/8ad68f6b364a30ecdd653700d53b5678ed09c1f3))
* **audit:** align Kafka topic names + add missing KafkaTopic manifests ([#380](https://github.com/JiRaska/open-bank/issues/380)) ([fe954e5](https://github.com/JiRaska/open-bank/commit/fe954e51e799b399c8ebba50eeed6c784818864f))
* **finops:** correct monthly total ( not /bin/zsh.17) + domain/process breakdown ([#331](https://github.com/JiRaska/open-bank/issues/331)) ([ce46ae8](https://github.com/JiRaska/open-bank/commit/ce46ae8fbb9832b2a93fbe4c94c8d94539fc7baf))
* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **security:** gate the last unauthenticated API routes (post-pentest review) ([#752](https://github.com/JiRaska/open-bank/issues/752)) ([fdb6822](https://github.com/JiRaska/open-bank/commit/fdb68227b5c8663f6c5d27a2c2339a6525f9693a))
* **security:** P1 follow-up — CSP on /auth pages + federated-logout public origin ([#707](https://github.com/JiRaska/open-bank/issues/707)) ([08ed9eb](https://github.com/JiRaska/open-bank/commit/08ed9eb67eee0f92fd6353c41f7deabb684e1711))
* **security:** pentest P0 — auth internal endpoints + least-privilege agent (ADR-0078) ([#700](https://github.com/JiRaska/open-bank/issues/700)) ([6d6866c](https://github.com/JiRaska/open-bank/commit/6d6866c48f7692b8478b19ec5915392dd41675fd))
* **security:** pentest P1 — federated logout, nonce CSP, BFF payments, cookies (ADR-0078) ([#705](https://github.com/JiRaska/open-bank/issues/705)) ([6bcbd5d](https://github.com/JiRaska/open-bank/commit/6bcbd5d7c476dcdc0154d9b4c7e1fac042f8e3a0))
* **security:** pentest P2 — prompt-leak hardening, 1h session, AI-proposal warning (ADR-0078) ([#746](https://github.com/JiRaska/open-bank/issues/746)) ([6c35555](https://github.com/JiRaska/open-bank/commit/6c355558b10498efefbfeebabb40b36aeb0895cb))


### Security

* **admin-ui:** drop cleartext localhost Keycloak URL default from the bundle ([#634](https://github.com/JiRaska/open-bank/issues/634)) ([ec6d5b3](https://github.com/JiRaska/open-bank/commit/ec6d5b3e7e5f72ad9684092786cd65bc0a018651))
* **admin-ui:** require operator session for /api/agent/mcp relay ([#622](https://github.com/JiRaska/open-bank/issues/622)) ([7121b1c](https://github.com/JiRaska/open-bank/commit/7121b1caf0517699ad39b5ee7048252e9c2e7ca8))
* harden HTTP headers + block Keycloak admin API from internet ([#359](https://github.com/JiRaska/open-bank/issues/359)) ([be8efc1](https://github.com/JiRaska/open-bank/commit/be8efc1ceea36f630054af44fa5293676a9f0a8f))
* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank/issues/342)) ([e368296](https://github.com/JiRaska/open-bank/commit/e3682965a4f7df3b7328e8a741e4809604706390))

## [0.16.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.15.0...admin-ui-v0.16.0) (2026-06-09)


### Features

* **agent:** HITL proposal queue — draft_ticket tool + admin-ui approvals (ADR-0031 D4) ([#657](https://github.com/JiRaska/open-bank/issues/657)) ([ba90e1b](https://github.com/JiRaska/open-bank/commit/ba90e1bde400f3641630743f4779db1c17f5659e))
* **statement:** harden monthly close cadence and enable the cron ([#470](https://github.com/JiRaska/open-bank/issues/470)) ([#629](https://github.com/JiRaska/open-bank/issues/629)) ([43b1fd7](https://github.com/JiRaska/open-bank/commit/43b1fd77b0cd4cfb839fc23cdffadec83587f8d1))


### Bug Fixes

* **admin-ui:** add /approvals layout so the page renders inside the app shell ([#660](https://github.com/JiRaska/open-bank/issues/660)) ([ad6f566](https://github.com/JiRaska/open-bank/commit/ad6f5667b61d93f7ff8a3f321ea678a5c340cc50))
* **admin-ui:** realign version.txt + deploy refreshed dossier ([#647](https://github.com/JiRaska/open-bank/issues/647)) ([39aa0b3](https://github.com/JiRaska/open-bank/commit/39aa0b38705f3be6943732f4f83424db9f5fe076))


### Security

* **admin-ui:** drop cleartext localhost Keycloak URL default from the bundle ([#634](https://github.com/JiRaska/open-bank/issues/634)) ([ec6d5b3](https://github.com/JiRaska/open-bank/commit/ec6d5b3e7e5f72ad9684092786cd65bc0a018651))
* **admin-ui:** require operator session for /api/agent/mcp relay ([#622](https://github.com/JiRaska/open-bank/issues/622)) ([7121b1c](https://github.com/JiRaska/open-bank/commit/7121b1caf0517699ad39b5ee7048252e9c2e7ca8))

## [0.15.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.14.0...admin-ui-v0.15.0) (2026-06-09)


### Features

* **admin-ui:** add Day-end close cockpit visualizing the EoD ledger tie-out ([#464](https://github.com/JiRaska/open-bank/issues/464)) ([f633063](https://github.com/JiRaska/open-bank/commit/f633063a2ea75e119ac8a2394af5fed8e40d513a))
* **admin-ui:** customer-app dossier + CI content-check & cross-repo transport (ADR-0072) ([#583](https://github.com/JiRaska/open-bank/issues/583)) ([c639c3c](https://github.com/JiRaska/open-bank/commit/c639c3c7247340a85a19b27f6ada31b80653ecc0))
* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **admin-ui:** retire manifest.ts data + CI governance gate (ADR-0071 phase 4) ([#527](https://github.com/JiRaska/open-bank/issues/527)) ([7a4db5a](https://github.com/JiRaska/open-bank/commit/7a4db5a6420ac9074d96c85fcb1b8a1980f54342))
* **admin-ui:** Service Map LEGO bricks — side view ([#608](https://github.com/JiRaska/open-bank/issues/608)) ([42ced8e](https://github.com/JiRaska/open-bank/commit/42ced8e86941f8995f130f127234257e0a97d3bd))
* **admin-ui:** Service Map redesign — LEGO bricks, clean layout, readable edges ([#606](https://github.com/JiRaska/open-bank/issues/606)) ([876adb9](https://github.com/JiRaska/open-bank/commit/876adb9589cf23e9fa06dc7b360bbe8d8591be92))
* **admin-ui:** switch governance pages to derived data + fix drift (ADR-0071 phase 3) ([#526](https://github.com/JiRaska/open-bank/issues/526)) ([7d032ed](https://github.com/JiRaska/open-bank/commit/7d032edff2cf638e279dfffb8b9db66117bb95c1))
* **sanctions:** list-scope selector in manual screening ([a2560dd](https://github.com/JiRaska/open-bank/commit/a2560dd2f23fecf6cbcc50c5b750b36a92e1ac2a))


### Bug Fixes

* **admin-ui:** add -service suffix candidate in SBOM path lookup + bump 0.23.0→0.23.1 ([#385](https://github.com/JiRaska/open-bank/issues/385)) ([9cd7fb3](https://github.com/JiRaska/open-bank/commit/9cd7fb3b0ff6061489f32581f16af65a09da540a))
* **admin-ui:** add app-level error boundary, not bare global-error ([#551](https://github.com/JiRaska/open-bank/issues/551)) ([0b24b47](https://github.com/JiRaska/open-bank/commit/0b24b474f4d174e3759cfb89dc5eed9d7a3aeb6b))
* **admin-ui:** add customer-edge and onboarding-service to governance manifest ([#491](https://github.com/JiRaska/open-bank/issues/491)) ([366fbd2](https://github.com/JiRaska/open-bank/commit/366fbd27b012efa26cd46a1515dd11a4c9ce1bf3))
* **admin-ui:** add missing namespace→group mappings and RoleBindings for discovery ([#487](https://github.com/JiRaska/open-bank/issues/487)) ([80f7786](https://github.com/JiRaska/open-bank/commit/80f77864e42c2bec6ddc9de95effd0f43c16486e))
* **admin-ui:** discover the kyc namespace (KYC section showed 'not deployed') ([#532](https://github.com/JiRaska/open-bank/issues/532)) ([90bc867](https://github.com/JiRaska/open-bank/commit/90bc867ab04dc9d28fa26d0d9241f01d93af1343))
* **admin-ui:** distinguish scaled-to-zero services from not-deployed ([#544](https://github.com/JiRaska/open-bank/issues/544)) ([d9e9a6f](https://github.com/JiRaska/open-bank/commit/d9e9a6ffc16e6b8b9a55ed32959118ccdd26771d))
* **admin-ui:** read KYC check field as checkType, not type ([#543](https://github.com/JiRaska/open-bank/issues/543)) ([7171dc1](https://github.com/JiRaska/open-bank/commit/7171dc1c2bf9c2782e76dfa045b37d5ac7e946b3))
* **admin-ui:** remove redundant flags layout.tsx — double sidebar bug ([23328b0](https://github.com/JiRaska/open-bank/commit/23328b0b74032a2b73287985f6f779c3f8825054))
* **admin-ui:** Service Map — fix render crash + restore edges (graph-derived) ([#599](https://github.com/JiRaska/open-bank/issues/599)) ([a0235ea](https://github.com/JiRaska/open-bank/commit/a0235ea00b552e32eb7d0f4563a489025c903f0d))
* **admin-ui:** Service Map LEGO bricks — real proportions ([#615](https://github.com/JiRaska/open-bank/issues/615)) ([f10e1dc](https://github.com/JiRaska/open-bank/commit/f10e1dc1d0a9502fd8af436b6d4abb4df1394ab5))
* **admin-ui:** Service Map LEGO bricks — uniform height (length scales with degree) ([#613](https://github.com/JiRaska/open-bank/issues/613)) ([167faf4](https://github.com/JiRaska/open-bank/commit/167faf4f0ac7b6db4f62844c0832a9d438582b8f))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.14.0](https://github.com/JiRaska/open-bank/compare/admin-ui-v0.13.0...admin-ui-v0.14.0) (2026-06-06)


### Features

* **admin-ui,infra:** D4 proposal flag in UI + D9 block mode in sandbox ([#324](https://github.com/JiRaska/open-bank/issues/324)) ([03e2254](https://github.com/JiRaska/open-bank/commit/03e2254511c75156cff1af8642937da3d99c6f87))
* **admin-ui:** add FinOps cost allocation showback (service/domain/business-flow) ([#348](https://github.com/JiRaska/open-bank/issues/348)) ([7e0ce40](https://github.com/JiRaska/open-bank/commit/7e0ce4006ee29c8acb00a4e773620d65ea9b8533))
* **admin-ui:** add onboarding cockpit page (ADR-0068 Gap 4) ([#455](https://github.com/JiRaska/open-bank/issues/455)) ([9a5797b](https://github.com/JiRaska/open-bank/commit/9a5797b9f343680434cec80e3f9ceae5b37407cc))
* **admin-ui:** code-derived catalog + service-graph generators (ADR-0029 D1+D3) ([#285](https://github.com/JiRaska/open-bank/issues/285)) ([c5f8050](https://github.com/JiRaska/open-bank/commit/c5f805006f3dda015d6cb99e1fee5bb1fc1afa16))
* **admin-ui:** cost route prefers live ConfigMap, falls back to baked ([#269](https://github.com/JiRaska/open-bank/issues/269)) ([cdab7fa](https://github.com/JiRaska/open-bank/commit/cdab7fa7fcc3f421fa3ee2dceaf7aad59a934584))
* **admin-ui:** DORA Deployment Frequency from a git-derived snapshot (ADR-0061) ([#328](https://github.com/JiRaska/open-bank/issues/328)) ([490cf68](https://github.com/JiRaska/open-bank/commit/490cf6891a806989074ffe855d8f924fbdd59b60))
* **admin-ui:** governance & zero-trust visualization surfaces ([#392](https://github.com/JiRaska/open-bank/issues/392)) ([5c4f40d](https://github.com/JiRaska/open-bank/commit/5c4f40de95c44d87d5e0eac831efbd5fbcc6d9d7))
* **admin-ui:** IAOps — AI governance section (ADR-0031) ([#283](https://github.com/JiRaska/open-bank/issues/283)) ([ae753a9](https://github.com/JiRaska/open-bank/commit/ae753a9587540d493d73cd75f31817045ebad642))
* **admin-ui:** quality dashboard — Pact contract tests, pitest mutation, composite score (ADR-0063) ([#360](https://github.com/JiRaska/open-bank/issues/360)) ([00b25bc](https://github.com/JiRaska/open-bank/commit/00b25bcc934fea8728bb4b404166cd21c273495b))
* **admin-ui:** read-only feature-flag registry at /docs/flags (ADR-0067) ([#461](https://github.com/JiRaska/open-bank/issues/461)) ([3a8db98](https://github.com/JiRaska/open-bank/commit/3a8db989b719fa1ebcf3a0886d407f15f9f93f5e))
* **admin-ui:** real AWS cloud cost panel in FinOps (Cost Explorer snapshot) ([#262](https://github.com/JiRaska/open-bank/issues/262)) ([8d715db](https://github.com/JiRaska/open-bank/commit/8d715dbe259c15e39f351f1941c3acac01f4fd87))
* **admin-ui:** Trace Explorer & Compliance Control Tower ([#393](https://github.com/JiRaska/open-bank/issues/393)) ([6cad862](https://github.com/JiRaska/open-bank/commit/6cad8622018ae2babee3c1d3d191780601822c34))
* **admin-ui:** visualise serverless tiers + scale-to-zero plan on services ([#308](https://github.com/JiRaska/open-bank/issues/308)) ([7de927e](https://github.com/JiRaska/open-bank/commit/7de927e1a416db8102ce91ace2f91b732fe6d5e7))
* **admin-ui:** wire /parties/search BFF — ADR-0055 Phase 4 ([#411](https://github.com/JiRaska/open-bank/issues/411)) ([6f53864](https://github.com/JiRaska/open-bank/commit/6f5386440b1f630539961ad110ac920951f1e3a1))
* **infra:** deploy Kafka UI to messaging namespace + fix dynamic topic list ([#389](https://github.com/JiRaska/open-bank/issues/389)) ([1db0b5e](https://github.com/JiRaska/open-bank/commit/1db0b5e5230865ac0849f151cb7f1cf94e842fd9))
* **security-scanner:** deploy security-scanner to GitOps + sync governance manifest ([#354](https://github.com/JiRaska/open-bank/issues/354)) ([eca7198](https://github.com/JiRaska/open-bank/commit/eca71982de5c16f4c7c827087f98b3af1f81cd97))


### Bug Fixes

* **admin-ui:** add sanctions+security-scanner to namespace discovery ([#370](https://github.com/JiRaska/open-bank/issues/370)) ([9e05ee6](https://github.com/JiRaska/open-bank/commit/9e05ee66408caa7040221c03d16f4b44b0536397))
* **admin-ui:** align FinOps cost bars on a fixed label column ([#325](https://github.com/JiRaska/open-bank/issues/325)) ([253146e](https://github.com/JiRaska/open-bank/commit/253146ebbb15e5d2df8b13fddd10ac6fc4bc7f74))
* **admin-ui:** correct account-search copy to match deployed capability ([#292](https://github.com/JiRaska/open-bank/issues/292)) ([2bfa4a9](https://github.com/JiRaska/open-bank/commit/2bfa4a93b5709775645ba9f457a542854b5df26d))
* **admin-ui:** correct security-scanner specId in API docs page ([fe954e5](https://github.com/JiRaska/open-bank/commit/fe954e51e799b399c8ebba50eeed6c784818864f))
* **admin-ui:** drive Service Documentation list from live cluster, not a static array ([#271](https://github.com/JiRaska/open-bank/issues/271)) ([30bdb2b](https://github.com/JiRaska/open-bank/commit/30bdb2b79fbbb838f608cf9d8958bfc8ec3e9172))
* **admin-ui:** dynamic Kafka topics from Kafka UI API ([#386](https://github.com/JiRaska/open-bank/issues/386)) ([33a28dc](https://github.com/JiRaska/open-bank/commit/33a28dc6892bfc4beef9ff176592b278cdc5e648))
* **admin-ui:** FinOps platform components reflect real in-cluster stack ([#260](https://github.com/JiRaska/open-bank/issues/260)) ([e1ddb25](https://github.com/JiRaska/open-bank/commit/e1ddb25944624a5cbffbcfe77d6807b928253e45))
* **admin-ui:** strip glob wildcards from IBAN fragment search + show BBAN in account detail ([#396](https://github.com/JiRaska/open-bank/issues/396)) ([cea664f](https://github.com/JiRaska/open-bank/commit/cea664f43a2bad12c16c3e9f93a3b11589d92d98))
* **admin-ui:** wire account search to the trigram /search endpoint ([#277](https://github.com/JiRaska/open-bank/issues/277)) ([de50213](https://github.com/JiRaska/open-bank/commit/de50213a08ffdd521aa3bd16d3d0cad7f08e12a9))
* **audit:** align Kafka topic names + add missing KafkaTopic manifests ([#380](https://github.com/JiRaska/open-bank/issues/380)) ([fe954e5](https://github.com/JiRaska/open-bank/commit/fe954e51e799b399c8ebba50eeed6c784818864f))
* **finops:** correct monthly total ( not /bin/zsh.17) + domain/process breakdown ([#331](https://github.com/JiRaska/open-bank/issues/331)) ([ce46ae8](https://github.com/JiRaska/open-bank/commit/ce46ae8fbb9832b2a93fbe4c94c8d94539fc7baf))
* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))


### Security

* harden HTTP headers + block Keycloak admin API from internet ([#359](https://github.com/JiRaska/open-bank/issues/359)) ([be8efc1](https://github.com/JiRaska/open-bank/commit/be8efc1ceea36f630054af44fa5293676a9f0a8f))
* **libs:** harden shared config + DB constraints + logging (beta pentest) ([#342](https://github.com/JiRaska/open-bank/issues/342)) ([e368296](https://github.com/JiRaska/open-bank/commit/e3682965a4f7df3b7328e8a741e4809604706390))
