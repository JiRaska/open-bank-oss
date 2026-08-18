// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useCallback } from 'react'
import { ScrollText, Search } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'

const AUDIT_SERVICE = '/api/svc/audit-service'

interface AuditEntry {
  id: string; aggregateId: string; aggregateType: string
  eventType: string; actorId?: string; actorType?: string
  payload?: Record<string, unknown>; occurredAt: string
}

const EVENT_COLOR: Record<string, string> = {
  CREATED: 'var(--green)', UPDATED: 'var(--accent)', DELETED: 'var(--red)',
  FROZEN: 'var(--yellow)', CLOSED: 'var(--text-muted)', APPROVED: 'var(--green)',
  REJECTED: 'var(--red)',
}

export default function AuditPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [entries, setEntries]   = useState<AuditEntry[]>([])
  const [loading, setLoading]   = useState(false)
  // Instead of a raw "HTTP 404" string, hold a typed reason that renders as a
  // calm <DataUnavailable> panel. audit-service isn't deployed in every
  // environment, so a failed lookup is normally "not deployed", not "broken".
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [aggregateId, setAggregateId] = useState('')
  const [expanded, setExpanded] = useState<string | null>(null)

  const search = useCallback(async () => {
    if (!aggregateId.trim()) return
    setLoading(true); setUnavailable(null)
    try {
      const res = await fetch(`${AUDIT_SERVICE}/api/v1/audit/entries/${aggregateId.trim()}?limit=100`, { signal: AbortSignal.timeout(5000) })
      if (!res.ok) {
        setEntries([])
        setUnavailable({ kind: await classifyBffFailure(res) })
        return
      }
      const data = await res.json()
      setEntries(Array.isArray(data) ? data : data.entries ?? [])
    } catch {
      // fetch threw (timeout/abort/network) — the BFF or audit-service didn't
      // answer at all. Treat as unreachable rather than leaking the raw error.
      setEntries([])
      setUnavailable({ kind: 'unreachable' })
    } finally { setLoading(false) }
  }, [aggregateId])

  return (
    <div>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Auditní log', 'Audit Log')}</span></div>}
        icon={<ScrollText size={18} aria-hidden="true" />}
        title={t('Auditní log', 'Audit Log')}
        subtitle={t('Neměnný auditní záznam pro všechny entity platformy', 'Immutable audit trail for all platform entities')}
      />

      {/* Search */}
      <div className="card" style={{ padding: '16px', marginBottom: '16px' }}>
        <div style={{ fontWeight: 600, fontSize: '13px', marginBottom: '12px' }}>{t('Prohledat auditní záznamy', 'Search Audit Trail')}</div>
        <div style={{ display: 'flex', gap: '10px' }}>
          <div style={{ position: 'relative', flex: 1 }}>
            <Search size={14} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
            <input
              className="input"
              style={{ paddingLeft: '32px', width: '100%', fontFamily: 'var(--font-mono)', fontSize: '12px' }}
              placeholder="Aggregate ID (account UUID, party UUID, transaction UUID…)"
              aria-label={t('ID agregátu', 'Aggregate ID')}
              value={aggregateId}
              onChange={e => setAggregateId(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && search()}
            />
          </div>
          <button className="btn btn-primary" onClick={search} disabled={loading || !aggregateId.trim()}>
            {loading ? t('Hledám…', 'Searching…') : t('Hledat', 'Search')}
          </button>
        </div>
        <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '8px' }}>
          {t('Zadejte UUID libovolné entity pro zobrazení jejího auditního záznamu — účty, klienti, transakce, KYC případy atd.', 'Enter any entity UUID to see its full audit trail — accounts, parties, transactions, KYC cases, etc.')}
        </div>
      </div>

      {unavailable && (
        <div className="card" style={{ padding: 0, marginBottom: '16px' }}>
          <DataUnavailable
            kind={unavailable.kind}
            service={t('Audit-service', 'Audit-service')}
            feature={t('Auditní záznamy', 'Audit trail')}
            lang={language}
          />
        </div>
      )}

      {entries.length > 0 && (
        <div className="card" style={{ overflow: 'hidden' }}>
          <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)', fontSize: '13px', color: 'var(--text-muted)' }}>
            {entries.length} {t('událostí pro', 'events for')} <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--text-primary)' }}>{aggregateId}</span>
          </div>
          <table className="data-table">
            <thead>
              <tr>
                <th>{t('Událost', 'Event')}</th>
                <th>{t('Typ agregátu', 'Aggregate Type')}</th>
                <th>{t('Aktér', 'Actor')}</th>
                <th>{t('Nastalo', 'Occurred At')}</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {entries.map(e => (
                <>
                  <tr key={e.id} tabIndex={0} aria-expanded={expanded === e.id} aria-label={expanded === e.id ? t('Sbalit auditní událost', 'Collapse audit event') : t('Rozbalit auditní událost', 'Expand audit event')} style={{ cursor: 'pointer' }} onClick={() => setExpanded(expanded === e.id ? null : e.id)}
                    onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); setExpanded(expanded === e.id ? null : e.id) } }}>
                    <td>
                      <span className="pill" style={{ background: `${EVENT_COLOR[e.eventType] ?? 'var(--text-muted)'}22`, color: EVENT_COLOR[e.eventType] ?? 'var(--text-muted)' }}>
                        {e.eventType}
                      </span>
                    </td>
                    <td><span className="tag">{e.aggregateType}</span></td>
                    <td style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                      {e.actorId ? `${e.actorType ?? 'USER'}:${e.actorId.slice(0, 8)}…` : 'system'}
                    </td>
                    <td style={{ fontSize: '12px', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                      {new Date(e.occurredAt).toLocaleString(dateLocale)}
                    </td>
                    <td style={{ color: 'var(--accent)', fontSize: '12px' }}>{expanded === e.id ? '▲' : '▼'}</td>
                  </tr>
                  {expanded === e.id && e.payload && (
                    <tr key={`${e.id}-payload`}>
                      <td colSpan={5} style={{ background: 'var(--surface-2)', padding: '12px 16px' }}>
                        <pre style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-secondary)', margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                          {JSON.stringify(e.payload, null, 2)}
                        </pre>
                      </td>
                    </tr>
                  )}
                </>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!loading && entries.length === 0 && aggregateId && !unavailable && (
        <div className="card" style={{ padding: 0 }}>
          <DataUnavailable
            kind="no_data"
            feature={t('Auditní záznamy', 'Audit trail')}
            lang={language}
            detail={t('Pro toto aggregate ID nebyly nalezeny žádné auditní záznamy.', 'No audit entries were found for this aggregate ID.')}
          />
        </div>
      )}

      {!aggregateId && (
        <div className="card" style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>
          {t('Zadejte aggregate ID výše pro zobrazení auditního záznamu', 'Enter an aggregate ID above to view its audit trail')}
        </div>
      )}
    </div>
  )
}
