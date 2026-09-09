// SPDX-License-Identifier: Apache-2.0

export type Incident = {
  id: string
  title: string
  severity: string
  status: string
  category: string
  detectedAt: string
  affectedServices: string[]
  reportedToRegulator: boolean
}

export type IncidentEnvelope =
  | { available: true; incidents: Incident[] }
  | { available: false; reason: 'unauthorized' | 'not_deployed' | 'unreachable' | 'error' }

function isIncident(value: unknown): value is Incident {
  if (!value || typeof value !== 'object') return false
  const incident = value as Partial<Incident>
  return typeof incident.id === 'string'
    && incident.id.length > 0
    && typeof incident.title === 'string'
    && typeof incident.severity === 'string'
    && typeof incident.status === 'string'
    && typeof incident.category === 'string'
    && typeof incident.detectedAt === 'string'
    && Number.isFinite(Date.parse(incident.detectedAt))
    && Array.isArray(incident.affectedServices)
    && incident.affectedServices.every(service => typeof service === 'string')
    && typeof incident.reportedToRegulator === 'boolean'
}

export async function readIncidentEnvelope(response: Response): Promise<IncidentEnvelope> {
  if (!response.ok) throw new Error(`incident register HTTP ${response.status}`)
  const value: unknown = await response.json()
  if (!value || typeof value !== 'object') throw new Error('invalid incident register envelope')
  const envelope = value as { available?: unknown; incidents?: unknown; reason?: unknown }
  if (envelope.available === true) {
    if (!Array.isArray(envelope.incidents) || !envelope.incidents.every(isIncident)) {
      throw new Error('invalid incident register payload')
    }
    return { available: true, incidents: envelope.incidents }
  }
  if (envelope.available === false) {
    const reason = envelope.reason
    return {
      available: false,
      reason: reason === 'unauthorized' || reason === 'not_deployed' || reason === 'unreachable'
        ? reason
        : 'error',
    }
  }
  throw new Error('invalid incident register availability')
}
