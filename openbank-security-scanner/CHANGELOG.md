# Changelog

## [0.4.0](https://github.com/JiRaska/open-bank/compare/security-scanner-v0.3.3...security-scanner-v0.4.0) (2026-06-27)


### Features

* **observability:** RUM gateway cardinality budget, attribute audit, HPA (ADR-0088 O1-O3) ([#2208](https://github.com/JiRaska/open-bank/issues/2208)) ([b7f0849](https://github.com/JiRaska/open-bank/commit/b7f08494cf923f66dbd910e92522fb9453394de7))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.3.3](https://github.com/JiRaska/open-bank/compare/security-scanner-v0.3.2...security-scanner-v0.3.3) (2026-06-25)


### Bug Fixes

* **sepa-instant,balance,audit,security-scanner,copilot,customer-edge,sca:** inject Clock via CDI (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2145](https://github.com/JiRaska/open-bank/issues/2145)) ([d680007](https://github.com/JiRaska/open-bank/commit/d68000775625cc423c95d8a27db29ff25a708f9f))

## [0.3.2](https://github.com/JiRaska/open-bank/compare/security-scanner-v0.3.1...security-scanner-v0.3.2) (2026-06-25)


### Bug Fixes

* **security-scanner:** correct stale target namespaces in scan config ([#1816](https://github.com/JiRaska/open-bank/issues/1816)) ([6442ba7](https://github.com/JiRaska/open-bank/commit/6442ba77d8a8b3fcfb861f04e65ac432bd72eaf3)), closes [#1811](https://github.com/JiRaska/open-bank/issues/1811)

## [0.3.1](https://github.com/JiRaska/open-bank/compare/security-scanner-v0.3.0...security-scanner-v0.3.1) (2026-06-15)


### Bug Fixes

* **dispute:** assign unique HTTP port 8135 (resolve collision with lending) ([#1045](https://github.com/JiRaska/open-bank/issues/1045)) ([b219e93](https://github.com/JiRaska/open-bank/commit/b219e93ac044ee2d7c4b8e352bbba17265c624dc))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/security-scanner-v0.2.0...security-scanner-v0.3.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **security-scanner:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#810](https://github.com/JiRaska/open-bank/issues/810)) ([0f7ffc9](https://github.com/JiRaska/open-bank/commit/0f7ffc92487e7eedb6039a93419cf9ae6774b979))

## [0.2.0](https://github.com/JiRaska/open-bank/compare/security-scanner-v0.1.1...security-scanner-v0.2.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **admin-ui:** quality dashboard — Pact contract tests, pitest mutation, composite score (ADR-0063) ([#360](https://github.com/JiRaska/open-bank/issues/360)) ([00b25bc](https://github.com/JiRaska/open-bank/commit/00b25bcc934fea8728bb4b404166cd21c273495b))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **security-scanner:** deploy security-scanner to GitOps + sync governance manifest ([#354](https://github.com/JiRaska/open-bank/issues/354)) ([eca7198](https://github.com/JiRaska/open-bank/commit/eca71982de5c16f4c7c827087f98b3af1f81cd97))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
