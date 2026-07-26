// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState } from 'react'
import { FileSignature, Search } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PartySearch, partyDisplayName, type PartyHit } from '@/components/party/PartySearch'
import { classifyBffFailure } from '@/lib/services/bff'
import { PageHeader, StatCard, StatusBadge, statusTone } from '@/components/ui'

// consent-service (ADR-0126) exposes NO "list all consents" endpoint — every read is keyed by
// consentId, partyId or granteeId. So this is deliberately a LOOKUP page, not a table of everything:
// inventing a global list would mean adding an unbounded-scan endpoint to a service that correctly
// refuses to have one.
//
// The grantee lens is the one genuinely global view available, and it is the useful one: querying
// `party-service:marketing-comms` (ADR-0205 D3's fixed internal grantee) answers "who has an ACTIVE
// marketing consent right now" straight from the owning service, with no projection to drift.
const MARKETING_GRANTEE = 'party-service:marketing-comms'

interface Consent {
  id: string
  partyId: string
  granteeId: string
  granteeType: string
  granteeName: string
  scopes: string[]
  accountIbans: string[] | null
  status: string
  validFrom: string
  validTo: string
  createdAt: string
}

type Lens = 'party' | 'grantee'

export default function ConsentsPage() {
  const { t, language } = useLanguage()
  // Party is the default lens: an operator arrives with a customer in mind, not a grantee id. The
  // grantee lens stays because it is the one genuinely global view (see MARKETING_GRANTEE above),
  // but it is the specialist path, not the landing state.
  const [lens, setLens] = useState<Lens>('party')
  const [term, setTerm] = useState('')
  const [party, setParty] = useState<PartyHit | null>(null)
  const [rows, setRows] = useState<Consent[] | null>(null)
  const [failure, setFailure] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(false)

  const lookup = async (override?: string) => {
    const q = (override ?? term).trim()
    if (!q) return
    setLoading(true)
    setFailure(null)
    setRows(null)
    try {
      const path = lens === 'party' ? `party/${encodeURIComponent(q)}` : `grantee/${encodeURIComponent(q)}`
      const res = await fetch(`/api/svc/consent-service/api/v1/consents/${path}`, { cache: 'no-store' })
      if (!res.ok) {
        setFailure(await classifyBffFailure(res))
        return
      }
      const data = (await res.json()) as Consent[]
      setRows(data)
      // NOT `no_data`: consent-service answered, it just holds no consent for this key. The old copy
      // said "the data source contains no records yet", which an operator cannot tell apart from a
      // broken page — and was false whenever any other party had a consent. Rendered below instead.
    } catch {
      setFailure('unreachable')
    } finally {
      setLoading(false)
    }
  }

  const onPartySelected = (p: PartyHit) => {
    setParty(p)
    setTerm(p.id)
    void lookup(p.id)
  }

  const active = rows?.filter(c => c.status === 'ACTIVE').length ?? 0
  const marketing = rows?.filter(c => c.scopes.some(s => s.startsWith('MARKETING_COMMS_'))).length ?? 0
  const fmt = (iso: string) =>
    new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', { dateStyle: 'medium' }).format(new Date(iso))

  return (
    <div style={{ padding: '32px', maxWidth: '1280px', margin: '0 auto' }}>
      <PageHeader
        icon={<FileSignature size={28} className="tone-text-accent" />}
        title={t('Souhlasy', 'Consents')}
        subtitle={t(
          'Souhlasy vlastní consent-service (ADR-0126/0198). Nemá endpoint pro výpis všeho — hledá se podle party nebo grantee. Marketingový grantee party-service:marketing-comms je globální pohled na aktivní marketingové souhlasy.',
          'consent-service owns consents (ADR-0126/0198). It has no list-everything endpoint — look up by party or by grantee. The marketing grantee party-service:marketing-comms is the global view of active marketing consents.',
        )}
      />

      <div className="card" style={{ marginBottom: '20px' }}>
        <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center' }}>
          <select
            value={lens}
            onChange={e => {
              const next = e.target.value as Lens
              setLens(next)
              setTerm(next === 'grantee' ? MARKETING_GRANTEE : '')
            }}
            style={{
              padding: '8px 12px', borderRadius: '8px', border: '1px solid var(--border)',
              background: 'var(--surface)', color: 'var(--text-primary)', fontSize: '13px', fontWeight: 600,
            }}
          >
            <option value="party">{t('Podle party', 'By party')}</option>
            <option value="grantee">{t('Podle grantee', 'By grantee')}</option>
          </select>
          {/* The party lens searches by NAME (party-service owns names, ADR-0055) and resolves to the
              id consent-service is keyed by. The grantee lens stays a literal id field: a grantee is
              a system identifier like party-service:marketing-comms, not a person to search for. */}
          {lens === 'grantee' && (
            <>
              <input
                value={term}
                onChange={e => setTerm(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') lookup() }}
                placeholder={t('ID grantee', 'Grantee id')}
                style={{
                  flex: '1 1 320px', padding: '8px 12px', borderRadius: '8px', border: '1px solid var(--border)',
                  background: 'var(--surface)', color: 'var(--text-primary)', fontSize: '13px',
                }}
              />
              <button
                onClick={() => lookup()}
                disabled={loading || !term.trim()}
                style={{
                  display: 'flex', alignItems: 'center', gap: '6px', padding: '8px 16px',
                  cursor: loading || !term.trim() ? 'not-allowed' : 'pointer', borderRadius: '8px',
                  border: '1px solid var(--border)', background: 'var(--surface)',
                  color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600,
                  opacity: loading || !term.trim() ? 0.6 : 1,
                }}
              >
                <Search size={15} /> {t('Vyhledat', 'Look up')}
              </button>
              <button
                onClick={() => { setTerm(MARKETING_GRANTEE); void lookup(MARKETING_GRANTEE) }}
                disabled={loading}
                style={{
                  padding: '8px 14px', borderRadius: '8px', border: '1px solid var(--border)',
                  background: 'var(--surface)', color: 'var(--text-secondary)', fontSize: '12px',
                  fontWeight: 600, cursor: loading ? 'not-allowed' : 'pointer',
                }}
              >
                {t('Marketingové souhlasy', 'Marketing consents')}
              </button>
            </>
          )}
        </div>
      </div>

      {lens === 'party' && (
        <PartySearch onSelect={onPartySelected} selectedId={party?.id} busy={loading} />
      )}

      {rows && rows.length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '12px', marginBottom: '20px' }}>
          <StatCard label={t('Nalezeno', 'Found')} value={rows.length} />
          <StatCard label={t('Aktivních', 'Active')} value={active} tone="success" />
          <StatCard label={t('Marketingových', 'Marketing')} value={marketing} tone="info" />
        </div>
      )}

      {loading && (
        <div style={{ color: 'var(--text-secondary)', padding: '40px', textAlign: 'center' }}>
          {t('Načítám…', 'Loading…')}
        </div>
      )}

      {!loading && failure && (
        <DataUnavailable
          kind={failure}
          service="Consent-service"
          feature={t('Souhlasy', 'Consents')}
          lang={language === 'cs' ? 'cs' : 'en'}
        />
      )}

      {/* Answered, but empty for this key. Distinct from an unavailable service (above): the earlier
          copy claimed the source held no records at all, while it held consents for other parties. */}
      {!loading && !failure && rows && rows.length === 0 && (
        <div className="card" style={{ padding: '32px', textAlign: 'center' }}>
          <p style={{ margin: '0 0 6px', fontWeight: 600, color: 'var(--text-primary)' }}>
            {lens === 'party'
              ? t('Tato party nemá žádný souhlas', 'This party has no consent')
              : t('Tento grantee nemá žádný souhlas', 'This grantee has no consent')}
          </p>
          <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-secondary)' }}>
            {t(
              'consent-service je dostupná a odpověděla — pro tento dotaz nemá žádný záznam. Nejde o chybu ani o prázdnou databázi.',
              'consent-service is available and answered — it has no record for this query. This is not an error, nor an empty database.',
            )}
          </p>
          {lens === 'party' && party && (
            <p style={{ margin: '10px 0 0', fontSize: '11px', fontFamily: 'var(--font-mono, monospace)', color: 'var(--text-tertiary)' }}>
              {partyDisplayName(party)} · {party.id}
            </p>
          )}
        </div>
      )}

      {!loading && rows && rows.length > 0 && (
        <div className="card" style={{ overflowX: 'auto' }}>
          <table className="table">
            <thead>
              <tr>
                <th>{t('Party', 'Party')}</th>
                <th>{t('Grantee', 'Grantee')}</th>
                <th>{t('Rozsahy', 'Scopes')}</th>
                <th>{t('Stav', 'Status')}</th>
                <th>{t('Platnost do', 'Valid to')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(c => (
                <tr key={c.id}>
                  <td style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: '11px' }}>{c.partyId}</td>
                  <td>
                    <div style={{ fontWeight: 600 }}>{c.granteeName}</div>
                    <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{c.granteeId}</div>
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: '4px', flexWrap: 'wrap' }}>
                      {c.scopes.map(s => (
                        <span key={s} className="tag" style={{ fontSize: '10px' }}>{s}</span>
                      ))}
                    </div>
                  </td>
                  <td>
                    {/* statusTone treats REVOKED/EXPIRED as neutral, which is the consent reading:
                        a withdrawal under Art. 7(3) is the customer exercising a right, not an
                        incident. This page is that domain, so the shared default is correct here and
                        no `tone` override is passed (ADR-0208 D2). */}
                    <StatusBadge status={c.status} withDot tone={statusTone(c.status)} />
                  </td>
                  <td>{fmt(c.validTo)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
