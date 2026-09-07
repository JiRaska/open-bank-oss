# Changelog

## [1.28.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.27.1...ledger-service-v1.28.0) (2026-09-07)


### Features

* **clearing:** post net-settlement journal per settled batch via the transactional outbox ([#8723](https://github.com/JiRaska/open-bank-oss/issues/8723)) ([5e38f44](https://github.com/JiRaska/open-bank-oss/commit/5e38f445e4169f24a4f0871ca748c22debf9e384))
* **ledger:** record the synthetic taint on the journal and let the trial balance exclude it ([#8629](https://github.com/JiRaska/open-bank-oss/issues/8629)) ([7989f39](https://github.com/JiRaska/open-bank-oss/commit/7989f39afab7a77bb389a1391878b0292c6254ed))


### Bug Fixes

* **balance,ledger:** fuzz-found 500s on date query params become 400/default ([#8835](https://github.com/JiRaska/open-bank-oss/issues/8835)) ([062c26a](https://github.com/JiRaska/open-bank-oss/commit/062c26afd615d4c973a51bfd4920618ceb5401f4))

## [1.27.1](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.27.0...ledger-service-v1.27.1) (2026-09-03)


### Bug Fixes

* **ledger:** entity timestamp defaults EPOCH -&gt; Instant.now() — burn-down complete ([#8465](https://github.com/JiRaska/open-bank-oss/issues/8465)) ([2ad36b6](https://github.com/JiRaska/open-bank-oss/commit/2ad36b67e9973e482e8d36aa47a231e11c774106))

## [1.27.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.26.2...ledger-service-v1.27.0) (2026-09-01)


### Features

* **ledger:** declare sourceService on five money-path event producers ([#7716](https://github.com/JiRaska/open-bank-oss/issues/7716)) ([bf489ad](https://github.com/JiRaska/open-bank-oss/commit/bf489ad147f16b461e7a6c3d6f1244f596741a73))


### Bug Fixes

* **ledger:** answer 400 for a null journal line and an absent body, not 500 ([#7860](https://github.com/JiRaska/open-bank-oss/issues/7860)) ([b8c7461](https://github.com/JiRaska/open-bank-oss/commit/b8c746157bc8e1ce121f5528f093423c29c46f31))

## [1.26.2](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.26.1...ledger-service-v1.26.2) (2026-08-30)


### Bug Fixes

* **ledger:** require human close draft maker ([#7624](https://github.com/JiRaska/open-bank-oss/issues/7624)) ([7b5d97e](https://github.com/JiRaska/open-bank-oss/commit/7b5d97eecc37f3f5d24dc76c1c7d06a7ad0933f9))

## [1.26.1](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.26.0...ledger-service-v1.26.1) (2026-08-27)


### Bug Fixes

* **regulatory:** complete FINREP and COREP previews ([#7296](https://github.com/JiRaska/open-bank-oss/issues/7296)) ([d387b3e](https://github.com/JiRaska/open-bank-oss/commit/d387b3ea51293416dc15a243d248564c2eeacf84))

## [1.26.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.25.0...ledger-service-v1.26.0) (2026-08-26)


### Features

* **ledger:** persist trusted synthetic taint ([#7176](https://github.com/JiRaska/open-bank-oss/issues/7176)) ([aee9dd8](https://github.com/JiRaska/open-bank-oss/commit/aee9dd800af708489bd75fb9e82f131a983e6a45))

## [1.25.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.24.0...ledger-service-v1.25.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [1.24.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.23.0...ledger-service-v1.24.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [1.23.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.22.0...ledger-service-v1.23.0) (2026-08-19)


### Features

* **ledger:** expose pending four-eyes approvals via approval inbox ([#5679](https://github.com/JiRaska/open-bank-oss/issues/5679)) ([#5687](https://github.com/JiRaska/open-bank-oss/issues/5687)) ([c0d5dee](https://github.com/JiRaska/open-bank-oss/commit/c0d5dee48e379bdc89e26b3ad852edd5084bdab4))

## [1.22.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.21.2...ledger-service-v1.22.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [1.21.2](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.21.1...ledger-service-v1.21.2) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [1.21.1](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.21.0...ledger-service-v1.21.1) (2026-08-16)


### Bug Fixes

* **infra:** give six services an OIDC client they can actually mint from ([#4990](https://github.com/JiRaska/open-bank-oss/issues/4990)) ([f43f88c](https://github.com/JiRaska/open-bank-oss/commit/f43f88c815fd50c32ef797147c6cbc57f060cab0))

## [1.21.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.20.2...ledger-service-v1.21.0) (2026-08-15)


### Features

* **ledger:** persist frozen trial balance evidence ([#4826](https://github.com/JiRaska/open-bank-oss/issues/4826)) ([22d8120](https://github.com/JiRaska/open-bank-oss/commit/22d812083c1c1f5d177e4718bdb1e95e12c6f06e))

## [1.20.2](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.20.1...ledger-service-v1.20.2) (2026-08-13)


### Bug Fixes

* **ledger:** resolve the ČNB fixing by business day and let a corrected one supersede ([#4397](https://github.com/JiRaska/open-bank-oss/issues/4397)) ([e2c5d87](https://github.com/JiRaska/open-bank-oss/commit/e2c5d87e21e913d24ff6e1558eff8425f5abf4a7)), closes [#1302](https://github.com/JiRaska/open-bank-oss/issues/1302)

## [1.20.1](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.20.0...ledger-service-v1.20.1) (2026-08-09)


### Bug Fixes

* **ledger:** make the ČNB fixing's age observable at the point of revaluation ([#4219](https://github.com/JiRaska/open-bank-oss/issues/4219)) ([368b2fe](https://github.com/JiRaska/open-bank-oss/commit/368b2fe33f98b28aaba07305df0e035fc0243cc2))

## [1.20.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.19.0...ledger-service-v1.20.0) (2026-08-07)


### Features

* **ledger:** drive the accounting-day lifecycle automatically (ADR-0207 increment 2) ([#3984](https://github.com/JiRaska/open-bank-oss/issues/3984)) ([4a36eab](https://github.com/JiRaska/open-bank-oss/commit/4a36eaba9557202fc86fa1b0d37c7f746089f196))

## [1.19.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.18.2...ledger-service-v1.19.0) (2026-08-07)


### Features

* **ledger:** register workflow liveness on maintainer and freshness watchdog (ADR-0237) ([#3707](https://github.com/JiRaska/open-bank-oss/issues/3707)) ([d4ff63c](https://github.com/JiRaska/open-bank-oss/commit/d4ff63ccc83d46a32829d4329c65317dd28e7393))

## [1.18.2](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.18.1...ledger-service-v1.18.2) (2026-08-03)


### Bug Fixes

* **governance:** gate the comments — prose naming a file or repo that does not exist ([#3515](https://github.com/JiRaska/open-bank-oss/issues/3515)) ([0e93874](https://github.com/JiRaska/open-bank-oss/commit/0e93874ea31eb43900bead5f6a4353b056a9977f))

## [1.18.1](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.18.0...ledger-service-v1.18.1) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [1.18.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.17.2...ledger-service-v1.18.0) (2026-08-02)


### Features

* **ledger:** statutory period freeze — attested, immutable trial balance (ADR-0096) ([#3006](https://github.com/JiRaska/open-bank-oss/issues/3006)) ([8567fbd](https://github.com/JiRaska/open-bank-oss/commit/8567fbd2612ff2ba1615417937fffa4f37c554b8))

## [1.17.2](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.17.1...ledger-service-v1.17.2) (2026-08-02)


### Bug Fixes

* **infra:** give the five money-path services the JDBC datasource Flyway migrates ([#3192](https://github.com/JiRaska/open-bank-oss/issues/3192)) ([d9b31d5](https://github.com/JiRaska/open-bank-oss/commit/d9b31d5d2cccd169ec6ce7e8e971d5853ef952f1)), closes [#3080](https://github.com/JiRaska/open-bank-oss/issues/3080)

## [1.17.1](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.17.0...ledger-service-v1.17.1) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))

## [1.17.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.16.1...ledger-service-v1.17.0) (2026-08-01)


### Features

* **ledger:** the accounting day becomes an owned concept, with a business-date authority (ADR-0207) ([#2962](https://github.com/JiRaska/open-bank-oss/issues/2962)) ([f994c49](https://github.com/JiRaska/open-bank-oss/commit/f994c4928141032ac940a61d0d8ed835d5167294))

## [1.16.1](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.16.0...ledger-service-v1.16.1) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [1.16.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.15.7...ledger-service-v1.16.0) (2026-07-26)


### Features

* **ledger:** register workflow-liveness gauges on the money-path schedulers ([#2488](https://github.com/JiRaska/open-bank-oss/issues/2488)) ([d0332b4](https://github.com/JiRaska/open-bank-oss/commit/d0332b4201aa965504c9f2494a5b8e1639c25ec5)), closes [#2239](https://github.com/JiRaska/open-bank-oss/issues/2239)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [1.15.7](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.15.6...ledger-service-v1.15.7) (2026-07-25)


### Bug Fixes

* **ledger:** run both daily schedulers on a Vert.x context ([#2187](https://github.com/JiRaska/open-bank-oss/issues/2187)) ([#2190](https://github.com/JiRaska/open-bank-oss/issues/2190)) ([7c2fae9](https://github.com/JiRaska/open-bank-oss/commit/7c2fae99e5e582237501d3713eae4841b756bcf5))

## [1.15.6](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.15.5...ledger-service-v1.15.6) (2026-07-24)


### Bug Fixes

* **lending:** select GL accounts by loan currency, seed EUR/USD/GBP ([#1275](https://github.com/JiRaska/open-bank-oss/issues/1275)) ([#1898](https://github.com/JiRaska/open-bank-oss/issues/1898)) ([768a6f7](https://github.com/JiRaska/open-bank-oss/commit/768a6f736ff578b90196628e300205fa4d8982ce))

## [1.15.5](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.15.4...ledger-service-v1.15.5) (2026-07-20)


### Bug Fixes

* **lending:** point funding-clearing at the account ledger actually seeded ([#1731](https://github.com/JiRaska/open-bank-oss/issues/1731)) ([a5f6acc](https://github.com/JiRaska/open-bank-oss/commit/a5f6acc8a051462aeaa03067625f799aa039ecab)), closes [#1720](https://github.com/JiRaska/open-bank-oss/issues/1720)

## [1.15.4](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.15.3...ledger-service-v1.15.4) (2026-07-17)


### Bug Fixes

* **ledger:** outbox FxRevaluedEvent instead of a separate post-commit publish ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1451](https://github.com/JiRaska/open-bank-oss/issues/1451)) ([86176ed](https://github.com/JiRaska/open-bank-oss/commit/86176edd5c85ccf82386bef4194d32d2c715796b))

## [1.15.3](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.15.2...ledger-service-v1.15.3) (2026-07-17)


### Bug Fixes

* **ledger:** Postgres advisory-lock cross-pod exclusion for once-per-cluster schedulers ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1440](https://github.com/JiRaska/open-bank-oss/issues/1440)) ([8de116e](https://github.com/JiRaska/open-bank-oss/commit/8de116e84c8db538042160063dd50782c3ea79f7))

## [1.15.2](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.15.1...ledger-service-v1.15.2) (2026-07-17)


### Bug Fixes

* **ledger:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1415](https://github.com/JiRaska/open-bank-oss/issues/1415)) ([ac5ef06](https://github.com/JiRaska/open-bank-oss/commit/ac5ef0629f0f5edde2461cfd607a89dc9b296bbd))

## [1.15.1](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.15.0...ledger-service-v1.15.1) (2026-07-17)


### Bug Fixes

* **ledger:** give TieOutScheduler catch-up so a deploy-window miss self-heals ([#1398](https://github.com/JiRaska/open-bank-oss/issues/1398)) ([a5e50bd](https://github.com/JiRaska/open-bank-oss/commit/a5e50bdf44035ba1e463a2bc4e252503167cca68))

## [1.15.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.14.0...ledger-service-v1.15.0) (2026-07-17)


### Features

* **interest:** post the ADR-0033 split so capitalization actually credits the customer ([#1316](https://github.com/JiRaska/open-bank-oss/issues/1316)) ([b2ae411](https://github.com/JiRaska/open-bank-oss/commit/b2ae4117f6995b63f16e53a3004fdabee4ed223d))

## [1.14.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.13.0...ledger-service-v1.14.0) (2026-07-16)


### Features

* **ledger:** persist tie-out runs, freshness watchdog, wire break alert ([#1192](https://github.com/JiRaska/open-bank-oss/issues/1192)) ([25214dd](https://github.com/JiRaska/open-bank-oss/commit/25214dd01adcc8fd6c08ca7445db526bdb799b97))


### Bug Fixes

* **ledger:** add balance-service provider states to broker verification ([#1198](https://github.com/JiRaska/open-bank-oss/issues/1198)) ([7214cb0](https://github.com/JiRaska/open-bank-oss/commit/7214cb07dc4ef75c091dfa814a9e153df1a0eea5))

## [1.13.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.12.1...ledger-service-v1.13.0) (2026-07-14)


### Features

* **ledger:** add operator replay endpoint for historical booked-change events ([#888](https://github.com/JiRaska/open-bank-oss/issues/888)) ([946807f](https://github.com/JiRaska/open-bank-oss/commit/946807f77c2d7e524c14df311f9d93ce9ab92cfb))
* **ledger:** wire four-eyes enforcement mechanism (ADR-0155) ([#929](https://github.com/JiRaska/open-bank-oss/issues/929)) ([b92c042](https://github.com/JiRaska/open-bank-oss/commit/b92c042af5b25aba65c9d10eea37b2f414b7e540))


### Bug Fixes

* **billing,ledger:** unusable fee GL accounts + add ledger pact coverage ([#468](https://github.com/JiRaska/open-bank-oss/issues/468)) ([#859](https://github.com/JiRaska/open-bank-oss/issues/859)) ([3583372](https://github.com/JiRaska/open-bank-oss/commit/3583372f76f5093516289108aa5f248dd481d35e))
* **ledger,psd2:** dedicated exception types, no more mapper collision ([#526](https://github.com/JiRaska/open-bank-oss/issues/526)) ([#752](https://github.com/JiRaska/open-bank-oss/issues/752)) ([e15464c](https://github.com/JiRaska/open-bank-oss/commit/e15464c58a43c514f3a33c67751c3a75de667e1a))
* **ledger:** add broker-based Pact provider verification for billing-service ([#1018](https://github.com/JiRaska/open-bank-oss/issues/1018)) ([0e1bee9](https://github.com/JiRaska/open-bank-oss/commit/0e1bee968bc90d2e4ff350ae168141c6a8ab20f2))
* **ledger:** generic repair for reversal-line corruption since V10 ([#527](https://github.com/JiRaska/open-bank-oss/issues/527)) ([#748](https://github.com/JiRaska/open-bank-oss/issues/748)) ([ca10585](https://github.com/JiRaska/open-bank-oss/commit/ca1058515a3873b5e74e6f7d59398fc3470ed9c8))
* **ledger:** include REVERSED entries in all balance-reading queries ([#945](https://github.com/JiRaska/open-bank-oss/issues/945)) ([83e070f](https://github.com/JiRaska/open-bank-oss/commit/83e070f62cc931da03ab4da5cf7a28940adf4d1f)), closes [#939](https://github.com/JiRaska/open-bank-oss/issues/939)
* **ledger:** unbreak main — InMemoryJournalRepository missing appendOutbox ([#902](https://github.com/JiRaska/open-bank-oss/issues/902)) ([7d5e7e1](https://github.com/JiRaska/open-bank-oss/commit/7d5e7e1ada0cccc5dd1f8b1070fa7ff7c50e1d10))
* **transaction,ledger:** per-currency cash-clearing GL accounts ([#747](https://github.com/JiRaska/open-bank-oss/issues/747)) ([#749](https://github.com/JiRaska/open-bank-oss/issues/749)) ([7e0f934](https://github.com/JiRaska/open-bank-oss/commit/7e0f9341b9d0f92f61665ff4cf981b9d181d73c6))


### Security

* **libs-testing,aml,ledger:** add shared authz conformance kit, fix live AML gap ([#467](https://github.com/JiRaska/open-bank-oss/issues/467)) ([#757](https://github.com/JiRaska/open-bank-oss/issues/757)) ([94e9c6d](https://github.com/JiRaska/open-bank-oss/commit/94e9c6d9a20cb2b1bf972bf60dfed1ff90e2443c))

## [1.12.1](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.12.0...ledger-service-v1.12.1) (2026-07-12)


### Bug Fixes

* **ledger:** include REVERSED entries in all balance-reading queries ([#945](https://github.com/JiRaska/open-bank-oss/issues/945)) ([83e070f](https://github.com/JiRaska/open-bank-oss/commit/83e070f62cc931da03ab4da5cf7a28940adf4d1f)), closes [#939](https://github.com/JiRaska/open-bank-oss/issues/939)

## [1.12.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.11.1...ledger-service-v1.12.0) (2026-07-12)


### Features

* **ledger:** wire four-eyes enforcement mechanism (ADR-0155) ([#929](https://github.com/JiRaska/open-bank-oss/issues/929)) ([b92c042](https://github.com/JiRaska/open-bank-oss/commit/b92c042af5b25aba65c9d10eea37b2f414b7e540))

## [1.11.1](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.11.0...ledger-service-v1.11.1) (2026-07-12)


### Bug Fixes

* **ledger:** unbreak main — InMemoryJournalRepository missing appendOutbox ([#902](https://github.com/JiRaska/open-bank-oss/issues/902)) ([7d5e7e1](https://github.com/JiRaska/open-bank-oss/commit/7d5e7e1ada0cccc5dd1f8b1070fa7ff7c50e1d10))

## [1.11.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.10.5...ledger-service-v1.11.0) (2026-07-12)


### Features

* **ledger:** add operator replay endpoint for historical booked-change events ([#888](https://github.com/JiRaska/open-bank-oss/issues/888)) ([946807f](https://github.com/JiRaska/open-bank-oss/commit/946807f77c2d7e524c14df311f9d93ce9ab92cfb))


### Bug Fixes

* **billing,ledger:** unusable fee GL accounts + add ledger pact coverage ([#468](https://github.com/JiRaska/open-bank-oss/issues/468)) ([#859](https://github.com/JiRaska/open-bank-oss/issues/859)) ([3583372](https://github.com/JiRaska/open-bank-oss/commit/3583372f76f5093516289108aa5f248dd481d35e))

## [1.10.5](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.10.4...ledger-service-v1.10.5) (2026-07-11)


### Bug Fixes

* **ledger,psd2:** dedicated exception types, no more mapper collision ([#526](https://github.com/JiRaska/open-bank-oss/issues/526)) ([#752](https://github.com/JiRaska/open-bank-oss/issues/752)) ([e15464c](https://github.com/JiRaska/open-bank-oss/commit/e15464c58a43c514f3a33c67751c3a75de667e1a))
* **ledger:** generic repair for reversal-line corruption since V10 ([#527](https://github.com/JiRaska/open-bank-oss/issues/527)) ([#748](https://github.com/JiRaska/open-bank-oss/issues/748)) ([ca10585](https://github.com/JiRaska/open-bank-oss/commit/ca1058515a3873b5e74e6f7d59398fc3470ed9c8))
* **transaction,ledger:** per-currency cash-clearing GL accounts ([#747](https://github.com/JiRaska/open-bank-oss/issues/747)) ([#749](https://github.com/JiRaska/open-bank-oss/issues/749)) ([7e0f934](https://github.com/JiRaska/open-bank-oss/commit/7e0f9341b9d0f92f61665ff4cf981b9d181d73c6))


### Security

* **libs-testing,aml,ledger:** add shared authz conformance kit, fix live AML gap ([#467](https://github.com/JiRaska/open-bank-oss/issues/467)) ([#757](https://github.com/JiRaska/open-bank-oss/issues/757)) ([94e9c6d](https://github.com/JiRaska/open-bank-oss/commit/94e9c6d9a20cb2b1bf972bf60dfed1ff90e2443c))

## [1.10.4](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.10.3...ledger-service-v1.10.4) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)
* **ledger:** race-safe reversal, idempotent concurrent posting, reversal line re-parenting ([#528](https://github.com/JiRaska/open-bank-oss/issues/528)) ([166728b](https://github.com/JiRaska/open-bank-oss/commit/166728b5e899522d39d0729b6ccfaf0483b4b375)), closes [#465](https://github.com/JiRaska/open-bank-oss/issues/465)

## [1.10.3](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.10.2...ledger-service-v1.10.3) (2026-07-07)


### Security

* **ledger:** enforce OPA authorization on ledger endpoints (ADR-0034 Phase 5) ([#411](https://github.com/JiRaska/open-bank-oss/issues/411)) ([73a4771](https://github.com/JiRaska/open-bank-oss/commit/73a47711b52583dfb97b57c3130031449c90df09)), closes [#266](https://github.com/JiRaska/open-bank-oss/issues/266)

## [1.10.2](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.10.1...ledger-service-v1.10.2) (2026-07-07)


### Bug Fixes

* **ledger:** define missing Unauthorized/Forbidden response components in openapi.yaml ([#306](https://github.com/JiRaska/open-bank-oss/issues/306)) ([7513884](https://github.com/JiRaska/open-bank-oss/commit/7513884dbc4aaf5e1a9a3bda37518724029cbd1d))

## [1.10.1](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.10.0...ledger-service-v1.10.1) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [1.10.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.9.1...ledger-service-v1.10.0) (2026-06-30)


### Features

* **libs,ledger,balance:** ADR-0077 Tier C — ledger posting amount + balance revaluation metrics ([#2797](https://github.com/JiRaska/open-bank-oss/issues/2797)) ([609cee4](https://github.com/JiRaska/open-bank-oss/commit/609cee4975b2e7066da327c58722b8fb0f3882f4))

## [1.9.1](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.9.0...ledger-service-v1.9.1) (2026-06-30)


### Security

* **ledger:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 1) ([#2722](https://github.com/JiRaska/open-bank-oss/issues/2722)) ([784b418](https://github.com/JiRaska/open-bank-oss/commit/784b4181a9120a0d4ecafd90c4b9fab3afe8d786))

## [1.9.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.8.1...ledger-service-v1.9.0) (2026-06-29)


### Features

* **observability:** RUM gateway cardinality budget, attribute audit, HPA (ADR-0088 O1-O3) ([#2208](https://github.com/JiRaska/open-bank-oss/issues/2208)) ([e65ce75](https://github.com/JiRaska/open-bank-oss/commit/e65ce75eb99121c49258f7b998d64e99a5e24dbe))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([ce39af7](https://github.com/JiRaska/open-bank-oss/commit/ce39af7c5b02ff3fef226f34e78c7e726b149ebd))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **libs,account,consent,ledger,pid,transaction:** make DomainEvent.occurredAt explicit ([#2137](https://github.com/JiRaska/open-bank-oss/issues/2137)) ([#2662](https://github.com/JiRaska/open-bank-oss/issues/2662)) ([9e0c2ea](https://github.com/JiRaska/open-bank-oss/commit/9e0c2ea14a65aec227df333b83b0b7283b6c16a5))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [1.8.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.7.0...ledger-service-v1.8.0) (2026-06-29)


### Features

* **observability:** RUM gateway cardinality budget, attribute audit, HPA (ADR-0088 O1-O3) ([#2208](https://github.com/JiRaska/open-bank-oss/issues/2208)) ([e65ce75](https://github.com/JiRaska/open-bank-oss/commit/e65ce75eb99121c49258f7b998d64e99a5e24dbe))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([ce39af7](https://github.com/JiRaska/open-bank-oss/commit/ce39af7c5b02ff3fef226f34e78c7e726b149ebd))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [1.7.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.6.0...ledger-service-v1.7.0) (2026-06-27)


### Features

* **ledger:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2099](https://github.com/JiRaska/open-bank-oss/issues/2099)) ([8d5f5f8](https://github.com/JiRaska/open-bank-oss/commit/8d5f5f861e49fe48d34068834acbc05ff8759105))
* **observability:** RUM gateway cardinality budget, attribute audit, HPA (ADR-0088 O1-O3) ([#2208](https://github.com/JiRaska/open-bank-oss/issues/2208)) ([b7f0849](https://github.com/JiRaska/open-bank-oss/commit/b7f08494cf923f66dbd910e92522fb9453394de7))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank-oss/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))

## [1.6.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.5.0...ledger-service-v1.6.0) (2026-06-25)


### Features

* **ledger:** sub-ledger tie-out for deposit-control accounts (ADR-0039 Phase B) ([#1560](https://github.com/JiRaska/open-bank-oss/issues/1560)) ([02c6f66](https://github.com/JiRaska/open-bank-oss/commit/02c6f66d0c094365049ba2f72968a051479faade))


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank-oss/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **ledger:** inject Clock into LedgerService; make JournalEntry.reverse time-free (ADR-0100) ([#1817](https://github.com/JiRaska/open-bank-oss/issues/1817)) ([4a97b57](https://github.com/JiRaska/open-bank-oss/commit/4a97b57135be1cd55bfc3ac2381b27d7dcd9bd80))
* **sdd:** add sdd_outbox_seq to V2 migration + fix outbox IT Vert.x context ([fceadf4](https://github.com/JiRaska/open-bank-oss/commit/fceadf4a679eddc7cbde749839846cc83bf8d5d5)), closes [#1360](https://github.com/JiRaska/open-bank-oss/issues/1360)
* **settlement:** fix management host binding and datasource username default ([89f7276](https://github.com/JiRaska/open-bank-oss/commit/89f72763c4f6ea558e49626421e9adf6715905d3))

## [1.5.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.4.0...ledger-service-v1.5.0) (2026-06-15)


### Features

* **ledger:** EoY increment 1 — fiscal-year trial balance + attestable YearClose record (ADR-0078 D5) ([#868](https://github.com/JiRaska/open-bank-oss/issues/868)) ([49ad0cf](https://github.com/JiRaska/open-bank-oss/commit/49ad0cfb6a32fa3b338a88a6e5de95a4997aa487))
* **ledger:** four-eyes (maker != checker) year-close attestation ([#1014](https://github.com/JiRaska/open-bank-oss/issues/1014)) ([14036f3](https://github.com/JiRaska/open-bank-oss/commit/14036f30184d6a97c68524c5c65453884b83c70b)), closes [#869](https://github.com/JiRaska/open-bank-oss/issues/869)
* **ledger:** period lock + re-verify endpoint for attested fiscal years ([#985](https://github.com/JiRaska/open-bank-oss/issues/985)) ([3584aa2](https://github.com/JiRaska/open-bank-oss/commit/3584aa2c61338284ca16c3d44e0a4a04072b663a)), closes [#869](https://github.com/JiRaska/open-bank-oss/issues/869)


### Bug Fixes

* **infra:** revert EC2NodeClass userData — breaks AL2023 node bootstrap ([#940](https://github.com/JiRaska/open-bank-oss/issues/940)) ([f7d128a](https://github.com/JiRaska/open-bank-oss/commit/f7d128ae7773d4d3237af13d45d2f4cf177aa89a))

## [1.4.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.3.0...ledger-service-v1.4.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **ledger:** domain posting metrics + outbox backlog gauge (ADR-0077/0079) ([#789](https://github.com/JiRaska/open-bank-oss/issues/789)) ([17507ac](https://github.com/JiRaska/open-bank-oss/commit/17507ac7f51f13645baaa316df617644143423e5))
* **ledger:** emit AccountBookedChanged per customer account (ADR-0039 Phase D) ([#765](https://github.com/JiRaska/open-bank-oss/issues/765)) ([b6d7905](https://github.com/JiRaska/open-bank-oss/commit/b6d7905dc2a694828e1ac91ebcd1c19a94b9d667))

## [1.3.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.2.0...ledger-service-v1.3.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [1.2.0](https://github.com/JiRaska/open-bank-oss/compare/ledger-service-v1.1.1...ledger-service-v1.2.0) (2026-06-06)


### Features

* **admin-ui:** quality dashboard — Pact contract tests, pitest mutation, composite score (ADR-0063) ([#360](https://github.com/JiRaska/open-bank-oss/issues/360)) ([00b25bc](https://github.com/JiRaska/open-bank-oss/commit/00b25bcc934fea8728bb4b404166cd21c273495b))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **coverage:** enforce kover 40% floor on all 13 money-path services ([#338](https://github.com/JiRaska/open-bank-oss/issues/338)) ([6e5f132](https://github.com/JiRaska/open-bank-oss/commit/6e5f132ab1f0c3723104276d373307f76076d483))
* **ledger:** deploy money-path ledger-service + regulatory-grade outbox dispatch (ADR-0050) ([#169](https://github.com/JiRaska/open-bank-oss/issues/169)) ([7cf5e97](https://github.com/JiRaska/open-bank-oss/commit/7cf5e9754257b1e1c0e8c6a536290d0ab3270402))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **ledger:** drop generic Exception mapper, defer to libs (ADR-0049 D4) ([#335](https://github.com/JiRaska/open-bank-oss/issues/335)) ([3fdb95a](https://github.com/JiRaska/open-bank-oss/commit/3fdb95adecbac238b70b39238471321fb02ce016))
