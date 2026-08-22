// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Charter-backed agent identity for the approval queue (issue #5904).
//
// WHAT THIS REPLACES
// /approvals decided whether a queued proposal was agent-made with
// `/assistant|agent|\bai\b/i.test(proposal.proposedBy)` — a regex over a free-text string
// the row happened to carry. That is a guess dressed as provenance: "risk-agent-review"
// (a human desk) matches, a real charter id like `compliance-officer` or `pid-verifier`
// does not, and either way the badge asserts something the bank cannot evidence.
//
// WHERE THE IDENTITY COMES FROM
// openbank-libs/governance/agents.yaml is the enforced registry (ADR-0031 D1) — the same
// document the OPA agent bundles are derived from, so it is what actually decides what an
// agent may do. If a proposer is not in it, the bank has no charter for that actor and the
// queue must SAY so rather than render a plausible-looking name.
//
// THREE STATES, ON PURPOSE
//   chartered    — the proposer maps to an agents.yaml entry; show what the charter says.
//   unresolved   — the registry was read and contains no such id. Real evidence of absence.
//   unverifiable — the registry could not be read at all (not bundled into the image, or
//                  the route failed). NOT the same as absence, and must not render as it:
//                  an unreadable registry would otherwise mark every agent proposal as
//                  "not a chartered agent", which is the reassuring-looking wrong answer.

/** The charter fields the queue renders. Subset of lib/governance/agentCharters.ts. */
export interface CharterIdentity {
  id: string
  plane: string
  charter: string
  requiresHuman: string[]
}

export interface AgentIdentityRegistry {
  /** False when agents.yaml could not be read — the registry is absent, not empty. */
  available: boolean
  agents: CharterIdentity[]
}

export type AgentIdentity =
  | { status: 'chartered'; raw: string; charter: CharterIdentity }
  | { status: 'unresolved'; raw: string }
  | { status: 'unverifiable'; raw: string }

/**
 * Identity forms a proposal's `proposedBy` can legitimately take for the SAME charter.
 * Deliberately a closed list of exact rewrites — no substring or fuzzy matching, because a
 * near-miss that resolves is worse than one that shows as unresolved.
 */
function candidateIds(raw: string): string[] {
  const v = raw.trim().toLowerCase()
  if (!v) return []
  const forms = new Set<string>([v])
  // Keycloak service accounts: `service-account-<clientId>`.
  if (v.startsWith('service-account-')) forms.add(v.slice('service-account-'.length))
  // Runtime-qualified forms seen on proposal rows.
  if (v.startsWith('agent:')) forms.add(v.slice('agent:'.length))
  if (v.startsWith('openbank-')) forms.add(v.slice('openbank-'.length))
  for (const f of [...forms]) if (f.endsWith('-service')) forms.add(f.slice(0, -'-service'.length))
  return [...forms]
}

export function resolveAgentIdentity(
  proposedBy: string | null | undefined,
  registry: AgentIdentityRegistry | null,
): AgentIdentity {
  const raw = (proposedBy ?? '').trim()
  // No registry (still loading, fetch failed, or agents.yaml absent from the image) — we
  // cannot claim the actor is unchartered, only that we could not check.
  if (!registry || !registry.available) return { status: 'unverifiable', raw }
  if (!raw) return { status: 'unresolved', raw }

  const byId = new Map(registry.agents.map(a => [a.id.toLowerCase(), a]))
  for (const candidate of candidateIds(raw)) {
    const hit = byId.get(candidate)
    if (hit) return { status: 'chartered', raw, charter: hit }
  }
  return { status: 'unresolved', raw }
}
