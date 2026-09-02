# Changelog

## [1.25.1](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.25.0...agent-service-v1.25.1) (2026-09-01)


### Bug Fixes

* **agent:** reject a null array element with 400 instead of 500 ([#8008](https://github.com/JiRaska/open-bank-oss/issues/8008)) ([f7beb2d](https://github.com/JiRaska/open-bank-oss/commit/f7beb2d9653a67b048fce01139a352d73428a29a)), closes [#7867](https://github.com/JiRaska/open-bank-oss/issues/7867)

## [1.25.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.24.0...agent-service-v1.25.0) (2026-08-25)


### Features

* **testing:** add trace contract kit ([#6805](https://github.com/JiRaska/open-bank-oss/issues/6805)) ([bcee169](https://github.com/JiRaska/open-bank-oss/commit/bcee1697140bbe1f96fec33a6ed6ddf7f90ab109))

## [1.24.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.23.2...agent-service-v1.24.0) (2026-08-24)


### Features

* **testing:** enforce synthetic taint REST boundaries ([#6724](https://github.com/JiRaska/open-bank-oss/issues/6724)) ([569c856](https://github.com/JiRaska/open-bank-oss/commit/569c85624aa3d6f1933865ae7ebcb69589d7d60d))

## [1.23.2](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.23.1...agent-service-v1.23.2) (2026-08-24)


### Bug Fixes

* **agent:** stop substituting actorId for a missing audit aggregateId ([#6579](https://github.com/JiRaska/open-bank-oss/issues/6579)) ([5268ef9](https://github.com/JiRaska/open-bank-oss/commit/5268ef98e7ec987266119606d49e5b0034e9e3cf))

## [1.23.1](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.23.0...agent-service-v1.23.1) (2026-08-22)


### Bug Fixes

* **fleet:** wire the dead-letter queue the rethrow depends on ([#5745](https://github.com/JiRaska/open-bank-oss/issues/5745)) ([#5751](https://github.com/JiRaska/open-bank-oss/issues/5751)) ([21049ae](https://github.com/JiRaska/open-bank-oss/commit/21049aef887668f2828bd1e719bd05ea32aa48b4))

## [1.23.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.22.0...agent-service-v1.23.0) (2026-08-22)


### Features

* **agent:** time the oversight sweep with its own run-duration metric ([#6208](https://github.com/JiRaska/open-bank-oss/issues/6208)) ([ca7eb98](https://github.com/JiRaska/open-bank-oss/commit/ca7eb98b01c679cb8f8dd90c6ba0b33ca5ba42e3)), closes [#6169](https://github.com/JiRaska/open-bank-oss/issues/6169)

## [1.22.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.21.0...agent-service-v1.22.0) (2026-08-21)


### Features

* **agent:** durable AI audit provenance ([#6209](https://github.com/JiRaska/open-bank-oss/issues/6209)) ([8a862f3](https://github.com/JiRaska/open-bank-oss/commit/8a862f387594f934f91bf5befcbc966ccf40abad))

## [1.21.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.20.2...agent-service-v1.21.0) (2026-08-21)


### Features

* **agent:** put the content-safety classifier on the operator plane too ([#6204](https://github.com/JiRaska/open-bank-oss/issues/6204)) ([13a21b5](https://github.com/JiRaska/open-bank-oss/commit/13a21b5835739937a16bcfdbb233381d305f3089)), closes [#5671](https://github.com/JiRaska/open-bank-oss/issues/5671)


### Bug Fixes

* **agent:** default the model endpoint to the LiteLLM gateway, not decommissioned Groq ([#6076](https://github.com/JiRaska/open-bank-oss/issues/6076)) ([026e071](https://github.com/JiRaska/open-bank-oss/commit/026e071106f6405b2343ed27a93210a8c800428c)), closes [#5736](https://github.com/JiRaska/open-bank-oss/issues/5736)
* **agent:** run the oversight sweep off the event loop — enforcement was degrading ([#6223](https://github.com/JiRaska/open-bank-oss/issues/6223)) ([55b538d](https://github.com/JiRaska/open-bank-oss/commit/55b538d95125a7fae0643266ecde8b38068526fc))

## [1.20.2](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.20.1...agent-service-v1.20.2) (2026-08-20)


### Bug Fixes

* **copilot:** record an LLM metric on every streaming outcome ([#5960](https://github.com/JiRaska/open-bank-oss/issues/5960)) ([f6fdb0f](https://github.com/JiRaska/open-bank-oss/commit/f6fdb0fc302acb7d84b288ee29d2d98b9de1e994))

## [1.20.1](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.20.0...agent-service-v1.20.1) (2026-08-20)


### Bug Fixes

* **agent:** correct the model-selection notes — I measured the cap, not the models ([#5923](https://github.com/JiRaska/open-bank-oss/issues/5923)) ([0e9c8af](https://github.com/JiRaska/open-bank-oss/commit/0e9c8af3399d43d32463ada59623f69dd3bc5cb2))

## [1.20.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.19.3...agent-service-v1.20.0) (2026-08-20)


### Features

* **copilot:** add model-based content-safety guardrail (Llama Guard) ([#5670](https://github.com/JiRaska/open-bank-oss/issues/5670)) ([e62a476](https://github.com/JiRaska/open-bank-oss/commit/e62a47656c403c9e9fcb608207313ec3e9c61a86))

## [1.19.3](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.19.2...agent-service-v1.19.3) (2026-08-17)


### Bug Fixes

* **agent:** back the PoP nonce replay guard with a shared Redis store ([#5137](https://github.com/JiRaska/open-bank-oss/issues/5137)) ([6931087](https://github.com/JiRaska/open-bank-oss/commit/69310874a5eecf514f6efe0866637c48aa209fb8))

## [1.19.2](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.19.1...agent-service-v1.19.2) (2026-08-16)


### Bug Fixes

* **observability:** track gauge refresh liveness ([#5087](https://github.com/JiRaska/open-bank-oss/issues/5087)) ([86904fa](https://github.com/JiRaska/open-bank-oss/commit/86904faa8ae0fdfd7e085b4c4f175691ae07c865))

## [1.19.1](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.19.0...agent-service-v1.19.1) (2026-08-16)


### Bug Fixes

* **agent:** make oversight scheduler live ([#5080](https://github.com/JiRaska/open-bank-oss/issues/5080)) ([3516598](https://github.com/JiRaska/open-bank-oss/commit/35165986663cfb09628ec2396da1e2ed32b46d98))

## [1.19.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.18.0...agent-service-v1.19.0) (2026-08-14)


### Features

* **product-catalog:** ship standalone platform and intelligence studio ([#4501](https://github.com/JiRaska/open-bank-oss/issues/4501)) ([cdd4af2](https://github.com/JiRaska/open-bank-oss/commit/cdd4af291b1f88500cdbcfdd3cf55dc316f94029))

## [1.18.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.17.0...agent-service-v1.18.0) (2026-08-09)


### Features

* **agent:** populate model_id on every AI-attributed audit event ([#3813](https://github.com/JiRaska/open-bank-oss/issues/3813)) ([0a4528c](https://github.com/JiRaska/open-bank-oss/commit/0a4528c95a08df85db9e822958d48f67c04ddb55))

## [1.17.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.16.3...agent-service-v1.17.0) (2026-08-07)


### Features

* **agent:** capture the charter-declared model id on every MCP tool-call audit event ([#3693](https://github.com/JiRaska/open-bank-oss/issues/3693)) ([9292c7d](https://github.com/JiRaska/open-bank-oss/commit/9292c7db493925530ce22b0a2638186de3d9eb3e)), closes [#3667](https://github.com/JiRaska/open-bank-oss/issues/3667)

## [1.16.3](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.16.2...agent-service-v1.16.3) (2026-08-02)


### Bug Fixes

* **infra:** make the per-service Dockerfiles honest, and keep them that way ([#3392](https://github.com/JiRaska/open-bank-oss/issues/3392)) ([21f2ff4](https://github.com/JiRaska/open-bank-oss/commit/21f2ff497fffb782162a5f8333ac6fff97d6c171)), closes [#3016](https://github.com/JiRaska/open-bank-oss/issues/3016)

## [1.16.2](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.16.1...agent-service-v1.16.2) (2026-08-02)


### Bug Fixes

* **agent:** @Path bound to a top-level function, so /mcp was never registered ([#3371](https://github.com/JiRaska/open-bank-oss/issues/3371)) ([644b2cf](https://github.com/JiRaska/open-bank-oss/commit/644b2cf27314dddb852af6b509c72481c52fcea0))

## [1.16.1](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.16.0...agent-service-v1.16.1) (2026-08-02)


### Bug Fixes

* **agent:** the admin assistant leaked its own system prompt — position, not wording ([#3228](https://github.com/JiRaska/open-bank-oss/issues/3228)) ([b52f989](https://github.com/JiRaska/open-bank-oss/commit/b52f989b4e24d4bf9489793bcf37cddb065dda2e))

## [1.16.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.15.3...agent-service-v1.16.0) (2026-08-01)


### Features

* **observability:** make fleet LLM spend and reliability observable in Prometheus ([#3043](https://github.com/JiRaska/open-bank-oss/issues/3043)) ([000ba2a](https://github.com/JiRaska/open-bank-oss/commit/000ba2a516069ba4c65b50015a76b4086b229b30))

## [1.15.3](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.15.2...agent-service-v1.15.3) (2026-07-31)


### Bug Fixes

* **governance:** correct 21 specs' dev port and gate it against quarkus.http.port ([#2697](https://github.com/JiRaska/open-bank-oss/issues/2697)) ([1d2f830](https://github.com/JiRaska/open-bank-oss/commit/1d2f8301d8b55664eed36860a0ec78717375a66b))

## [1.15.2](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.15.1...agent-service-v1.15.2) (2026-07-25)


### Bug Fixes

* **agent:** route the assistant LLM call through the in-cluster gateway (ADR-0174/0175) ([#2209](https://github.com/JiRaska/open-bank-oss/issues/2209)) ([bca0318](https://github.com/JiRaska/open-bank-oss/commit/bca0318ddc9fee5287b374eefab4d7c0f12960f6))

## [1.15.1](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.15.0...agent-service-v1.15.1) (2026-07-13)


### Bug Fixes

* **libs:** bump Quarkus BOM 3.33.2 -&gt; 3.37.2, refresh vulnerability pins ([#873](https://github.com/JiRaska/open-bank-oss/issues/873)) ([7de59e2](https://github.com/JiRaska/open-bank-oss/commit/7de59e27f618c5d923ba0cf519241ba675beb995))

## [1.15.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.14.1...agent-service-v1.15.0) (2026-07-11)


### Features

* **agent:** propagate a service token to product-catalog reads ([#401](https://github.com/JiRaska/open-bank-oss/issues/401) rollout) ([#745](https://github.com/JiRaska/open-bank-oss/issues/745)) ([b933ba9](https://github.com/JiRaska/open-bank-oss/commit/b933ba99bc9af16e2dc95b506905c0973f707a57))

## [1.14.1](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.14.0...agent-service-v1.14.1) (2026-07-09)


### Bug Fixes

* **agent-service:** wire Kafka mTLS + fix oversight-events group.id YAML bug ([#696](https://github.com/JiRaska/open-bank-oss/issues/696)) ([4e3c0b3](https://github.com/JiRaska/open-bank-oss/commit/4e3c0b35be8ee482a86110f0186e044b2bbba085)), closes [#686](https://github.com/JiRaska/open-bank-oss/issues/686)

## [1.14.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.13.2...agent-service-v1.14.0) (2026-07-08)


### Features

* **agent:** filter HITL proposals by agentId, document the endpoint ([#600](https://github.com/JiRaska/open-bank-oss/issues/600)) ([59dd0e7](https://github.com/JiRaska/open-bank-oss/commit/59dd0e7d1e0381ab4c6917f1e6b8a7bb131a5201))

## [1.13.2](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.13.1...agent-service-v1.13.2) (2026-07-07)


### Bug Fixes

* **account:** ktlintFormat AccountServiceLifecycleTest — Fleet lint red on main ([#480](https://github.com/JiRaska/open-bank-oss/issues/480)) ([37d303e](https://github.com/JiRaska/open-bank-oss/commit/37d303ef3e804f4cc30b79d8f0632ccfc2d942e7)), closes [#479](https://github.com/JiRaska/open-bank-oss/issues/479)

## [1.13.1](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.13.0...agent-service-v1.13.1) (2026-07-03)


### Security

* **agent-service:** sanitize policy-gate values before logging (CodeQL java/log-injection) ([#153](https://github.com/JiRaska/open-bank-oss/issues/153)) ([83fae6c](https://github.com/JiRaska/open-bank-oss/commit/83fae6c840338518b8189a42e0afc2b814f36885))
* **docker-svc:** pin all Dockerfile base images to digest (Scorecard Pinned-Dependencies) ([#103](https://github.com/JiRaska/open-bank-oss/issues/103)) ([27c2dab](https://github.com/JiRaska/open-bank-oss/commit/27c2dabe6b9f9440532edf29a73c6e48e677206b))

## [1.13.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.12.0...agent-service-v1.13.0) (2026-06-29)


### Features

* **agent:** D3b hardening — SVID CN cross-check + enforce (ADR-0031) ([#2488](https://github.com/JiRaska/open-bank-oss/issues/2488)) ([611618b](https://github.com/JiRaska/open-bank-oss/commit/611618b5c54f6b5d5b9dcd4387a1ff31bdb1df8a))
* **agent:** per-run OTel span for governed agent runs (ADR-0031 D7) ([#2385](https://github.com/JiRaska/open-bank-oss/issues/2385)) ([4423b45](https://github.com/JiRaska/open-bank-oss/commit/4423b45f5f9ca290be3ba86dab8804ba00e8ab38))


### Bug Fixes

* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2344](https://github.com/JiRaska/open-bank-oss/issues/2344)) ([b6fb6d5](https://github.com/JiRaska/open-bank-oss/commit/b6fb6d5a86a040f0dcc5f489d0b4c8d778c6ec50))
* **admin-ui:** build deps+build stages on native arch to avoid QEMU SIGILL on x86 ([#2358](https://github.com/JiRaska/open-bank-oss/issues/2358)) ([fab840a](https://github.com/JiRaska/open-bank-oss/commit/fab840aafcbc7b63782c8709f9d1bb34e3f4b0cd))
* **agent:** suppress UnusedParameter in OversightEventConsumer ([#2232](https://github.com/JiRaska/open-bank-oss/issues/2232)) ([52ff6d2](https://github.com/JiRaska/open-bank-oss/commit/52ff6d2224facb013ce772c37f9d12ac6f1a9376)), closes [#2084](https://github.com/JiRaska/open-bank-oss/issues/2084)
* **customer-edge:** add per-party rate-limit config key (ADR-0132) ([#2501](https://github.com/JiRaska/open-bank-oss/issues/2501)) ([213f528](https://github.com/JiRaska/open-bank-oss/commit/213f52818238585840a7dd18ad98066aebd135bb))
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([7295494](https://github.com/JiRaska/open-bank-oss/commit/72954940743f27bf7e49fede185ff20bc3e40060))
* **infra:** route docker.io CI pulls through ECR pull-through cache — zero NAT ([#2221](https://github.com/JiRaska/open-bank-oss/issues/2221)) ([52caaf2](https://github.com/JiRaska/open-bank-oss/commit/52caaf21097311e077e6ac011d388a7256769d89))
* **notification:** ROLE_CUSTOMER on DELETE, lastUsedAt sweep, IDOR scope ([#2485](https://github.com/JiRaska/open-bank-oss/issues/2485) follow-up) ([#2490](https://github.com/JiRaska/open-bank-oss/issues/2490)) ([4277222](https://github.com/JiRaska/open-bank-oss/commit/4277222937a768f0c3890ed7f57757d726ffa8a5))
* **release:** restore transaction-service entry in release-please manifest ([#2351](https://github.com/JiRaska/open-bank-oss/issues/2351)) ([7694897](https://github.com/JiRaska/open-bank-oss/commit/7694897b8f282fdd529175d24e0cb56139655839)), closes [#2342](https://github.com/JiRaska/open-bank-oss/issues/2342)


### Security

* **agent:** bind asserted X-Agent-Id to verified operator roles (ADR-0031 D3a) ([#2403](https://github.com/JiRaska/open-bank-oss/issues/2403)) ([132343d](https://github.com/JiRaska/open-bank-oss/commit/132343d9abfd7bb367ce1bf7df152125686aa6d8))
* **agent:** mint per-run pki-agent SVID in the BFF + base64 cert transport (ADR-0031 D3b) ([#2439](https://github.com/JiRaska/open-bank-oss/issues/2439)) ([1dce3db](https://github.com/JiRaska/open-bank-oss/commit/1dce3dbffc8b73accb902c7bef31865f0cc16402))
* **agent:** verify a PoP-signed pki-agent cert as the per-run agent identity (ADR-0031 D3b) ([#2412](https://github.com/JiRaska/open-bank-oss/issues/2412)) ([97e733c](https://github.com/JiRaska/open-bank-oss/commit/97e733c02699ac69c5ec469f00f4ee2ea39f00ac))

## [1.12.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.11.1...agent-service-v1.12.0) (2026-06-29)


### Features

* **agent:** D3b hardening — SVID CN cross-check + enforce (ADR-0031) ([#2488](https://github.com/JiRaska/open-bank-oss/issues/2488)) ([25c11b1](https://github.com/JiRaska/open-bank-oss/commit/25c11b134cc6ae4e30472424469e7a4a33533c08))

## [1.11.1](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.11.0...agent-service-v1.11.1) (2026-06-29)


### Security

* **agent:** bind asserted X-Agent-Id to verified operator roles (ADR-0031 D3a) ([#2403](https://github.com/JiRaska/open-bank-oss/issues/2403)) ([5b94bc3](https://github.com/JiRaska/open-bank-oss/commit/5b94bc3423c9a808e3c859ee8e485cff6dd97a37))
* **agent:** mint per-run pki-agent SVID in the BFF + base64 cert transport (ADR-0031 D3b) ([#2439](https://github.com/JiRaska/open-bank-oss/issues/2439)) ([e8d5c92](https://github.com/JiRaska/open-bank-oss/commit/e8d5c927d6f19d84c8c5eef5dc0df9a20718a011))
* **agent:** verify a PoP-signed pki-agent cert as the per-run agent identity (ADR-0031 D3b) ([#2412](https://github.com/JiRaska/open-bank-oss/issues/2412)) ([f2e3537](https://github.com/JiRaska/open-bank-oss/commit/f2e353757fd3255f0a3ffd2453be226a52546cb0))

## [1.11.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.10.1...agent-service-v1.11.0) (2026-06-28)


### Features

* **agent:** per-run OTel span for governed agent runs (ADR-0031 D7) ([#2385](https://github.com/JiRaska/open-bank-oss/issues/2385)) ([baa1ce6](https://github.com/JiRaska/open-bank-oss/commit/baa1ce68683e030bf62d2e5c2cd7b89d14161478))

## [1.10.1](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.10.0...agent-service-v1.10.1) (2026-06-27)


### Bug Fixes

* **agent:** suppress UnusedParameter in OversightEventConsumer ([#2232](https://github.com/JiRaska/open-bank-oss/issues/2232)) ([f8ff3d2](https://github.com/JiRaska/open-bank-oss/commit/f8ff3d29503c4be0e4905fbca516aebc3efb3586)), closes [#2084](https://github.com/JiRaska/open-bank-oss/issues/2084)
* **domestic-payment:** use ISO_LOCAL_DATE for valueDate in settlement ([#2237](https://github.com/JiRaska/open-bank-oss/issues/2237)) ([98f4e50](https://github.com/JiRaska/open-bank-oss/commit/98f4e502b116027bb12525b9c853044c39d30c53))

## [1.10.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.9.0...agent-service-v1.10.0) (2026-06-25)


### Features

* **tpp-registry,statement,onboarding,agent,settlement,sdd:** inject Clock (ADR-0100 Layer 1, Refs [#1612](https://github.com/JiRaska/open-bank-oss/issues/1612)) ([#2138](https://github.com/JiRaska/open-bank-oss/issues/2138)) ([baa0d03](https://github.com/JiRaska/open-bank-oss/commit/baa0d03bcef7a1cd48cb7e115410ab625a26acde))

## [1.9.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.8.0...agent-service-v1.9.0) (2026-06-25)


### Features

* **agent,admin-ui:** tag MCP tools with their service so the coverage grid is complete ([#744](https://github.com/JiRaska/open-bank-oss/issues/744)) ([#1860](https://github.com/JiRaska/open-bank-oss/issues/1860)) ([50ac1d3](https://github.com/JiRaska/open-bank-oss/commit/50ac1d33bbf9e5ab4b21ea0f1c7c7fdc458b3721))
* **agent:** add query.gl.readonly capability for GL-sensitive tools ([#1966](https://github.com/JiRaska/open-bank-oss/issues/1966)) ([5b32941](https://github.com/JiRaska/open-bank-oss/commit/5b32941c8c5cf0b12c4460ebc56adfa705cd53f3))
* **agent:** Kafka trigger for compliance-officer oversight sweep (ADR-0031 D9 P2) ([#1965](https://github.com/JiRaska/open-bank-oss/issues/1965)) ([9ec01cc](https://github.com/JiRaska/open-bank-oss/commit/9ec01cc8f34ff482e662cf07f1edf6a824633988))
* **c2-kover:** Kover coverage gate + anacredit oidc boot fix + AML FT interceptor fix (18 services) ([ad26ca7](https://github.com/JiRaska/open-bank-oss/commit/ad26ca7d58e62c8822e11f66f346926acc453058))


### Bug Fixes

* **lint:** resolve fleet-wide ktlint/compile violations (Refs [#1968](https://github.com/JiRaska/open-bank-oss/issues/1968)) ([#1971](https://github.com/JiRaska/open-bank-oss/issues/1971)) ([92dc2d6](https://github.com/JiRaska/open-bank-oss/commit/92dc2d636d857b526c8276e2647de440c540577b))

## [1.8.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.7.0...agent-service-v1.8.0) (2026-06-15)


### Features

* **agent:** autonomous compliance-officer oversight sweeps (ADR-0031 D9 phase 2) ([#844](https://github.com/JiRaska/open-bank-oss/issues/844)) ([7716c7c](https://github.com/JiRaska/open-bank-oss/commit/7716c7ccfe29cd6da79a2a097616ca4eeb61ebbd)), closes [#840](https://github.com/JiRaska/open-bank-oss/issues/840)
* **agent:** emit run-level AI attribution + proposal lifecycle audit (ADR-0031 D5) ([#837](https://github.com/JiRaska/open-bank-oss/issues/837)) ([943bff1](https://github.com/JiRaska/open-bank-oss/commit/943bff1de41a7ae94636f0c3307e54f962ea403b))
* **agent:** kill switch — config baseline + runtime break-glass (ADR-0031 D7) ([#987](https://github.com/JiRaska/open-bank-oss/issues/987)) ([39dbeea](https://github.com/JiRaska/open-bank-oss/commit/39dbeead5288c209ea532adcf186db3e7fbe582e))
* **agent:** observability read tools (Prometheus/Loki/Alertmanager) for compliance-officer ([#970](https://github.com/JiRaska/open-bank-oss/issues/970)) ([2e856f0](https://github.com/JiRaska/open-bank-oss/commit/2e856f05e648908cfb45195d5d65058a8b8e552c))
* **agent:** OIDC-authenticate the agent surface (ADR-0031 D3) ([#997](https://github.com/JiRaska/open-bank-oss/issues/997)) ([0fc6b52](https://github.com/JiRaska/open-bank-oss/commit/0fc6b52179c194e152bdfe73f7e74235024966f0))


### Bug Fixes

* **agent,balance,product-catalog:** unblock main CI — capability rename sync + /q/metrics registries ([#751](https://github.com/JiRaska/open-bank-oss/issues/751)) ([a561b91](https://github.com/JiRaska/open-bank-oss/commit/a561b91ee2f06ed71b23086a3a62d7db00a8c7ff))
* **dispute:** assign unique HTTP port 8135 (resolve collision with lending) ([#1045](https://github.com/JiRaska/open-bank-oss/issues/1045)) ([b219e93](https://github.com/JiRaska/open-bank-oss/commit/b219e93ac044ee2d7c4b8e352bbba17265c624dc))


### Security

* **agent:** prompt-injection guardrail in front of the reasoning loop (ADR-0031 D6) ([#845](https://github.com/JiRaska/open-bank-oss/issues/845)) ([c7e30f4](https://github.com/JiRaska/open-bank-oss/commit/c7e30f4ed1e8fabe574ec74d8c4c3316196b922e))

## [1.7.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.6.0...agent-service-v1.7.0) (2026-06-12)


### Features

* **agent:** add HITL proposal queue and MCP tool-execution audit (ADR-0031 D4/D8) ([#653](https://github.com/JiRaska/open-bank-oss/issues/653)) ([d169a24](https://github.com/JiRaska/open-bank-oss/commit/d169a244da39d8b304cce8ae89bf8df6d29ad028))
* **agent:** AI-attributed audit on MCP tool execution (ADR-0031 D5) ([#706](https://github.com/JiRaska/open-bank-oss/issues/706)) ([4787e8a](https://github.com/JiRaska/open-bank-oss/commit/4787e8ac88320b01b88d5f457cba6e77d4a397de))
* **domestic-payment:** wire DomainMetrics counters (ADR-0077 Phase 2 sweep) ([#684](https://github.com/JiRaska/open-bank-oss/issues/684)) ([7bc6633](https://github.com/JiRaska/open-bank-oss/commit/7bc663347fd81e5fe0f49076e7eb64055b4baa5e))
* **infra,agent:** feature-flag flip enforcement — CI gate + MCP tool (ADR-0067 / issue [#419](https://github.com/JiRaska/open-bank-oss/issues/419)) ([#758](https://github.com/JiRaska/open-bank-oss/issues/758)) ([96bfb7d](https://github.com/JiRaska/open-bank-oss/commit/96bfb7d506c9e2da22cde563ef8d676d77699019))


### Bug Fixes

* **security:** pentest P0 — auth internal endpoints + least-privilege agent (ADR-0078) ([#700](https://github.com/JiRaska/open-bank-oss/issues/700)) ([6d6866c](https://github.com/JiRaska/open-bank-oss/commit/6d6866c48f7692b8478b19ec5915392dd41675fd))
* **security:** pentest P2 — prompt-leak hardening, 1h session, AI-proposal warning (ADR-0078) ([#746](https://github.com/JiRaska/open-bank-oss/issues/746)) ([6c35555](https://github.com/JiRaska/open-bank-oss/commit/6c355558b10498efefbfeebabb40b36aeb0895cb))

## [1.6.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.5.0...agent-service-v1.6.0) (2026-06-10)


### Features

* **agent:** HITL proposal queue — draft_ticket tool + admin-ui approvals (ADR-0031 D4) ([#657](https://github.com/JiRaska/open-bank-oss/issues/657)) ([ba90e1b](https://github.com/JiRaska/open-bank-oss/commit/ba90e1bde400f3641630743f4779db1c17f5659e))
* **agent:** MCP read tools for aml/sanctions/fx/clearing/interest/dispute/sepa-instant ([#639](https://github.com/JiRaska/open-bank-oss/issues/639)) ([47e389c](https://github.com/JiRaska/open-bank-oss/commit/47e389c82bdb589761da2b7c9fa0b284dcfd0427))

## [1.5.0](https://github.com/JiRaska/open-bank-oss/compare/agent-service-v1.4.0...agent-service-v1.5.0) (2026-06-09)


### Features

* **admin-ui:** derive governance manifest from governance.yaml (ADR-0071 phase 2) ([#498](https://github.com/JiRaska/open-bank-oss/issues/498)) ([46c85e9](https://github.com/JiRaska/open-bank-oss/commit/46c85e98fe1e887eb82e2110efb5286fe0220d12))
* **agent:** D9 enforcement block mode + D2 charter rate-limiting + D4 proposal detection ([#311](https://github.com/JiRaska/open-bank-oss/issues/311)) ([1085787](https://github.com/JiRaska/open-bank-oss/commit/1085787a40a512033348c2d21d0fbbd052aa1eb3))
* **agent:** real-data assistant — service-to-service auth, fleet tools, free-tier hardening ([#290](https://github.com/JiRaska/open-bank-oss/issues/290)) ([a5548b6](https://github.com/JiRaska/open-bank-oss/commit/a5548b6182c6167f15aa1ee6a48862c1b5703b02))
* **build-logic:** convention plugin openbank.quarkus-service (ADR-0049 D1) ([#344](https://github.com/JiRaska/open-bank-oss/issues/344)) ([da71b7e](https://github.com/JiRaska/open-bank-oss/commit/da71b7e1705649c453b252c32fa06dc098210d63))


### Bug Fixes

* **gitops:** add Kafka value.deserializer env vars for payment services ([#366](https://github.com/JiRaska/open-bank-oss/issues/366)) ([b578775](https://github.com/JiRaska/open-bank-oss/commit/b57877557a04f6d4b7fe19bba90db3494eb6d6de))
* **gitops:** single-owner ArgoCD apps for product-catalog and audit-oidc ([#609](https://github.com/JiRaska/open-bank-oss/issues/609)) ([48959b1](https://github.com/JiRaska/open-bank-oss/commit/48959b1459fe696b05f0ec983a4daec3fce24207))
* **infra:** restore Keycloak login theme to dark blue/cyan ([#358](https://github.com/JiRaska/open-bank-oss/issues/358)) ([2e56cbc](https://github.com/JiRaska/open-bank-oss/commit/2e56cbc39dab44a2a7c6ed66edea533aebdca317))
