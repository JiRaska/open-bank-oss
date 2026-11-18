// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0384 D4 (first slice): presentational tiles for the customer MCP channel. A null value is
// rendered as "not measured", never as 0 — an absent series means nothing has measured the
// channel yet, which is a different fact from an idle one.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'

export type McpChannelPayload =
  | { state: 'unavailable' }
  | {
      state: 'ok'
      window: string
      customerCalls: number | null
      syntheticCalls: number | null
      agentCalls: number | null
      rejectsByLayer: Record<string, number> | null
      firingAlerts: string[]
      activeGrants: number | null
    }

export function isMcpChannelPayload(v: unknown): v is McpChannelPayload {
  if (!v || typeof v !== 'object') return false
  const s = (v as { state?: unknown }).state
  return s === 'ok' || s === 'unavailable'
}

function Tile({ label, value, hint }: { label: string; value: number | null; hint?: string }) {
  const { t } = useLanguage()
  return (
    <div className="card p-4" data-testid="mcp-tile">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-2xl font-semibold">
        {value === null ? <span className="text-base text-muted-foreground">{t('neměřeno', 'not measured')}</span> : value}
      </div>
      {hint && <div className="text-xs text-muted-foreground mt-1">{hint}</div>}
    </div>
  )
}

export function McpChannelHealth({ data }: { data: Extract<McpChannelPayload, { state: 'ok' }> }) {
  const { t } = useLanguage()
  const layers = data.rejectsByLayer ? Object.entries(data.rejectsByLayer).sort(([a], [b]) => a.localeCompare(b)) : null
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Tile
          label={t('Aktivní přístupy (granty MCP_CLIENT)', 'Active accesses (MCP_CLIENT grants)')}
          value={data.activeGrants}
          hint={t('Projekce v delegation-service zatím není (ADR-0384 D1).', 'delegation-service projection not built yet (ADR-0384 D1).')}
        />
        <Tile label={t('Volání zákazníků za 1 h', 'Customer calls, last 1h')} value={data.customerCalls} />
        <Tile label={t('Syntetická volání za 1 h', 'Synthetic calls, last 1h')} value={data.syntheticCalls} />
        <Tile label={t('Volání agentů a operátorů za 1 h', 'Agent and operator calls, last 1h')} value={data.agentCalls} />
      </div>
      <div className="card p-4">
        <h2 className="text-sm font-semibold mb-2">{t('Odmítnutí podle vrstvy (1 h)', 'Refusals by layer (1h)')}</h2>
        {layers === null ? (
          <p className="text-sm text-muted-foreground" data-testid="mcp-rejects-unmeasured">
            {t('Zatím neměřeno: zákaznická cesta ADR-0383 ještě neběží.', 'Not measured yet: the ADR-0383 customer path is not running.')}
          </p>
        ) : (
          <ul className="text-sm" data-testid="mcp-rejects">
            {layers.map(([layer, n]) => (
              <li key={layer}>
                <code>{layer}</code>: {n}
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="card p-4">
        <h2 className="text-sm font-semibold mb-2">{t('Aktivní upozornění', 'Firing alerts')}</h2>
        {data.firingAlerts.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('Žádné.', 'None.')}</p>
        ) : (
          <ul className="text-sm" data-testid="mcp-alerts">
            {data.firingAlerts.map(a => <li key={a}><code>{a}</code></li>)}
          </ul>
        )}
      </div>
    </div>
  )
}
