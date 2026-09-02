// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { ArrowRight, Clock3, Plus, ShieldCheck, Sparkles, Users } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { useSingleFlight, wasSkipped } from '@/lib/mutations/singleFlight'
import { trapDialogFocus } from '@/lib/a11y/trapDialogFocus'

// Read-only by design. ADR-0201 D1: a segment is a versioned artifact defined in code, reviewed and
// released like anything else — "no free-form SQL from a UI". A marketer picks from this catalogue;
// a new segment is a pull request. Preview exists so that choice is informed, not so it is editable.

interface Segment {
  name: string
  version: number
  rules: string[]
  state: 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED'
  createdBy: string
  approvedBy?: string | null
}

interface Preview {
  size?: number
  asOf?: string
  state: string
}

export default function SegmentsPage() {
  const { t, language } = useLanguage()
  const [items, setItems] = useState<Segment[]>([])
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)
  const [previews, setPreviews] = useState<Record<string, Preview | 'loading'>>({})
  const [lifecycleAction, setLifecycleAction] = useState<{ key: string; action: 'submit' | 'approve' } | null>(null)
  const [lifecycleError, setLifecycleError] = useState<string | null>(null)
  const [approvalIntent, setApprovalIntent] = useState<Segment | null>(null)
  const approvalTriggerRef = useRef<HTMLButtonElement | null>(null)
  const lifecycleFlight = useSingleFlight()

  const loadAudiences = useCallback(async (keepExistingOnFailure = false) => {
    try {
      const response = await fetch('/api/audiences')
      const d = await response.json() as { items: Segment[]; state: string }
      if (d.state !== 'ok') {
        if (!keepExistingOnFailure) setUnavailable(d.state === 'unauthorized' ? 'unauthorized' : d.state === 'not_deployed' ? 'not_deployed' : 'unreachable')
        return false
      }
      // Older catalogue rows were approved before lifecycle metadata existed. Treating an omitted
      // state as a draft would remove a previously targetable audience during a rolling rollout.
      setItems((d.items ?? []).map(item => ({ ...item, state: item.state ?? 'APPROVED' })))
      setUnavailable(null)
      return true
    } catch {
      if (!keepExistingOnFailure) setUnavailable('unreachable')
      return false
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadAudiences()
  }, [loadAudiences])

  const lifecycle = async (s: Segment, action: 'submit' | 'approve'): Promise<boolean> => {
    // Submit/approve are state transitions, not catalogue reads. One in-flight transition keeps
    // a double click or two cards from racing the same maker-checker lifecycle, while a failure
    // remains local to the action and never turns an already loaded catalogue into "unavailable".
    const audienceKey = key(s)
    let succeeded = false
    const outcome = await lifecycleFlight.run('audience:lifecycle', async () => {
      setLifecycleAction({ key: audienceKey, action })
      setLifecycleError(null)
      try {
        const response = await fetch(`/api/audiences/${encodeURIComponent(s.name)}/${s.version}/${action}`, { method: 'POST' })
        if (!response.ok) {
          const body = await response.json().catch(() => null) as { error?: string } | null
          throw new Error(body?.error || 'lifecycle mutation failed')
        }
        succeeded = true
        if (!await loadAudiences(true)) {
          setLifecycleError(t(
            'Stav se mohl změnit, ale katalog se nepodařilo obnovit. Zkuste načtení znovu.',
            'The state may have changed, but the catalogue could not be refreshed. Try loading it again.',
          ))
        }
      } catch {
        setLifecycleError(t(
          'Změna stavu publika se nepodařila. Katalog zůstává dostupný; zkuste akci znovu.',
          'The audience state change did not complete. The catalogue is still available; try the action again.',
        ))
      } finally {
        setLifecycleAction(null)
      }
    })
    if (wasSkipped(outcome)) return false
    return succeeded
  }

  const closeApprovalReview = () => {
    if (lifecycleFlight.busy) return
    setApprovalIntent(null)
    requestAnimationFrame(() => approvalTriggerRef.current?.focus())
  }

  const key = (s: Segment) => `${s.name}@${s.version}`

  // Previews are on demand, not eager: each one runs a real cohort evaluation against the silver
  // layer, so loading the page must not fire one per row.
  const loadPreview = (s: Segment) => {
    const k = key(s)
    setPreviews(p => ({ ...p, [k]: 'loading' }))
    fetch(`/api/audiences/${encodeURIComponent(s.name)}/${s.version}/preview`)
      .then(r => r.json())
      .then((d: Preview) => setPreviews(p => ({ ...p, [k]: d })))
      .catch(() => setPreviews(p => ({ ...p, [k]: { state: 'unreachable' } })))
  }

  const formatAsOf = (iso: string) =>
    new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', {
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(new Date(iso))

  // The service owns the stable identifier and the actual rules. These names only translate the
  // catalogue's current, known audience artifacts into a decision a marketer can scan. Unknown
  // future catalogue entries retain their service name — hiding an unlabelled audience would be
  // worse than showing its technical identity.
  const audienceName = (s: Segment) => {
    if (s.name === 'actives') return t('Aktivní zákazníci', 'Active customers')
    if (s.name === 'actives-tenured-30d') return t('Aktivní zákazníci 30+ dní', 'Active customers for 30+ days')
    return s.name
  }

  const audiencePurpose = (s: Segment) => {
    if (s.name === 'actives') return t('Široký výchozí okruh pro ověřenou produktovou nabídku.', 'A broad default audience for a verified product offer.')
    if (s.name === 'actives-tenured-30d') return t('Stabilnější publikum pro nabídky po prvním měsíci vztahu.', 'A more established audience for offers after the first month of a relationship.')
    return t('Verzované publikum s dohledatelným schválením.', 'A versioned audience with traceable approval.')
  }

  const renderPreview = (s: Segment) => {
    const p = previews[key(s)]
    if (!p) {
      return (
        <button
          type="button"
          onClick={() => loadPreview(s)}
          className="rounded-lg border border-violet-200 bg-white px-3 py-1.5 text-xs font-semibold text-violet-700 transition hover:border-violet-400 hover:bg-violet-50"
          data-audience-count={key(s)}
        >
          {t('Spočítat', 'Count')}
        </button>
      )
    }
    if (p === 'loading') return <span className="text-xs text-muted-foreground">{t('Počítám…', 'Counting…')}</span>
    if (p.state !== 'ok') {
      // Never render a failed preview as 0 — "nobody matches" is a business answer a marketer
      // would act on, and a 403 or a timeout is not that answer.
      return (
        <span className="text-xs text-amber-600">
          {p.state === 'unauthorized'
            ? t('Bez oprávnění', 'Not permitted')
            : p.state === 'unknown_segment'
              ? t('Neznámý segment', 'Unknown segment')
              : t('Nedostupné', 'Unavailable')}
        </span>
      )
    }
    return (
      <span className="text-sm" data-audience-size={key(s)}>
        <strong className="text-lg tracking-tight">{p.size?.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</strong>{' '}
        <span className="text-muted-foreground">{t('lidí nyní odpovídá', 'people match now')}</span>
        {p.asOf && (
          // The cohort moves as the silver layer moves; a number without its timestamp is a claim
          // with no time attached, which is what ADR-0201 D1's "provably a different version" rules out.
          <span className="ml-2 text-xs text-muted-foreground">{t('k', 'as of')} {formatAsOf(p.asOf)}</span>
        )}
      </span>
    )
  }

  return <AuthGuard permission="campaign:view">
    <div className="space-y-6">
      <PageHeader
        title={t('Publika', 'Audiences')}
        subtitle={t(
          'Vyberte publikum podle jeho záměru, ověřte aktuální dosah a přejděte rovnou k návrhu cesty.',
          'Choose an audience by intent, verify its current reach, then go straight to designing the journey.',
        )}
        icon={<Users className="h-6 w-6" />}
        actions={<Can permission="campaign:create"><Link href="/segments/new" className="inline-flex items-center gap-2 rounded-xl bg-slate-900 px-4 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-violet-700"><Plus className="h-4 w-4" />{t('Vytvořit publikum', 'Create audience')}</Link></Can>}
      />

      {loading && <p className="text-sm text-muted-foreground">{t('Načítám…', 'Loading…')}</p>}

      {!loading && unavailable && (
        <DataUnavailable kind={unavailable} service="Campaign-service" feature={t('Segmenty', 'Segments')} />
      )}

      {!loading && !unavailable && lifecycleError && !approvalIntent && (
        <p role="alert" className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
          {lifecycleError}
        </p>
      )}

      {!loading && !unavailable && items.length === 0 && (
        <p className="text-sm text-muted-foreground">{t('Katalog je prázdný.', 'The catalogue is empty.')}</p>
      )}

      {!loading && !unavailable && items.length > 0 && (
        <>
          <section className="grid gap-4 lg:grid-cols-[1.35fr_.65fr]">
            <div className="rounded-2xl border border-violet-100 bg-[radial-gradient(circle_at_top_left,_rgba(116,91,255,.18),_transparent_42%),linear-gradient(135deg,_#fff,_#f8f7ff)] p-5 shadow-sm">
              <p className="flex items-center gap-2 text-xs font-bold uppercase tracking-[.12em] text-violet-700"><Sparkles className="h-3.5 w-3.5" /> {t('Audience library', 'Audience library')}</p>
              <h2 className="mt-2 text-xl font-semibold tracking-tight text-slate-900">{t('Rozhodujte se nad skutečným dosahem, ne nad názvem segmentu.', 'Decide using real reach, not a segment name.')}</h2>
              <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-600">{t('Velikost se počítá stejným pravidlem, které následně zařazuje lidi do kampaně. Je to aktuální náhled, ne slib doručení.', 'Size uses the same rule that later enrols people into a campaign. It is a current preview, not a delivery promise.')}</p>
            </div>
            <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
              <ShieldCheck className="h-5 w-5 text-emerald-600" />
              <p className="mt-3 text-sm font-semibold text-slate-900">{t('Bezpečné publikum', 'Safe audiences')}</p>
              <p className="mt-1 text-xs leading-5 text-slate-500">{t('Pravidla jsou uzavřená a typovaná. Verze se stává použitelnou až po schválení jiným člověkem; souhlas a frekvenční ochrany se vyhodnotí znovu při odeslání.', 'Rules are closed and typed. A version becomes targetable only after a different person approves it; consent and frequency protections are evaluated again at send time.')}</p>
            </div>
          </section>

          <section className="grid gap-4 xl:grid-cols-2" aria-label={t('Katalog publik', 'Audience catalogue')}>
            {items.map(s => (
              <article key={key(s)} className="group rounded-2xl border border-slate-200 bg-white p-5 shadow-sm transition duration-200 hover:-translate-y-0.5 hover:border-violet-200 hover:shadow-lg hover:shadow-violet-950/5" data-audience-card={key(s)}>
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <p className="text-[.68rem] font-bold uppercase tracking-[.12em] text-slate-400">{s.state === 'APPROVED' ? t('Schválené publikum', 'Approved audience') : s.state === 'PENDING_APPROVAL' ? t('Čeká na schválení', 'Awaiting approval') : t('Rozpracované publikum', 'Draft audience')} · v{s.version}</p>
                    <h2 className="mt-1 text-lg font-semibold tracking-tight text-slate-900">{audienceName(s)}</h2>
                    <p className="mt-1 text-sm leading-5 text-slate-500">{audiencePurpose(s)}</p>
                  </div>
                  <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-violet-50 text-violet-700"><Users className="h-5 w-5" /></span>
                </div>

                <div className="mt-5 rounded-xl bg-slate-50 p-3">
                  <p className="text-[.68rem] font-bold uppercase tracking-[.1em] text-slate-400">{t('Pravidla výběru', 'Selection rules')}</p>
                  <ul className="mt-2 space-y-1.5 text-sm text-slate-600">
                    {s.rules.map(rule => <li key={rule} className="flex gap-2"><span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-violet-500" />{rule}</li>)}
                  </ul>
                </div>

                <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
                  <div className="min-h-8">{renderPreview(s)}</div>
                  {s.state === 'APPROVED' ? <Link
                    href={`/campaigns/new?audience=${encodeURIComponent(key(s))}`}
                    className="inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-3 py-2 text-xs font-semibold text-white transition hover:bg-violet-700"
                    data-use-audience={key(s)}
                  >
                    {t('Použít v kampani', 'Use in campaign')} <ArrowRight className="h-3.5 w-3.5" />
                  </Link> : s.state === 'DRAFT' ? <Can permission="campaign:submit" fallback={<span className="text-xs text-muted-foreground">{t('Čeká na oprávněného autora', 'Awaiting an authorized author')}</span>}><button type="button" onClick={() => void lifecycle(s, 'submit')} disabled={lifecycleFlight.busy} aria-busy={lifecycleAction?.key === key(s) && lifecycleAction.action === 'submit'} className="rounded-lg bg-violet-700 px-3 py-2 text-xs font-semibold text-white transition hover:bg-violet-800 disabled:cursor-wait disabled:opacity-60">{lifecycleAction?.key === key(s) && lifecycleAction.action === 'submit' ? t('Odesílám…', 'Submitting…') : t('Odeslat ke schválení', 'Submit for approval')}</button></Can> : <Can permission="campaign:activate" fallback={<span className="text-xs text-muted-foreground">{t('Čeká na oprávněného schvalovatele', 'Awaiting an authorized approver')}</span>}><button type="button" onClick={event => { approvalTriggerRef.current = event.currentTarget; setLifecycleError(null); setApprovalIntent(s) }} disabled={lifecycleFlight.busy} className="rounded-lg bg-emerald-600 px-3 py-2 text-xs font-semibold text-white transition hover:bg-emerald-700 disabled:cursor-wait disabled:opacity-60">{t('Zkontrolovat a schválit', 'Review and approve')}</button></Can>}
                </div>
                <p className="mt-3 flex items-center gap-1.5 text-[.68rem] text-slate-400"><Clock3 className="h-3 w-3" />{t('Dosah se mění s aktuálním stavem; verze pravidel zůstává stejná.', 'Reach changes with current state; the rule version stays fixed.')}</p>
              </article>
            ))}
          </section>
        </>
      )}
      {approvalIntent && <AudienceApprovalDialog
        audience={approvalIntent}
        busy={lifecycleFlight.busy}
        error={lifecycleError}
        onCancel={closeApprovalReview}
        onConfirm={async () => {
          if (await lifecycle(approvalIntent, 'approve')) setApprovalIntent(null)
        }}
      />}
    </div>
  </AuthGuard>
}

function AudienceApprovalDialog({ audience, busy, error, onCancel, onConfirm }: {
  audience: Segment
  busy: boolean
  error: string | null
  onCancel: () => void
  onConfirm: () => Promise<void>
}) {
  const { t } = useLanguage()
  const dialogRef = useRef<HTMLDivElement>(null)
  const titleId = `audience-approval-${audience.name}-${audience.version}-title`
  const impactId = `audience-approval-${audience.name}-${audience.version}-impact`

  return <div
    ref={dialogRef}
    role="alertdialog"
    aria-modal="true"
    aria-labelledby={titleId}
    aria-describedby={impactId}
    aria-busy={busy}
    onKeyDown={event => {
      if (event.key === 'Escape' && !busy) onCancel()
      trapDialogFocus(event, dialogRef.current)
    }}
    className="fixed inset-0 z-[1200] grid place-items-center bg-slate-950/70 p-5"
  >
    <div className="w-full max-w-xl overflow-y-auto rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl" style={{ maxHeight: 'calc(100dvh - 40px)' }}>
      <div className="flex items-start gap-3">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-emerald-50 text-emerald-700"><ShieldCheck className="h-5 w-5" aria-hidden="true" /></span>
        <div>
          <h2 id={titleId} className="text-lg font-semibold text-slate-950">{t('Schválit publikum', 'Approve audience')}</h2>
          <p id={impactId} className="mt-1 text-sm leading-6 text-slate-600">{t(
            'Tato verze se stane použitelnou v kampaních. Schválení samo nic neodešle; souhlas a frekvenční ochrany se znovu ověří při odeslání.',
            'This version will become available to campaigns. Approval sends nothing by itself; consent and frequency protections are checked again at send time.',
          )}</p>
        </div>
      </div>
      <dl className="mt-5 grid gap-3 rounded-xl border border-slate-200 bg-slate-50 p-4 text-sm">
        <div><dt className="text-xs font-bold uppercase tracking-wide text-slate-400">{t('Publikum', 'Audience')}</dt><dd className="mt-1 font-semibold text-slate-900">{audience.name} · v{audience.version}</dd></div>
        <div><dt className="text-xs font-bold uppercase tracking-wide text-slate-400">{t('Autor', 'Maker')}</dt><dd className="mt-1 text-slate-700">{audience.createdBy || t('neuvedeno', 'not provided')}</dd></div>
        <div><dt className="text-xs font-bold uppercase tracking-wide text-slate-400">{t('Pravidla, která schvalujete', 'Rules you are approving')}</dt><dd><ul className="mt-2 space-y-1.5 text-slate-700">{audience.rules.map(rule => <li key={rule} className="flex gap-2"><span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-violet-500" />{rule}</li>)}</ul></dd></div>
      </dl>
      {error && <p role="alert" className="mt-4 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">{error}</p>}
      <div className="mt-5 flex justify-end gap-2">
        <button type="button" autoFocus disabled={busy} onClick={onCancel} className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-700 disabled:opacity-60">{t('Zpět ke kontrole', 'Back to review')}</button>
        <button type="button" disabled={busy} aria-busy={busy} onClick={() => void onConfirm()} className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white disabled:cursor-wait disabled:opacity-60">{busy ? t('Schvaluji…', 'Approving…') : t('Potvrdit schválení', 'Confirm approval')}</button>
      </div>
    </div>
  </div>
}
