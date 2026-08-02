# Changelog

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.9.0...document-service-v0.9.1) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.8.9...document-service-v0.9.0) (2026-07-30)


### Features

* **finops:** route all four product-catalog callers through the KEDA interceptor ([#2699](https://github.com/JiRaska/open-bank-oss/issues/2699)) ([f603f4d](https://github.com/JiRaska/open-bank-oss/commit/f603f4d7d590200bb03bf97d83e36880ac74c862))

## [0.8.9](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.8.8...document-service-v0.8.9) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.8.8](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.8.7...document-service-v0.8.8) (2026-07-21)


### Bug Fixes

* **onboarding:** release the idempotency key when an agreement is archived ([#1851](https://github.com/JiRaska/open-bank-oss/issues/1851)) ([c724041](https://github.com/JiRaska/open-bank-oss/commit/c724041a60dd8ad282c26ad3b878a088ff65c305)), closes [#1850](https://github.com/JiRaska/open-bank-oss/issues/1850)

## [0.8.7](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.8.6...document-service-v0.8.7) (2026-07-20)


### Bug Fixes

* **document-service:** visible signature block, and stop duplicate agreements ([#1817](https://github.com/JiRaska/open-bank-oss/issues/1817)) ([b6b4faf](https://github.com/JiRaska/open-bank-oss/commit/b6b4faf3781c364efa5a11302e623be56463719f))

## [0.8.6](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.8.5...document-service-v0.8.6) (2026-07-17)


### Bug Fixes

* **document-service:** fill the RAMCOVA_SMLOUVA template with real party/account/product data ([#1595](https://github.com/JiRaska/open-bank-oss/issues/1595)) ([e154349](https://github.com/JiRaska/open-bank-oss/commit/e154349afd01b53ee0ad5040faa4435af171771b)), closes [#1497](https://github.com/JiRaska/open-bank-oss/issues/1497)

## [0.8.5](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.8.4...document-service-v0.8.5) (2026-07-17)


### Bug Fixes

* **document:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1519](https://github.com/JiRaska/open-bank-oss/issues/1519)) ([7afdeb4](https://github.com/JiRaska/open-bank-oss/commit/7afdeb4f7ad05f1ce0dba48380a823f7b4bc74cd))

## [0.8.4](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.8.3...document-service-v0.8.4) (2026-07-17)


### Bug Fixes

* **document-service:** heal documents signed before the SIGNED transition existed ([#1416](https://github.com/JiRaska/open-bank-oss/issues/1416)) ([84182a4](https://github.com/JiRaska/open-bank-oss/commit/84182a408cf7d9b304bfc4a2ef5f3fc5b5d2ad67))

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.8.2...document-service-v0.8.3) (2026-07-17)


### Bug Fixes

* **document-service:** persist the SIGNED transition after sealing ([#1380](https://github.com/JiRaska/open-bank-oss/issues/1380)) ([344924f](https://github.com/JiRaska/open-bank-oss/commit/344924f2a7393cde8117af8b20a5945737c0e047))

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.8.1...document-service-v0.8.2) (2026-07-16)


### Bug Fixes

* **document-service:** upsert in save(), so an onboarding language switch works ([#1279](https://github.com/JiRaska/open-bank-oss/issues/1279)) ([7d5688b](https://github.com/JiRaska/open-bank-oss/commit/7d5688b1fc97d7e9ca1b58ca2aca041c6f84b997))

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.8.0...document-service-v0.8.1) (2026-07-16)


### Bug Fixes

* **document:** refuse to seal with a throwaway cert (ADR-0172 D2) ([#1298](https://github.com/JiRaska/open-bank-oss/issues/1298)) ([b7fc299](https://github.com/JiRaska/open-bank-oss/commit/b7fc29954be274a7ca483a7a96b60c6f420dfe7c))
* **document:** warn about the DEV-ONLY seal identity at startup, not at first use ([#1299](https://github.com/JiRaska/open-bank-oss/issues/1299)) ([4834628](https://github.com/JiRaska/open-bank-oss/commit/4834628e9b4ba966e585fd29350d1405423de756)), closes [#1284](https://github.com/JiRaska/open-bank-oss/issues/1284)

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.7.1...document-service-v0.8.0) (2026-07-16)


### Features

* **document-service:** idempotent language-correct onboarding agreement (ADR-0169 D3) ([#1137](https://github.com/JiRaska/open-bank-oss/issues/1137)) ([7bf82fa](https://github.com/JiRaska/open-bank-oss/commit/7bf82fa4c723159c8c4813edbba6dea9399c5e4d))
* **document-service:** scope SCA verification to the exact document (ADR-0169 D2) ([#1142](https://github.com/JiRaska/open-bank-oss/issues/1142)) ([f713a74](https://github.com/JiRaska/open-bank-oss/commit/f713a748559feb19d20cefd4aa10f660c11fe78a))


### Bug Fixes

* **document-service:** consume challenge directly instead of a premature status pre-check ([#1155](https://github.com/JiRaska/open-bank-oss/issues/1155)) ([aeb6e20](https://github.com/JiRaska/open-bank-oss/commit/aeb6e2021b2329ba689a7e6127f8657499d4ca3d))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.7.0...document-service-v0.7.1) (2026-07-14)


### Bug Fixes

* **document-service:** data-level idempotency + resumability for onboarding issuance ([#1127](https://github.com/JiRaska/open-bank-oss/issues/1127)) ([a4c398b](https://github.com/JiRaska/open-bank-oss/commit/a4c398bce865766b2720255631aa488cecd5cc3b)), closes [#1112](https://github.com/JiRaska/open-bank-oss/issues/1112)
* **document-service:** poison-pill onboarding consumer + validate decision before signing ([#1123](https://github.com/JiRaska/open-bank-oss/issues/1123)) ([049e997](https://github.com/JiRaska/open-bank-oss/commit/049e9974d21f132c696b62c30fb618405fc63340)), closes [#1112](https://github.com/JiRaska/open-bank-oss/issues/1112)

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.6.0...document-service-v0.7.0) (2026-07-14)


### Features

* **document-service:** fail-closed go-live gate for real signatures ([#1115](https://github.com/JiRaska/open-bank-oss/issues/1115)) ([b18d9fb](https://github.com/JiRaska/open-bank-oss/commit/b18d9fb6fbb7feabea364ee08f0f929b59fc14de))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.5.0...document-service-v0.6.0) (2026-07-14)


### Features

* **document-service:** two-tier e-signature + onboarding wiring ([#1102](https://github.com/JiRaska/open-bank-oss/issues/1102)) ([bf4c757](https://github.com/JiRaska/open-bank-oss/commit/bf4c75761c6a5d0fc74f08e1c8e3aa0a0c494411))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.4.0...document-service-v0.5.0) (2026-07-14)


### Features

* **document-service:** enforce one PUBLISHED version per template code ([#1093](https://github.com/JiRaska/open-bank-oss/issues/1093)) ([6b69a34](https://github.com/JiRaska/open-bank-oss/commit/6b69a34d5b803b474b3de15a3d96e96d7e63bcb2))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.3.0...document-service-v0.4.0) (2026-07-14)


### Features

* **document-service,admin-ui:** letterhead + editor UX (new-window preview, syntax highlighting) ([#1083](https://github.com/JiRaska/open-bank-oss/issues/1083)) ([2da7ba6](https://github.com/JiRaska/open-bank-oss/commit/2da7ba6e8adb1bed9cc88d7f2b9c24c49ac39d91))


### Bug Fixes

* **document-service:** return bodyHtml from the templates API ([#1073](https://github.com/JiRaska/open-bank-oss/issues/1073)) ([7e49b9b](https://github.com/JiRaska/open-bank-oss/commit/7e49b9b504c69141269751b5fc3e9c8da662dd62))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.2.0...document-service-v0.3.0) (2026-07-14)


### Features

* **document-service:** seed VOP/framework/account templates (cs/en) + dynamic preview ([#1052](https://github.com/JiRaska/open-bank-oss/issues/1052)) ([0df4b9a](https://github.com/JiRaska/open-bank-oss/commit/0df4b9ab8142b2c59f10826099df56dc262ed142))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.1.0...document-service-v0.2.0) (2026-07-14)


### Features

* **document-service:** document management, templating and e-signature platform ([#1037](https://github.com/JiRaska/open-bank-oss/issues/1037)) ([237c397](https://github.com/JiRaska/open-bank-oss/commit/237c397cfb408f0cc1f0581fffa41557ffe08082))


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## Changelog
