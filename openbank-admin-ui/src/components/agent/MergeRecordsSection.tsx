// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// "Recorded human merges" — the ADR-0031 phase 3 merge evidence on the IAOps
// Control Room. Lists WHO (a human) merged an agent-authored PR. This panel is
// evidence, not attestation: it never claims phase 3 is reached — with zero
// records it says exactly that recording has not activated yet, and when the
// fetch fails it degrades to a quiet inline note, not a governance incident.

'use client'

import { useEffect, useState } from 'react'
import { GitPullRequest, Hand, Info, RefreshCw } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { getAgentPersona } from '@/components/agent/AgentIdentity'
import { parseAgentMergeRecordList, type AgentMergeRecord } from '@/lib/agent-merge-records/types'

const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000

interface MergeRecordsSectionProps {
  /** Chartered agent ids from the governance snapshot — the display-name mapping. */
  knownAgentIds: string[]
}

export function MergeRecordsSection({ knownAgentIds }: MergeRecordsSectionProps) {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const known = new Set(knownAgentIds)
  const [records, setRecords] = useState<AgentMergeRecord[] | null>(null)
  const [last30d, setLast30d] = useState(0)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const res = await fetch('/api/agent/merge-records', { cache: 'no-store' })
        if (!res.ok) { if (!cancelled) setFailed(true); return }
        const parsed = parseAgentMergeRecordList(await res.json())
        if (cancelled) return
        const now = Date.now()
        setRecords(parsed.records)
        setLast30d(parsed.records.filter(r => now - Date.parse(r.mergedAt) <= THIRTY_DAYS_MS).length)
      } catch {
        if (!cancelled) setFailed(true)
      }
    }
    const timer = window.setTimeout(() => { void load() }, 0)
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [])

  const agentLabel = (agentId: string) => known.has(agentId)
    ? getAgentPersona(agentId, language).name
    : agentId

  return (
    <div style={{ background: 'var(--surface)', border: '1px solid var(--border)',
      borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px' }}>
      <div style={{ marginBottom: '16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }}>
          <span style={{ color: 'var(--accent-text)' }}><GitPullRequest size={16} /></span>
          <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>
            {t('Zaznamenané lidské merge', 'Recorded human merges')}
          </span>
          {records !== null && (
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '11px', fontWeight: 700,
              padding: '2px 9px', borderRadius: '10px', background: 'var(--accent-bg)', color: 'var(--accent-text)',
              border: '1px solid var(--accent-border)' }}>
              {t(`${records.length} zaznamenaných · ${last30d} za posledních 30 dní`, `${records.length} recorded · ${last30d} in the last 30 days`)}
            </span>
          )}
        </div>
        <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: '6px 0 0' }}>
          {t(
            'Kdo (člověk) mergnul PR agenta — trvalý důkaz ADR-0031 fáze 3 vedle rozhodnutí o návrhu. Záznam vytváří pipeline po každém mergi.',
            'WHO (a human) merged an agent’s PR — the ADR-0031 phase 3 durable evidence next to the proposal decision. The pipeline records each merge.',
          )}
        </p>
      </div>

      {records === null && !failed && (
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '12px 0', color: 'var(--text-tertiary)' }}>
          <RefreshCw size={13} style={{ animation: 'spin 0.8s linear infinite' }} />
          <span style={{ fontSize: '12px' }}>{t('Načítám záznamy o mergi…', 'Loading merge records…')}</span>
        </div>
      )}

      {failed && (
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', padding: '10px 14px',
          borderRadius: '8px', background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
          <Info size={13} style={{ color: 'var(--text-tertiary)', marginTop: '1px', flexShrink: 0 }} />
          <span style={{ fontSize: '11px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
            {t(
              'Záznamy o mergi teď nejsou dostupné — agent-service neodpověděl. Tahle část je informativní; nejde o governance incident.',
              'Merge records are unavailable right now — agent-service did not respond. This panel is informational; it is not a governance incident.',
            )}
          </span>
        </div>
      )}

      {records !== null && records.length === 0 && (
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', padding: '10px 14px',
          borderRadius: '8px', background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
          <Info size={13} style={{ color: 'var(--text-tertiary)', marginTop: '1px', flexShrink: 0 }} />
          <span style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
            {t(
              'Zatím žádné zaznamenané merge — záznam se aktivuje při prvním mergi PR agenta (ADR-0031 fáze 3).',
              'No recorded merges yet — merge recording activates when the first agent PR is merged (ADR-0031 phase 3).',
            )}
          </span>
        </div>
      )}

      {records !== null && records.length > 0 && (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
            <thead>
              <tr style={{ borderBottom: '2px solid var(--border)' }}>
                {[t('Agent', 'Agent'), t('PR', 'PR'), t('Větev', 'Branch'), t('Mergnul', 'Merged by'), t('Mergnuto (UTC)', 'Merged at (UTC)'), t('Nadpis', 'Title')].map(h => (
                  <th key={h} style={{ padding: '8px 12px', textAlign: 'left', color: 'var(--text-tertiary)', fontWeight: 600, fontSize: '11px', whiteSpace: 'nowrap' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {records.map(r => (
                <tr key={r.id} style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '10px 12px', fontWeight: 700, color: 'var(--text-primary)', whiteSpace: 'nowrap', verticalAlign: 'top' }}>
                    {agentLabel(r.agentId)}
                    {known.has(r.agentId) && (
                      <div title={r.agentId} style={{ fontSize: '10px', color: 'var(--text-tertiary)', fontFamily: 'monospace', fontWeight: 400 }}>{t('Charterovaný agent', 'Chartered agent')}</div>
                    )}
                  </td>
                  <td style={{ padding: '10px 12px', verticalAlign: 'top', whiteSpace: 'nowrap' }}>
                    <a href={r.prUrl} target="_blank" rel="noreferrer"
                      style={{ color: 'var(--accent-text)', fontFamily: 'monospace', fontWeight: 700, textDecoration: 'none' }}>
                      #{r.prNumber}
                    </a>
                  </td>
                  <td style={{ padding: '10px 12px', color: 'var(--text-secondary)', verticalAlign: 'top', fontFamily: 'monospace', fontSize: '11px', whiteSpace: 'nowrap' }}>{r.headBranch}</td>
                  <td style={{ padding: '10px 12px', verticalAlign: 'top', whiteSpace: 'nowrap' }}>
                    <span style={{ color: 'var(--text-primary)', fontWeight: 600 }}>{r.mergedBy}</span>{' '}
                    <span title={t('Člověk — auditovaný aktér tohoto mergu', 'Human — the audited actor of this merge')}
                      style={{ display: 'inline-flex', alignItems: 'center', gap: '3px', fontSize: '10px', fontWeight: 750,
                        padding: '1px 6px', borderRadius: '8px', background: 'var(--warning-bg)', color: 'var(--warning-text)',
                        border: '1px solid var(--warning-border)' }}>
                      <Hand size={9} />
                      {t('Člověk', 'Human')}
                    </span>
                  </td>
                  <td style={{ padding: '10px 12px', color: 'var(--text-secondary)', verticalAlign: 'top', whiteSpace: 'nowrap' }}>
                    {new Date(r.mergedAt).toLocaleString(dateLocale, { timeZone: 'UTC', dateStyle: 'medium', timeStyle: 'short' })}
                  </td>
                  <td style={{ padding: '10px 12px', color: 'var(--text-secondary)', verticalAlign: 'top', minWidth: '220px' }}>{r.title}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
