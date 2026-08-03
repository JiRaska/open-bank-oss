---
date: 2026-08-03
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, privacy-gdpr, database]
summary: "Copilot memory ships in two tiers — resumable conversation history (default on, TTL-bounded) and explicit opt-in long-term facts (user-editable) — never implicit profiling, never remembered financial state, erased at PARTY_ERASED speed."
---

# ADR-0238 — Copilot conversation memory: two tiers, explicit only

## Context

`openbank-copilot-service` (ADR-0089) holds session context in Valkey, PII-masked, bounded to a
session; the KMP app's `BotService` is stateless per message. Every conversation therefore starts
from zero: a customer who explained their situation yesterday re-explains it today, the assistant
cannot honour "always ask me before a payment over X", and the finance-coach track (ADR-0203 D5)
has nothing to build continuity on. The app-side exploration for the ecosystem programme named
this the single biggest copilot gap after tool coverage.

Three forces decide the *shape*, not just the existence, of memory in a bank:

- **Persistence is an injection vector.** A remembered string is content the model will trust in
  every future prompt. "Remember that my refund IBAN is …" is a durable prompt-injection /
  social-engineering primitive if memory writes are inferred rather than explicit — the
  persistence-as-injection class the MCP estate already wraps with an untrusted-data marker
  contract (#2412).
- **Implicit memory is profiling.** Deriving facts about a customer from their messages without a
  told-them-so is profiling under GDPR, squarely against the conduct posture the engagement
  estate codifies (ADR-0220's "safety rules as domain invariants", ADR-0176's content
  allow-list), and indefensible to a regulator when the product is a bank.
- **Remembered financial state is a correctness violation.** A balance, limit or transaction
  recalled from memory breaks the model-proposes/bank-disposes invariant (ADR-0089): figures come
  from tool results at answer time, never from anything stored — parametric or persisted.

Today nothing ships: no cross-session history, no long-term memory, and the copilot threat
model's data-minimisation posture (PII minimiser, sandbox synthetic-only, prod EU/in-cluster) is
the governing constraint any design inherits.

## Decision

We will add conversation memory to `openbank-copilot-service` in **two tiers, both stored in the
service's own Postgres** (ADR-0009 database-per-service; Valkey remains a hot cache only), with
the safety rules below as domain invariants, tested as domain rules — not stated as content
policy.

**T1 — Conversation history (default on).** Full message history per conversation, resumable
across sessions and devices, retained on a rolling TTL (default 90 days), hard-deletable per
conversation by the customer. Default-on is honest here: the customer explicitly used the chat,
and a visible, deletable history of what they themselves said is the same posture as internet
banking message threads — it is not profiling.

**T2 — Long-term memory (explicit opt-in, off by default).** A small set of user-facing *facts*
(preferences: language, channel, "always confirm payments first"), each row user-inspectable in
the app with its origin and age, individually deletable, and wipeable as a whole. T2 is **never
written by inference**: a fact persists only from an explicit user statement ("remember that…",
a settings toggle) or from a model-*proposed* candidate the customer explicitly confirms — the
same HITL shape as payment proposals.

**Invariants (domain-enforced):**

1. **No financial state in memory.** Balances, transactions, limits, IBANs and amounts are
   answered from tool calls at answer time, always. A T2 write carrying financial identifiers or
   amounts is rejected by construction (validator), so "remember my balance is …" cannot exist —
   the model-proposes/bank-disposes invariant extended from the prompt to the store.
2. **Memory is untrusted input.** Every memory item injected into a future prompt is wrapped in
   the untrusted-data marker contract (#2412) and covered by the prompt-injection eval suite
   (ADR-0148) — persistence raises the poisoning blast radius, so the marker and the evals are a
   precondition for T2, not a follow-up.
3. **Erasure at event speed.** `PARTY_ERASED` wipes both tiers for the party (same fleet-wide
   consumer pattern as kyc/notification/card-issuance); per-conversation and per-fact deletes are
   hard deletes, never tombstones.
4. **Residency and egress unchanged.** Memory rows live in the copilot Postgres inside the EU
   estate (ADR-0175); the model sees only the minimal facts injected per call, under the ADR-0089
   D6 hosting posture (sandbox synthetic-only, production in-cluster or EU zero-retention).
   Memory content never becomes a separate egress path.
5. **Transparency (EU AI Act).** The app ships a "what the assistant remembers" surface; T2 facts
   show origin and age. An AI that remembers without showing what is not a compliant design in
   this estate.
6. **No cross-domain reuse.** Memory is input to the copilot only — never to NBA ranking
   (ADR-0201), campaigns/contact policy (ADR-0219/0220), or any credit outcome (ADR-0142). Those
   consume governed signals, not chat-derived state.

**Phasing.** T1 ships first (continuity has the weakest risk surface). T2 ships only after the
marker wrapping, the poisoning evals and the PARTY_ERASED wiring are proven in CI — an
inauthentic placeholder is worse than a missing feature (ADR-0220 D5's rule, applied here too).

## Alternatives considered

- **Stay stateless (status quo).** Correct for phase 1 and this ADR keeps it until T1 lands, but
  as the forward position it strands every continuity and coaching use case the platform has
  already decided to build (ADR-0203 D5, ADR-0222) and leaves the app permanently behind
  table-stakes assistant UX. Rejected as the end state.
- **Implicit, inferred memory (auto-extract facts from chat, ChatGPT-style).** Rejected: profiling
  without a told-them-so lawful basis, an unbounded poisoning surface (the model decides what to
  believe about the user, then trusts it later), and exactly the dark-pattern-adjacent mechanics
  ADR-0220 bars from engagement. If inference is ever wanted, it enters only through the T2
  propose-and-confirm path — which is this ADR's design, not a shortcut around it.
- **Client-side memory (the app stores history locally).** Rejected: lost on reinstall, broken
  across devices, invisible to GDPR erasure, outside the audit chain (ADR-0086), and un-auditable
  for the injection invariants above. Memory the bank cannot erase is memory the bank must not
  hold.
- **Vector search over past conversations (embed chat history).** Rejected for now: it turns the
  conversation log into a derived profiling dataset with none of T2's inspectability, and the
  corpus-size trigger logic of ADR-0183 does not support it. A future ADR may revisit retrieval
  over *history* once T1 exists and the residency posture for embeddings is settled.

## Consequences

**Positive**
- Continuity and resumable conversations across sessions and devices; the finance-coach and
  relationship-manager tracks (ADR-0203 D5, ADR-0222) get their continuity substrate.
- Memory a regulator can be shown: explicit, inspectable, editable, erasable, never financial
  state, never inferred — the same "safety as domain invariant" posture as ADR-0220.
- Injection surface bounded by construction: untrusted-data markers + evals are T2's gate, so
  poisoning resistance is testable in CI rather than aspirational.

**Negative**
- Two new stores of personal data in copilot-service (history, facts) with retention and erasure
  duties; DPIA update required before production.
- T2's propose-and-confirm UX adds friction to memory creation — deliberately; friction here is
  the control.

**Neutral**
- Valkey stays as the session hot cache; Postgres becomes the system of record for both tiers, so
  backup/DR inherits the CNPG posture with no new runtime.
- The app gains one settings surface (memory controls) and one read surface ("what the assistant
  remembers") — client work in `openbank-app`, behind the same feature-flag regime as the
  copilot itself (ADR-0067).

## Compliance impact

- PCI DSS: not applicable — no card data; the no-financial-state invariant keeps PAN-shaped
  content out by construction.
- DORA:    no new third party and no new datastore — memory rides the existing CNPG estate already
  in the ADR-0174 register.
- GDPR:    memory is personal data. Lawful basis is the customer's explicit use/request (T1) and
  explicit opt-in (T2); Art. 17 satisfied by PARTY_ERASED at event speed plus hard per-item
  deletes; retention is TTL-bounded; residency unchanged (ADR-0175). DPIA update required pre-prod.
- PSD2 / SCA / RTS: not applicable — memory never authorises anything; payment proposals keep the
  ADR-0089 SCA regime untouched.
- AML / 5AMLD: not applicable.
- CNB reporting: not applicable.
- EU AI Act: transparency obligation met by the "what the assistant remembers" surface (Art. 52
  lineage); memory-enabled copilot behaviour joins the ADR-0148 eval gate, incl. the
  poisoning suite above.

## References

- [ADR-0089](0089-customer-facing-ai-assistant.md) — the assistant this extends; model-proposes/bank-disposes.
- [ADR-0148](0148-ai-assurance-prompt-registry-evals-eu-ai-act.md) — eval gate the poisoning suite joins.
- [ADR-0175](0175-data-residency-and-sovereignty.md) — residency posture both tiers inherit.
- [ADR-0183](0183-pgvector-retrieval-augmentation-for-the-copilot-knowledge-base.md) — retrieval over the *help corpus*; deliberately not over chat history.
- [ADR-0203](0203-business-plane-ai-agents.md) — finance-coach (D5), the first consumer of continuity.
- [ADR-0220](0220-in-app-engagement-surfaces-gamification-and-pre-approved-offers.md) — "safety rules as domain invariants" posture this mirrors; "an inauthentic placeholder is worse than a missing feature".
- [ADR-0222](0222-offer-explanation-and-relationship-manager-agents.md) — relationship-manager agent, the second consumer.
- `docs/threat-models/openbank-copilot-service.md` — data-minimisation posture this design inherits and must update when T1/T2 ship.
