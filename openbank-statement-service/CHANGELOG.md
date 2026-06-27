# Changelog

## [0.5.1](https://github.com/JiRaska/open-bank/compare/statement-service-v0.5.0...statement-service-v0.5.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/statement-service-v0.4.0...statement-service-v0.5.0) (2026-06-25)


### Features

* **tpp-registry,statement,onboarding,agent,settlement,sdd:** inject Clock (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2138](https://github.com/JiRaska/open-bank/issues/2138)) ([baa0d03](https://github.com/JiRaska/open-bank/commit/baa0d03bcef7a1cd48cb7e115410ab625a26acde))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/statement-service-v0.3.1...statement-service-v0.4.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank/commit/ad26ca7d58e62c8822e11f66f346926acc453058))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))
* **statement:** add @Authorize resource-gate on REST endpoints (ADR-0034 D3) ([#1349](https://github.com/JiRaska/open-bank/issues/1349)) ([cce7c01](https://github.com/JiRaska/open-bank/commit/cce7c01dbdb004f574553c0be764816615979c3f))
* **statement:** ADR-0101 P4 — Temporal durable workflow for close runs ([#1532](https://github.com/JiRaska/open-bank/issues/1532)) ([c21694d](https://github.com/JiRaska/open-bank/commit/c21694df4a10a0d01566803a8acae15139c9a109))


### Bug Fixes

* **statement:** align openapi info.version major to API v1 (ADR-0048) ([#1400](https://github.com/JiRaska/open-bank/issues/1400)) ([bc79b63](https://github.com/JiRaska/open-bank/commit/bc79b63e4a72b61db173d3aa06e0808c53969532))
* **statement:** retention-independent close-cadence gauge to end StatementCloseCadenceStalled false positive ([#1737](https://github.com/JiRaska/open-bank/issues/1737)) ([b3271d0](https://github.com/JiRaska/open-bank/commit/b3271d08de599ff1b36f1f86220cf80bb4579c0a))
* **statement:** skip NOT_VIABLE debris accounts in period-close instead of failing ([#862](https://github.com/JiRaska/open-bank/issues/862)) ([#1554](https://github.com/JiRaska/open-bank/issues/1554)) ([e316032](https://github.com/JiRaska/open-bank/commit/e316032816ce7a25e689661552ab0e9591a5c237))
* **temporal:** remove non-existent @WorkflowImpl annotation from fx+statement ([#1538](https://github.com/JiRaska/open-bank/issues/1538)) ([0d73e0d](https://github.com/JiRaska/open-bank/commit/0d73e0d7548d2953536975b257e646de6272cff4))

## [0.3.1](https://github.com/JiRaska/open-bank/compare/statement-service-v0.3.0...statement-service-v0.3.1) (2026-06-15)


### Bug Fixes

* **statement:** assign unique HTTP port 8136 (resolve collision with customer-edge) ([#1046](https://github.com/JiRaska/open-bank/issues/1046)) ([d9aa9c6](https://github.com/JiRaska/open-bank/commit/d9aa9c6538ab767118de226fddb6cfc2d0eafb53))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/statement-service-v0.2.0...statement-service-v0.3.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **statement:** harden monthly close cadence and enable the cron ([#470](https://github.com/JiRaska/open-bank/issues/470)) ([#629](https://github.com/JiRaska/open-bank/issues/629)) ([43b1fd7](https://github.com/JiRaska/open-bank/commit/43b1fd77b0cd4cfb839fc23cdffadec83587f8d1))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/statement-service-v0.1.2...statement-service-v0.2.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **statement:** account registry + scheduled close enumeration + sandbox deploy ([#466](https://github.com/JiRaska/open-bank/issues/466)) ([90fc60c](https://github.com/JiRaska/open-bank/commit/90fc60c5adf4e08355657c743a5d228a99a22243))
* **statement:** anchor period-close balances via point-in-time asOf ([#580](https://github.com/JiRaska/open-bank/issues/580)) ([430d473](https://github.com/JiRaska/open-bank/commit/430d4737210434642f5ba5a986bcb670efdc46f2))
* **statements:** customer statement list — M2M auth fix + edge route ([#574](https://github.com/JiRaska/open-bank/issues/574)) ([05d81d3](https://github.com/JiRaska/open-bank/commit/05d81d3400dde07a0f38a4965cef3699a5aed493))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **statement:** persist period close + outbox event atomically ([#557](https://github.com/JiRaska/open-bank/issues/557)) ([72bebf4](https://github.com/JiRaska/open-bank/commit/72bebf40ea6a25057f76853fc16bbbeb40b8e506))
* **statement:** populate IBAN + holder name on rendered statements ([#610](https://github.com/JiRaska/open-bank/issues/610)) ([50841eb](https://github.com/JiRaska/open-bank/commit/50841eb660ae3033f9ce64d0643642f75dc2c207))
* **statements:** enumerate currency pockets via /pockets, not the account body ([#576](https://github.com/JiRaska/open-bank/issues/576)) ([7d52e7c](https://github.com/JiRaska/open-bank/commit/7d52e7c1765a5b08c64ed87f068908c637cd4798))
* **statements:** point transaction + balance reads at the real service APIs ([#577](https://github.com/JiRaska/open-bank/issues/577)) ([6e74446](https://github.com/JiRaska/open-bank/commit/6e7444640b576762d0a716cf8f9f2b4acb254e1b))
