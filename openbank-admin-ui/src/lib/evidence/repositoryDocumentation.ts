// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod'

export interface RepositoryDocument { path: string; markdown: string }
export interface RepositoryFlag {
  service: string; key: string; state: 'ENABLED' | 'DISABLED'; defaultVariant: string
  variants: Record<string, unknown>; targeted: boolean; classification: 'money-path' | 'feature'
}
export interface RepositoryEvents {
  schema: 'openbank.events/v1'; generator: 'generate-events.mjs'; source: string; collectedAt: string | null; available: true
  topics: Array<{ channel: string; topic: string; description: string | null; publishers: string[]; consumers: string[]; color: string }>
  unmatched: string[]
}
export interface RepositoryDocumentation {
  schema: 'openbank.repository-documentation/v1'
  adrs: RepositoryDocument[]; threatModels: RepositoryDocument[]; agentCharters: RepositoryDocument[]
  events: RepositoryEvents; featureFlags: RepositoryFlag[]
}

const bounded = (maxBytes: number) => z.string().refine(value => Buffer.byteLength(value, 'utf8') <= maxBytes)
const text = bounded(4096).refine(value => value.trim().length > 0)
const id = z.string().min(1).max(256).regex(/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/)
const controls = /[\x00-\x1f\\]/
const documents = (corpus: string) => z.array(z.object({
  path: z.string().max(512).refine(value => !controls.test(value)
    && (corpus !== 'adr' || /^docs\/adr\/\d{4}[-.]/.test(value))
    && (corpus !== 'agents' || value !== 'docs/agents/README.md')
    && new RegExp(`^docs/${corpus}/[a-zA-Z0-9][a-zA-Z0-9_.-]*\\.md$`).test(value)),
  markdown: bounded(256 * 1024).refine(value => value.length > 0),
}).strict()).min(1).max(1000).refine(rows => new Set(rows.map(row => row.path)).size === rows.length)
function boundedJson(value: unknown, depth = 0): boolean {
  if (depth > 8) return false
  if (value === null || typeof value === 'boolean') return true
  if (typeof value === 'number') return Number.isFinite(value)
  if (typeof value === 'string') return Buffer.byteLength(value, 'utf8') <= 4096
  if (Array.isArray(value)) return value.length <= 128 && value.every(item => boundedJson(item, depth + 1))
  if (typeof value !== 'object') return false
  const entries = Object.entries(value)
  return entries.length <= 128 && entries.every(([key, item]) => key.length <= 256 && !controls.test(key) && boundedJson(item, depth + 1))
}
const flag = z.object({
  service: id, key: id, state: z.enum(['ENABLED', 'DISABLED']), defaultVariant: id,
  variants: z.record(id, z.unknown()).refine(value => Object.keys(value).length > 0 && boundedJson(value)),
  targeted: z.boolean(), classification: z.enum(['money-path', 'feature']),
}).strict().refine(value => Object.hasOwn(value.variants, value.defaultVariant))
const event = z.object({
  channel: id, topic: id, description: bounded(256 * 1024).nullable(), publishers: z.array(id).max(1000), consumers: z.array(id).max(1000),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/),
}).strict()
const schema = z.object({
  schema: z.literal('openbank.repository-documentation/v1'),
  adrs: documents('adr'), threatModels: documents('threat-models'), agentCharters: documents('agents'),
  events: z.object({
    schema: z.literal('openbank.events/v1'), generator: z.literal('generate-events.mjs'), source: text,
    collectedAt: z.string().datetime({ offset: true }).nullable(), available: z.literal(true),
    topics: z.array(event).max(2000).refine(rows => new Set(rows.map(row => row.channel)).size === rows.length),
    unmatched: z.array(text).max(2000),
  }).strict(),
  featureFlags: z.array(flag).max(2000).refine(rows => new Set(rows.map(row => `${row.service}:${row.key}`)).size === rows.length),
}).strict()

/** Bound reviewed repository declarations, never operational enforcement or runtime state. */
export function isRepositoryDocumentation(value: unknown, moneyPathServices: ReadonlySet<string>): value is RepositoryDocumentation {
  const result = schema.safeParse(value)
  return result.success && result.data.featureFlags.every(flag => flag.classification === (moneyPathServices.has(flag.service.replace(/^openbank-/, '')) ? 'money-path' : 'feature'))
}
