// SPDX-License-Identifier: Apache-2.0

export interface TraceSummary {
  traceID: string
  rootServiceName?: string
  rootTraceName?: string
  startTimeUnixNano?: string
  durationMs?: number
}

export interface FlatSpan {
  spanId: string
  parentSpanId?: string
  name: string
  service: string
  startNano: number
  endNano: number
}

type RecordValue = Record<string, unknown>

function record(value: unknown): RecordValue | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as RecordValue : null
}

function optionalString(value: unknown): string | undefined | null {
  if (value === undefined) return undefined
  return typeof value === 'string' && value.trim().length > 0 ? value : null
}

function nano(value: unknown): number | null {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return null
  const parsed = Number(value)
  // OTLP Unix nanoseconds are normally above Number.MAX_SAFE_INTEGER. The existing
  // waterfall uses relative Number arithmetic, so preserve that representation while
  // rejecting non-finite, fractional, negative, and non-decimal evidence.
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
}

export function parseTempoSearch(value: unknown): TraceSummary[] | null {
  const payload = record(value)
  if (!payload || !Array.isArray(payload.traces) || payload.traces.length > 100) return null

  const seen = new Set<string>()
  const traces: TraceSummary[] = []
  for (const raw of payload.traces) {
    const item = record(raw)
    if (!item) return null
    const traceID = optionalString(item.traceID)
    const rootServiceName = optionalString(item.rootServiceName)
    const rootTraceName = optionalString(item.rootTraceName)
    const startTimeUnixNano = optionalString(item.startTimeUnixNano)
    if (!traceID || rootServiceName === null || rootTraceName === null || startTimeUnixNano === null) return null
    if (!/^[0-9a-f]{16,64}$/i.test(traceID) || seen.has(traceID)) return null
    if (startTimeUnixNano !== undefined && !/^\d+$/.test(startTimeUnixNano)) return null
    if (item.durationMs !== undefined && (typeof item.durationMs !== 'number' || !Number.isFinite(item.durationMs) || item.durationMs < 0)) return null
    seen.add(traceID)
    traces.push({ traceID, rootServiceName, rootTraceName, startTimeUnixNano, durationMs: item.durationMs as number | undefined })
  }
  return traces
}

function resourceService(value: unknown): string | null {
  if (value === undefined) return 'unknown'
  const resource = record(value)
  if (!resource || (resource.attributes !== undefined && !Array.isArray(resource.attributes))) return null
  if (!Array.isArray(resource.attributes)) return 'unknown'
  for (const raw of resource.attributes) {
    const attribute = record(raw)
    if (!attribute || typeof attribute.key !== 'string') return null
    if (attribute.key !== 'service.name') continue
    const wrapped = record(attribute.value)
    return wrapped && typeof wrapped.stringValue === 'string' && wrapped.stringValue.trim()
      ? wrapped.stringValue
      : null
  }
  return 'unknown'
}

export function parseTempoTrace(value: unknown): FlatSpan[] | null {
  const payload = record(value)
  if (!payload || !Array.isArray(payload.batches)) return null
  const spans: FlatSpan[] = []
  const seen = new Set<string>()

  for (const rawBatch of payload.batches) {
    const batch = record(rawBatch)
    if (!batch) return null
    const service = resourceService(batch.resource)
    if (!service) return null
    const rawScopes = batch.scopeSpans ?? batch.instrumentationLibrarySpans ?? []
    if (!Array.isArray(rawScopes)) return null
    for (const rawScope of rawScopes) {
      const scope = record(rawScope)
      if (!scope || !Array.isArray(scope.spans)) return null
      for (const rawSpan of scope.spans) {
        const span = record(rawSpan)
        if (!span) return null
        const spanId = optionalString(span.spanId)
        const parentSpanId = optionalString(span.parentSpanId)
        const name = optionalString(span.name)
        const startNano = nano(span.startTimeUnixNano)
        const endNano = nano(span.endTimeUnixNano)
        if (!spanId || parentSpanId === null || !name || startNano === null || endNano === null || endNano < startNano || seen.has(spanId)) return null
        seen.add(spanId)
        spans.push({ spanId, parentSpanId, name, service, startNano, endNano })
      }
    }
  }
  return spans.sort((a, b) => a.startNano - b.startNano)
}
