// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

'use client'

import { useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { Zap, ChevronDown, ChevronRight } from 'lucide-react'

interface Row {
  tier: string
  name: () => string
  tech: () => string
  idle: () => string
  color: string
}

/**
 * Collapsible legend explaining the FinOps workload tiers + the technology/plan behind
 * scale-to-zero (KEDA, KEDA HTTP add-on, Quarkus native, Knative as the considered
 * alternative, CronJob). Read-only reflection of ADR-0057 / ADR-0083.
 */
export function ServerlessLegend() {
  const { t } = useLanguage()
  const [open, setOpen] = useState(false)

  const rows: Row[] = [
    {
      tier: 'T0', color: '#6b7280',
      name: () => t('Vždy běží', 'Always-on'),
      tech: () => t('bez škálování na nulu — money-path / regulatorně nepřetržité',
                    'no scale-to-zero — money-path / regulator-continuous'),
      idle: () => t('plný', 'full'),
    },
    {
      tier: 'T1', color: '#d97706',
      name: () => t('HTTP → 0', 'HTTP → 0'),
      tech: () => t('KEDA HTTP add-on + Quarkus native image (alternativa: Knative Serving)',
                    'KEDA HTTP add-on + Quarkus native image (alternative: Knative Serving)'),
      idle: () => t('≈ nula', '≈ zero'),
    },
    {
      tier: 'T2', color: '#059669',
      name: () => t('Event → 0', 'Event → 0'),
      tech: () => t('KEDA ScaledObject na Kafka consumer-group lag',
                    'KEDA ScaledObject on Kafka consumer-group lag'),
      idle: () => t('≈ nula', '≈ zero'),
    },
    {
      tier: 'T3', color: '#2563eb',
      name: () => t('Cron / Job', 'Cron / Job'),
      tech: () => t('Kubernetes CronJob — bez rezidentního podu',
                    'Kubernetes CronJob — no resident pod'),
      idle: () => t('nula', 'zero'),
    },
  ]

  return (
    <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', overflow: 'hidden' }}>
      <button
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        style={{
          width: '100%', display: 'flex', alignItems: 'center', gap: '8px',
          padding: '12px 16px', background: 'transparent', border: 'none', cursor: 'pointer',
          color: 'var(--text-primary)', textAlign: 'left',
        }}
      >
        <Zap size={15} style={{ color: '#059669' }} />
        <span style={{ fontSize: '13px', fontWeight: 600 }}>
          {t('Serverless tiery a plán (škálování na nulu)', 'Serverless tiers & plan (scale-to-zero)')}
        </span>
        <span style={{ marginLeft: 'auto', display: 'inline-flex' }}>
          {open ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </span>
      </button>

      {open && (
        <div style={{ padding: '0 16px 16px' }}>
          <p style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5, margin: '0 0 12px' }}>
            {t('Každá služba spadá do jednoho tieru (ADR-0057). Nové služby defaultně do nejnižšího, který jejich trigger dovolí — vždy-běžící je opt-in. Implementováno na stávajícím Kubernetes + KEDA / Karpenter spot, žádný proprietární FaaS (ADR-0027).',
               'Every service falls into one tier (ADR-0057). New services default to the lowest tier their trigger allows — always-on is opt-in. Built on the existing Kubernetes + KEDA / Karpenter spot, no proprietary FaaS (ADR-0027).')}
          </p>
          <div style={{ display: 'grid', gap: '8px' }}>
            {rows.map(r => (
              <div key={r.tier} style={{ display: 'flex', alignItems: 'flex-start', gap: '10px', fontSize: '12px' }}>
                <span style={{
                  flexShrink: 0, fontWeight: 700, color: r.color,
                  border: `1px solid ${r.color}55`, borderRadius: '6px',
                  padding: '1px 7px', minWidth: '30px', textAlign: 'center',
                }}>{r.tier}</span>
                <div style={{ flex: 1 }}>
                  <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{r.name()}</span>
                  <span style={{ color: 'var(--text-tertiary)' }}> · {t('idle náklad', 'idle cost')}: {r.idle()}</span>
                  <div style={{ color: 'var(--text-secondary)', marginTop: '2px' }}>{r.tech()}</div>
                </div>
              </div>
            ))}
          </div>
          <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', lineHeight: 1.5, margin: '12px 0 0' }}>
            {t('Stavy: ⚡ Serverless = reálně spí · ◌ Bude serverless = návrh hotový (ADR-0083) · ● Money-path = vždy běží. Tiery se ověřují proti měřeným metrikám (klasifikátor, ADR-0057).',
               'States: ⚡ Serverless = actually sleeps · ◌ To be serverless = designed (ADR-0083) · ● Money-path = always-on. Tiers are checked against measured metrics (classifier, ADR-0057).')}
          </p>
        </div>
      )}
    </div>
  )
}
