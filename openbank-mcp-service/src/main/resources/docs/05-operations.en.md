# openbank-mcp-service — operations

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.

## account-service client

The read ports call account-service through the client generated from its `openapi.yaml` (`openbank-client-account`, ADR-0319, #13169) instead of a hand-written REST client. `AccountDirectory` reads both lookups (`GET /api/v1/accounts/iban/{iban}` and `GET /api/v1/accounts/{accountId}`, each `@Authorize(account.read)` downstream) through the shared `AccountClient` facade. A 404 still raises a `NotFoundException`, so the tool call is denied and never answered with an empty account; any other status or a failed call is thrown unchanged. Reads stay blocking, as the previous client was. An account answer without an `id` (IBAN lookup) or without an `accountNumber` (debtor lookup for a domestic payment) is treated as malformed and fails the call.

## Test profile configuration

`application.yaml` has a `%test` section that turns off the OIDC resource server (`quarkus.oidc.tenant-enabled`) and the outbound `oidc-client`, because `@QuarkusTest` classes run without Keycloak. It applies only under the test profile and does not change deployed behaviour.

## Business ownership advertised by tools/list

The tool `service` identity is unchanged. Its `domain` is the canonical English display
label from the taxonomy and service governance declarations packaged with this image
(ADR-0364), rather than an independently maintained MCP category. Account, balance and
statement reads therefore report **Accounts & deposits**; transaction reads report
**Financial accounting**, and consent reads report **Open banking**. The local proposal
orchestration belongs to **Channels**; this label does not make the proposal executable.

Payment confirmation spans the SEPA and domestic rails. It retains a null single-service
identity and reports **Payments** only while both source owners agree. Missing, unknown
or conflicting owners report **Unclassified**. The canonical taxonomy is a required build
resource; an image missing it is invalid and must not be promoted.

Both MCP registries copy the same authoritative ownership inputs during resource
processing. CI and deployment equivalence include those inputs, so a governance change
rebuilds each reader. Inspect the reviewed source and packaged `business-domains/`
resources when investigating metadata drift; do not replace the image metadata with
another hand-written domain map. Redeploy or roll back through the normal signed-image
and GitOps workflow. No database migration, consent grant, event replay, capability
change or payment execution is part of this ownership correction.

## Tool-result sanitisation (ADR-0383 D7)

Every tool result passes three steps in `McpToolRegistry.call`, in this order: `McpPiiMasker`
(field-name and whole-value masking), `McpToolResultSanitiser` (D7), then `McpUntrustedData.wrap`.
The sanitiser walks every string value and every object key in the result. It does not use a list
of free-text field names, so a field a new tool adds is covered without an edit here. For each
string it:

- cuts the input to 560 code points (4 x the cap) before any other step, so every later step runs on
  bounded input, and every pattern is linear;
- applies Unicode NFKC normalisation;
- removes control, format (zero-width, bidi, word joiner, BOM), Unicode tag, private-use and
  line/paragraph separator code points (a line break becomes a space);
- decodes percent-encoded ASCII and HTML entities once, then repeats the step above;
- replaces markdown image and link syntax, reference definitions, HTML tags, autolinks, URLs
  (scheme, `www.` and protocol-relative `//host`) and `javascript:`/`data:`-style schemes with a
  placeholder, and again on the text with combining marks removed when markup only shows there;
- masks IBANs inside free text (country code with its ISO 13616 registry length, any case,
  separated by space, dot, hyphen or slash) and phone numbers (3-3-3 or 3-2-2-2, optional
  `+CC`/`00CC`, separated by space, dot or hyphen). It skips both for identifier and amount fields
  (`*Id`, `id`, amount, currency, date, balance), so an id a model chains into the next call is never
  altered;
- neutralises look-alikes of the untrusted-data markers, matched on a skeleton (NFD, combining marks
  dropped, case folded, Cyrillic/Greek/Armenian/Cherokee confusables mapped to Latin, any
  separator between the words);
- caps the result at 140 characters (the SEPA unstructured remittance maximum) and ends a cut
  value with `…`.

IBANs are checked by country and length, not by the ISO 7064 mod-97 checksum. An RF creditor
reference passes mod-97 and is not an account, and a mistyped IBAN fails it and still identifies
one. `McpPiiMasker` uses the same check for whole values, so it no longer masks an RF reference.

A `logoUrl` field is the only URL kept. It survives only as `https` on a host in
`McpToolResultSanitiser.CDN_HOSTS`, with no query, fragment or userinfo. Any other value removes the
field. There is no configuration toggle.

The red-team pack (`src/test/resources/redteam/`) measures these properties, and `known-gaps.json`
lists what is still open. Today that is one item: a natural-person name inside free text, which has
no shape to match on.
