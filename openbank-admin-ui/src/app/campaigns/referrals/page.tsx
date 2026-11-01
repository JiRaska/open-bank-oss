// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowRight, Gift, Plus, ShieldCheck, Users } from 'lucide-react'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { useLanguage } from '@/lib/i18n/LanguageContext'

interface ReferralProgram {
  id: string
  name: string
  version: number
}

// A DRAFT concept from the approval queue — the full program shape, not just the catalogue's
// immutable reference: the approver needs the reward and qualification to sanity-check what
// they are about to publish.
interface ReferralProgramDraft {
  id: string
  name: string
  version: number
  rewardAmount?: number | string
  currency?: string
  qualifyingEvent?: string
  attributionWindowEndsAt?: string
  maker?: string
}

export default function ReferralProgramsPage() {
  const { t } = useLanguage()
  const [items, setItems] = useState<ReferralProgram[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)
  // null = still loading. Every non-ok state (unauthorized, not_deployed, unreachable) renders
  // as NO section: drafts are additive to the published catalogue, and a campaign:view-only
  // persona (e.g. an auditor) must not get an error panel for a queue they have no business in.
  const [drafts, setDrafts] = useState<{ items: ReferralProgramDraft[]; state: string } | null>(null)
  const [publishError, setPublishError] = useState<Record<string, string>>({})

  useEffect(() => {
    fetch('/api/referral-programs')
      .then(response => response.json())
      .then((body: { items?: ReferralProgram[]; state?: string }) => {
        if (body.state !== 'ok') {
          setUnavailable(body.state === 'unauthorized' ? 'unauthorized' : body.state === 'not_deployed' ? 'not_deployed' : 'unreachable')
          return
        }
        setItems(body.items ?? [])
      })
      .catch(() => setUnavailable('unreachable'))
      .finally(() => setLoading(false))

    fetch('/api/referral-programs/drafts')
      .then(response => response.json())
      .then((body: { items?: ReferralProgramDraft[]; state?: string }) => {
        setDrafts({ items: body.state === 'ok' ? body.items ?? [] : [], state: body.state ?? 'unreachable' })
      })
      .catch(() => setDrafts({ items: [], state: 'unreachable' }))
  }, [])

  const reload = () => Promise.all([
    fetch('/api/referral-programs')
      .then(response => response.json())
      .then((body: { items?: ReferralProgram[]; state?: string }) => {
        if (body.state === 'ok') setItems(body.items ?? [])
      })
      .catch(() => undefined),
    fetch('/api/referral-programs/drafts')
      .then(response => response.json())
      .then((body: { items?: ReferralProgramDraft[]; state?: string }) => {
        setDrafts({ items: body.state === 'ok' ? body.items ?? [] : [], state: body.state ?? 'unreachable' })
      })
      .catch(() => undefined),
  ])

  // The service refuses a transition with the domain invariant that blocked it ("maker cannot
  // publish their own program"), and that sentence is shown verbatim — a four-eyes refusal must
  // not read like an outage.
  const publish = (program: { id: string }) => {
    setPublishError(previous => ({ ...previous, [program.id]: '' }))
    fetch(`/api/referral-programs/${encodeURIComponent(program.id)}/publish`, { method: 'POST' })
      .then(response => response.json())
      .then(async (body: { state?: string; error?: string; message?: string }) => {
        if (body.state === 'ok') {
          setPublishError(previous => ({ ...previous, [program.id]: '' }))
          await reload()
          return
        }
        const detail = body.state === 'rejected' && body.message
          ? body.message
          : body.state === 'unauthorized'
            ? t('Nemáte oprávnění publikovat.', 'You are not allowed to publish.')
            : t('Referral-service není dostupný.', 'Referral-service is unavailable.')
        setPublishError(previous => ({ ...previous, [program.id]: detail }))
      })
      .catch(() => setPublishError(previous => ({
        ...previous,
        [program.id]: t('Referral-service není dostupný.', 'Referral-service is unavailable.'),
      })))
  }

  return <AuthGuard permission="campaign:view">
    <div className="space-y-6">
      <PageHeader
        title={t('MGM programy', 'Member-get-member programs')}
        subtitle={t('Vyberte schválenou verzi programu doporučení. Odměna i kvalifikační událost zůstávají uzamčené v auditovatelném kontraktu.', 'Choose an approved referral-program version. Reward and qualification stay locked in an auditable contract.')}
        icon={<Gift className="h-6 w-6" />}
        actions={
          <Can permission="referral:program:create">
            <Link href="/campaigns/referrals/new" className="btn btn-primary" style={{ fontSize: 12 }}>
              <Plus className="h-4 w-4" /> {t('Nový MGM program', 'New MGM program')}
            </Link>
          </Can>
        }
      />

      <section className="grid gap-4 md:grid-cols-3" aria-label={t('Zásady MGM', 'MGM principles')}>
        <div className="rounded-2xl border border-violet-100 bg-violet-50 p-5 dark:border-violet-800 dark:bg-violet-950"><ShieldCheck className="h-5 w-5 text-violet-700 dark:text-violet-300" /><p className="mt-3 font-semibold">{t('Pouze publikované verze', 'Published versions only')}</p><p className="mt-1 text-sm text-slate-600 dark:text-slate-300">{t('Autor a schvalovatel musí být různí lidé.', 'Maker and checker must be different people.')}</p></div>
        <div className="rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900"><Users className="h-5 w-5 text-emerald-600 dark:text-emerald-300" /><p className="mt-3 font-semibold">{t('Bez self-referral', 'No self-referral')}</p><p className="mt-1 text-sm text-slate-600 dark:text-slate-300">{t('Atribuce odmítá doporučení sebe sama a opakované použití.', 'Attribution rejects self-referral and replay.')}</p></div>
        <div className="rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900"><Gift className="h-5 w-5 text-amber-600 dark:text-amber-300" /><p className="mt-3 font-semibold">{t('Peníze mimo kampaň', 'Money outside campaigns')}</p><p className="mt-1 text-sm text-slate-600 dark:text-slate-300">{t('Kampaň odkazuje na program; nemůže přepsat jeho odměnu.', 'A campaign references a program; it cannot override its reward.')}</p></div>
      </section>

      {loading && <p className="text-sm text-muted-foreground">{t('Načítám katalog…', 'Loading catalogue…')}</p>}
      {!loading && unavailable && <DataUnavailable kind={unavailable} service="Referral-service" feature={t('MGM programy', 'MGM programs')} />}
      {!loading && !unavailable && items.length === 0 && <p className="text-sm text-muted-foreground">{t('Zatím není publikovaný žádný MGM program.', 'No MGM program has been published yet.')}</p>}

      {drafts && drafts.state === 'ok' && drafts.items.length > 0 && <section className="space-y-3" aria-label={t('Koncepty čekající na schválení', 'Drafts awaiting approval')}>
        <h2 className="text-base font-semibold">{t('Koncepty čekající na schválení', 'Drafts awaiting approval')}</h2>
        <div className="grid gap-4 xl:grid-cols-2">
          {drafts.items.map(draft => <article key={draft.id} className="rounded-2xl border border-amber-200 bg-white p-5 shadow-sm dark:border-amber-800 dark:bg-slate-900" data-referral-draft={`${draft.name}@${draft.version}`}>
            <div>
              <p className="text-xs font-bold uppercase tracking-[.12em] text-amber-700 dark:text-amber-300">{t('Koncept', 'Draft')} · v{draft.version}</p>
              <h3 className="mt-1 text-lg font-semibold">{draft.name}</h3>
            </div>
            <dl className="mt-4 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
              <div><dt className="text-muted-foreground">{t('Odměna', 'Reward')}</dt><dd>{draft.rewardAmount ?? '—'} {draft.currency ?? ''}</dd></div>
              <div><dt className="text-muted-foreground">{t('Kvalifikační událost', 'Qualifying event')}</dt><dd className="font-mono text-xs">{draft.qualifyingEvent ?? '—'}</dd></div>
              <div><dt className="text-muted-foreground">{t('Platnost atribuce do', 'Attribution window ends')}</dt><dd>{draft.attributionWindowEndsAt ? new Date(draft.attributionWindowEndsAt).toLocaleDateString() : '—'}</dd></div>
              <div><dt className="text-muted-foreground">{t('Autor', 'Author')}</dt><dd>{draft.maker ?? '—'}</dd></div>
            </dl>
            <Can permission="referral:program:publish">
              <div className="mt-4 border-t border-slate-100 pt-4 dark:border-slate-800">
                <button
                  type="button"
                  onClick={() => publish(draft)}
                  className="rounded-md border border-emerald-200 px-3 py-1.5 text-sm font-semibold text-emerald-700 dark:border-emerald-800 dark:text-emerald-300"
                  data-publish-referral-draft={draft.id}
                >
                  {t('Publikovat', 'Publish')}
                </button>
                {publishError[draft.id] && (
                  <p role="alert" className="mt-2 text-sm text-amber-700 dark:text-amber-300" data-publish-error={draft.id}>
                    {t('Publikování odmítnuto', 'Publishing refused')}: {publishError[draft.id]}
                    {/maker/i.test(publishError[draft.id]) && (
                      <>
                        {' — '}
                        {t('Tuto verzi musí publikovat jiný člověk — vy jste autorem konceptu.', 'A different person must publish this version — you authored the draft.')}
                      </>
                    )}
                  </p>
                )}
              </div>
            </Can>
          </article>)}
        </div>
      </section>}

      {!loading && !unavailable && items.length > 0 && <section className="grid gap-4 xl:grid-cols-2" aria-label={t('Katalog MGM programů', 'MGM program catalogue')}>
        {items.map(program => <article key={program.id} className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900" data-referral-program={`${program.name}@${program.version}`}>
          <div><p className="text-xs font-bold uppercase tracking-[.12em] text-emerald-700 dark:text-emerald-300">{t('Publikováno', 'Published')} · v{program.version}</p><h2 className="mt-1 text-lg font-semibold">{program.name}</h2></div>
          <p className="mt-5 rounded-xl bg-slate-50 p-4 text-sm text-slate-600 dark:bg-slate-800 dark:text-slate-300">{t('Katalog zveřejňuje pouze neměnnou referenci. Odměnu, kvalifikaci a schvalování spravuje Referral Service.', 'The catalogue publishes only the immutable reference. Referral Service owns reward, qualification, and approval.')}</p>
          <Link href={`/campaigns/new?referralProgram=${encodeURIComponent(program.id)}`} className="mt-4 inline-flex items-center gap-2 text-sm font-semibold text-violet-700 dark:text-violet-300" data-use-referral-program={program.id}>{t('Použít v nové kampani', 'Use in a new campaign')} <ArrowRight className="h-4 w-4" /></Link>
        </article>)}
      </section>}
    </div>
  </AuthGuard>
}
