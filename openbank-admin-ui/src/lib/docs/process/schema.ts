// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ---------------------------------------------------------------------------
// Process manifest schema (ADR-0019 / ADR-0029). The canonical "one process,
// three lenses" shape that the auth-flow page proved as a vertical slice — now
// extracted so every process is authored once as data (src/content/processes/
// <slug>.yaml) and rendered by ProcessView, instead of being hardcoded per page.
//
// This Zod schema is the single contract: it INFERS the TypeScript types the UI
// consumes AND validates every manifest at build/test time (the loader calls
// `.parse`, so `next build` fails on a malformed or drifted manifest — that is
// the CI gate). Keep this in lockstep with ProcessView's rendering.
// ---------------------------------------------------------------------------

import { z } from 'zod'

// Honest maturity overlay: what actually runs vs what is on the roadmap.
export const StatusSchema = z.enum(['live', 'partial', 'planned'])

// Lens 1 — plain-language narrative; each step carries its own maturity so the
// reality/target toggle can hide planned steps.
const StoryStepSchema = z.object({
  text: z.string().min(1),
  status: StatusSchema,
})

// Lens 2 — the token exchange. `reality`/`target` are Mermaid `sequenceDiagram`
// sources; `claims` annotates the access-token (JWT) anatomy.
const JwtClaimSchema = z.object({
  claim: z.string().min(1),
  desc: z.string().min(1),
})
const TokenLensSchema = z.object({
  reality: z.string().min(1),
  target: z.string().min(1),
  claims: z.array(JwtClaimSchema),
})

// Lens 3 — component topology. Nodes carry an id (stable, for selection + a
// future service-graph.json cross-check), a maturity status and a one-liner.
const TechNodeSchema = z.object({
  id: z.string().regex(/^[a-z0-9-]+$/, 'node id must be kebab-case'),
  name: z.string().min(1),
  status: StatusSchema,
  desc: z.string().min(1),
})
const TechZoneSchema = z.object({
  title: z.string().min(1),
  accent: z.string().regex(/^#[0-9a-fA-F]{6}$/, 'accent must be a #rrggbb hex'),
  nodes: z.array(TechNodeSchema).min(1),
})

// Compliance scorecard — each control cites a regulation, a weight and a 0–100
// maturity. `why` is one honest line; it must NOT publish exploitable specifics
// (feedback_no_publish_security_leaks) — framed as maturity/roadmap.
const ControlSchema = z.object({
  name: z.string().min(1),
  regulation: z.string().min(1),
  pct: z.number().int().min(0).max(100),
  weight: z.number().positive(),
  status: StatusSchema,
  why: z.string().min(1),
})

export const ProcessSchema = z.object({
  // `slug` is injected from the filename by the loader, never authored twice.
  slug: z.string().regex(/^[a-z0-9-]+$/),
  title: z.string().min(1),
  subtitle: z.string().min(1),
  // lucide-react icon name shown in the page header (mapped in ProcessView).
  icon: z.string().min(1),
  story: z.array(StoryStepSchema).min(1),
  token: TokenLensSchema,
  tech: z.array(TechZoneSchema).min(1),
  controls: z.array(ControlSchema).min(1),
})

export type Status = z.infer<typeof StatusSchema>
export type StoryStep = z.infer<typeof StoryStepSchema>
export type JwtClaim = z.infer<typeof JwtClaimSchema>
export type TechNode = z.infer<typeof TechNodeSchema>
export type TechZone = z.infer<typeof TechZoneSchema>
export type Control = z.infer<typeof ControlSchema>
export type Process = z.infer<typeof ProcessSchema>

// Weighted compliance score (0–100), derived — never authored. Single source of
// the number the scorecard gauge shows.
export function overallScore(controls: Control[]): number {
  const wsum = controls.reduce((a, c) => a + c.weight, 0)
  if (wsum === 0) return 0
  return Math.round(controls.reduce((a, c) => a + c.weight * c.pct, 0) / wsum)
}
