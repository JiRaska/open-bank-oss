// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Communication Studio — persona list (ADR-0285 D7, phase 1).
//
// Phase 1 is deliberately a MIRROR, not an editor: it shows what each channel says today and
// which part of it is locked. The style and playbook editors arrive with
// openbank-communication-service (phase 2/3); rendering an inert editor now would read as a
// shipped capability that silently does nothing.

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { MessagesSquare, RefreshCw, Lock, ChevronRight } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'

interface PersonaRow {
  id: string
  status: 'registered' | 'pending' | 'external' | 'not-applicable'
  plane: string | null
  charter: string | null
  source: string | null
  reason: string | null
  blockedBy: string | null
  placeholders: string[]
  editableLayers: 'not-published' | 'published'
  versionCount: number
  versionIds: string[]
  coreChars: number
}

interface PersonaList {
  available: boolean
  schemaVersion: number | null
  relatedAdrs: string[]
  personas: PersonaRow[]
}

const STATUS_BADGE: Record<PersonaRow['status'], string> = {
  registered: 'badge-success',
  pending: 'badge-warning',
  external: 'badge-info',
  'not-applicable': 'badge-neutral',
}

export default function CommunicationStudioPage() {
  const { t } = useLanguage()
  const [data, setData] = useState<PersonaList | null>(null)
  const [failure, setFailure] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/communication/personas', { cache: 'no-store' })
      if (!res.ok) { setFailure('error'); setData(null); return }
      const body = (await res.json()) as PersonaList
      setFailure(body.available ? null : 'no_data')
      setData(body)
    } catch {
      setFailure('error')
      setData(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const personas = data?.personas ?? []
  const speaking = personas.filter(p => p.status === 'registered')

  return (
    <AuthGuard permission="communication:view">
      <div className="page">
        <PageHeader
          icon={<MessagesSquare size={22} />}
          title={t('Komunikační studio', 'Communication Studio')}
          subtitle={t(
            'Jak banka mluví — hlas botů i lidí na jednom místě. Fáze 1 je jen náhled: jádro promptu je zamčené a stylová vrstva se zatím nepublikuje.',
            'How the bank speaks — the voice of bots and people in one place. Phase 1 is a read-only mirror: the prompt core is locked and no style layer is published yet.',
          )}
          breadcrumb={
            <div className="breadcrumb">
              <span>OpenBank</span>
              <span className="breadcrumb-sep">/</span>
              <span className="breadcrumb-current">{t('Komunikace', 'Communication')}</span>
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
            feature={t('Komunikační studio', 'Communication Studio')}
            detail={t(
              'Registr promptů (openbank-libs/governance/prompts) není v tomto sestavení dostupný.',
              'The prompt registry (openbank-libs/governance/prompts) is not available in this build.',
            )}
          />
        )}

        {!failure && data && (
          <>
            <div className="card" style={{ marginBottom: '16px' }}>
              <div className="flex items-center gap-3">
                <Lock size={16} />
                <p style={{ fontSize: '13px', color: 'var(--text-secondary)', margin: 0 }}>
                  {t(
                    'Jádro promptu (bezpečnostní pravidla, obrana proti injekci, směrování nástrojů, HITL a SCA) vlastní inženýrství v gitu a nelze ho měnit z žádného rozhraní. Editovatelné vrstvy stylu a playbooku dodá openbank-communication-service ve fázi 2 a 3 podle ADR-0285.',
                    'The prompt core (safety rules, injection defence, tool routing, HITL and SCA) is owned by engineering in git and is not writable through any interface. The editable style and playbook layers arrive with openbank-communication-service in phases 2 and 3 per ADR-0285.',
                  )}
                </p>
              </div>
            </div>

            <div className="grid-4" style={{ marginBottom: '16px' }}>
              <div className="stat-card">
                <div className="stat-label">{t('Persony celkem', 'Personas total')}</div>
                <div className="stat-value">{personas.length}</div>
              </div>
              <div className="stat-card">
                <div className="stat-label">{t('S promptem v registru', 'With a registered prompt')}</div>
                <div className="stat-value">{speaking.length}</div>
              </div>
              <div className="stat-card">
                <div className="stat-label">{t('Publikovaný styl', 'Published style')}</div>
                <div className="stat-value">{personas.filter(p => p.editableLayers === 'published').length}</div>
              </div>
              <div className="stat-card">
                <div className="stat-label">{t('Verze promptů', 'Prompt versions')}</div>
                <div className="stat-value">{personas.reduce((sum, p) => sum + p.versionCount, 0)}</div>
              </div>
            </div>

            <div className="card">
              <table className="table">
                <thead>
                  <tr>
                    <th>{t('Persona', 'Persona')}</th>
                    <th>{t('Rovina', 'Plane')}</th>
                    <th>{t('Pokrytí registrem', 'Registry coverage')}</th>
                    <th>{t('Verze', 'Versions')}</th>
                    <th>{t('Stylová vrstva', 'Style layer')}</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {personas.map(persona => (
                    <tr key={persona.id}>
                      <td>
                        <Link href={`/communication/${persona.id}`} style={{ fontWeight: 600 }}>{persona.id}</Link>
                        {persona.charter && <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{persona.charter}</div>}
                      </td>
                      <td>{persona.plane ?? '—'}</td>
                      <td><span className={`badge ${STATUS_BADGE[persona.status]}`}>{persona.status}</span></td>
                      <td>{persona.versionIds.length ? persona.versionIds.join(', ') : '—'}</td>
                      <td>
                        <span className="badge badge-neutral">
                          {persona.editableLayers === 'published'
                            ? t('publikováno', 'published')
                            : t('zatím nepublikováno', 'not published yet')}
                        </span>
                      </td>
                      <td>
                        <Link href={`/communication/${persona.id}`} aria-label={t(`Detail persony ${persona.id}`, `Persona detail ${persona.id}`)}>
                          <ChevronRight size={16} />
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
    </AuthGuard>
  )
}
