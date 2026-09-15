// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useCallback, useEffect, useState } from 'react'
import { ArrowRight, Gift, Inbox, RefreshCw, ShieldCheck, Users } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { EmptyState, LoadingState, PageHeader } from '@/components/ui'
import { useLanguage } from '@/lib/i18n/LanguageContext'

interface ReferralProgram {
  id: string
  name: string
  version: number
  rewardAmount: number
  currency: string
  qualifyingEvent: string
  attributionWindowEndsAt: string
  status: 'PUBLISHED'
  checker: string
}

export default function ReferralProgramsPage() {
  const { t, language } = useLanguage()
  const [items, setItems] = useState<ReferralProgram[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)

  const loadPrograms = useCallback(async (showLoading = true) => {
    if (showLoading) {
      setLoading(true)
      setUnavailable(null)
    }
    await fetch('/api/referral-programs')
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
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadPrograms(false), 0)
    return () => window.clearTimeout(timer)
  }, [loadPrograms])

  const money = (program: ReferralProgram) => new Intl.NumberFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', {
    style: 'currency', currency: program.currency,
  }).format(program.rewardAmount)

  return <AuthGuard permission="campaign:view">
    <div className="space-y-6">
      <PageHeader
        title={t('MGM programy', 'Member-get-member programs')}
        subtitle={t('Vyberte schválenou verzi programu doporučení. Odměna i kvalifikační událost zůstávají uzamčené v auditovatelném kontraktu.', 'Choose an approved referral-program version. Reward and qualification stay locked in an auditable contract.')}
        icon={<Gift className="h-6 w-6" />}
      />

      <section className="grid gap-4 md:grid-cols-3" aria-label={t('Zásady MGM', 'MGM principles')}>
        <div className="rounded-2xl border border-[var(--accent-border)] bg-[var(--accent-bg)] p-5"><ShieldCheck className="h-5 w-5 text-[var(--accent-text)]" /><p className="mt-3 font-semibold text-[var(--text-primary)]">{t('Pouze publikované verze', 'Published versions only')}</p><p className="mt-1 text-sm text-[var(--text-secondary)]">{t('Autor a schvalovatel musí být různí lidé.', 'Maker and checker must be different people.')}</p></div>
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5"><Users className="h-5 w-5 text-[var(--success-text)]" /><p className="mt-3 font-semibold text-[var(--text-primary)]">{t('Bez self-referral', 'No self-referral')}</p><p className="mt-1 text-sm text-[var(--text-secondary)]">{t('Atribuce odmítá doporučení sebe sama a opakované použití.', 'Attribution rejects self-referral and replay.')}</p></div>
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5"><Gift className="h-5 w-5 text-[var(--warning-text)]" /><p className="mt-3 font-semibold text-[var(--text-primary)]">{t('Peníze mimo kampaň', 'Money outside campaigns')}</p><p className="mt-1 text-sm text-[var(--text-secondary)]">{t('Kampaň odkazuje na program; nemůže přepsat jeho odměnu.', 'A campaign references a program; it cannot override its reward.')}</p></div>
      </section>

      {loading && <LoadingState label={t('Načítám katalog MGM programů', 'Loading MGM programme catalogue')} description={t('Ověřuji publikované verze, uzamčenou odměnu a kvalifikační událost.', 'Checking published versions, locked rewards and qualification events.')} />}
      {!loading && unavailable && <DataUnavailable kind={unavailable} service="Referral-service" feature={t('MGM programy', 'MGM programs')} lang={language}><button type="button" className="btn btn-secondary" onClick={() => void loadPrograms()}><RefreshCw size={14} aria-hidden="true" />{t('Zkusit načíst znovu', 'Try loading again')}</button></DataUnavailable>}
      {!loading && !unavailable && items.length === 0 && <EmptyState icon={<Inbox size={24} />} title={t('Žádný program zatím není publikovaný', 'No programme has been published yet')} description={t('Koncept se zde nezobrazí: do kampaně lze připnout až nezávisle schválenou, neměnnou verzi referral programu.', 'Drafts are deliberately hidden here: a campaign can pin only an independently approved, immutable referral-program version.')} />}

      {!loading && !unavailable && items.length > 0 && <section className="grid gap-4 xl:grid-cols-2" aria-label={t('Katalog MGM programů', 'MGM program catalogue')}>
        {items.map(program => <article key={program.id} className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5 shadow-sm" data-referral-program={`${program.name}@${program.version}`}>
          <div className="flex items-start justify-between gap-4"><div><p className="text-xs font-bold uppercase tracking-[.12em] text-[var(--success-text)]">{t('Publikováno', 'Published')} · v{program.version}</p><h2 className="mt-1 text-lg font-semibold text-[var(--text-primary)]">{program.name}</h2></div><span className="rounded-xl bg-[var(--warning-bg)] px-3 py-2 text-lg font-bold text-[var(--warning-text)]">{money(program)}</span></div>
          <dl className="mt-5 grid gap-3 rounded-xl bg-[var(--surface-2)] p-4 text-sm sm:grid-cols-2"><div><dt className="text-xs text-[var(--text-tertiary)]">{t('Kvalifikace', 'Qualification')}</dt><dd className="mt-1 font-medium text-[var(--text-primary)]">{program.qualifyingEvent}</dd></div><div><dt className="text-xs text-[var(--text-tertiary)]">{t('Platí do', 'Window ends')}</dt><dd className="mt-1 font-medium text-[var(--text-primary)]">{new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', { dateStyle: 'medium' }).format(new Date(program.attributionWindowEndsAt))}</dd></div></dl>
          <p className="mt-4 inline-flex items-center gap-2 text-sm font-semibold text-[var(--accent-text)]">{t('Další krok: připnout tuto verzi ke kampani', 'Next: pin this version to a campaign')} <ArrowRight className="h-4 w-4" /></p>
        </article>)}
      </section>}
    </div>
  </AuthGuard>
}
