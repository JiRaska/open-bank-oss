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
  const [nameTouched, setNameTouched] = useState(false)
  const [tenureTouched, setTenureTouched] = useState(false)
  // React state is rendered asynchronously. Keep the one operator action single-flight before a
  // second click can observe the disabled button; audience creation otherwise allocates a new,
  // governed version on each request.
  const createInFlight = useRef(false)

  const create = async () => {
    const validName = /^[a-z0-9][a-z0-9-]*$/.test(name)
    const validTenure = minDays.trim() === '' || (Number.isInteger(Number(minDays)) && Number(minDays) >= 0)
    if (!validName || !validTenure) {
      setNameTouched(true)
      setTenureTouched(true)
      return
    }
    if (createInFlight.current) return
    createInFlight.current = true
    setSaving(true); setError(null)
    const rules: Array<Record<string, unknown>> = [{ type: 'PARTY_STATUS_IS', status }]
    if (minDays.trim() !== '') rules.push({ type: 'TENURE_AT_LEAST_DAYS', minDays: Number(minDays) })
    try {
      const response = await fetch('/api/audiences', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name, rules }) })
      if (!response.ok) {
        if (response.status === 401) setError(t('Vaše přihlášení vypršelo. Přihlaste se a návrh odešlete znovu.', 'Your session has expired. Sign in and submit the draft again.'))
        else if (response.status === 403) setError(t('Vaše role nemá oprávnění vytvářet publika. Požádejte vlastníka kampaní o přístup.', 'Your role cannot create audiences. Ask a campaign owner for access.'))
        else if (response.status === 409) setError(t('Publikum s tímto názvem již existuje. Zvolte jiný popisný název.', 'An audience with this name already exists. Choose another descriptive name.'))
        else if (response.status === 400 || response.status === 422) setError(t('Služba pravidla nepřijala. Zkontrolujte název a délku vztahu.', 'The service did not accept these rules. Check the name and relationship age.'))
        else setError(t('Návrh se nepodařilo vytvořit. Vaše údaje zůstaly ve formuláři; zkuste to znovu.', 'The draft could not be created. Your entries remain in the form; try again.'))
        return
      }
      router.push('/segments')
    } catch {
      setError(t('Služba publik není dostupná. Vaše údaje zůstaly ve formuláři; zkuste to znovu.', 'The audience service is unavailable. Your entries remain in the form; try again.'))
    } finally {
      createInFlight.current = false
      setSaving(false)
    }
  }

  const validName = /^[a-z0-9][a-z0-9-]*$/.test(name)
  const validTenure = minDays.trim() === '' || (Number.isInteger(Number(minDays)) && Number(minDays) >= 0)

  return <AuthGuard permission="campaign:create">
    <div className="mx-auto max-w-4xl space-y-6">
      <Link href="/segments" className="inline-flex items-center gap-2 text-sm font-medium text-[var(--text-tertiary)] transition hover:text-[var(--accent-text)]"><ArrowLeft className="h-4 w-4" />{t('Zpět do knihovny publik', 'Back to audience library')}</Link>
      <PageHeader title={t('Nové publikum', 'New audience')} subtitle={t('Sestavte bezpečný návrh z pravidel, která platforma umí skutečně vyhodnotit.', 'Compose a safe draft from rules the platform can actually evaluate.')} icon={<Users className="h-6 w-6" />} />
      <section className="grid gap-5 lg:grid-cols-[1fr_.72fr]">
        <form noValidate onSubmit={e => { e.preventDefault(); void create() }} className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6 shadow-sm" data-audience-create-form>
          <p className="text-xs font-bold uppercase tracking-[.12em] text-[var(--accent-text)]">{t('Návrh publika', 'Audience draft')}</p>
          <h2 className="mt-2 text-xl font-semibold tracking-tight text-[var(--text-primary)]">{t('Koho chcete oslovit?', 'Who should enter?')}</h2>
          <label htmlFor="segment-name" className="mt-6 block text-sm font-semibold text-[var(--text-secondary)]">{t('Název', 'Name')}<input id="segment-name" value={name} onChange={e => { setName(e.target.value); setError(null) }} onBlur={() => setNameTouched(true)} aria-invalid={nameTouched && !validName} aria-describedby="segment-name-help segment-name-error" placeholder="new-savers" className="input mt-2 w-full font-mono text-sm" /></label>
          <p id="segment-name-help" className="mt-1 text-xs text-[var(--text-tertiary)]">{t('Malá písmena, čísla a pomlčky. Po schválení dostane tento název neměnnou verzi.', 'Lowercase letters, digits and hyphens. Approval creates an immutable version under this name.')}</p>
          <p id="segment-name-error" aria-live="polite" className="mt-1 min-h-4 text-xs font-medium text-[var(--danger-text)]">{nameTouched && !validName ? t('Začněte písmenem nebo číslicí a použijte jen malá písmena, číslice a pomlčky.', 'Start with a letter or number and use only lowercase letters, digits, and hyphens.') : ''}</p>
          <fieldset className="mt-6 space-y-4"><legend className="text-sm font-semibold text-[var(--text-secondary)]">{t('Pravidla výběru', 'Selection rules')}</legend>
            <label htmlFor="segment-status" className="block rounded-xl border border-[var(--accent-border)] bg-[var(--accent-bg)] p-4 text-sm text-[var(--text-secondary)]"><span className="font-semibold">{t('Stav zákazníka', 'Customer status')}</span><select id="segment-status" value={status} onChange={e => setStatus(e.target.value)} className="input mt-3 block w-full"><option value="ACTIVE">{t('Aktivní', 'Active')}</option><option value="PENDING_KYC">{t('Čeká na KYC', 'Pending KYC')}</option><option value="SUSPENDED">{t('Pozastavený', 'Suspended')}</option></select></label>
            <label htmlFor="segment-min-days" className="block rounded-xl border border-[var(--border)] p-4 text-sm text-[var(--text-secondary)]"><span className="font-semibold">{t('Minimální délka vztahu (volitelné)', 'Minimum relationship age (optional)')}</span><input id="segment-min-days" inputMode="numeric" value={minDays} onChange={e => { setMinDays(e.target.value); setError(null) }} onBlur={() => setTenureTouched(true)} aria-invalid={tenureTouched && !validTenure} aria-describedby="segment-tenure-help segment-tenure-error" placeholder="30" className="input mt-3 block w-full" /><span id="segment-tenure-help" className="mt-2 block text-xs text-[var(--text-tertiary)]">{t('Prázdné = bez omezení podle délky vztahu.', 'Blank = no relationship-age restriction.')}</span><span id="segment-tenure-error" aria-live="polite" className="mt-1 block min-h-4 text-xs font-medium text-[var(--danger-text)]">{tenureTouched && !validTenure ? t('Zadejte celé nezáporné číslo dnů, například 30.', 'Enter a whole non-negative number of days, such as 30.') : ''}</span></label>
          </fieldset>
          {error && <p role="alert" className="mt-4 rounded-xl border border-[var(--danger-border)] bg-[var(--danger-bg)] p-3 text-sm text-[var(--danger-text)]">{error}</p>}
          <button type="submit" aria-busy={saving} aria-label={saving ? t('Ukládám návrh publika', 'Saving audience draft') : t('Vytvořit návrh publika', 'Create audience draft')} disabled={!validName || !validTenure || saving} className="btn btn-primary mt-6"><CheckCircle2 aria-hidden="true" className="h-4 w-4" />{saving ? t('Ukládám…', 'Saving…') : t('Vytvořit návrh', 'Create draft')}</button>
        </form>
        <aside className="rounded-2xl border border-[var(--success-border)] p-6 shadow-sm" style={{ background: 'linear-gradient(160deg, var(--success-bg), var(--surface))' }} data-audience-create-guidance><ShieldCheck className="h-6 w-6 text-[var(--success-text)]" /><h2 className="mt-4 text-lg font-semibold tracking-tight text-[var(--text-primary)]">{t('Co se stane dál', 'What happens next')}</h2><ol className="mt-4 space-y-4 text-sm leading-6 text-[var(--text-secondary)]"><li><strong className="text-[var(--text-primary)]">1. {t('Návrh', 'Draft')}</strong><br />{t('Můžete bezpečně zkontrolovat aktuální dosah stejným evaluátorem jako při zařazení do kampaně.', 'You can safely check current reach with the same evaluator used for campaign enrolment.')}</li><li><strong className="text-[var(--text-primary)]">2. {t('Schválení', 'Approval')}</strong><br />{t('Jiný člověk schválí přesnou, verzovanou definici. Autor ji nemůže schválit sám.', 'A different person approves the exact versioned definition. The maker cannot approve it.')}</li><li><strong className="text-[var(--text-primary)]">3. {t('Použití', 'Use')}</strong><br />{t('Teprve schválené publikum lze vybrat do kampaně; souhlas a limity se stále ověřují při doručení.', 'Only an approved audience can be chosen in a campaign; consent and caps are still checked at delivery.')}</li></ol></aside>
      </section>
    </div>
  </AuthGuard>
}
