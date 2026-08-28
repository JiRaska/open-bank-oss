// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowRight, Gift, ShieldCheck, Users } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { useLanguage } from '@/lib/i18n/LanguageContext'

interface ReferralProgram {
  id: string
  name: string
  version: number
}

export default function ReferralProgramsPage() {
  const { t } = useLanguage()
  const [items, setItems] = useState<ReferralProgram[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)

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
  }, [])

  return <AuthGuard permission="campaign:view">
    <div className="space-y-6">
      <PageHeader
        title={t('MGM programy', 'Member-get-member programs')}
        subtitle={t('Vyberte schválenou verzi programu doporučení. Odměna i kvalifikační událost zůstávají uzamčené v auditovatelném kontraktu.', 'Choose an approved referral-program version. Reward and qualification stay locked in an auditable contract.')}
        icon={<Gift className="h-6 w-6" />}
      />

      <section className="grid gap-4 md:grid-cols-3" aria-label={t('Zásady MGM', 'MGM principles')}>
        <div className="rounded-2xl border border-violet-100 bg-violet-50 p-5"><ShieldCheck className="h-5 w-5 text-violet-700" /><p className="mt-3 font-semibold">{t('Pouze publikované verze', 'Published versions only')}</p><p className="mt-1 text-sm text-slate-600">{t('Autor a schvalovatel musí být různí lidé.', 'Maker and checker must be different people.')}</p></div>
        <div className="rounded-2xl border border-slate-200 bg-white p-5"><Users className="h-5 w-5 text-emerald-600" /><p className="mt-3 font-semibold">{t('Bez self-referral', 'No self-referral')}</p><p className="mt-1 text-sm text-slate-600">{t('Atribuce odmítá doporučení sebe sama a opakované použití.', 'Attribution rejects self-referral and replay.')}</p></div>
        <div className="rounded-2xl border border-slate-200 bg-white p-5"><Gift className="h-5 w-5 text-amber-600" /><p className="mt-3 font-semibold">{t('Peníze mimo kampaň', 'Money outside campaigns')}</p><p className="mt-1 text-sm text-slate-600">{t('Kampaň odkazuje na program; nemůže přepsat jeho odměnu.', 'A campaign references a program; it cannot override its reward.')}</p></div>
      </section>

      {loading && <p className="text-sm text-muted-foreground">{t('Načítám katalog…', 'Loading catalogue…')}</p>}
      {!loading && unavailable && <DataUnavailable kind={unavailable} service="Referral-service" feature={t('MGM programy', 'MGM programs')} />}
      {!loading && !unavailable && items.length === 0 && <p className="text-sm text-muted-foreground">{t('Zatím není publikovaný žádný MGM program.', 'No MGM program has been published yet.')}</p>}

      {!loading && !unavailable && items.length > 0 && <section className="grid gap-4 xl:grid-cols-2" aria-label={t('Katalog MGM programů', 'MGM program catalogue')}>
        {items.map(program => <article key={program.id} className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm" data-referral-program={`${program.name}@${program.version}`}>
          <div><p className="text-xs font-bold uppercase tracking-[.12em] text-emerald-700">{t('Publikováno', 'Published')} · v{program.version}</p><h2 className="mt-1 text-lg font-semibold">{program.name}</h2></div>
          <p className="mt-5 rounded-xl bg-slate-50 p-4 text-sm text-slate-600">{t('Katalog zveřejňuje pouze neměnnou referenci. Odměnu, kvalifikaci a schvalování spravuje Referral Service.', 'The catalogue publishes only the immutable reference. Referral Service owns reward, qualification, and approval.')}</p>
          <Link href={`/campaigns/new?referralProgram=${encodeURIComponent(program.id)}`} className="mt-4 inline-flex items-center gap-2 text-sm font-semibold text-violet-700" data-use-referral-program={program.id}>{t('Použít v nové kampani', 'Use in a new campaign')} <ArrowRight className="h-4 w-4" /></Link>
        </article>)}
      </section>}
    </div>
  </AuthGuard>
}
