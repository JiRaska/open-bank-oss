# KYB source integration evidence — 2026-09-26

Status: incomplete local synthetic integration evidence; not scenario acceptance or sandbox delivery.

The KYB and Party runtime packages were built from `6e72a6e489` and copied with machine-local SHA-256 manifests. Both used separate new databases owned by the normal application role. Actual Keycloak client-credentials tokens identified `service-account-openbank-kyb` and `service-account-openbank-party`, each with only `ROLE_API`. The unchanged Context, Fraud, KYB and Party OPA policies passed validation together; authorization remained enforced.

## Proven source flow

A controlled HTTPS Companies House wire fixture served company profile, officers, PSC owners and PSC statements. This tests the production registry adapters against synthetic input, not the live external register. ARES was deliberately not treated as a UBO source. TLS verification remained active. Party used a named TLS configuration with CA trust and required client certificates: the client-certificate handshake passed, while a bearer-free request returned 403.

The actual Party API created a synthetic initiator (201, `SYNTHETIC`). A staff API call set its KYC status for fixture identity matching; this is not proof of an external KYC verification workflow. The actual KYB API created one `REGISTRY_VERIFIED` case. Initiator matching and declarations returned 200, with the case reaching `READY_TO_SIGN`. One UBO observation was persisted; its reference outbox and the registry-verification outbox both reached `SENT`. Context contained one KYB observation reference.

The initially attempted runtime Temporal disable was ineffective because adapter selection is a build-time decision. A dedicated real local Temporal server and actual KYB worker were subsequently started. The existing case was preserved and reused, rather than creating a replacement to conceal the failed response. Timer/workflow lifecycle completion has not been independently asserted.

## Unresolved authorization latency

The actual maker proposal did not pass: attempts returned a Context SQL deadline error or 503 from an OPA request timeout. No assignment was inserted directly into the database. The original 500 ms SQL/OPA limits were retained.

Direct OPA samples measured 765, 166, 69, 16 and 4 ms and consistently allowed the assignment action. A Java HttpClient probe using the same 500 ms request budget showed an initial connection timeout and successful warmed HTTP/1.1 and HTTP/2 requests. A later direct replay of the captured synthetic assignment-action input returned the expected policy allow but took 564 ms; a diagnostic forwarding relay took 1779 ms. The relay therefore adds material measurement overhead and is not performance proof. The running JVM's selected OPA environment was verified. An event-loop thread snapshot showed idle selector stacks at that observation, which does not exclude earlier blocking or scheduling delays.

The local host was concurrently busy with other processes; these observations do not establish production capacity or a definitive root cause. They do not justify increasing security budgets or bypassing policy decisions.

## Remaining acceptance

Still required: successful exact-case maker/checker assignment, assigned-reader history and source detail, unassigned/incorrect-role denial, correction lineage, restriction suppression including historical reads, browser UI, independent workflow assertions, deployed Kafka ACLs, Audit ingestion, annual 1x/10x performance, required review and sandbox delivery. The other banking scenarios remain outside this evidence. Request bodies, tokens, certificates and synthetic screenshots stay machine-local.
