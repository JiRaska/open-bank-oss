// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useRef, useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { ArrowLeft, CheckCircle2, ShieldCheck, Users } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader } from '@/components/ui'
import { AuthGuard } from '@/components/auth/AuthGuard'

/** A deliberately small, typed composer. It sends no query language or arbitrary JSON path. */
export default function NewAudiencePage() {
  const { t } = useLanguage()
  const router = useRouter()
  const [name, setName] = useState('')
  const [status, setStatus] = useState('ACTIVE')
  const [minDays, setMinDays] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // React state is rendered asynchronously. Keep the one operator action single-flight before a
  // second click can observe the disabled button; audience creation otherwise allocates a new,
  // governed version on each request.
  const createInFlight = useRef(false)

  const create = async () => {
    if (createInFlight.current) return
    createInFlight.current = true
    setSaving(true); setError(null)
    const rules: Array<Record<string, unknown>> = [{ type: 'PARTY_STATUS_IS', status }]
    if (minDays.trim() !== '') rules.push({ type: 'TENURE_AT_LEAST_DAYS', minDays: Number(minDays) })
    try {
      const response = await fetch('/api/audiences', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name, rules }) })
      if (!response.ok) throw new Error((await response.json()).error ?? 'Unable to create audience')
      router.push('/segments')
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to create audience') } finally {
      createInFlight.current = false
      setSaving(false)
    }
  }

  const validName = /^[a-z0-9][a-z0-9-]*$/.test(name)
  const validTenure = minDays.trim() === '' || (Number.isInteger(Number(minDays)) && Number(minDays) >= 0)

  return <AuthGuard permission="campaign:create">
    <div className="mx-auto max-w-4xl space-y-6">
      <Link href="/segments" className="inline-flex items-center gap-2 text-sm font-medium text-slate-500 transition hover:text-violet-700"><ArrowLeft className="h-4 w-4" />{t('Zpět do knihovny publik', 'Back to audience library')}</Link>
      <PageHeader title={t('Nové publikum', 'New audience')} subtitle={t('Sestavte bezpečný návrh z pravidel, která platforma umí skutečně vyhodnotit.', 'Compose a safe draft from rules the platform can actually evaluate.')} icon={<Users className="h-6 w-6" />} />
      <section className="grid gap-5 lg:grid-cols-[1fr_.72fr]">
        <form onSubmit={e => { e.preventDefault(); void create() }} className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
          <p className="text-xs font-bold uppercase tracking-[.12em] text-violet-700">{t('Návrh publika', 'Audience draft')}</p>
          <h2 className="mt-2 text-xl font-semibold tracking-tight text-slate-900">{t('Koho chcete oslovit?', 'Who should enter?')}</h2>
          <label htmlFor="segment-name" className="mt-6 block text-sm font-semibold text-slate-700">{t('Název', 'Name')}<input id="segment-name" value={name} onChange={e => setName(e.target.value)} placeholder="new-savers" className="mt-2 w-full rounded-xl border border-slate-200 px-3 py-2.5 font-mono text-sm outline-none transition focus:border-violet-500 focus:ring-4 focus:ring-violet-100" /></label>
          <p className="mt-1 text-xs text-slate-500">{t('Malá písmena, čísla a pomlčky. Po schválení dostane tento název neměnnou verzi.', 'Lowercase letters, digits and hyphens. Approval creates an immutable version under this name.')}</p>
          <fieldset className="mt-6 space-y-4"><legend className="text-sm font-semibold text-slate-700">{t('Pravidla výběru', 'Selection rules')}</legend>
            <label htmlFor="segment-status" className="block rounded-xl border border-violet-100 bg-violet-50/50 p-4 text-sm text-slate-700"><span className="font-semibold">{t('Stav zákazníka', 'Customer status')}</span><select id="segment-status" value={status} onChange={e => setStatus(e.target.value)} className="mt-3 block w-full rounded-lg border border-violet-200 bg-white px-3 py-2"><option value="ACTIVE">{t('Aktivní', 'Active')}</option><option value="PENDING_KYC">{t('Čeká na KYC', 'Pending KYC')}</option><option value="SUSPENDED">{t('Pozastavený', 'Suspended')}</option></select></label>
            <label htmlFor="segment-min-days" className="block rounded-xl border border-slate-200 p-4 text-sm text-slate-700"><span className="font-semibold">{t('Minimální délka vztahu (volitelné)', 'Minimum relationship age (optional)')}</span><input id="segment-min-days" inputMode="numeric" value={minDays} onChange={e => setMinDays(e.target.value)} placeholder="30" className="mt-3 block w-full rounded-lg border border-slate-200 px-3 py-2" /><span className="mt-2 block text-xs text-slate-500">{t('Prázdné = bez omezení podle délky vztahu.', 'Blank = no relationship-age restriction.')}</span></label>
          </fieldset>
          {error && <p role="alert" className="mt-4 rounded-xl bg-rose-50 p-3 text-sm text-rose-700">{error}</p>}
          <button type="submit" aria-busy={saving} aria-label={saving ? t('Ukládám návrh publika', 'Saving audience draft') : t('Vytvořit návrh publika', 'Create audience draft')} disabled={!validName || !validTenure || saving} className="mt-6 inline-flex items-center gap-2 rounded-xl bg-slate-900 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-violet-700 disabled:cursor-not-allowed disabled:opacity-40"><CheckCircle2 aria-hidden="true" className="h-4 w-4" />{saving ? t('Ukládám…', 'Saving…') : t('Vytvořit návrh', 'Create draft')}</button>
        </form>
        <aside className="rounded-2xl border border-emerald-100 bg-[linear-gradient(160deg,#f6fffb,#fff)] p-6 shadow-sm"><ShieldCheck className="h-6 w-6 text-emerald-600" /><h2 className="mt-4 text-lg font-semibold tracking-tight text-slate-900">{t('Co se stane dál', 'What happens next')}</h2><ol className="mt-4 space-y-4 text-sm leading-6 text-slate-600"><li><strong className="text-slate-900">1. {t('Návrh', 'Draft')}</strong><br />{t('Můžete bezpečně zkontrolovat aktuální dosah stejným evaluátorem jako při zařazení do kampaně.', 'You can safely check current reach with the same evaluator used for campaign enrolment.')}</li><li><strong className="text-slate-900">2. {t('Schválení', 'Approval')}</strong><br />{t('Jiný člověk schválí přesnou, verzovanou definici. Autor ji nemůže schválit sám.', 'A different person approves the exact versioned definition. The maker cannot approve it.')}</li><li><strong className="text-slate-900">3. {t('Použití', 'Use')}</strong><br />{t('Teprve schválené publikum lze vybrat do kampaně; souhlas a limity se stále ověřují při doručení.', 'Only an approved audience can be chosen in a campaign; consent and caps are still checked at delivery.')}</li></ol></aside>
      </section>
    </div>
  </AuthGuard>
}
