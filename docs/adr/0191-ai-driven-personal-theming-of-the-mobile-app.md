---
date: 2026-07-24
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [openbank-app]
tags: [mobile-app, ai-agents, customer-edge, architecture]
summary: "The app's look becomes a per-customer, AI-generated design-token document (ThemeSpec) validated by a deterministic on-device guardrail; the LLM designs within the token system via the existing copilot relay and never emits layout or code."
---

# ADR-0191 — AI-driven personal theming of the mobile app

## Context

The app already has a "Vzhled" studio (`ObDesignStudioOverlay`): six curated
presets, five accent profiles, dark/light, corner-radius scale, density, and a
natural-language input ("řekni, jak chceš vypadat"). The NL path is a local
keyword matcher (`parseDesignCommand` / `BotService.parseLocalThemeCommand`)
emitting a small closed `ThemeCommand` set; the copilot relay
(ADR-0065 edge BFF, ADR-0089 copilot-service) is already wired for the online
path but the theme vocabulary it can express is the same five accents.

Product goal: every customer can make the app *theirs* — "chci to jako západ
slunce nad Lipnem, ale ať čísla zůstanou vážná" should produce a coherent,
personal, still-bankable design. No mainstream CZ banking app offers
AI-personalised appearance; this is a visible differentiator that also
compounds an anti-phishing property: a fake login screen cannot know what
*your* bank app looks like.

Forces pulling against each other:

- **Expressiveness vs. trust.** A bank app that can look like anything can
  also be made illegible, inaccessible, or scam-adjacent. Money must always
  read as money.
- **AI creativity vs. determinism.** LLM output is unbounded; rendering and
  accessibility must be bounded and testable.
- **Personalisation vs. fleet cost.** Every new visual degree of freedom
  multiplies the QA surface of ~40 screens.
- **Device-local vs. roaming.** Today the theme is a device-local pipe-string
  (`obSaveTheme`); customers expect their look to follow them across devices
  and reinstalls.

## Decision

We will make the app's appearance a **data document, not code**: a versioned,
schema-validated **ThemeSpec** of design tokens, and let the AI *design within
the token system* — never emit layout, components, or code.

1. **ThemeSpec v2 (shared module).** Replace the closed 5-accent
   `ThemeCommand` vocabulary with a serialisable token document:
   - `palette` — arbitrary accent as HSL (derived tones `glow/deep/tint`
     computed deterministically on device, not chosen by the model), optional
     surface tint (bounded chroma), light/dark/auto mode.
   - `shape` — continuous radius scale (clamped 0.4–2.0), density.
   - `typography` — choice from a vetted, bundled font-pair list (Space
     Grotesk/Mono default + 2–3 licensed alternatives); scale nudge ±10 %.
   - `decor` — named, hand-built decoration packs (gradient headers, textures,
     seasonal accents), referenced by id, never generated pixel content.
   - `meta` — name, emoji, generation prompt hash, schema version.
   `ThemeState` remains the applied runtime form; `ThemeSpec` is the stored
   and transported form.

2. **AI generation stays server-side, structured, and privilege-free.** The
   design conversation goes through the existing copilot relay
   (`POST /customer/v1/bot/chat`, ADR-0065). copilot-service gains a
   `theme_designer` tool: the model receives the customer's current ThemeSpec
   plus the utterance and must return a full ThemeSpec JSON (schema-enforced
   tool call), plus a one-line Czech/English rationale for the chat log. The
   app never talks to the model directly, no new scopes, no new service. The
   local keyword parser is kept verbatim as the offline fallback.

3. **A deterministic on-device guardrail owns the final word.** A pure-Kotlin
   `ThemeSpecValidator` in the shared module runs on every spec regardless of
   origin (AI, preset, import):
   - WCAG-derived contrast repair: body text ≥ 4.5:1, large text ≥ 3:1 against
     computed surfaces; the validator *adjusts lightness* to comply rather than
     rejecting, so the AI's intent survives.
   - Semantic invariants: positive/negative amount colours, error/danger red,
     and status colours are non-themeable; monetary figures always render in
     the mono numeric face.
   - Clamps on radius, density, scale nudge, surface chroma.
   Because the validator is deterministic and shared, it is unit-testable and
   identical on both platforms.

4. **Trusted-surface carve-out.** SCA approval sheets, the signing ceremony,
   PIN/app-lock, and card-number reveal render with the customer's accent but
   a **locked layout, typography, and iconography** ("trusted style"). A theme
   can never restyle the surfaces where the customer authorises money or
   identity. This is the banking-specific boundary that free-form theming
   would erase.

5. **Roaming persistence.** ThemeSpec is stored per party via a small
   customer-edge preferences endpoint (`GET/PUT /customer/v1/preferences/theme`)
   and cached device-locally (replacing the pipe-string with versioned JSON,
   with a one-time migration). Offline-first: local wins until sync. Last N
   specs are kept as an on-device history for one-tap undo ("Vrátit").

6. **Delivery in three phases**, each shippable alone:
   - **P1 — token system**: ThemeSpec v2 + validator + arbitrary accent +
     typography choice + roaming persistence + undo history. No AI change yet;
     the studio's colour picker and presets get the full space.
   - **P2 — AI designer**: `theme_designer` tool in copilot-service,
     conversational refinement ("tmavší", "míň křiklavé") diffing against the
     current spec, LLM-seeded "Překvap mě".
   - **P3 — delight**: decor packs, seasonal drops, alternate app icons (iOS
     alternate-icon set), shareable "theme cards" (export/import a ThemeSpec
     as QR/deeplink — import passes the same validator).

## Alternatives considered

- **Free-form generated UI (server-driven layout or generated Compose code).**
  Maximum expressiveness; rejected outright: untestable QA surface, app-store
  review risk, accessibility unenforceable, and a themable SCA surface is a
  phishing-shaped hole. The token boundary is the decision.
- **On-device LLM.** Private and offline; rejected for now: multiplatform
  runtime cost, model quality for Czech design chat, and the copilot relay
  already exists with auth, quotas, and audit. Revisit if an on-device tier
  lands for other features.
- **Bank-curated theme packs only (no AI).** Cheap, safe, zero new backend;
  rejected as the headline feature — it is exactly what competitors ship.
  Retained anyway as P1 presets and the offline fallback, so the AI path is an
  enhancement, not a dependency.
- **Direct model API from the app.** Rejected — would put a model key or a new
  auth surface in the client; ADR-0065 exists precisely to avoid this.

## Consequences

**Positive**
- Visible market differentiator; appearance becomes a retention and identity
  feature, and a soft anti-phishing signal.
- Token document + deterministic validator keeps the whole space unit-testable
  and screenshot-testable at the extremes (min/max radius, worst-case
  contrast, both modes).
- No new service, no new client privilege; reuses copilot relay, auth, and
  conversation UX already in production.

**Negative**
- `ObTheme.kt`'s static object graph becomes computed-from-spec; a one-time
  refactor touching every colour token consumer.
- QA matrix grows: snapshot tests must run against validator boundary specs,
  not just presets.
- Support burden of "my app looks broken" — mitigated by prominent Reset and
  server-side history.
- Licensed font alternatives add binary size and a licensing checklist item.

**Neutral**
- ThemeSpec is versioned; older app versions ignore fields they don't know
  (forward-tolerant decoding), so server-stored specs never brick a client.
- The keyword parser remains as offline fallback and grows no further.

## Compliance impact

- PCI DSS: not applicable — no cardholder data involved; card-reveal surface
  is explicitly in the locked trusted style.
- DORA:    not applicable — no new ICT third party; model access stays within
  the existing copilot relay arrangement.
- GDPR:    theme preferences and design-chat utterances are personal data tied
  to the party; stored via the existing preferences/GDPR export-and-erasure
  flows, and raw prompts follow the copilot logging policy (no new log sinks).
- PSD2:    SCA surfaces are excluded from theming by the trusted-surface
  carve-out; authorisation UX is unchanged.
- CNB:     not applicable.

## References

- ADR-0065 — customer-edge BFF relay for model access
- ADR-0089 — customer-facing AI assistant (copilot-service)
- ADR-0183 — pgvector retrieval for the copilot knowledge base
- `openbank-app` — `shared/.../bot/ThemeCommand.kt`, `composeApp/.../ui/theme/ObTheme.kt`,
  `composeApp/.../ui/DesignStudioScreen.kt` (current implementation)
