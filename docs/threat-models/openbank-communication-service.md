# Communication service threat model

## Scope

ADR-0285 phases 2-3, plus the golden-set CRUD slice of phase 4 (D4). This service owns the
editable "style" layer (tone, formality, form of address, vocabulary, signature) and the
"playbook" layer (call scripts as a step tree, approved answers) of every conversational prompt,
for a closed, deploy-time persona catalogue. It also stores each persona's golden set —
question/expected-properties pairs D4 uses to replay a draft before publish — but does NOT yet
run that replay; see the residual risk below. It never stores the immutable safety "core" (that
stays git-only, per ADR-0285 D1) and never stores the customer's own data — the content is
bank-authored prose about *how* the bank talks and what it says in recurring situations, plus
test fixtures for checking that, not customer PII.

## Why this service exists, as a threat

A business editor with write access to any text the bot ultimately sends is a new prompt-injection
vector, structurally the same class of risk the `ui-assistant` v1 leak (#3187) came from. Two
independent, mandatory controls exist because of that (ADR-0285 D3), not one:

| Threat | Control |
|---|---|
| An editor writes instruction-shaped content into a style field ("ignore previous instructions", "developer mode", tool-name references, promised completed actions, amounts/rates stated as fact) | `CommStyleLinter` — a deterministic, closed regex rule set with a known-positive/known-negative test for every rule (`CommStyleLinterTest`). Never an LLM judgement: a guard is proven by what it rejects, and an LLM judge cannot be held to that test. Runs on every `draft()` call, before persistence. |
| An editor writes PII into a style field (IBAN, card PAN, birth-number-shaped strings) | Same linter, same call site — `iban-shaped`/`pan-shaped`/`national-id-shaped` rules. This service never legitimately needs a customer identifier in a style field, so any match is a rejection, not a mask. |
| A single compromised or careless editor publishes a weakened style straight to production | `commstyle.publish` is four-eyes-gated (ADR-0155 mechanism, wired exactly as `opsmessage.compose`): the call is paused until a *different* `ROLE_COMMS_APPROVER` decides the `PendingApproval`. Independently, the application layer refuses a caller who is the draft's own `maker` (`CommunicationStyleService.publish`) — two separate checks, not one control counted twice. |
| A compromised communication-service serves a poisoned "published" style to a consumer | Out of scope for THIS threat model to close: a consumer (`openbank-copilot-service`, phase 2b) must itself validate/bound what it composes into a system prompt, and the `core` layer that precedes it in composition order is immutable and git-only — the blast radius of a poisoned style is confined to tone/vocabulary, never to the safety rules, tool-routing or injection defence, which this service can never touch. |
| The service is unreachable when a consumer needs a style | Not this service's control to provide, but its contract: `GET /api/v1/personas/{key}/published` is read-only and side-effect-free, so a consumer's documented fallback (D5: cache + short TTL + revert to the git-registered baseline style) degrades gracefully rather than failing closed or open on garbage. |
| An editor writes injection-shaped, PII-shaped, or secret-shaped content into a call-script step or an approved answer | Identical linter, identical call site (`CommunicationPlaybookService.draft`) — the playbook layer is exactly as much an injection surface as style, and gets the same D3 control #1 with no exceptions carved out for it. |
| A single compromised or careless editor publishes a weakened playbook straight to production | `commstyle.publish` — the SAME action name and SAME four-eyes mechanism as style (D3's own text: "publishing a style OR playbook version"); no separate, weaker gate was introduced for playbook content. |
| Approved-answer search surfaces a not-yet-reviewed or stale answer to an operator | `searchApprovedAnswers` reads only `findPublished` — the four-eyes-gated, currently-live version; a DRAFT/IN_REVIEW answer is invisible to search by construction, the same way it is invisible to `GET .../published`. |
| An editor writes injection-shaped content into a golden-set `question` | Not treated as a threat here, deliberately: a golden-set entry is a TEST INPUT ("what might a customer ask"), and a legitimate entry may need to look adversarial to check the composed prompt's defence against it. `CommunicationGoldenSetService`'s KDoc names this explicitly. No four-eyes control applies for the same reason — see the residual risk below for what DOES bound the blast radius (nothing reads golden-set entries yet). |

## Trust boundaries

- **Caller ↔ service (OIDC).** Every endpoint requires a Keycloak-issued bearer token.
  `ROLE_COMMS_EDITOR` may draft/submit; `ROLE_COMMS_APPROVER` may publish/retire/decide
  approvals; `ROLE_API` (never `ROLE_SERVICE` — that principal type is unreachable, see
  `check-no-service-principal-type.sh`) is the read grant for consumer services.
- **Service ↔ Postgres.** An isolated `openbank_communication` database (CNPG, own
  NetworkPolicy scope); no other service reads or writes this schema directly.
- **Service ↔ Redis.** Holds only ephemeral `PendingApproval` records (24h TTL, ADR-0155) —
  no style content, no customer data.
- **Service ↔ OPA sidecar.** `commstyle.publish` / `commstyle.approval.{read,decide}` are
  `@Authorize`-annotated and evaluated by the shared `rest.rego` bundle; the only allow rules
  for these three actions require `HUMAN` + `ROLE_COMMS_APPROVER`/`ROLE_ADMIN` and explicitly
  exclude any `service-account-*` principal (rest.rego `commstyle-publish` /
  `commstyle-decide-publish-approval` / `commstyle-read-publish-approval`) — confirmed no M2M
  caller for the publish action, matching the `four_eyes.actions` guardrail requirement.

## Bootstrap

Ships at `replicas: 0` in gitops (mirrors `openbank-referral-service`'s convention for a brand
new, non-money-path service): activation is a separate, reviewed step once the image is signed
and attested.

## Residual risks

- **Event transport not wired.** `communication.persona.published.v1` (ADR-0285 D5) has no
  Kafka producer in this slice (`UnwiredCommunicationEventPublisher`, mirroring
  `openbank-referral-service`'s identical, already-accepted pattern) — every publish is dropped
  and counted (`openbank_communication_events_dropped_total`), never silently. Not a correctness
  gap: consumers refresh on a short TTL regardless (D5), so this only delays refresh. Wiring a
  real outbox → Kafka adapter is a fast follow.
- **Persona catalogue is Flyway-seeded, not editable.** By design (D2's "closed catalogue"
  argument, one level up from ADR-0176 D2) — an editor shapes a persona's *style*, never creates
  a new persona. If a future phase needs self-service persona creation, that is a new decision,
  not a gap in this one.
- **The linter is a lint, not a proof.** A sufficiently indirect phrasing could still evade every
  regex — this is a known, accepted property of any deterministic pattern set (documented in
  `CommStyleLinter`'s own KDoc) and is why the four-eyes control exists as a *second*, independent
  layer rather than the lint being trusted alone.
- **Approved-answer retrieval is keyword-only (term-overlap scoring), not the fleet's hybrid
  keyword+pgvector-semantic stack.** A deliberate, documented scope cut (`PlaybookAnswerSearch`'s
  own KDoc): the existing `HybridHelpRetrieval`/`HelpCorpusIndexer` stack lives entirely inside
  `openbank-copilot-service`'s own hexagon with no shared library and no cross-service index, so
  reusing it means standing up this service's own pgvector table and embedding-gateway wiring from
  scratch. copilot-service's own keyword-only mode is already "a first-class supported mode, not a
  degraded error state" when semantic search is off, so this is the same fallback tier that
  service ships as fully supported, not a shortcut invented here. Consequence worth naming: an
  operator's query must share literal terms with an approved answer to find it — a paraphrase or a
  synonym will not match. Semantic search is a scoped, straightforward fast-follow once this
  service has its own pgvector infrastructure (mirrors `EmbeddingProducer` +
  `PgVectorPassageIndex` + `HybridHelpRetrieval`'s RRF fusion exactly), never a gap silently left
  open.
- **Golden-set entries are stored but never replayed — D4's actual publish-blocking gate is not
  built in this slice, and nothing here changes that a publish today is four-eyes-only.** D4
  requires publishing a style/playbook draft to first replay it against the persona's golden set
  through "the real composing service" (core + style + playbook, on a synthetic customer) and
  block publication on a regression. Building that means the cross-service prompt-composition
  wiring ADR-0285's own delivery phases stage separately from phases 2-3's four-eyes-only publish
  (phase 4, after adoption groundwork), and is deliberately NOT part of this change — same
  judgement as the deferred `CopilotChatService.systemPrompt()` composition wiring (a live
  customer-facing safety prompt, needing careful eval-replay verification before any code touches
  it). Consequence: `CommunicationGoldenSetService`/`CommunicationGoldenSetResource` are pure CRUD
  today — an editor can create, list and delete entries, and nothing in the system ever reads one
  back except that same CRUD API. No blast radius exists yet because no consumer exists yet;
  wiring the replay engine is the next, separately-reviewed change, and IS the safety mechanism
  this slice's own scope cut is protecting — not a shortcut around it.
