// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One onboarding application, operator view (ADR-0334, pension-service API 1.2.0,
// GET /operator/onboarding/applications/{id}): the assessment outcome as the application records it
// — the recommended strategy, the one chosen, whether a riskier choice was acknowledged — and the
// key-information document the participant accepted and signed. Read-only: the questionnaire
// answers belong to the participant, and the operator sees the decision trail, not the answers.

'use client'

import { use, useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, ClipboardCheck } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { getJson, PENSION, pensionUrl } from '@/components/pension/api'
import { fieldOf, operatorApplicationSchema, type OperatorApplication } from '@/components/pension/contracts'
import { isUuid, statusLabel } from '@/components/pension/model'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionApplicationPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  return (
    <AuthGuard permission="pension:view">
      <Application id={id} />
    </AuthGuard>
  )
}

const ROWS: { key: string; cs: string; en: string }[] = [
  { key: 'kind', cs: 'Druh', en: 'Kind' },
  { key: 'productLine', cs: 'Produkt', en: 'Product' },
  { key: 'jurisdiction', cs: 'Jurisdikce', en: 'Jurisdiction' },
  { key: 'packVersion', cs: 'Verze balíčku pravidel', en: 'Rule-pack version' },
  { key: 'recommendedStrategy', cs: 'Doporučená strategie', en: 'Recommended strategy' },
  { key: 'chosenStrategy', cs: 'Zvolená strategie', en: 'Chosen strategy' },
  { key: 'unsuitableChoiceAcknowledged', cs: 'Rizikovější volba potvrzena', en: 'Riskier choice acknowledged' },
  { key: 'keyInformationDocumentId', cs: 'Dokument klíčových informací', en: 'Key-information document' },
  { key: 'keyInformationDocumentSha256', cs: 'Otisk dokumentu (SHA-256)', en: 'Document hash (SHA-256)' },
  { key: 'kidAcceptedAt', cs: 'Dokument přijat', en: 'Document accepted' },
  { key: 'signedAt', cs: 'Podepsáno (SCA)', en: 'Signed (SCA)' },
  { key: 'coolingOffEndsOn', cs: 'Konec lhůty na rozmyšlenou', en: 'Cooling-off ends' },
  { key: 'expiresOn', cs: 'Platnost žádosti do', en: 'Application expires' },
  { key: 'closedReason', cs: 'Důvod uzavření', en: 'Closed reason' },
  { key: 'rejectionReasons', cs: 'Důvody zamítnutí', en: 'Rejection reasons' },
]

function show(value: unknown, t: (cs: string, en: string) => string): string {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'boolean') return value ? t('ano', 'yes') : t('ne', 'no')
  if (Array.isArray(value)) return value.length === 0 ? '—' : value.map(String).join(', ')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function Application({ id }: { id: string }) {
  const { t, language } = useLanguage()
  const [data, setData] = useState<OperatorApplication | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const valid = isUuid(id)

  const load = useCallback(async () => {
    if (!valid) return
    const res = await getJson(pensionUrl(`/operator/onboarding/applications/${encodeURIComponent(id)}`), operatorApplicationSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setData(null); return }
    setUnavailable(null)
    setData(res.data)
  }, [id, valid])

  useEffect(() => { void load() }, [load])

  const app = data?.application ?? {}
  const status = fieldOf(app, 'status')
  const contractId = fieldOf(app, 'contractId')

  return (
    <div>
      <Link href="/pension/queues" style={{ display: 'inline-flex', gap: 4, alignItems: 'center', fontSize: 13, marginBottom: 8 }}>
        <ArrowLeft size={14} aria-hidden="true" /> {t('Zpět na frontu', 'Back to the queue')}
      </Link>
      <PageHeader
        title={t('Žádost o sjednání', 'Onboarding application')}
        subtitle={id}
        icon={<ClipboardCheck size={20} aria-hidden="true" />}
      />
      {!valid ? (
        <div role="alert" className="card">{t('ID žádosti není platné UUID.', 'The application id is not a valid UUID.')}</div>
      ) : unavailable ? (
        <DataUnavailable kind={unavailable.kind} service={PENSION} feature={t('žádost o sjednání', 'onboarding application')} lang={language} />
      ) : data === null ? null : (
        <section className="card">
          <p style={{ marginTop: 0 }}>
            {t('Stav', 'Status')}: <strong>{typeof status === 'string' ? statusLabel(status, t) : '—'}</strong>
            {typeof contractId === 'string' && (
              <> · <Link href={`/pension/contracts/${encodeURIComponent(contractId)}`}>{t('Smlouva', 'Contract')} {contractId}</Link></>
            )}
          </p>
          <dl style={{ display: 'grid', gridTemplateColumns: 'minmax(180px, max-content) 1fr', gap: '6px 16px', fontSize: 13, margin: 0 }}>
            {ROWS.map(r => (
              <div key={r.key} style={{ display: 'contents' }}>
                <dt style={{ color: 'var(--text-muted)' }}>{t(r.cs, r.en)}</dt>
                <dd style={{ margin: 0, wordBreak: 'break-all' }}>{show(fieldOf(app, r.key), t)}</dd>
              </div>
            ))}
          </dl>
        </section>
      )}
    </div>
  )
}
