# Changelog

## [0.22.2](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.22.1...party-service-v0.22.2) (2026-09-03)


### Bug Fixes

* send occurredAt on the last four audit-consumed producers that omit it ([#8352](https://github.com/JiRaska/open-bank-oss/issues/8352)) ([#8503](https://github.com/JiRaska/open-bank-oss/issues/8503)) ([146fe87](https://github.com/JiRaska/open-bank-oss/commit/146fe87adaeca4e56fb8da285a57daaaf840cb1d))

## [0.22.1](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.22.0...party-service-v0.22.1) (2026-09-01)


### Bug Fixes

* **party:** answer 400 for a null phone hash in a directory lookup, not 500 ([#7861](https://github.com/JiRaska/open-bank-oss/issues/7861)) ([0b05abe](https://github.com/JiRaska/open-bank-oss/commit/0b05abe058827a8fa0bdfa9955164d278a41182f)), closes [#5913](https://github.com/JiRaska/open-bank-oss/issues/5913)

## [0.22.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.21.0...party-service-v0.22.0) (2026-08-28)


### Features

* **party:** expose pending approvals in unified inbox ([#7020](https://github.com/JiRaska/open-bank-oss/issues/7020)) ([0445a3e](https://github.com/JiRaska/open-bank-oss/commit/0445a3ec6c9fafc25c3640998b3872cb118aa94b))

## [0.21.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.20.0...party-service-v0.21.0) (2026-08-24)


### Features

* **libs:** persist synthetic outbox taint ([#6731](https://github.com/JiRaska/open-bank-oss/issues/6731)) ([f8d165d](https://github.com/JiRaska/open-bank-oss/commit/f8d165dd695cc63ad0181ac97f4303b26c4ded18))

## [0.20.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.19.0...party-service-v0.20.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [0.19.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.18.3...party-service-v0.19.0) (2026-08-24)


### Features

* **party:** classify synthetic canary parties ([#6730](https://github.com/JiRaska/open-bank-oss/issues/6730)) ([f478d04](https://github.com/JiRaska/open-bank-oss/commit/f478d04fec9d4c6a0a31ca0a443d4783c4d796de))

## [0.18.3](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.18.2...party-service-v0.18.3) (2026-08-22)


### Bug Fixes

* **fleet:** stop event handlers acking work they did not do, and gate it ([#5719](https://github.com/JiRaska/open-bank-oss/issues/5719)) ([7b1c78d](https://github.com/JiRaska/open-bank-oss/commit/7b1c78d5b6a7223a05ad9b52860f5e0aac7db9d3))
* **party:** create the Hibernate id sequences in lower case so inserts can allocate an id ([#6467](https://github.com/JiRaska/open-bank-oss/issues/6467)) ([134cce5](https://github.com/JiRaska/open-bank-oss/commit/134cce517a32a3e8253885b3735e4877e95fab9a)), closes [#5913](https://github.com/JiRaska/open-bank-oss/issues/5913)

## [0.18.2](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.18.1...party-service-v0.18.2) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [0.18.1](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.18.0...party-service-v0.18.1) (2026-08-21)


### Bug Fixes

* **transaction:** publish the full TransactionType and TransactionStatus vocabularies ([#5982](https://github.com/JiRaska/open-bank-oss/issues/5982)) ([11baea4](https://github.com/JiRaska/open-bank-oss/commit/11baea4482c50d838f8c913d4ae466ccc198a53c))

## [0.18.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.17.2...party-service-v0.18.0) (2026-08-18)


### Features

* **ci:** isolate provider-pact verification from main-push build (ADR-0250 Phase 2) ([#5462](https://github.com/JiRaska/open-bank-oss/issues/5462)) ([deca231](https://github.com/JiRaska/open-bank-oss/commit/deca23153b0785265e421fa3c86bde64bf80f222))


### Bug Fixes

* **libs-runtime:** switch AbstractOutboxDispatcher.metrics to constructor injection ([#5199](https://github.com/JiRaska/open-bank-oss/issues/5199)) ([1d07563](https://github.com/JiRaska/open-bank-oss/commit/1d075635ef70004e8b9b50475cd97aa31c9beafd)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.17.2](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.17.1...party-service-v0.17.2) (2026-08-18)


### Bug Fixes

* **account,party:** add sourceService to audit-consumed events ([#5267](https://github.com/JiRaska/open-bank-oss/issues/5267)) ([2aeefeb](https://github.com/JiRaska/open-bank-oss/commit/2aeefebc6275c45068049aa40869e84a1efd58c4))

## [0.17.1](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.17.0...party-service-v0.17.1) (2026-08-17)


### Bug Fixes

* **libs-domain:** read markFailed's persisted status instead of predicting it ([#5203](https://github.com/JiRaska/open-bank-oss/issues/5203)) ([14fae69](https://github.com/JiRaska/open-bank-oss/commit/14fae6995e78bfa47f18aba75a6da056b2f62a7a)), closes [#5128](https://github.com/JiRaska/open-bank-oss/issues/5128)

## [0.17.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.16.1...party-service-v0.17.0) (2026-08-17)


### Features

* **payees:** server-synced saved payees (TOP-10 [#5](https://github.com/JiRaska/open-bank-oss/issues/5)) ([#5154](https://github.com/JiRaska/open-bank-oss/issues/5154)) ([9c93621](https://github.com/JiRaska/open-bank-oss/commit/9c936211df184df867a6a274ce4cb09b64114f21))

## [0.16.1](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.16.0...party-service-v0.16.1) (2026-08-16)


### Bug Fixes

* **party:** bump the API contract version to 1.16.0 ([#4986](https://github.com/JiRaska/open-bank-oss/issues/4986)) ([f731440](https://github.com/JiRaska/open-bank-oss/commit/f73144007ecd27cd322fea497a475b7fc18b92eb)), closes [#4808](https://github.com/JiRaska/open-bank-oss/issues/4808)

## [0.16.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.15.6...party-service-v0.16.0) (2026-08-14)


### Features

* **party:** declare materiality on PARTY_UPDATED events ([#4751](https://github.com/JiRaska/open-bank-oss/issues/4751)) ([cf3a545](https://github.com/JiRaska/open-bank-oss/commit/cf3a545a2f9835ce65e3c8fc38a361c679fde5b8))


### Bug Fixes

* **party:** point the merge refusal at the real balance-sweep endpoint ([#4753](https://github.com/JiRaska/open-bank-oss/issues/4753)) ([46932fc](https://github.com/JiRaska/open-bank-oss/commit/46932fc578df7fedc33cc45491f14e6ce1a29b04))

## [0.15.6](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.15.5...party-service-v0.15.6) (2026-08-10)


### Bug Fixes

* **audit:** give the unattributed producers a real actor, and a way to say nobody did it ([#4424](https://github.com/JiRaska/open-bank-oss/issues/4424)) ([0cadda3](https://github.com/JiRaska/open-bank-oss/commit/0cadda3725280119a86dba722efa692e1f783fc9))

## [0.15.5](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.15.4...party-service-v0.15.5) (2026-08-09)


### Bug Fixes

* **jaxrs-params:** answer 400, not 500, for a missing required query/header parameter ([#4375](https://github.com/JiRaska/open-bank-oss/issues/4375)) ([32ab2a2](https://github.com/JiRaska/open-bank-oss/commit/32ab2a2dfe0d208ae5ba865758c774ec47a92d09)), closes [#4175](https://github.com/JiRaska/open-bank-oss/issues/4175)

## [0.15.4](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.15.3...party-service-v0.15.4) (2026-08-09)


### Bug Fixes

* **libs:** stamp ApiError.timestamp at construction instead of serving 1970 ([#3880](https://github.com/JiRaska/open-bank-oss/issues/3880)) ([b3e6672](https://github.com/JiRaska/open-bank-oss/commit/b3e6672c9e13470fc6353ad8a5483e4075875b1f))

## [0.15.3](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.15.2...party-service-v0.15.3) (2026-08-09)


### Bug Fixes

* **party:** write party lifecycle events to the outbox, retire the direct emitter ([#4158](https://github.com/JiRaska/open-bank-oss/issues/4158)) ([5ef337c](https://github.com/JiRaska/open-bank-oss/commit/5ef337c1f432537f96644613ef5b2b1ae782268e))

## [0.15.2](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.15.1...party-service-v0.15.2) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [0.15.1](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.15.0...party-service-v0.15.1) (2026-08-01)


### Bug Fixes

* **approvals:** a null JSON body on the four-eyes decide endpoint returned 500 ([#3029](https://github.com/JiRaska/open-bank-oss/issues/3029)) ([#3032](https://github.com/JiRaska/open-bank-oss/issues/3032)) ([36ff2ac](https://github.com/JiRaska/open-bank-oss/commit/36ff2ac571df954a408f80fa7d661967953d6144))

## [0.15.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.14.0...party-service-v0.15.0) (2026-07-31)


### Features

* **directory:** pay-to-phone lookup, opt-in and honest about what hashing buys ([#2840](https://github.com/JiRaska/open-bank-oss/issues/2840)) ([73c4827](https://github.com/JiRaska/open-bank-oss/commit/73c48273d5956259d7356be9cb4fa39b4d70e311))

## [0.14.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.13.0...party-service-v0.14.0) (2026-07-31)


### Features

* **party:** add the GDPR Art. 20 portability export endpoint (ADR-0204) ([#2704](https://github.com/JiRaska/open-bank-oss/issues/2704)) ([2412a55](https://github.com/JiRaska/open-bank-oss/commit/2412a551c843adba60dd6cd119923f37476fec6b))

## [0.13.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.12.1...party-service-v0.13.0) (2026-07-31)


### Features

* **party:** search by business keys — email, phone, tax id, registration number (ADR-0228 D1) ([#2781](https://github.com/JiRaska/open-bank-oss/issues/2781)) ([b6fd315](https://github.com/JiRaska/open-bank-oss/commit/b6fd3153501b2553bcf3cabe6586328ed861630b))


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [0.12.1](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.12.0...party-service-v0.12.1) (2026-07-27)


### Security

* **party:** gate the ADR-0179 identity merge behind four-eyes (maker/checker) ([#2608](https://github.com/JiRaska/open-bank-oss/issues/2608)) ([d8164ac](https://github.com/JiRaska/open-bank-oss/commit/d8164ac793c70daeb0b8c03999bcbee76ffd4b07)), closes [#1984](https://github.com/JiRaska/open-bank-oss/issues/1984)

## [0.12.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.11.0...party-service-v0.12.0) (2026-07-26)


### Features

* **party:** forward marketing-consent toggle to consent-service (ADR-0198 D3, ADR-0206 D5) ([#2499](https://github.com/JiRaska/open-bank-oss/issues/2499)) ([09a626c](https://github.com/JiRaska/open-bank-oss/commit/09a626c30b671cde5390f0f589446b30d6d0023d))


### Bug Fixes

* **authz:** grant ROLE_API to the M2M account, sweep the dead ROLE_SERVICE name, enforce parity ([#2442](https://github.com/JiRaska/open-bank-oss/issues/2442)) ([#2475](https://github.com/JiRaska/open-bank-oss/issues/2475)) ([9f138c1](https://github.com/JiRaska/open-bank-oss/commit/9f138c133051a44c13790578a2864a703bda3425))

## [0.11.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.10.1...party-service-v0.11.0) (2026-07-25)


### Features

* **party:** project consent-service's marketing consent into consent_marketing (ADR-0205 D4) ([#2432](https://github.com/JiRaska/open-bank-oss/issues/2432)) ([5423ee9](https://github.com/JiRaska/open-bank-oss/commit/5423ee9cc9fbf0e05c044da6ab7bebdceebe36bd))

## [0.10.1](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.10.0...party-service-v0.10.1) (2026-07-22)


### Bug Fixes

* **party:** authenticate the GDPR Art. 15 aggregation hops ([#1883](https://github.com/JiRaska/open-bank-oss/issues/1883)) ([c0a54fd](https://github.com/JiRaska/open-bank-oss/commit/c0a54fd6ed64bff200d99c1a9943f57bb3733bbd))

## [0.10.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.9.2...party-service-v0.10.0) (2026-07-19)


### Features

* **party:** add duplicate party identity merge (ADR-0179) ([#1783](https://github.com/JiRaska/open-bank-oss/issues/1783)) ([ceab2c6](https://github.com/JiRaska/open-bank-oss/commit/ceab2c65039e3bfbbaae020444ca533afa5d3bcd))

## [0.9.2](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.9.1...party-service-v0.9.2) (2026-07-17)


### Bug Fixes

* **party:** atomic FOR UPDATE SKIP LOCKED outbox claim ([#1201](https://github.com/JiRaska/open-bank-oss/issues/1201)) ([#1540](https://github.com/JiRaska/open-bank-oss/issues/1540)) ([08a6537](https://github.com/JiRaska/open-bank-oss/commit/08a65375f6cd5a4bb06769d749be42f1a7931eb7))

## [0.9.1](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.9.0...party-service-v0.9.1) (2026-07-16)


### Bug Fixes

* **party-service:** restore @PactBroker on provider verification (unblocks auto-deploy) ([#1166](https://github.com/JiRaska/open-bank-oss/issues/1166)) ([f9f28e5](https://github.com/JiRaska/open-bank-oss/commit/f9f28e5c700d5e98df59416aba4ac669e62e47a3))

## [0.9.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.8.3...party-service-v0.9.0) (2026-07-16)


### Features

* **party-service,customer-edge:** revocable marketing consent (Profile screen) ([#1161](https://github.com/JiRaska/open-bank-oss/issues/1161)) ([dd1d757](https://github.com/JiRaska/open-bank-oss/commit/dd1d7571972abaf4c516c97f29edaa2f121d133f))

## [0.8.3](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.8.2...party-service-v0.8.3) (2026-07-16)


### Bug Fixes

* **party-service,customer-edge:** forward + persist onboarding consent ([#1157](https://github.com/JiRaska/open-bank-oss/issues/1157)) ([b16b143](https://github.com/JiRaska/open-bank-oss/commit/b16b1437e268b1d58115f9e194e40d31a3cfe596))

## [0.8.2](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.8.1...party-service-v0.8.2) (2026-07-11)


### Security

* **party:** require authentication on the GDPR export endpoint ([#779](https://github.com/JiRaska/open-bank-oss/issues/779)) ([df93c24](https://github.com/JiRaska/open-bank-oss/commit/df93c249362559bc15a2b91560fe71b92d39db1a)), closes [#467](https://github.com/JiRaska/open-bank-oss/issues/467)

## [0.8.1](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.8.0...party-service-v0.8.1) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [0.8.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.7.3...party-service-v0.8.0) (2026-07-07)


### Features

* **gdpr:** add kyc/card export coverage and disabled-by-default session-log retention ([#356](https://github.com/JiRaska/open-bank-oss/issues/356)) ([d627e0a](https://github.com/JiRaska/open-bank-oss/commit/d627e0a0d9c7514f65b53f8d253c2ae5394e5386))

## [0.7.3](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.7.2...party-service-v0.7.3) (2026-07-04)


### Bug Fixes

* three api-fuzz.yml boot failures (dispute, party, consent) ([#233](https://github.com/JiRaska/open-bank-oss/issues/233)) ([6534e12](https://github.com/JiRaska/open-bank-oss/commit/6534e12e5a90e62df1190d356f8058f8b476b84d))

## [0.7.2](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.7.1...party-service-v0.7.2) (2026-07-02)


### Security

* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [0.7.1](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.7.0...party-service-v0.7.1) (2026-06-30)


### Security

* **party:** Kafka mTLS migration — tls:9093 + KafkaUser + cert projection (ADR-0137 [#2665](https://github.com/JiRaska/open-bank-oss/issues/2665) Tier 2a) ([#2750](https://github.com/JiRaska/open-bank-oss/issues/2750)) ([f4e1a41](https://github.com/JiRaska/open-bank-oss/commit/f4e1a4176783f8671fe0d0ede3df7d31df3ecac8))

## [0.7.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.6.0...party-service-v0.7.0) (2026-06-29)


### Features

* **party:** emit AuditEvent on GDPR erasure and subject-access endpoints ([#2447](https://github.com/JiRaska/open-bank-oss/issues/2447)) ([9013866](https://github.com/JiRaska/open-bank-oss/commit/9013866de91977dd61b95653529dadad544bba0a))
* **party:** GDPR Art. 15 subject-access export endpoint ([#2440](https://github.com/JiRaska/open-bank-oss/issues/2440)) ([54f0d1c](https://github.com/JiRaska/open-bank-oss/commit/54f0d1cb503cc633b6a2aa6d71c7add50ddbd389))
* **party:** GDPR Art.15 aggregation — KYC + card PII in subject-access export ([#2630](https://github.com/JiRaska/open-bank-oss/issues/2630)) ([dfdccfa](https://github.com/JiRaska/open-bank-oss/commit/dfdccfa1e3c72c0ee1a4a6b5b22072c41d154411))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([ce39af7](https://github.com/JiRaska/open-bank-oss/commit/ce39af7c5b02ff3fef226f34e78c7e726b149ebd))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **party:** add missing imports and Clock injection after [#2272](https://github.com/JiRaska/open-bank-oss/issues/2272) wildcard expansion ([b32d1bc](https://github.com/JiRaska/open-bank-oss/commit/b32d1bc59fd2535c5cbc12a5fd7bd6351eb7b90a))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)

## [0.6.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.5.1...party-service-v0.6.0) (2026-06-29)


### Features

* **party:** emit AuditEvent on GDPR erasure and subject-access endpoints ([#2447](https://github.com/JiRaska/open-bank-oss/issues/2447)) ([b068b20](https://github.com/JiRaska/open-bank-oss/commit/b068b2098af9e0d7b1b83eb6b317194a1f40b25c))
* **party:** GDPR Art. 15 subject-access export endpoint ([#2440](https://github.com/JiRaska/open-bank-oss/issues/2440)) ([a54e69b](https://github.com/JiRaska/open-bank-oss/commit/a54e69b466c831e7cf4cb6d00fc51c59d802d21d))

## [0.5.1](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.5.0...party-service-v0.5.1) (2026-06-28)


### Bug Fixes

* **party:** add missing imports and Clock injection after [#2272](https://github.com/JiRaska/open-bank-oss/issues/2272) wildcard expansion ([b1b53b6](https://github.com/JiRaska/open-bank-oss/commit/b1b53b6ff3a62d740c1e36565b2c8748f4e6749b))

## [0.5.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.4.1...party-service-v0.5.0) (2026-06-27)


### Features

* **notification,party,standing-order:** inject Clock for DST determinism (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2115](https://github.com/JiRaska/open-bank-oss/issues/2115)) ([596924a](https://github.com/JiRaska/open-bank-oss/commit/596924a5ea3f05e8722767c66fe89638aeaaeb87))


### Bug Fixes

* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))
* **fleet:** resolve ktlint violations and sepa-payment compile error after ADR-0100 Clock sweep ([#2272](https://github.com/JiRaska/open-bank-oss/issues/2272)) ([3cd3637](https://github.com/JiRaska/open-bank-oss/commit/3cd3637372c52025cfac6f29d23129bab4d3919b))

## [0.4.1](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.4.0...party-service-v0.4.1) (2026-06-25)


### Bug Fixes

* **ci:** can-i-deploy --latest main — avoid 'No pacts' on path-scoped SHA ([4ea2731](https://github.com/JiRaska/open-bank-oss/commit/4ea273195d038704acc6341f684c0f1cb039ce82))
* **ci:** pre-warm TC image cache + inmemory blob descriptors — eliminates NAT burst on CI sweeps ([#1675](https://github.com/JiRaska/open-bank-oss/issues/1675)) ([0c42e1f](https://github.com/JiRaska/open-bank-oss/commit/0c42e1ffc2c805fa84f029b45e70408281eb976b))
* **party:** map PgException(23505) on rc_blind_index to 409 CONFLICT ([#1547](https://github.com/JiRaska/open-bank-oss/issues/1547)) ([77c6b31](https://github.com/JiRaska/open-bank-oss/commit/77c6b31b3bfd96641e92ac4a0876d1c789c17fa0)), closes [#1417](https://github.com/JiRaska/open-bank-oss/issues/1417)

## [0.4.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.3.0...party-service-v0.4.0) (2026-06-15)


### Features

* **onboarding:** self-service E2E — savings account, sub-bound party, own-account transfers ([#894](https://github.com/JiRaska/open-bank-oss/issues/894)) ([91dd603](https://github.com/JiRaska/open-bank-oss/commit/91dd603e62f8f9038a0c71d1a0187ce0303442ce))


### Bug Fixes

* **accounts:** wire account-service to sanctions-service (unblocks onboarding accounts) ([#932](https://github.com/JiRaska/open-bank-oss/issues/932)) ([e78d03c](https://github.com/JiRaska/open-bank-oss/commit/e78d03cd354744d602101455045884a4bb9bbc11))
* **infra:** revert EC2NodeClass userData — breaks AL2023 node bootstrap ([#940](https://github.com/JiRaska/open-bank-oss/issues/940)) ([f7d128a](https://github.com/JiRaska/open-bank-oss/commit/f7d128ae7773d4d3237af13d45d2f4cf177aa89a))
* **party:** append-only migrations + deploy (unblocks party.id == sub onboarding) ([#930](https://github.com/JiRaska/open-bank-oss/issues/930)) ([5e4d18e](https://github.com/JiRaska/open-bank-oss/commit/5e4d18e8d14174a119b027597c8af8d77cac86aa))

## [0.3.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.2.0...party-service-v0.3.0) (2026-06-12)


### Features

* **account,party,onboarding:** sprint 2 — sanctions persistence, GDPR erasure, doc download, AbandonedCleaner fix ([#475](https://github.com/JiRaska/open-bank-oss/issues/475)) ([05b20d7](https://github.com/JiRaska/open-bank-oss/commit/05b20d764a6373d0ffd96ca84ab5a9a6ed54291f))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **party:** party lifecycle metrics + outbox backlog gauge (ADR-0077/0079) ([#793](https://github.com/JiRaska/open-bank-oss/issues/793)) ([cbd8a7f](https://github.com/JiRaska/open-bank-oss/commit/cbd8a7f03f08ccd7f3cb523012b3244b0d42f119))


### Bug Fixes

* **party:** resolve duplicate Flyway V8 (party-service could not boot) ([#771](https://github.com/JiRaska/open-bank-oss/issues/771)) ([759bd09](https://github.com/JiRaska/open-bank-oss/commit/759bd09607fbd349a64ddbd0c4468f2b6452d303)), closes [#699](https://github.com/JiRaska/open-bank-oss/issues/699)

## [0.2.0](https://github.com/JiRaska/open-bank-oss/compare/party-service-v0.1.1...party-service-v0.2.0) (2026-06-10)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))
* **libs:** add party-self-service and operator-read-any OPA rules for device.list ([#418](https://github.com/JiRaska/open-bank-oss/issues/418)) ([a4499b6](https://github.com/JiRaska/open-bank-oss/commit/a4499b605d640caa1b6b269ffb0388bf07fd98a8))
* **party,kyc:** add ?status= filter to list endpoints for onboarding cockpit ([#420](https://github.com/JiRaska/open-bank-oss/issues/420)) ([3c3cee8](https://github.com/JiRaska/open-bank-oss/commit/3c3cee8cec1ca4896f9e30a1da5ff1c180e2a05f))
* **party:** bounded name search (ADR-0055) ([#410](https://github.com/JiRaska/open-bank-oss/issues/410)) ([a03dc60](https://github.com/JiRaska/open-bank-oss/commit/a03dc605344703ed9f77ed27fc78d5cb613e535d))
* **party:** gate /parties/search behind @FeatureFlag (ADR-0067 phase 2) ([aa17239](https://github.com/JiRaska/open-bank-oss/commit/aa172393570c427afdef0523f03125493fbcf479))
* **party:** include legalName in party events (onboarding cockpit NAME column) ([#529](https://github.com/JiRaska/open-bank-oss/issues/529)) ([5b8aa03](https://github.com/JiRaska/open-bank-oss/commit/5b8aa03c62d0ab47e106bf89b2274ad1d2a89d0a))
* **party:** KYC + AML two-key activation gate (ADR-0073 phase 2) ([#537](https://github.com/JiRaska/open-bank-oss/issues/537)) ([2a8d1e0](https://github.com/JiRaska/open-bank-oss/commit/2a8d1e07797a2006751f1851b9fd316217b67998))
* **party:** pilot live feature-flag evaluation via flagd sidecar (ADR-0067) ([#456](https://github.com/JiRaska/open-bank-oss/issues/456)) ([f2ffdbb](https://github.com/JiRaska/open-bank-oss/commit/f2ffdbb399802e419d134d17305138b5d1ed92c3))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
* **party:** make email a required field in the create-party contract ([#525](https://github.com/JiRaska/open-bank-oss/issues/525)) ([dc8a2ad](https://github.com/JiRaska/open-bank-oss/commit/dc8a2adb71923e89fbb1b858b49fe458e65ead13))
