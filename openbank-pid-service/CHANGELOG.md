# Changelog

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.9.1...pid-service-v0.10.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.9.0...pid-service-v0.9.1) (2026-08-21)


### Bug Fixes

* **transaction:** publish the full TransactionType and TransactionStatus vocabularies ([#5982](https://github.com/JiRaska/open-bank-oss/issues/5982)) ([11baea4](https://github.com/JiRaska/open-bank-oss/commit/11baea4482c50d838f8c913d4ae466ccc198a53c))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.17...pid-service-v0.9.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.8.17](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.16...pid-service-v0.8.17) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.8.16](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.15...pid-service-v0.8.16) (2026-08-16)


### Bug Fixes

* **pid:** track trusted-list refresh liveness ([#5085](https://github.com/JiRaska/open-bank-oss/issues/5085)) ([bea782f](https://github.com/JiRaska/open-bank-oss/commit/bea782f0af29b04dee0d25233454205138bc4d03))

## [0.8.15](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.14...pid-service-v0.8.15) (2026-08-09)


### Bug Fixes

* **jaxrs-params:** answer 400, not 500, for a missing required query/header parameter ([#4375](https://github.com/JiRaska/open-bank-oss/issues/4375)) ([32ab2a2](https://github.com/JiRaska/open-bank-oss/commit/32ab2a2dfe0d208ae5ba865758c774ec47a92d09)), closes [#4175](https://github.com/JiRaska/open-bank-oss/issues/4175)

## [0.8.14](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.13...pid-service-v0.8.14) (2026-08-09)


### Bug Fixes

* **libs:** stamp ApiError.timestamp at construction instead of serving 1970 ([#3880](https://github.com/JiRaska/open-bank-oss/issues/3880)) ([b3e6672](https://github.com/JiRaska/open-bank-oss/commit/b3e6672c9e13470fc6353ad8a5483e4075875b1f))

## [0.8.13](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.12...pid-service-v0.8.13) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.8.12](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.11...pid-service-v0.8.12) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.8.11](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.10...pid-service-v0.8.11) (2026-07-26)


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.8.10](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.9...pid-service-v0.8.10) (2026-07-25)


### Bug Fixes

* **authz:** realm-issued role names across 9 services + enforce @RolesAllowed parity ([#2404](https://github.com/JiRaska/open-bank-oss/issues/2404)) ([#2418](https://github.com/JiRaska/open-bank-oss/issues/2418)) ([64a1f9b](https://github.com/JiRaska/open-bank-oss/commit/64a1f9be47bedbda5ffad876bb0394f404503821))

## [0.8.9](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.8...pid-service-v0.8.9) (2026-07-25)


### Bug Fixes

* **pid:** document the two endpoints openapi.yaml omits ([#2341](https://github.com/JiRaska/open-bank-oss/issues/2341)) ([107ba94](https://github.com/JiRaska/open-bank-oss/commit/107ba945a46cacffffa0be2b8d2bfda84f672ebe)), closes [#2255](https://github.com/JiRaska/open-bank-oss/issues/2255)

## [0.8.8](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.7...pid-service-v0.8.8) (2026-07-17)


### Bug Fixes

* **pid:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1541](https://github.com/JiRaska/open-bank-oss/issues/1541)) ([cd6ff1b](https://github.com/JiRaska/open-bank-oss/commit/cd6ff1bd255cec544ae3b252c317a056d554d9cc))

## [0.8.7](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.6...pid-service-v0.8.7) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.8.6](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.5...pid-service-v0.8.6) (2026-07-03)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.8.5](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.4...pid-service-v0.8.5) (2026-06-30)


### Security

* **pid:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2c) ([#2758](https://github.com/JiRaska/open-bank-oss/issues/2758)) ([b850595](https://github.com/JiRaska/open-bank-oss/commit/b8505950ea883026999a132eb604307adb8880b8))

## [0.8.4](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.3...pid-service-v0.8.4) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **libs,account,consent,ledger,pid,transaction:** make DomainEvent.occurredAt explicit ([#2137](https://github.com/JiRaska/open-bank-oss/issues/2137)) ([#2662](https://github.com/JiRaska/open-bank-oss/issues/2662)) ([9e0c2ea](https://github.com/JiRaska/open-bank-oss/commit/9e0c2ea14a65aec227df333b83b0b7283b6c16a5))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.1...pid-service-v0.8.2) (2026-06-29)


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.8.0...pid-service-v0.8.1) (2026-06-27)


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.7.0...pid-service-v0.8.0) (2026-06-25)


### Features

* **pid:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2123](https://github.com/JiRaska/open-bank-oss/issues/2123)) ([0091032](https://github.com/JiRaska/open-bank-oss/commit/00910324c96a29cc821f63e217c542124e38feb4))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.6.0...pid-service-v0.7.0) (2026-06-25)


### Features

* **pid:** implement evidence-link action hook in PartyService ([#2021](https://github.com/JiRaska/open-bank-oss/issues/2021)) ([088ff93](https://github.com/JiRaska/open-bank-oss/commit/088ff933ac3c87b48e540c2990d006a3f03d2b0a))

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.5.0...pid-service-v0.6.0) (2026-06-25)


### Features

* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))
* **libs:** flip authz.enforce to true for non-money-path services (ADR-0034 D5) ([#1365](https://github.com/JiRaska/open-bank-oss/issues/1365)) ([6a4df3d](https://github.com/JiRaska/open-bank-oss/commit/6a4df3d763b026f66c683b161e1160d22a2a89e6))
* **pid:** durable persistence for EUDI issuer/verifier stores (ADR-0094) ([#1646](https://github.com/JiRaska/open-bank-oss/issues/1646)) ([73177ef](https://github.com/JiRaska/open-bank-oss/commit/73177efd89823c2b3b33cdc1c73133193776de1e))
* **pid:** eIDAS Trusted List trust framework for EUDI verification (ADR-0094) ([#1658](https://github.com/JiRaska/open-bank-oss/issues/1658)) ([0cfa373](https://github.com/JiRaska/open-bank-oss/commit/0cfa37325314c97a6c9fe1f452399ee378e84d4d))
* **pid:** EUDI tier-0 — verify wallet PID presentation as the strongest dedup key (ADR-0094) ([#1568](https://github.com/JiRaska/open-bank-oss/issues/1568)) ([b5c21f3](https://github.com/JiRaska/open-bank-oss/commit/b5c21f325761e7346d4942a82c745f29b047a1fb)), closes [#1294](https://github.com/JiRaska/open-bank-oss/issues/1294)
* **pid:** four-eyes identity-verification cases (ADR-0072 §1 / ADR-0030) ([#1517](https://github.com/JiRaska/open-bank-oss/issues/1517)) ([98e50c5](https://github.com/JiRaska/open-bank-oss/commit/98e50c5efd588698e51ce70c720ceaa63f7b9fb8)), closes [#1294](https://github.com/JiRaska/open-bank-oss/issues/1294)
* **pid:** ISO 18013-5 mdoc (CBOR/COSE) PID verification (ADR-0094) ([#1668](https://github.com/JiRaska/open-bank-oss/issues/1668)) ([56da40e](https://github.com/JiRaska/open-bank-oss/commit/56da40ea312ba4a360346959625c09dd3ea0a9f7))
* **pid:** OpenID4VCI credential issuance — the bank as an EUDI PID issuer (ADR-0094) ([#1610](https://github.com/JiRaska/open-bank-oss/issues/1610)) ([d5457dc](https://github.com/JiRaska/open-bank-oss/commit/d5457dc58b19643830290da18ccad52debf8b34e))
* **pid:** OpenID4VP relying-party flow for EUDI wallet presentations (ADR-0094) ([#1603](https://github.com/JiRaska/open-bank-oss/issues/1603)) ([c5a1be9](https://github.com/JiRaska/open-bank-oss/commit/c5a1be9bbc2b6a258ef7014d8ec37c6d5631ee4d))
* **pid:** signed Request Object (JAR) for the OpenID4VP flow (ADR-0094) ([#1642](https://github.com/JiRaska/open-bank-oss/issues/1642)) ([2943ced](https://github.com/JiRaska/open-bank-oss/commit/2943cedf9e226bd1c63fb3ad082d6bafa0cecbc2))
* **pid:** Token Status List revocation for issued EUDI credentials (ADR-0094) ([#1638](https://github.com/JiRaska/open-bank-oss/issues/1638)) ([d59f33e](https://github.com/JiRaska/open-bank-oss/commit/d59f33e5e8fa31c1a442067e21eb25419e992f7a))
* **pid:** wire probabilistic tier-2′ into the resolver (ADR-0072) ([#1536](https://github.com/JiRaska/open-bank-oss/issues/1536)) ([a419e72](https://github.com/JiRaska/open-bank-oss/commit/a419e7247536a63b9a902299d704e059df458cc3)), closes [#1294](https://github.com/JiRaska/open-bank-oss/issues/1294)
* **pid:** write EUDI_PID_SUB blind index on register-identity (ADR-0094) ([#1575](https://github.com/JiRaska/open-bank-oss/issues/1575)) ([d224bb5](https://github.com/JiRaska/open-bank-oss/commit/d224bb582e75c2d537b4115e77188a7cc8d7952b))


### Bug Fixes

* **ci:** pre-warm TC image cache + inmemory blob descriptors — eliminates NAT burst on CI sweeps ([#1675](https://github.com/JiRaska/open-bank-oss/issues/1675)) ([0c42e1f](https://github.com/JiRaska/open-bank-oss/commit/0c42e1ffc2c805fa84f029b45e70408281eb976b))
* **pid:** lazy auth so the OpenID4VCI credential endpoint accepts a wallet token (ADR-0094) ([#1631](https://github.com/JiRaska/open-bank-oss/issues/1631)) ([18518d5](https://github.com/JiRaska/open-bank-oss/commit/18518d5b6c87eeaf0686dc850fbc8b7743f37700))
* **pid:** route OpenID4VCI endpoints under the shared eudi prefix (ADR-0094) ([#1621](https://github.com/JiRaska/open-bank-oss/issues/1621)) ([ba55186](https://github.com/JiRaska/open-bank-oss/commit/ba5518686e829e04733b108be884104373c7814c))
* **pid:** V7 migration — allow PROBABILISTIC_CANDIDATE in chk_ivc_trigger ([#1558](https://github.com/JiRaska/open-bank-oss/issues/1558)) ([3016e5a](https://github.com/JiRaska/open-bank-oss/commit/3016e5aa3539e208527daf1de97fe47c4d47caff))


### Security

* **pid:** reject SD-JWT VC declaring unsupported _sd_alg (ADR-0094) ([#1573](https://github.com/JiRaska/open-bank-oss/issues/1573)) ([f121587](https://github.com/JiRaska/open-bank-oss/commit/f121587184c330262c87a1a4060ec9da6dc32f09))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.4.0...pid-service-v0.5.0) (2026-06-12)


### Features

* **infra,agent:** feature-flag flip enforcement — CI gate + MCP tool (ADR-0067 / issue [#419](https://github.com/JiRaska/open-bank-oss/issues/419)) ([#758](https://github.com/JiRaska/open-bank-oss/issues/758)) ([96bfb7d](https://github.com/JiRaska/open-bank-oss/commit/96bfb7d506c9e2da22cde563ef8d676d77699019))
* **pid:** identity resolution — blind-index dedup + resolve endpoint (ADR-0072, issue [#699](https://github.com/JiRaska/open-bank-oss/issues/699)) ([#759](https://github.com/JiRaska/open-bank-oss/issues/759)) ([53803bf](https://github.com/JiRaska/open-bank-oss/commit/53803bf54adc8335f09893aaa898df0c792bdba4))
* **pid:** outbox backlog gauge + countProcessable (ADR-0077/0079) ([#803](https://github.com/JiRaska/open-bank-oss/issues/803)) ([67ecea2](https://github.com/JiRaska/open-bank-oss/commit/67ecea2f1e41a6359ee96978b9e86844ec3bf295))

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.3.0...pid-service-v0.4.0) (2026-06-11)


### Features

* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.2.0...pid-service-v0.3.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))


### Bug Fixes

* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/pid-service-v0.1.1...pid-service-v0.2.0) (2026-06-06)


### Features

* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
