# Changelog

## [0.14.1](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.14.0...document-service-v0.14.1) (2026-09-01)


### Bug Fixes

* **document:** reject a null array element with 400 instead of 500 ([#8010](https://github.com/JiRaska/open-bank-oss/issues/8010)) ([49de0d8](https://github.com/JiRaska/open-bank-oss/commit/49de0d8c1e2f781bb01c586d3a051f1a5ab03a77)), closes [#7867](https://github.com/JiRaska/open-bank-oss/issues/7867)

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.13.0...document-service-v0.14.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.12.3...document-service-v0.13.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.12.3](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.12.2...document-service-v0.12.3) (2026-08-22)


### Bug Fixes

* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))

## [0.12.2](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.12.1...document-service-v0.12.2) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.12.1](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.12.0...document-service-v0.12.1) (2026-08-19)


### Bug Fixes

* stop swallowing transient event-consumer failures as an ack across 4 services ([#5698](https://github.com/JiRaska/open-bank-oss/issues/5698)) ([#5725](https://github.com/JiRaska/open-bank-oss/issues/5725)) ([3219c5d](https://github.com/JiRaska/open-bank-oss/commit/3219c5de3944c39f22a94b4c44532b8521f8a6b5))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.11.3...document-service-v0.12.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.11.3](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.11.2...document-service-v0.11.3) (2026-08-18)


### Bug Fixes

* **document-service:** add sourceService to outbox events for audit attribution ([#5391](https://github.com/JiRaska/open-bank-oss/issues/5391)) ([e89923a](https://github.com/JiRaska/open-bank-oss/commit/e89923acc9d091fb397eec3fe2a57742ff603fe1)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [0.11.2](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.11.1...document-service-v0.11.2) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.11.1](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.11.0...document-service-v0.11.1) (2026-08-13)


### Bug Fixes

* **document-service:** a stub delivery must not record the statement as delivered ([#4659](https://github.com/JiRaska/open-bank-oss/issues/4659)) ([77ebab0](https://github.com/JiRaska/open-bank-oss/commit/77ebab08e3cafc0c99fa0480595d417fa6c3bf03)), closes [#4109](https://github.com/JiRaska/open-bank-oss/issues/4109)

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.10.1...document-service-v0.11.0) (2026-08-09)


### Features

* **document-service:** consume annual fee-summary events and deliver statement documents ([#4122](https://github.com/JiRaska/open-bank-oss/issues/4122)) ([02e4373](https://github.com/JiRaska/open-bank-oss/commit/02e43736724d0e383546a4d382798d981a90a0ab))

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.10.0...document-service-v0.10.1) (2026-08-08)


### Bug Fixes

* send occurredAt on the four non-money-path domain-event producers ([#3926](https://github.com/JiRaska/open-bank-oss/issues/3926)) ([4a2080c](https://github.com/JiRaska/open-bank-oss/commit/4a2080c3a4de10b2a858b7111ac83d63c60114d1))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/document-service-v0.9.1...document-service-v0.10.0) (2026-08-07)


### Features

* **document-service:** seed statement and payment confirmation document templates ([#4134](https://github.com/JiRaska/open-bank-oss/issues/4134)) ([6eb49da](https://github.com/JiRaska/open-bank-oss/commit/6eb49dacc623c6d3f0882407bef0d8353a722301))

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
