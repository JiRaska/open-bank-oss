// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Compliance hub (ADR-0379). Renders the derived trace obligation -> control -> evidence. Nothing
// on this page is hand-written status: obligations come from regulatory-obligations.yaml, controls
// from compliance-controls.yaml, evidence from the gate manifest, and the verdict from
// src/lib/compliance/trace.ts. Gate `compliance-trace` keeps a requirement literal from returning.

'use client'
import Link from 'next/link'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Shield, Info, RefreshCw, Download } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import { PrintDocumentButton } from '@/components/docs/PrintDocumentButton'
import { StatCard, StatusBadge, Tabs } from '@/components/ui'
import type { Tone } from '@/components/ui/tone'
import type { TraceSnapshot } from '@/lib/compliance/trace-server'
import type { TraceControl, TraceEvidence, Verdict, EvidenceLevel } from '@/lib/compliance/trace'

type View = 'gaps' | 'departments' | 'audit'

const VERDICT_TONE: Record<Verdict, Tone> = {
  enforced: 'success', partial: 'info', audit: 'warning', planned: 'neutral', unknown: 'neutral', unverified: 'danger',
}

function useLabels() {
  const { t } = useLanguage()
  return {
    verdict: (v: Verdict) => ({
      enforced: t('Vynuceno', 'Enforced'), partial: t('Částečně', 'Partial'), audit: t('Jen audit', 'Audit only'),
      planned: t('Plánováno', 'Planned'), unknown: t('Neznámé', 'Unknown'), unverified: t('Neověřeno', 'Unverified'),
    })[v],
    level: (l: EvidenceLevel) => ({
      'enforced-gate': t('Blokující CI gate', 'Blocking CI gate'), ci: t('Běh v CI', 'CI run'),
      documented: t('Jen dokumentace', 'Documentation only'), none: t('Bez důkazu', 'No evidence'),
    })[l],
    kind: (k: TraceEvidence['kind']) => ({ gate: 'gate', workflow: 'workflow', test: 'test', adr: 'ADR', path: t('soubor', 'path') })[k],
    reason: (r: NonNullable<TraceControl['downgradeReason']>) => ({
      'no-machine-evidence': t('tvrzení nepodpírá žádný strojově opakovaný důkaz', 'no machine-repeated evidence backs the claim'),
      'stale-evidence': t('poslední úspěšný běh gatu je starší než povolené stáří', 'the newest passing gate run is older than the allowed age'),
      'evidence-age-unknown': t('stáří důkazu nelze zjistit (chybí snímek běhů gatů)', 'the evidence age cannot be established (no gate-run snapshot)'),
    })[r],
    age: (iso: string | null) => iso ? t(`poslední úspěšný běh ${iso.slice(0, 10)}`, `last pass ${iso.slice(0, 10)}`) : t('běh nepozorován', 'no run observed'),
  }
}

function EvidenceList({ refs }: { refs: TraceEvidence[] }) {
  const { t } = useLanguage()
  const labels = useLabels()
  if (refs.length === 0) return <span style={{ color: 'var(--text-tertiary)' }}>{t('Žádný odkaz na důkaz', 'No evidence reference')}</span>
  return (
    <ul style={{ margin: 0, paddingLeft: '16px', display: 'grid', gap: '2px' }}>
      {refs.map(r => (
        <li key={`${r.kind}:${r.ref}`} style={{ fontSize: '12px' }}>
          <span style={{ color: 'var(--text-tertiary)' }}>{labels.kind(r.kind)}</span>{' '}
          {r.href
            ? (r.href.startsWith('/') ? <Link href={r.href}>{r.label}</Link> : <a href={r.href} target="_blank" rel="noreferrer">{r.label}</a>)
            : <span>{r.label}</span>}
          {r.kind === 'gate' && r.gateMode ? (
            <span style={{ color: r.gateMode === 'enforced' ? 'var(--success-text)' : 'var(--warning-text)' }}>
              {' '}· {r.gateMode === 'enforced' ? t('blokuje', 'blocking') : r.gateMode === 'advisory' ? t('jen hlásí', 'advisory') : t('nenalezen', 'not found')}
            </span>
          ) : null}
          {r.kind === 'gate' && r.gateStatus === 'failed'
            ? <span style={{ color: 'var(--danger-text)' }}> · {t('poslední běh selhal', 'latest run failed')}</span>
            : r.kind === 'gate' ? <span style={{ color: 'var(--text-tertiary)' }}> · {labels.age(r.lastOkAt ?? null)}</span> : null}
        </li>
      ))}
    </ul>
  )
}

function ControlCard({ control }: { control: TraceControl }) {
  const { t } = useLanguage()
  const labels = useLabels()
  return (
    <article className="card" style={{ padding: '14px', display: 'grid', gap: '8px' }} aria-label={control.title}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: '8px', flexWrap: 'wrap', alignItems: 'center' }}>
        <strong style={{ fontSize: '14px', color: 'var(--text-primary)' }}>{control.title}</strong>
        <StatusBadge status={control.verdict} tone={VERDICT_TONE[control.verdict]} label={labels.verdict(control.verdict)} withDot />
      </div>
      <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
        {t('Deklarováno', 'Declared')}: {labels.verdict(control.declaredStatus)} · {t('Síla důkazu', 'Evidence strength')}: {labels.level(control.evidenceLevel)}
        {control.maxEvidenceAgeDays ? <> · {t(`max. stáří ${control.maxEvidenceAgeDays} dní`, `max age ${control.maxEvidenceAgeDays} days`)}</> : null}
        {control.downgradeReason
          ? <> · <span style={{ color: 'var(--danger-text)' }}>{labels.reason(control.downgradeReason)}</span></>
          : null}
      </div>
      <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-primary)', lineHeight: 1.5 }}>{control.evidence}</p>
      <div style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{control.frameworks.join(' · ')}{control.references.length ? ` — ${control.references.join(', ')}` : ''}</div>
      <EvidenceList refs={control.evidenceRefs} />
    </article>
  )
}

export default function CompliancePage() {
  const { t, language } = useLanguage()
  const labels = useLabels()
  const [snapshot, setSnapshot] = useState<TraceSnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [view, setView] = useState<View>('gaps')
  const [framework, setFramework] = useState<string>('all')

  const refresh = useCallback(async () => {
    setLoading(true)
    setFailed(false)
    try {
      const response = await fetch('/api/compliance/trace', { cache: 'no-store' })
      if (!response.ok) throw new Error('trace unavailable')
      setSnapshot(await response.json() as TraceSnapshot)
    } catch {
      setSnapshot(null)
      setFailed(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => { void refresh() }, 0)
    return () => window.clearTimeout(timer)
  }, [refresh])

  const trace = snapshot?.status === 'available' ? snapshot : null
  const controlsById = useMemo(() => new Map((trace?.controls ?? []).map(c => [c.id, c])), [trace])
  const name = (n: { cs: string; en: string }) => (language === 'cs' ? n.cs : n.en)

  const unverified = trace?.controls.filter(c => c.verdict === 'unverified') ?? []
  const uncovered = trace?.obligations.filter(o => o.coverage.status === 'missing' || o.coverage.status === 'bff-only') ?? []
  const departmentControls = trace?.controls.filter(c => framework === 'all' || c.frameworks.includes(framework)) ?? []

  return (
    <div className="docs-printable">
      <DocsPageHeader
        crumbs={<>
          <span>OpenBank</span><span className="breadcrumb-sep">/</span>
          <span>{t('Dokumentace', 'Docs')}</span><span className="breadcrumb-sep">/</span>
          <span className="breadcrumb-current">{t('Compliance', 'Compliance')}</span>
        </>}
        title={t('Stav compliance', 'Compliance posture')}
        subtitle={t('Povinnost → kontrola → důkaz, odvozeno z registrů v repozitáři', 'Obligation → control → evidence, derived from the registers in the repository')}
        icon={<Shield aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<>
          <button type="button" className="btn btn-secondary" onClick={() => void refresh()} disabled={loading}>
            <RefreshCw size={14} /> {t('Obnovit', 'Refresh')}
          </button>
          <a className="btn btn-secondary" href="/api/compliance/trace?download=1"><Download size={14} /> {t('Export JSON', 'Export JSON')}</a>
          <PrintDocumentButton />
        </>}
      />

      <div style={{
        padding: '12px 16px', marginBottom: '20px', background: 'var(--info-bg)', border: '1px solid var(--info-border)',
        borderRadius: 'var(--r-lg)', display: 'flex', gap: '10px',
      }}>
        <Info size={14} style={{ color: 'var(--info-text)', flexShrink: 0, marginTop: '1px' }} />
        <div style={{ fontSize: '12px', color: 'var(--text-primary)', lineHeight: 1.5 }}>
          {t(
            'Verdikt počítá kód, ne člověk ani AI: kontrola nesmí tvrdit víc, než ukáže její důkaz. Tvrzení „vynuceno“ nebo „částečně“ bez CI gate, workflow či testu, nebo s důkazem z gatu starším, než kontrola povoluje, se zobrazí jako neověřené. Stránka neprokazuje právní shodu; ukazuje, co platforma deklaruje a čím to dokládá.',
            'The verdict is computed by code, not by a person or an AI: a control may claim no more than its evidence shows. An "enforced" or "partial" claim with no CI gate, workflow or test behind it, or whose gate evidence is older than the control allows, renders as unverified. This page does not establish legal compliance; it shows what the platform declares and what backs it.',
          )}
        </div>
      </div>

      {loading ? <p role="status">{t('Načítám trasu…', 'Loading the trace…')}</p> : null}
      {failed || snapshot?.status === 'unavailable' ? (
        <p role="status" style={{ color: 'var(--warning-text)' }}>
          {t('Trasa compliance není dostupná; stav je neznámý.', 'The compliance trace is unavailable; status is unknown.')}
        </p>
      ) : null}
      {trace && trace.missing.length > 0 ? (
        <p role="status" style={{ color: 'var(--warning-text)', fontSize: '12px' }}>
          {t('Chybí zdroj', 'Missing source')}: {trace.missing.join(', ')}
        </p>
      ) : null}

      {trace ? (<>
        <section aria-label={t('Souhrn', 'Summary')} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))', gap: '12px', marginBottom: '20px' }}>
          <StatCard label={t('Kontroly se strojovým důkazem', 'Controls with machine evidence')} value={`${trace.summary.machineEvidenced} / ${trace.summary.controls}`} tone="accent" />
          <StatCard label={t('Neověřená tvrzení', 'Unverified claims')} value={trace.summary.byVerdict.unverified} tone={trace.summary.byVerdict.unverified ? 'danger' : 'success'} />
          <StatCard label={t('Povinnosti s kontrolou', 'Obligations with a control')} value={`${trace.summary.obligationsWithControl} / ${trace.summary.obligations}`} tone="info" />
          <StatCard label={t('Nepokryté výkazy', 'Uncovered returns')} value={uncovered.length} hint={t('stav missing nebo bff-only', 'status missing or bff-only')} tone={uncovered.length ? 'warning' : 'success'} />
        </section>

        <p style={{ fontSize: '12px', color: 'var(--text-secondary)', margin: '0 0 12px' }}>
          {t('Snímek k commitu', 'Snapshot as of commit')}: <code>{trace.asOf.commit ?? t('neznámý', 'unknown')}</code>
          {trace.asOf.postureCollectedAt ? <> · {t('signály manifestů', 'manifest signals')} {trace.asOf.postureCollectedAt}</> : null}
          {' · '}{t('běhy gatů', 'gate runs')} {trace.asOf.gateHealthCollectedAt ?? t('nedostupné', 'unavailable')}
        </p>

        <Tabs<View>
          idPrefix="compliance-view"
          label={t('Pohled', 'View')}
          value={view}
          onChange={setView}
          items={[
            { id: 'gaps', label: t('Mezery (compliance)', 'Gaps (compliance)') },
            { id: 'departments', label: t('Podle oblasti (oddělení)', 'By framework (departments)') },
            { id: 'audit', label: t('Trasa (audit)', 'Trace (audit)') },
          ]}
        />

        <div role="tabpanel" id={`compliance-view-panel-${view}`} aria-labelledby={`compliance-view-tab-${view}`} style={{ marginTop: '16px', display: 'grid', gap: '16px' }}>
          {view === 'gaps' ? (<>
            <section aria-label={t('Neověřené kontroly', 'Unverified controls')}>
              <h2 style={{ fontSize: '15px', margin: '0 0 8px' }}>{t('Kontroly, jejichž tvrzení nic nedokládá', 'Controls whose claim nothing backs')} ({unverified.length})</h2>
              {unverified.length === 0
                ? <p style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{t('Žádné.', 'None.')}</p>
                : <div style={{ display: 'grid', gap: '10px' }}>{unverified.map(c => <ControlCard key={c.id} control={c} />)}</div>}
            </section>
            <section aria-label={t('Nepokryté povinnosti', 'Uncovered obligations')}>
              <h2 style={{ fontSize: '15px', margin: '0 0 8px' }}>{t('Výkaznické povinnosti bez výstupu z backendu', 'Reporting duties with no backend output')} ({uncovered.length})</h2>
              <div className="table-wrap">
                <table className="table">
                  <thead><tr><th>{t('Povinnost', 'Obligation')}</th><th>{t('Stav', 'Status')}</th><th>{t('Lhůta', 'Cadence')}</th><th>{t('Jistota lhůty', 'Cadence confidence')}</th></tr></thead>
                  <tbody>
                    {uncovered.map(o => (
                      <tr key={o.id}>
                        <td>{name(o.name)}<div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{o.legalBasis}</div></td>
                        <td><code>{o.coverage.status}</code></td>
                        <td style={{ fontSize: '12px' }}>{o.cadence}</td>
                        <td><code>{o.cadenceConfidence}</code></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p style={{ fontSize: '12px', margin: '8px 0 0' }}><Link href="/regulatory/obligations">{t('Celý registr povinností', 'Full obligation register')}</Link></p>
            </section>
          </>) : null}

          {view === 'departments' ? (<>
            <label style={{ fontSize: '12px', display: 'flex', gap: '8px', alignItems: 'center' }}>
              {t('Rámec', 'Framework')}
              <select className="input" value={framework} onChange={e => setFramework(e.target.value)}>
                <option value="all">{t('Všechny', 'All')}</option>
                {trace.frameworks.map(f => <option key={f.id} value={f.id}>{f.name} ({f.machineEvidenced}/{f.controls})</option>)}
              </select>
            </label>
            <div style={{ display: 'grid', gap: '10px' }}>{departmentControls.map(c => <ControlCard key={c.id} control={c} />)}</div>
          </>) : null}

          {view === 'audit' ? (
            <section aria-label={t('Úplná trasa', 'Full trace')} className="table-wrap">
              <table className="table">
                <thead><tr><th>{t('Povinnost', 'Obligation')}</th><th>{t('Kontrola', 'Control')}</th><th>{t('Verdikt', 'Verdict')}</th><th>{t('Důkaz', 'Evidence')}</th></tr></thead>
                <tbody>
                  {trace.obligations.filter(o => o.hasControl).flatMap(o => o.controls.map(cid => {
                    const c = controlsById.get(cid)
                    if (!c) return null
                    return (
                      <tr key={`${o.id}:${cid}`}>
                        <td>{name(o.name)}<div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{o.legalBasis}</div></td>
                        <td>{c.title}</td>
                        <td><StatusBadge status={c.verdict} tone={VERDICT_TONE[c.verdict]} label={labels.verdict(c.verdict)} /></td>
                        <td><EvidenceList refs={c.evidenceRefs} /></td>
                      </tr>
                    )
                  }))}
                  {trace.controls.filter(c => c.obligationIds.length === 0).map(c => (
                    <tr key={`none:${c.id}`}>
                      <td style={{ color: 'var(--text-tertiary)' }}>{c.references.join(', ') || '—'}</td>
                      <td>{c.title}</td>
                      <td><StatusBadge status={c.verdict} tone={VERDICT_TONE[c.verdict]} label={labels.verdict(c.verdict)} /></td>
                      <td><EvidenceList refs={c.evidenceRefs} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          ) : null}
        </div>
      </>) : null}
    </div>
  )
}
