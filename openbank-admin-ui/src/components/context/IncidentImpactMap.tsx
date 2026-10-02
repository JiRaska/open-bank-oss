// SPDX-License-Identifier: Apache-2.0

import { useId } from 'react'
import { ArrowMarker } from '@/components/topology/TopologyDefs'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { IncidentImpact } from '@/lib/context/incidentImpact'

/** One node per authorized aggregate type, never per affected customer or case. */
export function IncidentImpactMap({ impact }: { impact: IncidentImpact }) {
  const { t } = useLanguage()
  const markerId = `impact-${useId().replaceAll(':', '')}`
  const entries = Object.entries(impact.affectedByType).sort(([a], [b]) => a.localeCompare(b))
  if (impact.projectionStatus === 'MISSING' || entries.length === 0) return null
  const height = Math.max(160, entries.length * 64 + 32)
  return <div style={{ overflowX: 'auto', marginTop: 16, border: '1px solid var(--border)', borderRadius: 12, background: 'var(--surface-2)' }}>
    <svg viewBox={`0 0 760 ${height}`} role="img" aria-label={t('Agregovaná mapa hlášeného rozsahu incidentu', 'Aggregate reported incident scope map')}
      style={{ display: 'block', width: '100%', minWidth: 580 }}>
      <title>{t('Incident a počty souvisejících objektů podle typu', 'Incident and counts of related objects by type')}</title>
      <defs><ArrowMarker id={markerId} color="var(--accent)" /></defs>
      <rect x={22} y={height / 2 - 32} width={145} height={64} rx={14} fill="var(--surface)" stroke="var(--accent)" strokeWidth={2} />
      <text x={94} y={height / 2 - 3} textAnchor="middle" fill="var(--text-primary)" fontSize={15}>{t('Incident', 'Incident')}</text>
      <text x={94} y={height / 2 + 17} textAnchor="middle" fill="var(--text-secondary)" fontSize={11}>{t('Zdrojová projekce', 'Source projection')}</text>
      {entries.map(([type, count], index) => {
        const y = entries.length === 1 ? height / 2 : index * 64 + 48
        return <g key={type}>
          <path d={`M 167 ${height / 2} C 245 ${height / 2}, 250 ${y}, 323 ${y}`} fill="none" stroke="var(--accent)" strokeOpacity={.55} markerEnd={`url(#${markerId})`} />
          <rect x={332} y={y - 23} width={406} height={46} rx={10} fill="var(--surface)" stroke="var(--border)" />
          <text x={348} y={y + 4} fill="var(--text-primary)" fontSize={12}>{type.length > 42 ? `${type.slice(0, 39)}…` : type}<title>{type}</title></text>
          <text x={718} y={y + 5} textAnchor="end" fill="var(--accent-text)" fontSize={17} fontWeight={700}>{count}</text>
        </g>
      })}
    </svg>
  </div>
}
