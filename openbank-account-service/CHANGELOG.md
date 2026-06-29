# Changelog

## [0.10.0](https://github.com/JiRaska/open-bank/compare/account-service-v0.9.1...account-service-v0.10.0) (2026-06-29)


### Features

* **account:** same-account FX pocket exchange (ADR-0110) ([#2425](https://github.com/JiRaska/open-bank/issues/2425)) ([3757160](https://github.com/JiRaska/open-bank/commit/3757160e55aaa3d1201685e95f258694b45facb2))


### Bug Fixes

* **account:** GDPR Art. 17 — handle PARTY_ERASED to nullify legalName (ADR-0118) ([#2443](https://github.com/JiRaska/open-bank/issues/2443)) ([131fb7e](https://github.com/JiRaska/open-bank/commit/131fb7e3adc20f6f4164d28460a320f6c62bfa9c))

## [0.9.1](https://github.com/JiRaska/open-bank/compare/account-service-v0.9.0...account-service-v0.9.1) (2026-06-27)


### Bug Fixes

* **account:** sort accounts CURRENT-first in findByPartyId ([#2257](https://github.com/JiRaska/open-bank/issues/2257)) ([3493c4c](https://github.com/JiRaska/open-bank/commit/3493c4cd3e0c1675e8de33fd41c84dcc1ca6ad06))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))

## [0.9.0](https://github.com/JiRaska/open-bank/compare/account-service-v0.8.0...account-service-v0.9.0) (2026-06-25)


### Features

* **product-catalog,libs:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2165](https://github.com/JiRaska/open-bank/issues/2165)) ([4956fc3](https://github.com/JiRaska/open-bank/commit/4956fc3eca24ea884281d09cd5c667c9f2f0dfb3))

## [0.8.0](https://github.com/JiRaska/open-bank/compare/account-service-v0.7.1...account-service-v0.8.0) (2026-06-25)


### Features

* **account:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2098](https://github.com/JiRaska/open-bank/issues/2098)) ([1c33954](https://github.com/JiRaska/open-bank/commit/1c33954f61fc4c5a7c5f74467c0d5e1bd89e906a))

## [0.7.1](https://github.com/JiRaska/open-bank/compare/account-service-v0.7.0...account-service-v0.7.1) (2026-06-25)


### Bug Fixes

* **account:** inject Clock into domain/application layers (ADR-0100, Refs [#1612](https://github.com/JiRaska/open-bank/issues/1612)) ([#2008](https://github.com/JiRaska/open-bank/issues/2008)) ([148b489](https://github.com/JiRaska/open-bank/commit/148b4893522206eaa0174115bfa875d3354cf5c1))

## [0.7.0](https://github.com/JiRaska/open-bank/compare/account-service-v0.6.0...account-service-v0.7.0) (2026-06-25)


### Features

* **customer-edge:** ADR-0104 P1 — expose currency-pocket lifecycle to customers ([#1683](https://github.com/JiRaska/open-bank/issues/1683)) ([24b3530](https://github.com/JiRaska/open-bank/commit/24b35308f61cd2dfdc5ac4a6f040955a5abf237a))


### Bug Fixes

* **account:** add customer ownership guard to listPockets + resolvePocket (pentest A1) ([#1422](https://github.com/JiRaska/open-bank/issues/1422)) ([95f65f6](https://github.com/JiRaska/open-bank/commit/95f65f66f7d604b248734b0064f4b185ea26775d))
* **account:** align openapi info.version major to API v1 (ADR-0048) ([#1398](https://github.com/JiRaska/open-bank/issues/1398)) ([604b5eb](https://github.com/JiRaska/open-bank/commit/604b5eb717f45d3b0b29fe20c02adcdece3841b0))
* **account:** sync openapi.yaml info.version to lockstep 0.6.0 ([#1379](https://github.com/JiRaska/open-bank/issues/1379)) ([a693c50](https://github.com/JiRaska/open-bank/commit/a693c5053febdf004813eaf9ef7aba3b49cd3462))
* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **ci:** latent ktlint violations in openbank-libs + account-service ([fc4a63e](https://github.com/JiRaska/open-bank/commit/fc4a63ece44019d85c9047b960f93380acbc6c5b))
* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank/commit/92dc2d636d857b526c8276e2647de440c540577b))

## [0.6.0](https://github.com/JiRaska/open-bank/compare/account-service-v0.5.0...account-service-v0.6.0) (2026-06-15)


### Features

* **onboarding:** self-service E2E — savings account, sub-bound party, own-account transfers ([#894](https://github.com/JiRaska/open-bank/issues/894)) ([91dd603](https://github.com/JiRaska/open-bank/commit/91dd603e62f8f9038a0c71d1a0187ce0303442ce))


### Bug Fixes

* **account:** default sandbox bank code 0000 instead of Fio's 2010 ([#1094](https://github.com/JiRaska/open-bank/issues/1094)) ([395b3dd](https://github.com/JiRaska/open-bank/commit/395b3ddb9040a87f387c150abaf6a53cfd87e23b))

## [0.5.0](https://github.com/JiRaska/open-bank/compare/account-service-v0.4.1...account-service-v0.5.0) (2026-06-12)


### Features

* **account,party,onboarding:** sprint 2 — sanctions persistence, GDPR erasure, doc download, AbandonedCleaner fix ([#475](https://github.com/JiRaska/open-bank/issues/475)) ([05b20d7](https://github.com/JiRaska/open-bank/commit/05b20d764a6373d0ffd96ca84ab5a9a6ed54291f))
* **account:** account lifecycle metrics + outbox backlog gauge (ADR-0077/0079) ([#790](https://github.com/JiRaska/open-bank/issues/790)) ([5bb1b3a](https://github.com/JiRaska/open-bank/commit/5bb1b3a75d42ab302aee2c07b87e743194f1fa65))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **infra,agent:** feature-flag flip enforcement — CI gate + MCP tool (ADR-0067 / issue [#419](https://github.com/JiRaska/open-bank/issues/419)) ([#758](https://github.com/JiRaska/open-bank/issues/758)) ([96bfb7d](https://github.com/JiRaska/open-bank/commit/96bfb7d506c9e2da22cde563ef8d676d77699019))


### Bug Fixes

* **account:** consume the deployed party-service event contract (onboarding→account) ([#764](https://github.com/JiRaska/open-bank/issues/764)) ([71006e1](https://github.com/JiRaska/open-bank/commit/71006e1987e571fd9a145ae740b5e2440e263d09))

## [0.4.1](https://github.com/JiRaska/open-bank/compare/account-service-v0.4.0...account-service-v0.4.1) (2026-06-10)


### Security

* **account:** enforce X-Customer-Party-Id ownership on reads (IDOR defense-in-depth) ([#632](https://github.com/JiRaska/open-bank/issues/632)) ([cd81cd5](https://github.com/JiRaska/open-bank/commit/cd81cd5c0f3490f870eb613d2cfce11dbc4405c4))

## [0.4.0](https://github.com/JiRaska/open-bank/compare/account-service-v0.3.0...account-service-v0.4.0) (2026-06-09)


### Features

* **account:** auto-grant welcome bonus on account activation ([#555](https://github.com/JiRaska/open-bank/issues/555)) ([7ea7548](https://github.com/JiRaska/open-bank/commit/7ea75483aab5e8d1148ca08fbd521ee0934ad8b8))
* **account:** open PENDING_ACTIVATION account on onboarding (ADR-0073 phase 1) ([#533](https://github.com/JiRaska/open-bank/issues/533)) ([df6f48c](https://github.com/JiRaska/open-bank/commit/df6f48c920098436b47277878bc180f42db3ec2b))
* **account:** welcome-bonus notification + edge notification feed ([#565](https://github.com/JiRaska/open-bank/issues/565)) ([a7c8d8f](https://github.com/JiRaska/open-bank/commit/a7c8d8ff3e6b43c792727fb4e6eb71b1608def52))
* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **account:** make onboarding balance init event-driven (no balance row bug) ([#550](https://github.com/JiRaska/open-bank/issues/550)) ([c3757aa](https://github.com/JiRaska/open-bank/commit/c3757aa9432ec8e6a30d2cb9656b9bf52ace28d8))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.3.0](https://github.com/JiRaska/open-bank/compare/account-service-v0.2.0...account-service-v0.3.0) (2026-06-06)


### Features

* **account:** trigram IBAN-fragment search endpoint (money-path) ([#268](https://github.com/JiRaska/open-bank/issues/268)) ([6c5a7da](https://github.com/JiRaska/open-bank/commit/6c5a7daf59ccb31a914e1fdc1b667949bacd89d1))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank/commit/6e5f132ab1f0c3723104276d373307f76076d483))


### Bug Fixes

* **account:** generate nationally-valid Czech BBAN (ČNB mod-11), not just mod-97 ([#230](https://github.com/JiRaska/open-bank/issues/230)) ([fbf1595](https://github.com/JiRaska/open-bank/commit/fbf15953f765e5f3a4296e951800327f526a6aff)), closes [#66](https://github.com/JiRaska/open-bank/issues/66)
* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank/issues/366)) ([b578775](https://github.com/JiRaska/open-bank/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
