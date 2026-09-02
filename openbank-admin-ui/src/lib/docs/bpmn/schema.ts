// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ---------------------------------------------------------------------------
// BPMN process-diagram schema (ADR-0019 / ADR-0029). Same data-as-contract
// philosophy as the "three lenses" ProcessSchema (auth-flow), but a different
// shape: these are spatial BPMN-2.0 flow diagrams (steps with x/y, sequence +
// async message flows), not narrative/token/compliance lenses. Each diagram is
// authored once as data (src/content/bpmn/<slug>.yaml) and rendered by BpmnView
// instead of being hardcoded in the page.
//
// This Zod schema is the single contract: it INFERS the TypeScript types the UI
// consumes AND validates every manifest at build/test time (the loader calls
// `.parse`, so `next build` fails on a malformed or drifted manifest — the CI
// gate). Keep it in lockstep with BpmnView's rendering.
//
// Async is a first-class concept here (the whole reason this replaced the
// hardcoded page): the real fleet is heavily event-driven (transactional outbox
// + Kafka, ADR-0003/0050). A flow with `kind: async` is an event/outbox edge
// (dashed, carries an optional Kafka `topic`); a step may `emits`/`consumes`
// domain-event topics; a node of type `event` is a message catch/throw point.
// ---------------------------------------------------------------------------

import { z } from 'zod'

// BPMN node kinds the renderer knows how to draw.
//   start    — start event (green circle)
//   end      — normal end event (blue circle)
//   end-err  — error/terminate end event (red circle)
//   gateway  — exclusive decision (diamond)
//   task     — activity (rounded rect, coloured by lane)
//   event    — intermediate message event (catch/throw), e.g. an event sink
export const StepTypeSchema = z.enum(['start', 'end', 'end-err', 'gateway', 'task', 'event'])

// Swimlane → colour family (resolved in BpmnView). Enumerated so a typo in a
// manifest fails the build rather than silently rendering as the default lane.
export const LaneSchema = z.enum([
  'compliance',
  'core',
  'psd2',
  'payment',
  'kyc',
  'identity',
  'platform',
  'cards',
  'aml',
])

const StepSchema = z.object({
  id: z.string().regex(/^[a-z0-9-]+$/, 'step id must be kebab-case'),
  type: StepTypeSchema,
  x: z.number(),
  y: z.number(),
  label: z.string().min(1),
  lane: LaneSchema.optional(),
  apis: z.array(z.string().min(1)).optional(),
  relatedServices: z.array(z.string().min(1)).optional(),
  // Event semantics (ADR-0050 transactional outbox): domain-event topics this
  // step publishes / is triggered by. Surfaced in the coverage table so the
  // async edges are not the only place the event-driven reality shows.
  emits: z.array(z.string().min(1)).optional(),
  consumes: z.array(z.string().min(1)).optional(),
})

const FlowSchema = z.object({
  from: z.string().min(1),
  to: z.string().min(1),
  // Edge caption (gateway branch like 'Ano'/'Ne', or a short note). For async
  // edges the `topic` is shown instead when present.
  label: z.string().optional(),
  // `sequence` = synchronous control flow (solid). `async` = event-driven /
  // outbox-dispatched edge (dashed) — the real cross-service choreography.
  kind: z.enum(['sequence', 'async']).default('sequence'),
  // Kafka topic carried by an async edge (e.g. openbank.party.events).
  topic: z.string().min(1).optional(),
})

export const BpmnProcessSchema = z.object({
  // `slug` is injected from the filename by the loader, never authored twice.
  slug: z.string().regex(/^[a-z0-9-]+$/),
  name: z.string().min(1),
  desc: z.string().min(1),
  regulation: z.string().min(1),
  steps: z.array(StepSchema).min(2),
  flows: z.array(FlowSchema).min(1),
  services: z.array(z.string().min(1)).min(1),
})

export type StepType = z.infer<typeof StepTypeSchema>
export type Lane = z.infer<typeof LaneSchema>
export type BpmnStep = z.infer<typeof StepSchema>
export type BpmnFlow = z.infer<typeof FlowSchema>
export type BpmnProcess = z.infer<typeof BpmnProcessSchema>
