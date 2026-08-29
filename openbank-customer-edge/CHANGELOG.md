# Changelog

## [0.70.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.69.0...customer-edge-v0.70.0) (2026-08-27)


### Features

* **lending:** ADR-0269 platform — quotes, credit profile, AI levels, consent surface, financial health, funnel ([#6235](https://github.com/JiRaska/open-bank-oss/issues/6235)) ([3b62a4a](https://github.com/JiRaska/open-bank-oss/commit/3b62a4a5d42a80d0726c8018ca1af58599fb371b))

## [0.69.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.68.0...customer-edge-v0.69.0) (2026-08-27)


### Features

* **customer-edge:** add campaign incentive claims ([#7281](https://github.com/JiRaska/open-bank-oss/issues/7281)) ([e8a1d6c](https://github.com/JiRaska/open-bank-oss/commit/e8a1d6c0cbb8b203666822650c0d0a9f95d26203))

## [0.68.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.67.0...customer-edge-v0.68.0) (2026-08-26)


### Features

* **customer-edge:** add term deposit journey ([#6838](https://github.com/JiRaska/open-bank-oss/issues/6838)) ([c99828e](https://github.com/JiRaska/open-bank-oss/commit/c99828e110223ceebe63befda471bd9232720fad))

## [0.67.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.66.1...customer-edge-v0.67.0) (2026-08-22)


### Features

* **lending:** ADR-0269 slice 1 — one credit journey, three product shapes, customer-readable projection ([#6230](https://github.com/JiRaska/open-bank-oss/issues/6230)) ([a969810](https://github.com/JiRaska/open-bank-oss/commit/a969810df5541832f63580dfa828efaec81a3ba4))


### Bug Fixes

* **docs:** repair the 7 .mmd diagrams that do not parse ([#6496](https://github.com/JiRaska/open-bank-oss/issues/6496)) ([c1e6ad7](https://github.com/JiRaska/open-bank-oss/commit/c1e6ad7b14887db70ec3365747f2ed06d9ec02db))

## [0.66.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.66.0...customer-edge-v0.66.1) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.66.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.65.1...customer-edge-v0.66.0) (2026-08-21)


### Features

* **lending:** ADR-0269 slice 0 — credit-offer consent and the distress suppression floor ([#6226](https://github.com/JiRaska/open-bank-oss/issues/6226)) ([bf87d31](https://github.com/JiRaska/open-bank-oss/commit/bf87d314745d72eae965a256e6f68f34e8bf01b2))

## [0.65.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.65.0...customer-edge-v0.65.1) (2026-08-19)


### Bug Fixes

* stop swallowing transient event-consumer failures as an ack across 4 services ([#5698](https://github.com/JiRaska/open-bank-oss/issues/5698)) ([#5725](https://github.com/JiRaska/open-bank-oss/issues/5725)) ([3219c5d](https://github.com/JiRaska/open-bank-oss/commit/3219c5de3944c39f22a94b4c44532b8521f8a6b5))

## [0.65.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.64.0...customer-edge-v0.65.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))

## [0.64.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.63.2...customer-edge-v0.64.0) (2026-08-17)


### Features

* **payees:** server-synced saved payees (TOP-10 [#5](https://github.com/JiRaska/open-bank-oss/issues/5)) ([#5154](https://github.com/JiRaska/open-bank-oss/issues/5154)) ([9c93621](https://github.com/JiRaska/open-bank-oss/commit/9c936211df184df867a6a274ce4cb09b64114f21))

## [0.63.2](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.63.1...customer-edge-v0.63.2) (2026-08-16)


### Bug Fixes

* **accounts:** refuse to close an account that still holds money ([#5072](https://github.com/JiRaska/open-bank-oss/issues/5072)) ([d7be3a3](https://github.com/JiRaska/open-bank-oss/commit/d7be3a3f82f29b190160e8cd6ebaa3dddcfc96ca))

## [0.63.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.63.0...customer-edge-v0.63.1) (2026-08-16)


### Bug Fixes

* **customer-edge:** announce the single-replica assumption behind PaymentSessionStore ([#5101](https://github.com/JiRaska/open-bank-oss/issues/5101)) ([0e48bd7](https://github.com/JiRaska/open-bank-oss/commit/0e48bd794b7db6aefe9047d60b83da183a776607)), closes [#4728](https://github.com/JiRaska/open-bank-oss/issues/4728)

## [0.63.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.62.0...customer-edge-v0.63.0) (2026-08-16)


### Features

* **accounts:** let a customer rename an account (TOP-10 [#10](https://github.com/JiRaska/open-bank-oss/issues/10), part 1) ([#5002](https://github.com/JiRaska/open-bank-oss/issues/5002)) ([b9b3fc6](https://github.com/JiRaska/open-bank-oss/commit/b9b3fc675da7c5920d1d8fd4562fb001eb04635d))

## [0.62.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.61.0...customer-edge-v0.62.0) (2026-08-13)


### Features

* **cards:** additional cardholder — a card issued to someone else, with its own limits ([#4194](https://github.com/JiRaska/open-bank-oss/issues/4194)) ([25bd631](https://github.com/JiRaska/open-bank-oss/commit/25bd63177daa65dae31a2958cae80cdab2def3b9))

## [0.61.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.60.0...customer-edge-v0.61.0) (2026-08-13)


### Features

* **campaign:** add in-app banner channel ([#4577](https://github.com/JiRaska/open-bank-oss/issues/4577)) ([d95c85c](https://github.com/JiRaska/open-bank-oss/commit/d95c85cf3fbe0428e4cc5e44bcca27d05bc574ab))

## [0.60.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.59.0...customer-edge-v0.60.0) (2026-08-13)


### Features

* add trusted campaign engagement analytics ([#4555](https://github.com/JiRaska/open-bank-oss/issues/4555)) ([22ab0ba](https://github.com/JiRaska/open-bank-oss/commit/22ab0ba6930bff0d70594ab2ee72cf5407bee0b8))

## [0.59.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.58.0...customer-edge-v0.59.0) (2026-08-13)


### Features

* **campaign:** validate push engagement attribution ([#4526](https://github.com/JiRaska/open-bank-oss/issues/4526)) ([512c831](https://github.com/JiRaska/open-bank-oss/commit/512c831570cc654246f92e4447d5b868b40957f8))

## [0.58.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.57.1...customer-edge-v0.58.0) (2026-08-13)


### Features

* **campaign:** add measured holdout experiments ([#4471](https://github.com/JiRaska/open-bank-oss/issues/4471)) ([8756228](https://github.com/JiRaska/open-bank-oss/commit/8756228553b5daa828762cace3a84457d3a4b816))

## [0.57.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.57.0...customer-edge-v0.57.1) (2026-08-09)


### Bug Fixes

* **customer-edge:** answer 400, not 500, for a missing required query parameter ([#4211](https://github.com/JiRaska/open-bank-oss/issues/4211)) ([4ddb6ef](https://github.com/JiRaska/open-bank-oss/commit/4ddb6efeb23864fe65a4f2624f8722e1fcae04fb)), closes [#3624](https://github.com/JiRaska/open-bank-oss/issues/3624)

## [0.57.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.56.0...customer-edge-v0.57.0) (2026-08-07)


### Features

* **customer-edge:** honour a shared DOCUMENT, so the share button leads somewhere ([#4115](https://github.com/JiRaska/open-bank-oss/issues/4115)) ([c79f442](https://github.com/JiRaska/open-bank-oss/commit/c79f44251ac7a5ac6605824ae98e1ac84350955a))

## [0.56.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.55.0...customer-edge-v0.56.0) (2026-08-07)


### Features

* let a delegate pay from a shared account, and audit it as delegated ([#3633](https://github.com/JiRaska/open-bank-oss/issues/3633)) ([568686b](https://github.com/JiRaska/open-bank-oss/commit/568686bfc3ba15e824252f3502b0fddc856c7d37))

## [0.55.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.54.2...customer-edge-v0.55.0) (2026-08-07)


### Features

* **customer-edge:** honour delegated read access, so a shared account can actually be seen ([#4021](https://github.com/JiRaska/open-bank-oss/issues/4021)) ([7a93bd4](https://github.com/JiRaska/open-bank-oss/commit/7a93bd4327fae7d26d45aa47419e85da87e6b332))
* **customer-edge:** resolve a directory hit to a payable target without disclosing the account ([#4014](https://github.com/JiRaska/open-bank-oss/issues/4014)) ([8ea271f](https://github.com/JiRaska/open-bank-oss/commit/8ea271f85811f1a44b168bf1c75c1827e996afc2))


### Bug Fixes

* **delegation:** refuse dailyLimit/monthlyLimit — ADR-0232's cumulative ceilings are enforced nowhere ([#3613](https://github.com/JiRaska/open-bank-oss/issues/3613)) ([841d20e](https://github.com/JiRaska/open-bank-oss/commit/841d20e7f6f5e8674a20bdbd08e9488f64365fc6))

## [0.54.2](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.54.1...customer-edge-v0.54.2) (2026-08-06)


### Bug Fixes

* **customer-edge:** follow the ADR-0179 merged_into pointer at the identity chokepoint ([#3901](https://github.com/JiRaska/open-bank-oss/issues/3901)) ([d9b4876](https://github.com/JiRaska/open-bank-oss/commit/d9b487643480fd1947ff177b1e5f2e2182e90002)), closes [#1984](https://github.com/JiRaska/open-bank-oss/issues/1984)

## [0.54.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.54.0...customer-edge-v0.54.1) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.54.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.53.0...customer-edge-v0.54.0) (2026-08-02)


### Features

* **customer-edge:** customer routes for delegated access (ADR-0232 D6) ([#3412](https://github.com/JiRaska/open-bank-oss/issues/3412)) ([e6a4652](https://github.com/JiRaska/open-bank-oss/commit/e6a4652c45a4142236d294951141d0e34dd033df))

## [0.53.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.52.1...customer-edge-v0.53.0) (2026-08-02)


### Features

* **lending:** customer self-service loan application intake (ADR-0211) ([#3197](https://github.com/JiRaska/open-bank-oss/issues/3197)) ([3848b5c](https://github.com/JiRaska/open-bank-oss/commit/3848b5c25ab638765c355414fd04c773997d43c5))

## [0.52.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.52.0...customer-edge-v0.52.1) (2026-07-31)


### Bug Fixes

* **customer-edge:** make the committed bank list the declared source, not a pretend fallback ([#2959](https://github.com/JiRaska/open-bank-oss/issues/2959)) ([14b6771](https://github.com/JiRaska/open-bank-oss/commit/14b6771d7e4faf70f0e31b918b471d9a9b26c39e))

## [0.52.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.51.0...customer-edge-v0.52.0) (2026-07-31)


### Features

* **governance:** write grants + one-level role inheritance in the authz matrix (ADR-0223 phase 1.5) ([#2864](https://github.com/JiRaska/open-bank-oss/issues/2864)) ([654e843](https://github.com/JiRaska/open-bank-oss/commit/654e84375f6b52853d9e33f15dbf4ba33cba9551))

## [0.51.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.50.0...customer-edge-v0.51.0) (2026-07-31)


### Features

* **edge:** resolve the counterparty account on a transaction ([#2900](https://github.com/JiRaska/open-bank-oss/issues/2900)) ([20e6d23](https://github.com/JiRaska/open-bank-oss/commit/20e6d23529059463a79374f053d01e0078221335))

## [0.50.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.49.1...customer-edge-v0.50.0) (2026-07-31)


### Features

* **directory:** pay-to-phone lookup, opt-in and honest about what hashing buys ([#2840](https://github.com/JiRaska/open-bank-oss/issues/2840)) ([73c4827](https://github.com/JiRaska/open-bank-oss/commit/73c48273d5956259d7356be9cb4fa39b4d70e311))

## [0.49.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.49.0...customer-edge-v0.49.1) (2026-07-31)


### Bug Fixes

* **edge:** let a customer issue a virtual card again after the old one dies ([#2832](https://github.com/JiRaska/open-bank-oss/issues/2832)) ([b075316](https://github.com/JiRaska/open-bank-oss/commit/b0753164e428a70aab794e633eae14b6787b17d7))

## [0.49.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.48.0...customer-edge-v0.49.0) (2026-07-31)


### Features

* **edge+security:** claimed-HTTPS app OAuth, wire-aligned OpenAPI, customer access log, VDP link ([#2814](https://github.com/JiRaska/open-bank-oss/issues/2814)) ([48fd47e](https://github.com/JiRaska/open-bank-oss/commit/48fd47e6f9b1db8bf5b43d38766adbc969aec30d))

## [0.48.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.47.0...customer-edge-v0.48.0) (2026-07-25)


### Features

* **feedback:** rendering context and session id on screen feedback (ADR-0192) ([#2176](https://github.com/JiRaska/open-bank-oss/issues/2176)) ([f9c75d7](https://github.com/JiRaska/open-bank-oss/commit/f9c75d7e3c9470dfb6d669be835d792071a3cce8))
* **feedback:** rendering context and session id on screen feedback (ADR-0192) ([#2176](https://github.com/JiRaska/open-bank-oss/issues/2176)) ([e715cb7](https://github.com/JiRaska/open-bank-oss/commit/e715cb75e4d5411801d0cf237035ae6c3a6498b9))

## [0.47.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.46.0...customer-edge-v0.47.0) (2026-07-25)


### Features

* **cards:** card lifecycle, synthetic PAN vault and SCA-gated card operations (ADR-0194) ([#2135](https://github.com/JiRaska/open-bank-oss/issues/2135)) ([991cd92](https://github.com/JiRaska/open-bank-oss/commit/991cd928a9ea8a267aeb5aa82c33ae5a32aa3887))

## [0.46.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.45.0...customer-edge-v0.46.0) (2026-07-24)


### Features

* **feedback:** screen-feedback endpoint, Kafka stream and ClickHouse marts (ADR-0192) ([#2108](https://github.com/JiRaska/open-bank-oss/issues/2108)) ([828c24f](https://github.com/JiRaska/open-bank-oss/commit/828c24f6fe9812147fe09d83edb059c08aaf773c))
* **theme:** edge theme prefs + copilot theme designer (ADR-0191) ([#2076](https://github.com/JiRaska/open-bank-oss/issues/2076)) ([91b9ac7](https://github.com/JiRaska/open-bank-oss/commit/91b9ac77238214e1363bec87b5f932dfd9becb42))

## [0.45.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.44.0...customer-edge-v0.45.0) (2026-07-24)


### Features

* **edge:** authorise a new SEPA Direct Debit mandate ([#2042](https://github.com/JiRaska/open-bank-oss/issues/2042)) ([5dfa9ea](https://github.com/JiRaska/open-bank-oss/commit/5dfa9ea78e274418e72accdbf0f727eb2f8341da))

## [0.44.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.43.0...customer-edge-v0.44.0) (2026-07-23)


### Features

* **edge:** revoke a registered device (DELETE /customer/v1/devices/{id}) ([#2004](https://github.com/JiRaska/open-bank-oss/issues/2004)) ([faf3388](https://github.com/JiRaska/open-bank-oss/commit/faf3388a144eee1b86181f3b1cdb1420d184e4b3)), closes [#2003](https://github.com/JiRaska/open-bank-oss/issues/2003)

## [0.43.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.42.0...customer-edge-v0.43.0) (2026-07-23)


### Features

* **notifications:** per-party push preferences with category opt-out ([#1990](https://github.com/JiRaska/open-bank-oss/issues/1990)) ([6de0d5e](https://github.com/JiRaska/open-bank-oss/commit/6de0d5e95b849ad5d84cd663503e307cec435dc6)), closes [#1989](https://github.com/JiRaska/open-bank-oss/issues/1989)
* **onboarding:** business funnel analytics — edge events + ClickHouse + admin board + alerts ([#1974](https://github.com/JiRaska/open-bank-oss/issues/1974)) ([7a14a79](https://github.com/JiRaska/open-bank-oss/commit/7a14a799967566fae36ac9c8eeecbb885b6d0668))

## [0.42.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.41.0...customer-edge-v0.42.0) (2026-07-23)


### Features

* **cards:** channel controls (contactless / online / ATM / abroad) ([#1981](https://github.com/JiRaska/open-bank-oss/issues/1981)) ([fef2bda](https://github.com/JiRaska/open-bank-oss/commit/fef2bdadf18b2fcafab6b7d19ab82de0f0b33b8d)), closes [#1980](https://github.com/JiRaska/open-bank-oss/issues/1980)

## [0.41.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.40.0...customer-edge-v0.41.0) (2026-07-23)


### Features

* **customer-edge:** publish response schemas for the core read endpoints ([#1773](https://github.com/JiRaska/open-bank-oss/issues/1773)) ([#1975](https://github.com/JiRaska/open-bank-oss/issues/1975)) ([b756a0f](https://github.com/JiRaska/open-bank-oss/commit/b756a0fdcf415a48c4b652e0cb5df30f97b13b64))

## [0.40.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.39.0...customer-edge-v0.40.0) (2026-07-23)


### Features

* **sca:** pending-approvals list for decoupled/push SCA ([#1969](https://github.com/JiRaska/open-bank-oss/issues/1969)) ([5684d76](https://github.com/JiRaska/open-bank-oss/commit/5684d7613bfb1ba6e8991f9b0a8b9b17ca24aa7d)), closes [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)

## [0.39.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.38.0...customer-edge-v0.39.0) (2026-07-23)


### Features

* **edge:** self-service virtual card issuance (POST /customer/v1/cards) ([#1963](https://github.com/JiRaska/open-bank-oss/issues/1963)) ([25424b9](https://github.com/JiRaska/open-bank-oss/commit/25424b979cb5c56aeb6b7c61dd82729e038d3941)), closes [#1962](https://github.com/JiRaska/open-bank-oss/issues/1962)

## [0.38.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.37.1...customer-edge-v0.38.0) (2026-07-21)


### Features

* **edge:** PSD2 consent list + revoke for the customer app ([#1880](https://github.com/JiRaska/open-bank-oss/issues/1880)) ([e6cc4bf](https://github.com/JiRaska/open-bank-oss/commit/e6cc4bf5800363258c704231a57651bb48d3dd69))

## [0.37.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.37.0...customer-edge-v0.37.1) (2026-07-21)


### Bug Fixes

* **customer-edge:** hide only superseded documents, not every ARCHIVED one ([#1841](https://github.com/JiRaska/open-bank-oss/issues/1841)) ([79333d7](https://github.com/JiRaska/open-bank-oss/commit/79333d7b745a6c3661069e571d702681affd81f4))

## [0.37.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.36.0...customer-edge-v0.37.0) (2026-07-21)


### Features

* **edge:** expose SDD mandate cancel/suspend/resume ([#1871](https://github.com/JiRaska/open-bank-oss/issues/1871)) ([e3dc268](https://github.com/JiRaska/open-bank-oss/commit/e3dc268fa3e80838647267cec5927264b1b77a37)), closes [#1870](https://github.com/JiRaska/open-bank-oss/issues/1870)

## [0.36.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.35.0...customer-edge-v0.36.0) (2026-07-21)


### Features

* **cards:** customer-settable daily/monthly spending limits ([#1863](https://github.com/JiRaska/open-bank-oss/issues/1863)) ([1a37f1d](https://github.com/JiRaska/open-bank-oss/commit/1a37f1d2c0009b127efd6c00f6274b9376937e30)), closes [#1862](https://github.com/JiRaska/open-bank-oss/issues/1862)

## [0.35.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.34.1...customer-edge-v0.35.0) (2026-07-21)


### Features

* **edge:** expose Verification of Payee at POST /customer/v1/vop/verify ([#1857](https://github.com/JiRaska/open-bank-oss/issues/1857)) ([a729127](https://github.com/JiRaska/open-bank-oss/commit/a729127f94c3ad286b8178223df8d84e8023393b)), closes [#1856](https://github.com/JiRaska/open-bank-oss/issues/1856)

## [0.34.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.34.0...customer-edge-v0.34.1) (2026-07-20)


### Bug Fixes

* **document-service:** visible signature block, and stop duplicate agreements ([#1817](https://github.com/JiRaska/open-bank-oss/issues/1817)) ([b6b4faf](https://github.com/JiRaska/open-bank-oss/commit/b6b4faf3781c364efa5a11302e623be56463719f))

## [0.34.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.33.0...customer-edge-v0.34.0) (2026-07-20)


### Features

* **customer-edge:** list the caller's documents ([#1801](https://github.com/JiRaska/open-bank-oss/issues/1801)) ([43d25d2](https://github.com/JiRaska/open-bank-oss/commit/43d25d25680e4e41a08b1993aaba5a8f0e21640b))

## [0.33.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.32.0...customer-edge-v0.33.0) (2026-07-19)


### Features

* **customer-edge:** customer complaint filing route (ADR-0085) ([#1766](https://github.com/JiRaska/open-bank-oss/issues/1766)) ([0b693b8](https://github.com/JiRaska/open-bank-oss/commit/0b693b8924b68f3f4460419b48a8841b68fb44bb))
* **customer-edge:** list + recall SCT Inst payments (payment recall) ([#1770](https://github.com/JiRaska/open-bank-oss/issues/1770)) ([dd96adf](https://github.com/JiRaska/open-bank-oss/commit/dd96adf21b29e9528ac0db8d4b7aad458bc3e238))
* **customer-edge:** notification detail route with body (in-app inbox) ([#1765](https://github.com/JiRaska/open-bank-oss/issues/1765)) ([3830d93](https://github.com/JiRaska/open-bank-oss/commit/3830d939e7dce5ba6b8d0f46ff4c5c2f3f0c565b))
* **customer-edge:** permanent card block (report lost/stolen) ([#1769](https://github.com/JiRaska/open-bank-oss/issues/1769)) ([8405a07](https://github.com/JiRaska/open-bank-oss/commit/8405a071a3d7f66a2001f6f76a6b2af36ec851cd))

## [0.32.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.31.0...customer-edge-v0.32.0) (2026-07-19)


### Features

* **edge:** revoke device-session on logout ([#1762](https://github.com/JiRaska/open-bank-oss/issues/1762)) ([a06445c](https://github.com/JiRaska/open-bank-oss/commit/a06445c36d8c08976a9f13a7fb81fcc05beb8473))

## [0.31.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.30.0...customer-edge-v0.31.0) (2026-07-19)


### Features

* **edge:** device-session refresh so passkey sessions can resume ([#1753](https://github.com/JiRaska/open-bank-oss/issues/1753)) ([85f04ac](https://github.com/JiRaska/open-bank-oss/commit/85f04ac049cc05170f68e2fb58151bd7eacb2017))

## [0.30.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.29.0...customer-edge-v0.30.0) (2026-07-19)


### Features

* **edge:** mint an offline_access token for the mobile app session ([#1741](https://github.com/JiRaska/open-bank-oss/issues/1741)) ([bb499c7](https://github.com/JiRaska/open-bank-oss/commit/bb499c73991e90d4cf04a52f5a4e485d8e3c688f)), closes [#1740](https://github.com/JiRaska/open-bank-oss/issues/1740)

## [0.29.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.28.0...customer-edge-v0.29.0) (2026-07-19)


### Features

* **edge:** SEPA Direct Debit mandates (inkasa) read view for customers ([#1732](https://github.com/JiRaska/open-bank-oss/issues/1732)) ([321e985](https://github.com/JiRaska/open-bank-oss/commit/321e985270dbdd42b61c44fbc44e61890fcb4cae)), closes [#1730](https://github.com/JiRaska/open-bank-oss/issues/1730)

## [0.28.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.27.0...customer-edge-v0.28.0) (2026-07-19)


### Features

* **edge:** international SWIFT (MT103) wire transfer for customers ([#1719](https://github.com/JiRaska/open-bank-oss/issues/1719)) ([b5bd667](https://github.com/JiRaska/open-bank-oss/commit/b5bd6672faba1e5729f9dfef001252240a886acc)), closes [#1718](https://github.com/JiRaska/open-bank-oss/issues/1718)

## [0.27.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.26.1...customer-edge-v0.27.0) (2026-07-19)


### Features

* **customer-edge:** expose POST /customer/v1/sepa-instant (SCT Inst credit transfer) ([#1713](https://github.com/JiRaska/open-bank-oss/issues/1713)) ([08471c0](https://github.com/JiRaska/open-bank-oss/commit/08471c0f4faa3459481affe9c5a535e28f108a46))

## [0.26.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.26.0...customer-edge-v0.26.1) (2026-07-19)


### Bug Fixes

* **customer-edge:** read Money.currency.code in the loans projection ([#1703](https://github.com/JiRaska/open-bank-oss/issues/1703)) ([e4667d1](https://github.com/JiRaska/open-bank-oss/commit/e4667d1b99b19d043eebd68c9bcd1cd524348f48))

## [0.26.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.25.0...customer-edge-v0.26.0) (2026-07-19)


### Features

* **customer-edge:** expose the customer's loans + repayment schedule (read-only) ([#1686](https://github.com/JiRaska/open-bank-oss/issues/1686)) ([54b6054](https://github.com/JiRaska/open-bank-oss/commit/54b60543b11992eb1c31efb46b602beb8f30d702))

## [0.25.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.24.0...customer-edge-v0.25.0) (2026-07-18)


### Features

* **customer-edge:** expose GET /customer/v1/kyc (own identity-verification status) ([#1676](https://github.com/JiRaska/open-bank-oss/issues/1676)) ([6f3937c](https://github.com/JiRaska/open-bank-oss/commit/6f3937cd5c30d9b8a3d22f1dc504bd98ae62776c))

## [0.24.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.23.0...customer-edge-v0.24.0) (2026-07-18)


### Features

* **fees:** surface the account fee schedule to the app + heal stale catalogue ([#1652](https://github.com/JiRaska/open-bank-oss/issues/1652)) ([7f49a11](https://github.com/JiRaska/open-bank-oss/commit/7f49a11d8e7a12aa1cf04c107cc86b3b68f6d6cb))

## [0.23.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.22.0...customer-edge-v0.23.0) (2026-07-18)


### Features

* **interest:** per-account rate override; CURRENT defaults to zero interest ([#1618](https://github.com/JiRaska/open-bank-oss/issues/1618)) ([#1645](https://github.com/JiRaska/open-bank-oss/issues/1645)) ([743090b](https://github.com/JiRaska/open-bank-oss/commit/743090b38ab59a27becd05ff3665045c18c2f793))

## [0.22.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.21.0...customer-edge-v0.22.0) (2026-07-18)


### Features

* **customer-edge:** expose GET /accounts/{id}/interest (rate + accrued) for the app ([#1615](https://github.com/JiRaska/open-bank-oss/issues/1615)) ([5a24a5e](https://github.com/JiRaska/open-bank-oss/commit/5a24a5ea0ae25d5486c12c074dd3be0f5640e1fb))

## [0.21.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.20.2...customer-edge-v0.21.0) (2026-07-16)


### Features

* **customer-edge:** mint the session from the passkey enrolment ceremony ([#1349](https://github.com/JiRaska/open-bank-oss/issues/1349)) ([ed34e32](https://github.com/JiRaska/open-bank-oss/commit/ed34e323bef55918a35c26d20f62749eac545c64))

## [0.20.2](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.20.1...customer-edge-v0.20.2) (2026-07-16)


### Bug Fixes

* **customer-edge:** ask document-service for the terms PDF with a wildcard accept ([#1337](https://github.com/JiRaska/open-bank-oss/issues/1337)) ([2648580](https://github.com/JiRaska/open-bank-oss/commit/26485804c5cb36b83d798d6252e1e837d24f5087))

## [0.20.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.20.0...customer-edge-v0.20.1) (2026-07-16)


### Bug Fixes

* **customer-edge:** consent step serves only the terms, as a PDF ([#1310](https://github.com/JiRaska/open-bank-oss/issues/1310)) ([1dec331](https://github.com/JiRaska/open-bank-oss/commit/1dec331f858db53d9818520e16fd44f4a8de0ad1))

## [0.20.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.19.0...customer-edge-v0.20.0) (2026-07-16)


### Features

* **customer-edge:** serve published consent documents to the onboarding step ([#1254](https://github.com/JiRaska/open-bank-oss/issues/1254)) ([c1b6ac6](https://github.com/JiRaska/open-bank-oss/commit/c1b6ac65c8fbdaede5364f161a85e48f03073946))


### Bug Fixes

* **customer-edge:** accept a customer's access token to enrol a native passkey ([#1268](https://github.com/JiRaska/open-bank-oss/issues/1268)) ([fa1712c](https://github.com/JiRaska/open-bank-oss/commit/fa1712c561a7c3ab385abf8ce899f7bb83acbdfe))

## [0.19.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.18.0...customer-edge-v0.19.0) (2026-07-16)


### Features

* **party-service,customer-edge:** revocable marketing consent (Profile screen) ([#1161](https://github.com/JiRaska/open-bank-oss/issues/1161)) ([dd1d757](https://github.com/JiRaska/open-bank-oss/commit/dd1d7571972abaf4c516c97f29edaa2f121d133f))

## [0.18.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.17.4...customer-edge-v0.18.0) (2026-07-16)


### Features

* **customer-edge:** document & signature routes for ADR-0169 D1 ([#1139](https://github.com/JiRaska/open-bank-oss/issues/1139)) ([9664622](https://github.com/JiRaska/open-bank-oss/commit/9664622a94b34afa35e29bd064670333344ab78b))
* **customer-edge:** rebuild WebAuthn RP for native passkey login (ADR-0066 F2) ([#1119](https://github.com/JiRaska/open-bank-oss/issues/1119)) ([bc45776](https://github.com/JiRaska/open-bank-oss/commit/bc45776d483563959e9e697ab7ce8f5dd8722fcb))


### Bug Fixes

* **party-service,customer-edge:** forward + persist onboarding consent ([#1157](https://github.com/JiRaska/open-bank-oss/issues/1157)) ([b16b143](https://github.com/JiRaska/open-bank-oss/commit/b16b1437e268b1d58115f9e194e40d31a3cfe596))

## [0.17.4](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.17.3...customer-edge-v0.17.4) (2026-07-12)


### Bug Fixes

* **customer-edge:** use Admin.listGroups instead of deprecated listConsumerGroups ([#868](https://github.com/JiRaska/open-bank-oss/issues/868)) ([d4fcfbe](https://github.com/JiRaska/open-bank-oss/commit/d4fcfbe25018bc9a3d51acb0dd88e83c7da5af7d)), closes [#865](https://github.com/JiRaska/open-bank-oss/issues/865)

## [0.17.3](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.17.2...customer-edge-v0.17.3) (2026-07-09)


### Bug Fixes

* **customer-edge:** move group.id off dotted YAML keys onto env vars ([#703](https://github.com/JiRaska/open-bank-oss/issues/703)) ([bfb2c1c](https://github.com/JiRaska/open-bank-oss/commit/bfb2c1ca73084200f05443e84af091b40b9e23f8))

## [0.17.2](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.17.1...customer-edge-v0.17.2) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.17.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.17.0...customer-edge-v0.17.1) (2026-07-04)


### Bug Fixes

* **customer-edge:** mark validatedUri() inline to fix recurring CodeQL SSRF false positive ([#232](https://github.com/JiRaska/open-bank-oss/issues/232)) ([4f8e77b](https://github.com/JiRaska/open-bank-oss/commit/4f8e77b1661f336ccfecea01863779f12712a44f))

## [0.17.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.16.2...customer-edge-v0.17.0) (2026-07-04)


### Features

* **account,customer-edge:** implement ADR-0153 — savings goal metadata ([#219](https://github.com/JiRaska/open-bank-oss/issues/219)) ([05f73fd](https://github.com/JiRaska/open-bank-oss/commit/05f73fdd14d9fd9f7a9d30c4ffae50d15d5dfe07))

## [0.16.2](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.16.1...customer-edge-v0.16.2) (2026-07-03)


### Bug Fixes

* **notification:** notification-service authz fails closed with no OPA sidecar; add read-state ([#212](https://github.com/JiRaska/open-bank-oss/issues/212)) ([b976fbd](https://github.com/JiRaska/open-bank-oss/commit/b976fbdfb932f2e5adfb1c0fb62d8fc4e76f3b7e))

## [0.16.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.16.0...customer-edge-v0.16.1) (2026-07-03)


### Security

* **customer-edge:** add SSRF host allowlist to UpstreamClient (CodeQL java/ssrf) ([#100](https://github.com/JiRaska/open-bank-oss/issues/100)) ([7490c6d](https://github.com/JiRaska/open-bank-oss/commit/7490c6d358f248549db67cf185146aa18253924a))
* **customer-edge:** validate URL via regex before URI.create (CodeQL java/ssrf, take 2) ([#180](https://github.com/JiRaska/open-bank-oss/issues/180)) ([b58a4e8](https://github.com/JiRaska/open-bank-oss/commit/b58a4e8ce9b4dffcbc5cbd3bbb3f36137f707240))
* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.16.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.15.1...customer-edge-v0.16.0) (2026-06-29)


### Features

* **customer-edge:** GET /domestic-payments/{id} status (app settlement poll) ([#2633](https://github.com/JiRaska/open-bank-oss/issues/2633)) ([cea1fcb](https://github.com/JiRaska/open-bank-oss/commit/cea1fcb480f35dbb773cc758bca0bfe13c18c751))
* **customer-edge:** settlement-honest payment status read-path + session reconcile (ADR-0108) ([#2634](https://github.com/JiRaska/open-bank-oss/issues/2634)) ([4b1c057](https://github.com/JiRaska/open-bank-oss/commit/4b1c057cdf1b8001e358ad61b253860d3924bee3))
* **observability:** RUM gateway cardinality budget, attribute audit, HPA (ADR-0088 O1-O3) ([#2208](https://github.com/JiRaska/open-bank-oss/issues/2208)) ([e65ce75](https://github.com/JiRaska/open-bank-oss/commit/e65ce75eb99121c49258f7b998d64e99a5e24dbe))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **customer-edge:** per-party Valkey rate limit — 100 req/min, configurable ([#2492](https://github.com/JiRaska/open-bank-oss/issues/2492)) ([b0ba238](https://github.com/JiRaska/open-bank-oss/commit/b0ba238257a514b11c414960dd50b9a6b000a588))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.15.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.15.0...customer-edge-v0.15.1) (2026-06-29)


### Bug Fixes

* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([7306f85](https://github.com/JiRaska/open-bank-oss/commit/7306f85891b7c9f440a8c376bf741523d6eef498))
* **customer-edge:** per-party Valkey rate limit — 100 req/min, configurable ([#2492](https://github.com/JiRaska/open-bank-oss/issues/2492)) ([fc1e8f6](https://github.com/JiRaska/open-bank-oss/commit/fc1e8f6e42e8421e29b4f7d26ef6fe4bf41d469d))

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.14.2...customer-edge-v0.15.0) (2026-06-27)


### Features

* **observability:** RUM gateway cardinality budget, attribute audit, HPA (ADR-0088 O1-O3) ([#2208](https://github.com/JiRaska/open-bank-oss/issues/2208)) ([b7f0849](https://github.com/JiRaska/open-bank-oss/commit/b7f08494cf923f66dbd910e92522fb9453394de7))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.14.2](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.14.1...customer-edge-v0.14.2) (2026-06-25)


### Bug Fixes

* **sepa-instant,balance,audit,security-scanner,copilot,customer-edge,sca:** inject Clock via CDI (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2145](https://github.com/JiRaska/open-bank-oss/issues/2145)) ([d680007](https://github.com/JiRaska/open-bank-oss/commit/d68000775625cc423c95d8a27db29ff25a708f9f))

## [0.14.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.14.0...customer-edge-v0.14.1) (2026-06-25)


### Bug Fixes

* **detekt:** suppress LongMethod/CyclomaticComplexMethod on createDomesticPayment; fix MagicNumber ([#2036](https://github.com/JiRaska/open-bank-oss/issues/2036)) ([3612651](https://github.com/JiRaska/open-bank-oss/commit/36126511ffbf0329f9e5762785df885b6186c173))

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.13.0...customer-edge-v0.14.0) (2026-06-25)


### Features

* **domestic-payment,customer-edge:** add INSTANT priority; forward priority from app body ([e683832](https://github.com/JiRaska/open-bank-oss/commit/e683832c0f71a69531d2a8e53bbca94da22b2749))
* **domestic-payment,customer-edge:** add INSTANT priority; forward priority from app body ([9fddff9](https://github.com/JiRaska/open-bank-oss/commit/9fddff995d15fe94b6db4ae9eb05732a99938cff))
* **nearbypay:** resolve real creditor server-side in domestic payment (ADR-0087) ([#2020](https://github.com/JiRaska/open-bank-oss/issues/2020)) ([936c121](https://github.com/JiRaska/open-bank-oss/commit/936c1213381384b085b970488a346b6876c2d35f))


### Bug Fixes

* **customer-edge:** remove DIAG logs from SCA decision endpoint ([#2022](https://github.com/JiRaska/open-bank-oss/issues/2022)) ([5f2f646](https://github.com/JiRaska/open-bank-oss/commit/5f2f646189b5e21c16f0c9f1263132e3627ef426))


### Security

* **infra:** Kafka mTLS + write-ACL for payment.scheme-accepted (Closes [#2013](https://github.com/JiRaska/open-bank-oss/issues/2013)) ([#2018](https://github.com/JiRaska/open-bank-oss/issues/2018)) ([0bcfdf1](https://github.com/JiRaska/open-bank-oss/commit/0bcfdf1092d07ba975b7f5aa42793a9a82a6edac))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.12.0...customer-edge-v0.13.0) (2026-06-25)


### Features

* **customer-edge:** add @Authorize resource-gate on REST endpoints (ADR-0034 D3) ([#1357](https://github.com/JiRaska/open-bank-oss/issues/1357)) ([17ce859](https://github.com/JiRaska/open-bank-oss/commit/17ce859caaf99969aa15c591251d2c2a20165a58))
* **customer-edge:** ADR-0104 P1 — expose currency-pocket lifecycle to customers ([#1683](https://github.com/JiRaska/open-bank-oss/issues/1683)) ([24b3530](https://github.com/JiRaska/open-bank-oss/commit/24b35308f61cd2dfdc5ac4a6f040955a5abf237a))
* **customer-edge:** onboarding auto-resume on four-eyes decision (ADR-0072) ([#1563](https://github.com/JiRaska/open-bank-oss/issues/1563)) ([be42ad4](https://github.com/JiRaska/open-bank-oss/commit/be42ad4e9f7e147a7dadd631a0887382da91cae5))
* **customer-edge:** same-account currency exchange endpoint (ADR-0108) ([#1830](https://github.com/JiRaska/open-bank-oss/issues/1830)) ([c41f0a8](https://github.com/JiRaska/open-bank-oss/commit/c41f0a8e091c1a99cd67f7e89240b6cdbb87f42b))
* **customer-edge:** set Keycloak party_id attribute after MATCH_EXISTING re-link (issue [#1270](https://github.com/JiRaska/open-bank-oss/issues/1270) PR3) ([7c7396a](https://github.com/JiRaska/open-bank-oss/commit/7c7396aff7960a0998a923da8409b25c8b10e2e5))
* **infra:** OPA namespace deployment + docker-compose REST policy wiring (ADR-0034 D4) ([#1355](https://github.com/JiRaska/open-bank-oss/issues/1355)) ([dd7022b](https://github.com/JiRaska/open-bank-oss/commit/dd7022be9bdcd77df33a84bb4679442960ef8598))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank-oss/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank-oss/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))
* **pockets:** convert pocket balance to primary currency (ADR-0107) ([#1797](https://github.com/JiRaska/open-bank-oss/issues/1797)) ([c3df994](https://github.com/JiRaska/open-bank-oss/commit/c3df99430d6976555b052fefd78186f3b55de795))


### Bug Fixes

* **customer-edge:** accept Czech IBAN as creditor in domestic payment ([#1942](https://github.com/JiRaska/open-bank-oss/issues/1942)) ([57ff658](https://github.com/JiRaska/open-bank-oss/commit/57ff658a7b8982ee2ea545d4685f97be3907b063))
* **customer-edge:** accept JsonNode body in initiateChallenge to fix empty-body 400 ([462ab2a](https://github.com/JiRaska/open-bank-oss/commit/462ab2adb67406b0aaeba0bbbe0054d20cbe7c1d))
* **customer-edge:** fix SCA challenge 400 — switch body from String to JsonNode ([213e577](https://github.com/JiRaska/open-bank-oss/commit/213e577b9fb53a83f2fe8a28d294c5380381c0bf))
* **customer-edge:** move /onboarding/register to OnboardingResource (JAX-RS path conflict) ([#1858](https://github.com/JiRaska/open-bank-oss/issues/1858)) ([71c051b](https://github.com/JiRaska/open-bank-oss/commit/71c051b6da59f55cfe32f89926eebc3b7da0801d))
* **customer-edge:** onboarding /start input validation — Jackson parsing + partyType + legalName length (pentest [#628](https://github.com/JiRaska/open-bank-oss/issues/628)) ([6fdafed](https://github.com/JiRaska/open-bank-oss/commit/6fdafed427a844de081cbec12ba16681dab771a9))
* **customer-edge:** registerParty syntax fix — onboarding/register endpoint ([#1861](https://github.com/JiRaska/open-bank-oss/issues/1861)) ([cccf00c](https://github.com/JiRaska/open-bank-oss/commit/cccf00c4e3e82b29023dc45d9500b23f17a58d8c))
* **customer-edge:** replace substringAfter JSON parsing with Jackson (pentest [#628](https://github.com/JiRaska/open-bank-oss/issues/628)) ([#1421](https://github.com/JiRaska/open-bank-oss/issues/1421)) ([b68f074](https://github.com/JiRaska/open-bank-oss/commit/b68f0745e62c4bc916dcf271abe73481a8f91aed))
* **customer-edge:** route /onboarding/register via OnboardingResource delegate ([#1863](https://github.com/JiRaska/open-bank-oss/issues/1863)) ([7b01355](https://github.com/JiRaska/open-bank-oss/commit/7b01355fdb0bc270323504e53480707c434cc4e0))
* **infra:** harden registry-cache + gradle-build-cache pod specs, enforce PSS restricted ([#853](https://github.com/JiRaska/open-bank-oss/issues/853)) ([#1551](https://github.com/JiRaska/open-bank-oss/issues/1551)) ([002f8e1](https://github.com/JiRaska/open-bank-oss/commit/002f8e123a4fc1ce423ec49ed00324dd93dcdd04))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.11.0...customer-edge-v0.12.0) (2026-06-15)


### Features

* **customer-edge:** enrich FX rate sheet with ČNB reference mid + spread % ([#1063](https://github.com/JiRaska/open-bank-oss/issues/1063)) ([4b285ab](https://github.com/JiRaska/open-bank-oss/commit/4b285ab5b4b7eda443a4ba2b6f2360ca94fbd5bd))
* **customer-edge:** GET /customer/v1/banks — CNB bank-code registry proxy ([#1096](https://github.com/JiRaska/open-bank-oss/issues/1096)) ([a2de025](https://github.com/JiRaska/open-bank-oss/commit/a2de0251dd96c6929f11ac6e7ecb76e8d6ec16d1))
* **customer-edge:** GET /customer/v1/fx/rates/{base}/{quote}/history ([#1080](https://github.com/JiRaska/open-bank-oss/issues/1080)) ([6a8fe20](https://github.com/JiRaska/open-bank-oss/commit/6a8fe20d91deaffb37e705b1fdb3e9ebb97609cf))


### Bug Fixes

* **dispute:** assign unique HTTP port 8135 (resolve collision with lending) ([#1045](https://github.com/JiRaska/open-bank-oss/issues/1045)) ([b219e93](https://github.com/JiRaska/open-bank-oss/commit/b219e93ac044ee2d7c4b8e352bbba17265c624dc))
* **fx,customer-edge:** FX history prázdná + chybí ECB odchylka ([#1115](https://github.com/JiRaska/open-bank-oss/issues/1115)) ([2a5d872](https://github.com/JiRaska/open-bank-oss/commit/2a5d872c9e3f68be2afa6860ae7a3b363ac43908))
* **statement:** assign unique HTTP port 8136 (resolve collision with customer-edge) ([#1046](https://github.com/JiRaska/open-bank-oss/issues/1046)) ([d9aa9c6](https://github.com/JiRaska/open-bank-oss/commit/d9aa9c6538ab767118de226fddb6cfc2d0eafb53))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.10.0...customer-edge-v0.11.0) (2026-06-14)


### Features

* **customer-edge:** expose published FX rate sheet to the customer app ([#1004](https://github.com/JiRaska/open-bank-oss/issues/1004)) ([d4f4abe](https://github.com/JiRaska/open-bank-oss/commit/d4f4abe81bab39ffa4c10ca841ed5129be50f26a))
* **customer-edge:** FX, cards, disputes, nearby-pay + card-issuance deploy ([#955](https://github.com/JiRaska/open-bank-oss/issues/955)) ([921c657](https://github.com/JiRaska/open-bank-oss/commit/921c65707991a1a101f4571e5110f3bbc28a3570))
* **onboarding:** self-service E2E — savings account, sub-bound party, own-account transfers ([#894](https://github.com/JiRaska/open-bank-oss/issues/894)) ([91dd603](https://github.com/JiRaska/open-bank-oss/commit/91dd603e62f8f9038a0c71d1a0187ce0303442ce))
* **security:** customer payment non-repudiation — SCA settlement gate, identity threading, audit hash chain (ADR-0086) ([#900](https://github.com/JiRaska/open-bank-oss/issues/900)) ([fcc1e52](https://github.com/JiRaska/open-bank-oss/commit/fcc1e52b247b0eb61b9ee8d5332f110984a6fb33))
* standing orders + open additional account (customer self-service) ([#906](https://github.com/JiRaska/open-bank-oss/issues/906)) ([9a6c07a](https://github.com/JiRaska/open-bank-oss/commit/9a6c07aa51f7077d4e2660a0bad44bdaa39b27de))


### Bug Fixes

* **customer-edge:** expose /q/metrics via micrometer prometheus registry ([#991](https://github.com/JiRaska/open-bank-oss/issues/991)) ([203e05f](https://github.com/JiRaska/open-bank-oss/commit/203e05f9c2c2c15fa78d610a88768c6ff7ca7ac2))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.9.1...customer-edge-v0.10.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.9.0...customer-edge-v0.9.1) (2026-06-10)


### Security

* **customer-edge:** enforce account ownership on account & balance reads (IDOR) ([#627](https://github.com/JiRaska/open-bank-oss/issues/627)) ([0eca1f6](https://github.com/JiRaska/open-bank-oss/commit/0eca1f689defb0aacc169fc4402b26fd0969c876))
* **customer-edge:** parse party status with Jackson in the KYC gate ([#633](https://github.com/JiRaska/open-bank-oss/issues/633)) ([8956117](https://github.com/JiRaska/open-bank-oss/commit/89561171ce786c840066d47923cda82d2431e1f6))
* **customer-edge:** pin OIDC token issuer instead of accepting any ([#621](https://github.com/JiRaska/open-bank-oss/issues/621)) ([2d187d3](https://github.com/JiRaska/open-bank-oss/commit/2d187d330d57a8def3ea77344c4d93a0afa550ca))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/customer-edge-v0.8.1...customer-edge-v0.9.0) (2026-06-08)


### Features

* **account:** welcome-bonus notification + edge notification feed ([#565](https://github.com/JiRaska/open-bank-oss/issues/565)) ([a7c8d8f](https://github.com/JiRaska/open-bank-oss/commit/a7c8d8ff3e6b43c792727fb4e6eb71b1608def52))
* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **customer-edge:** add onboarding routes + party_id JWT claim fix (ADR-0069) ([f3d345b](https://github.com/JiRaska/open-bank-oss/commit/f3d345b0b88d14fee2956a2ae9109dee89961ff0))
* **customer-edge:** customer-facing edge proxy + gitops (ADR-0065) ([#403](https://github.com/JiRaska/open-bank-oss/issues/403)) ([389e3c6](https://github.com/JiRaska/open-bank-oss/commit/389e3c6a258900cbccfd9569f435fe61afa8c26f))
* **customer-edge:** Dockerfile + openbank-edge service account in operator realm ([#404](https://github.com/JiRaska/open-bank-oss/issues/404)) ([0bf2e73](https://github.com/JiRaska/open-bank-oss/commit/0bf2e731d77f4d5d5fbe937975bb90f143868c97))
* **customer-edge:** enrich customer domestic payment into full instruction ([#588](https://github.com/JiRaska/open-bank-oss/issues/588)) ([09b99c0](https://github.com/JiRaska/open-bank-oss/commit/09b99c0c4fa3a566b68f851a9b76da67c52156f1))
* **customer-edge:** expose customer transaction history (ownership-enforced) ([#564](https://github.com/JiRaska/open-bank-oss/issues/564)) ([a6c7ae3](https://github.com/JiRaska/open-bank-oss/commit/a6c7ae3771abc617f6c2de2d08196baeff883298))
* **customer-edge:** expose the caller's own customer profile ([#572](https://github.com/JiRaska/open-bank-oss/issues/572)) ([9fecee6](https://github.com/JiRaska/open-bank-oss/commit/9fecee60eb404b12e8bc10abf8e6724f705cdb49))
* **customer-edge:** payment initiation + SCA challenge routes (3a, no settlement) ([#569](https://github.com/JiRaska/open-bank-oss/issues/569)) ([ecb82aa](https://github.com/JiRaska/open-bank-oss/commit/ecb82aa4773aa8341f8c5768b6350bcc41c162b7))
* **customer-edge:** proxy + enrich SEPA credit-transfer initiation ([#614](https://github.com/JiRaska/open-bank-oss/issues/614)) ([4d14d3d](https://github.com/JiRaska/open-bank-oss/commit/4d14d3d1d8c2c04a82a369904947d859b246e267))
* **customer-edge:** proxy on-demand statement document render ([#584](https://github.com/JiRaska/open-bank-oss/issues/584)) ([8b2549d](https://github.com/JiRaska/open-bank-oss/commit/8b2549de3609b3de2bc3ca6ac0045dfdb7b0cfe8))
* **notification:** PUSH delivery via FCM/APNs + device token registry ([#535](https://github.com/JiRaska/open-bank-oss/issues/535)) ([73c4ebd](https://github.com/JiRaska/open-bank-oss/commit/73c4ebdac4dc96fffc6c60823df00d88a07f1c78))
* **statements:** customer statement list — M2M auth fix + edge route ([#574](https://github.com/JiRaska/open-bank-oss/issues/574)) ([05d81d3](https://github.com/JiRaska/open-bank-oss/commit/05d81d3400dde07a0f38a4965cef3699a5aed493))


### Bug Fixes

* **customer-edge:** bump version 0.1.0 → 0.1.1 after singleton HttpClient fix ([#476](https://github.com/JiRaska/open-bank-oss/issues/476)) ([976718e](https://github.com/JiRaska/open-bank-oss/commit/976718e08dd0992c328e8126bd116cd56277b5a7))
* **customer-edge:** complete onboarding wiring — field-inject M2M secret, idempotency-key, HTTP/1.1 ([#499](https://github.com/JiRaska/open-bank-oss/issues/499)) ([44af3cc](https://github.com/JiRaska/open-bank-oss/commit/44af3cca81fe0d421e5fd3450920aa4924eb3a3d))
* **customer-edge:** force HTTP/1.1 upstream + getEntity body read (onboarding 502) ([#496](https://github.com/JiRaska/open-bank-oss/issues/496)) ([c2eca1f](https://github.com/JiRaska/open-bank-oss/commit/c2eca1f3fb4f047b48c6a9dd054ace1c771eb4e3))
* **customer-edge:** inject partyId via Jackson on device + onboarding routes ([#571](https://github.com/JiRaska/open-bank-oss/issues/571)) ([9630670](https://github.com/JiRaska/open-bank-oss/commit/963067045cb2fc5309a6af8c5548accc51f7d141))
* **customer-edge:** make @PermitAll onboarding routes truly public (lazy auth) + deploy ([#494](https://github.com/JiRaska/open-bank-oss/issues/494)) ([f2a0798](https://github.com/JiRaska/open-bank-oss/commit/f2a0798746edcc08328149f4ff1214138cd8c49a))
* **customer-edge:** move public /onboarding/start to its own un-annotated resource + deploy ([#495](https://github.com/JiRaska/open-bank-oss/issues/495)) ([b54ec7b](https://github.com/JiRaska/open-bank-oss/commit/b54ec7ba80e9a43434dfdec0a8e3a4607947d569))
* **customer-edge:** read getRaw body as ByteArray for binary-safe document proxy ([#593](https://github.com/JiRaska/open-bank-oss/issues/593)) ([4612f0e](https://github.com/JiRaska/open-bank-oss/commit/4612f0eed72e0e2b7b98b1b1b664e36d76c110c9))
* **customer-edge:** send Accept */* when proxying statement render (was 406) ([#585](https://github.com/JiRaska/open-bank-oss/issues/585)) ([72ea64e](https://github.com/JiRaska/open-bank-oss/commit/72ea64efc8c8f489303d87aebb447c03a477f00f))
* **customer-edge:** singleton HttpClient + token cache, compile fix ([#406](https://github.com/JiRaska/open-bank-oss/issues/406)) ([625b7fb](https://github.com/JiRaska/open-bank-oss/commit/625b7fb95cc43af2b7faa266cdbc361c1b045962))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
