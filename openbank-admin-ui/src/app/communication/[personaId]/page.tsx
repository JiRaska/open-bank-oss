// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Communication Studio — persona detail (ADR-0285 D7, phase 1).
//
// Shows the composed prompt exactly as it ships, marked as CORE and visibly locked, plus the
// three layers of ADR-0285 D1 with their real state. The style and playbook rows say which phase
// lands them rather than rendering a disabled editor: a greyed-out field reads as "temporarily
// unavailable", which is a different and untrue claim.

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, Lock, MessagesSquare, RefreshCw, PencilRuler, BookOpen } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'

interface PromptVersion { id: string; text: string; chars: number }

interface Persona {
  id: string
  status: 'registered' | 'pending' | 'external' | 'not-applicable'
  plane: string | null
  charter: string | null
  source: string | null
  reason: string | null
  blockedBy: string | null
  placeholders: string[]
  versions: PromptVersion[]
  editableLayers: 'not-published' | 'published'
}

export default function CommunicationPersonaPage() {
  const { t } = useLanguage()
  const params = useParams<{ personaId: string }>()
  const personaId = params?.personaId ?? ''
  const [persona, setPersona] = useState<Persona | null>(null)
  const [failure, setFailure] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!personaId) return
    setLoading(true)
    try {
      const res = await fetch(`/api/communication/personas/${encodeURIComponent(personaId)}`, { cache: 'no-store' })
      if (res.status === 404) { setFailure('no_data'); setPersona(null); return }
      if (!res.ok) { setFailure('error'); setPersona(null); return }
      const body = (await res.json()) as Persona
      setPersona(body)
      setSelected(body.versions.length ? body.versions[body.versions.length - 1].id : null)
      setFailure(null)
    } catch {
      setFailure('error')
      setPersona(null)
    } finally {
      setLoading(false)
    }
  }, [personaId])

  useEffect(() => { void load() }, [load])

  const active = persona?.versions.find(v => v.id === selected) ?? null
  const muted = { fontSize: '13px', color: 'var(--text-secondary)' } as const

  return (
    <AuthGuard permission="communication:view">
      <div className="page">
        <PageHeader
          icon={<MessagesSquare size={22} />}
          title={personaId}
          subtitle={persona?.charter ?? t('Persona komunikačního studia', 'Communication Studio persona')}
          breadcrumb={
            <div className="breadcrumb">
              <Link href="/communication"><ArrowLeft size={14} /> {t('Komunikace', 'Communication')}</Link>
              <span className="breadcrumb-sep">/</span>
              <span className="breadcrumb-current">{personaId}</span>
            </div>
          }
          actions={
            <button type="button" className="btn btn-secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw size={15} />
              {t('Obnovit', 'Refresh')}
            </button>
          }
        />

        {failure && (
          <DataUnavailable
            kind={failure}
            feature={t('Persona', 'Persona')}
            detail={t(
              'Tuto personu registr promptů neobsahuje.',
              'The prompt registry does not contain this persona.',
            )}
          />
        )}

        {!failure && persona && (
          <>
            <div className="card" style={{ marginBottom: '16px' }}>
              <h2 style={{ fontSize: '13px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-secondary)', marginBottom: '10px' }}>{t('Vrstvy promptu (ADR-0285 D1)', 'Prompt layers (ADR-0285 D1)')}</h2>
              <table className="table">
                <thead>
                  <tr>
                    <th>{t('Vrstva', 'Layer')}</th>
                    <th>{t('Vlastník', 'Owner')}</th>
                    <th>{t('Stav', 'State')}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td><Lock size={14} /> {t('Jádro', 'Core')}</td>
                    <td>{t('Inženýrství — git, ADR-0148 registr', 'Engineering — git, ADR-0148 registry')}</td>
                    <td>
                      <span className="badge badge-success">{t('zamčeno, nelze editovat z UI', 'locked, not editable from any UI')}</span>
                      {persona.source && <div style={muted}>{persona.source}</div>}
                    </td>
                  </tr>
                  <tr>
                    <td><PencilRuler size={14} /> {t('Styl', 'Style')}</td>
                    <td>{t('Byznys editor, čtyři oči', 'Business editor, four-eyes')}</td>
                    <td>
                      <span className="badge badge-neutral">{t('zatím nepublikováno', 'not published yet')}</span>
                      <div style={muted}>{t('Dodá openbank-communication-service ve fázi 2.', 'Delivered by openbank-communication-service in phase 2.')}</div>
                    </td>
                  </tr>
                  <tr>
                    <td><BookOpen size={14} /> {t('Playbook', 'Playbook')}</td>
                    <td>{t('Byznys editor, čtyři oči', 'Business editor, four-eyes')}</td>
                    <td>
                      <span className="badge badge-neutral">{t('zatím nepublikováno', 'not published yet')}</span>
                      <div style={muted}>{t('Call scripty a schválené odpovědi přijdou ve fázi 3.', 'Call scripts and approved answers arrive in phase 3.')}</div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            {persona.status !== 'registered' && (
              <div className="card" style={{ marginBottom: '16px' }}>
                <p style={muted}>
                  {t('Pokrytí registrem: ', 'Registry coverage: ')}<strong>{persona.status}</strong>
                  {persona.reason ? ` — ${persona.reason}` : ''}
                  {persona.blockedBy ? ` (${persona.blockedBy})` : ''}
                </p>
              </div>
            )}

            {persona.versions.length > 0 && (
              <div className="card">
                <h2 style={{ fontSize: '13px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-secondary)', marginBottom: '10px' }}>{t('Znění promptu (jen ke čtení)', 'Prompt text (read-only)')}</h2>
                <div className="flex items-center gap-2" style={{ flexWrap: 'wrap', marginBottom: '12px' }}>
                  {persona.versions.map(version => (
                    <button
                      key={version.id}
                      type="button"
                      className={`btn ${version.id === selected ? 'btn-primary' : 'btn-secondary'}`}
                      onClick={() => setSelected(version.id)}
                      aria-pressed={version.id === selected}
                    >
                      {version.id}
                    </button>
                  ))}
                </div>
                {persona.placeholders.length > 0 && (
                  <p style={muted}>
                    {t('Zástupné proměnné dosazované za běhu: ', 'Placeholders substituted at runtime: ')}
                    {persona.placeholders.map(p => `{{${p}}}`).join(', ')}
                  </p>
                )}
                {active && (
                  <>
                    <p style={muted}>{t('Délka: ', 'Length: ')}{active.chars}</p>
                    <pre
                      style={{
                        whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: '13px',
                        background: 'var(--surface-2)', border: '1px solid var(--border)',
                        borderRadius: '10px', padding: '14px', margin: 0, overflowX: 'auto',
                      }}
                    >{active.text}</pre>
                  </>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </AuthGuard>
  )
}
