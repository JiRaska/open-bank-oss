# Changelog

## [0.12.1](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.12.0...statement-service-v0.12.1) (2026-09-07)


### Bug Fixes

* **kafka:** resolve the 11 baselined auto.offset.reset config lies ([#8370](https://github.com/JiRaska/open-bank-oss/issues/8370)) ([#8860](https://github.com/JiRaska/open-bank-oss/issues/8860)) ([f328ebd](https://github.com/JiRaska/open-bank-oss/commit/f328ebdf265f2dd3dd90ad3db3d2a052eb657923))

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.11.0...statement-service-v0.12.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.10.2...statement-service-v0.11.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.10.2](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.10.1...statement-service-v0.10.2) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.10.0...statement-service-v0.10.1) (2026-08-21)


### Bug Fixes

* **statement-service:** add sourceService and occurredAt to the restated event ([#5898](https://github.com/JiRaska/open-bank-oss/issues/5898)) ([7143162](https://github.com/JiRaska/open-bank-oss/commit/7143162b16121899e0b25fd671fb7287691e2339)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.9.0...statement-service-v0.10.0) (2026-08-20)


### Features

* **analytics:** fail closed without durable backfill ([#6050](https://github.com/JiRaska/open-bank-oss/issues/6050)) ([8fca000](https://github.com/JiRaska/open-bank-oss/commit/8fca000162af7d6f6c3ed0bcb4c9fcba5d8742d8))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.8.7...statement-service-v0.9.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.8.7](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.8.6...statement-service-v0.8.7) (2026-08-18)


### Bug Fixes

* **statement-service:** add sourceService to outbox events for audit attribution ([#5392](https://github.com/JiRaska/open-bank-oss/issues/5392)) ([a77c8f5](https://github.com/JiRaska/open-bank-oss/commit/a77c8f51f338597494d63e6dd56e24377a7b1945)), closes [#5256](https://github.com/JiRaska/open-bank-oss/issues/5256)

## [0.8.6](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.8.5...statement-service-v0.8.6) (2026-08-17)


### Bug Fixes

* **statement:** track scheduled close liveness ([#5357](https://github.com/JiRaska/open-bank-oss/issues/5357)) ([78adec3](https://github.com/JiRaska/open-bank-oss/commit/78adec3f5ab86d8e912a510592e772f8b90b8964))

## [0.8.5](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.8.4...statement-service-v0.8.5) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.8.4](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.8.3...statement-service-v0.8.4) (2026-08-10)


### Bug Fixes

* **audit:** give the unattributed producers a real actor, and a way to say nobody did it ([#4424](https://github.com/JiRaska/open-bank-oss/issues/4424)) ([0cadda3](https://github.com/JiRaska/open-bank-oss/commit/0cadda3725280119a86dba722efa692e1f783fc9))

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.8.2...statement-service-v0.8.3) (2026-08-09)


### Bug Fixes

* **statement:** freeze render inputs at close so a closed period re-renders byte-identically ([#4271](https://github.com/JiRaska/open-bank-oss/issues/4271)) ([20b8d85](https://github.com/JiRaska/open-bank-oss/commit/20b8d85508791854704cbaacd24c94b9a28f8b28)), closes [#3986](https://github.com/JiRaska/open-bank-oss/issues/3986) [#1302](https://github.com/JiRaska/open-bank-oss/issues/1302) [#3920](https://github.com/JiRaska/open-bank-oss/issues/3920)

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.8.1...statement-service-v0.8.2) (2026-08-09)


### Bug Fixes

* **customer-edge:** answer 400, not 500, for a missing required query parameter ([#4211](https://github.com/JiRaska/open-bank-oss/issues/4211)) ([4ddb6ef](https://github.com/JiRaska/open-bank-oss/commit/4ddb6efeb23864fe65a4f2624f8722e1fcae04fb)), closes [#3624](https://github.com/JiRaska/open-bank-oss/issues/3624)

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.8.0...statement-service-v0.8.1) (2026-08-08)


### Bug Fixes

* send occurredAt on the four non-money-path domain-event producers ([#3926](https://github.com/JiRaska/open-bank-oss/issues/3926)) ([4a2080c](https://github.com/JiRaska/open-bank-oss/commit/4a2080c3a4de10b2a858b7111ac83d63c60114d1))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.7.0...statement-service-v0.8.0) (2026-08-07)


### Features

* **mcp-service:** add statement and payment confirmation query tools ([#4127](https://github.com/JiRaska/open-bank-oss/issues/4127)) ([98841b4](https://github.com/JiRaska/open-bank-oss/commit/98841b41dafd900429141744f3f3f57b1f0fa1b6))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.6.0...statement-service-v0.7.0) (2026-08-07)


### Features

* **statement-service:** add customer-facing statement download endpoint ([#4125](https://github.com/JiRaska/open-bank-oss/issues/4125)) ([e78ea34](https://github.com/JiRaska/open-bank-oss/commit/e78ea34468c726bf16f813deeb0bc4e79c2e5f35))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.5.11...statement-service-v0.6.0) (2026-08-07)


### Features

* **statement:** build the SUPERSEDED write path for closed-period restatement ([#3920](https://github.com/JiRaska/open-bank-oss/issues/3920)) ([f0b5f58](https://github.com/JiRaska/open-bank-oss/commit/f0b5f58d541fc4c965f431e582d54b30e52e3291))

## [0.5.11](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.5.10...statement-service-v0.5.11) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.5.10](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.5.9...statement-service-v0.5.10) (2026-07-29)


### Bug Fixes

* **statement:** pin the period close to the injected clock and an explicit cron zone ([#1302](https://github.com/JiRaska/open-bank-oss/issues/1302)) ([#2703](https://github.com/JiRaska/open-bank-oss/issues/2703)) ([e5edf0c](https://github.com/JiRaska/open-bank-oss/commit/e5edf0ce6b833c30cd31f8ca011db80d21f808e7))

## [0.5.9](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.5.8...statement-service-v0.5.9) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.5.8](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.5.7...statement-service-v0.5.8) (2026-07-17)


### Bug Fixes

* **statement:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1556](https://github.com/JiRaska/open-bank-oss/issues/1556)) ([9c4c1d6](https://github.com/JiRaska/open-bank-oss/commit/9c4c1d6f20d992fe14630c94c0399663c0479e07))

## [0.5.7](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.5.6...statement-service-v0.5.7) (2026-07-16)


### Bug Fixes

* **statement:** paginate booked-entry reads; heal oldest owed months first ([#1191](https://github.com/JiRaska/open-bank-oss/issues/1191)) ([94b0ad8](https://github.com/JiRaska/open-bank-oss/commit/94b0ad893d44cc05ddbaff7ce11c0e1bb80b6241)), closes [#470](https://github.com/JiRaska/open-bank-oss/issues/470)

## [0.5.6](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.5.5...statement-service-v0.5.6) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.5.5](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.5.4...statement-service-v0.5.5) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.5.4](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.5.3...statement-service-v0.5.4) (2026-06-30)


### Security

* **statement:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2762](https://github.com/JiRaska/open-bank-oss/issues/2762)) ([033abec](https://github.com/JiRaska/open-bank-oss/commit/033abec81770ccf09333bd102d56c676ad2a171b))

## [0.5.3](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.5.2...statement-service-v0.5.3) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)
* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([c61fcaf](https://github.com/JiRaska/open-bank-oss/commit/c61fcafde76be08f716c710462be70752073aba1))

## [0.5.2](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.5.1...statement-service-v0.5.2) (2026-06-28)


### Bug Fixes

* **temporal:** wire MicrometerClientStatsReporter in all Temporal workers ([35c6a63](https://github.com/JiRaska/open-bank-oss/commit/35c6a6309e10c0a56558d93de5c494a89da72ceb))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.5.0...statement-service-v0.5.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.4.0...statement-service-v0.5.0) (2026-06-25)


### Features

* **tpp-registry,statement,onboarding,agent,settlement,sdd:** inject Clock (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2138](https://github.com/JiRaska/open-bank-oss/issues/2138)) ([baa0d03](https://github.com/JiRaska/open-bank-oss/commit/baa0d03bcef7a1cd48cb7e115410ab625a26acde))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.3.1...statement-service-v0.4.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank-oss/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank-oss/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))
* **statement:** add @Authorize resource-gate on REST endpoints (ADR-0034 D3) ([#1349](https://github.com/JiRaska/open-bank-oss/issues/1349)) ([cce7c01](https://github.com/JiRaska/open-bank-oss/commit/cce7c01dbdb004f574553c0be764816615979c3f))
* **statement:** ADR-0101 P4 — Temporal durable workflow for close runs ([#1532](https://github.com/JiRaska/open-bank-oss/issues/1532)) ([c21694d](https://github.com/JiRaska/open-bank-oss/commit/c21694df4a10a0d01566803a8acae15139c9a109))


### Bug Fixes

* **statement:** align openapi info.version major to API v1 (ADR-0048) ([#1400](https://github.com/JiRaska/open-bank-oss/issues/1400)) ([bc79b63](https://github.com/JiRaska/open-bank-oss/commit/bc79b63e4a72b61db173d3aa06e0808c53969532))
* **statement:** retention-independent close-cadence gauge to end StatementCloseCadenceStalled false positive ([#1737](https://github.com/JiRaska/open-bank-oss/issues/1737)) ([b3271d0](https://github.com/JiRaska/open-bank-oss/commit/b3271d08de599ff1b36f1f86220cf80bb4579c0a))
* **statement:** skip NOT_VIABLE debris accounts in period-close instead of failing ([#862](https://github.com/JiRaska/open-bank-oss/issues/862)) ([#1554](https://github.com/JiRaska/open-bank-oss/issues/1554)) ([e316032](https://github.com/JiRaska/open-bank-oss/commit/e316032816ce7a25e689661552ab0e9591a5c237))
* **temporal:** remove non-existent @WorkflowImpl annotation from fx+statement ([#1538](https://github.com/JiRaska/open-bank-oss/issues/1538)) ([0d73e0d](https://github.com/JiRaska/open-bank-oss/commit/0d73e0d7548d2953536975b257e646de6272cff4))

## [0.3.1](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.3.0...statement-service-v0.3.1) (2026-06-15)


### Bug Fixes

* **statement:** assign unique HTTP port 8136 (resolve collision with customer-edge) ([#1046](https://github.com/JiRaska/open-bank-oss/issues/1046)) ([d9aa9c6](https://github.com/JiRaska/open-bank-oss/commit/d9aa9c6538ab767118de226fddb6cfc2d0eafb53))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.2.0...statement-service-v0.3.0) (2026-06-12)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **statement:** harden monthly close cadence and enable the cron ([#470](https://github.com/JiRaska/open-bank-oss/issues/470)) ([#629](https://github.com/JiRaska/open-bank-oss/issues/629)) ([43b1fd7](https://github.com/JiRaska/open-bank-oss/commit/43b1fd77b0cd4cfb839fc23cdffadec83587f8d1))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/statement-service-v0.1.2...statement-service-v0.2.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **statement:** account registry + scheduled close enumeration + sandbox deploy ([#466](https://github.com/JiRaska/open-bank-oss/issues/466)) ([90fc60c](https://github.com/JiRaska/open-bank-oss/commit/90fc60c5adf4e08355657c743a5d228a99a22243))
* **statement:** anchor period-close balances via point-in-time asOf ([#580](https://github.com/JiRaska/open-bank-oss/issues/580)) ([430d473](https://github.com/JiRaska/open-bank-oss/commit/430d4737210434642f5ba5a986bcb670efdc46f2))
* **statements:** customer statement list — M2M auth fix + edge route ([#574](https://github.com/JiRaska/open-bank-oss/issues/574)) ([05d81d3](https://github.com/JiRaska/open-bank-oss/commit/05d81d3400dde07a0f38a4965cef3699a5aed493))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **statement:** persist period close + outbox event atomically ([#557](https://github.com/JiRaska/open-bank-oss/issues/557)) ([72bebf4](https://github.com/JiRaska/open-bank-oss/commit/72bebf40ea6a25057f76853fc16bbbeb40b8e506))
* **statement:** populate IBAN + holder name on rendered statements ([#610](https://github.com/JiRaska/open-bank-oss/issues/610)) ([50841eb](https://github.com/JiRaska/open-bank-oss/commit/50841eb660ae3033f9ce64d0643642f75dc2c207))
* **statements:** enumerate currency pockets via /pockets, not the account body ([#576](https://github.com/JiRaska/open-bank-oss/issues/576)) ([7d52e7c](https://github.com/JiRaska/open-bank-oss/commit/7d52e7c1765a5b08c64ed87f068908c637cd4798))
* **statements:** point transaction + balance reads at the real service APIs ([#577](https://github.com/JiRaska/open-bank-oss/issues/577)) ([6e74446](https://github.com/JiRaska/open-bank-oss/commit/6e7444640b576762d0a716cf8f9f2b4acb254e1b))
