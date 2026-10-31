// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server component (ADR-0056). Compliance Control Tower — the regulation →
// control → evidence matrix. Reads the authored control catalogue and joins
// observed status from the build-derived security posture. Authored catalogue
// status and snapshot-derived status are shown separately; this is not a live
// cluster probe or a legal-compliance determination.

import Link from 'next/link'
import { cookies } from 'next/headers'
import { ChevronLeft, ShieldCheck, Radio, FileText } from 'lucide-react'
import { loadComplianceCatalog, type ControlStatus, type ComplianceControl } from '@/lib/governance/compliance'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import { ContentSourceNote } from '@/components/docs/ContentSourceNote'

export const dynamic = 'force-dynamic'

function statusStyle(s: ControlStatus): { color: string; bg: string } {
  switch (s) {
    case 'enforced': return { color: 'var(--success-text)', bg: 'var(--success-bg)' }
    case 'partial':  return { color: 'var(--info-text)', bg: 'var(--info-bg)' }
    case 'audit':    return { color: 'var(--warning-text)', bg: 'var(--warning-bg)' }
    default:         return { color: 'var(--text-tertiary)', bg: 'var(--surface-2)' }
  }
}

export default async function ControlTowerPage() {
  const lang = (await cookies()).get('openbank-admin-lang')?.value === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)
  const statusLabel = (s: ControlStatus) => ({
    enforced: t('Vynuceno', 'Enforced'),
    partial: t('Částečně', 'Partial'),
    audit: t('Audit', 'Audit'),
    planned: t('Plánováno', 'Planned'),
    unknown: t('Neznámé', 'Unknown'),
  }[s])

  const catalog = await loadComplianceCatalog()

  if (!catalog) {
    return (
      <div>
        <DocsPageHeader
          crumbs={<>
              <span>OpenBank</span><span className="breadcrumb-sep">/</span>
              <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>Docs</Link>
              <span className="breadcrumb-sep">/</span>
              <span className="breadcrumb-current">{t('Control Tower', 'Control Tower')}</span>
          </>}
          title={t('Compliance Control Tower', 'Compliance Control Tower')}
          icon={<ShieldCheck aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        />
        <div className="card" style={{ padding: '24px' }}>
          <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
            {t(
              'Katalog kontrol není v tomto prostředí dostupný (compliance-controls.yaml chybí).',
              'The control catalogue is not available in this environment (compliance-controls.yaml missing).',
            )}
          </p>
        </div>
      </div>
    )
  }

  const fwName = (id: string) => catalog.frameworks.find(f => f.id === id)?.name ?? id

  // Group controls by category for the matrix layout.
  const byCategory = new Map<string, ComplianceControl[]>()
  for (const c of catalog.controls) {
    const list = byCategory.get(c.category) ?? []
    list.push(c)
    byCategory.set(c.category, list)
  }

  const declaredEnforced = catalog.controls.filter(c => c.declaredStatus === 'enforced').length
  const snapshotObserved = catalog.controls.filter(c => c.derivedFrom && c.signalState !== 'unknown').length

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>Docs</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Control Tower', 'Control Tower')}</span>
          </>}
        title={t('Compliance Control Tower', 'Compliance Control Tower')}
        subtitle={t(
              `Katalog deklaruje ${declaredEnforced}/${catalog.controls.length} kontrol jako vynucené; ${snapshotObserved} kontrol má odvozený signál.`,
              `The catalogue declares ${declaredEnforced}/${catalog.controls.length} controls enforced; ${snapshotObserved} controls have a derived signal.`,
            )}
        icon={<ShieldCheck aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<Link href="/docs" className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <ChevronLeft size={14} />
          {t('Zpět na dokumentaci', 'Back to docs')}
        </Link>}
      />

      <ContentSourceNote source={catalog.contentSource} />

      <p style={{ margin: '-12px 0 20px', color: 'var(--text-tertiary)', fontSize: '11px' }}>
        {catalog.posture?.collectedAt
          ? t(`Odvozené signály pocházejí ze snímku manifestů pořízeného ${catalog.posture.collectedAt}; nejde o živou kontrolu clusteru.`, `Derived signals come from a manifest snapshot collected ${catalog.posture.collectedAt}; this is not a live cluster check.`)
          : t('Snímek odvozených signálů není dostupný; odvozené stavy jsou neznámé.', 'The derived-signal snapshot is unavailable; derived states are unknown.')}
      </p>

      {/* Per-framework coverage */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: '10px', marginBottom: '24px' }}>
        {catalog.frameworks.filter(f => catalog.coverage[f.id]).map(f => {
          const cov = catalog.coverage[f.id]
          const pct = Math.round((cov.enforced / cov.total) * 100)
          return (
            <div key={f.id} className="card" style={{ padding: '12px 14px' }}>
              <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '2px' }}>{f.id}</div>
              <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '8px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={f.name}>{f.name}</div>
              <div style={{ height: 6, background: 'var(--surface-2)', borderRadius: 3, overflow: 'hidden', marginBottom: '5px' }}>
                <div style={{ width: `${pct}%`, height: '100%', background: 'var(--accent)' }} />
              </div>
              <div style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>
                {cov.enforced}/{cov.total} {t('deklarováno/odvozeno jako vynucené', 'catalogued/derived as enforced')}
              </div>
            </div>
          )
        })}
      </div>

      {/* Controls grouped by category */}
      {[...byCategory.entries()].map(([category, controls]) => (
        <div key={category} style={{ marginBottom: '22px' }}>
          <div style={{
            fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.06em',
            color: 'var(--text-tertiary)', fontWeight: 700, marginBottom: '8px',
          }}>
            {category}
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
            {controls.map(c => {
              const st = statusStyle(c.status)
              return (
                <div key={c.id} className="card" style={{ padding: '14px 16px' }}>
                  <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: '12px', marginBottom: '6px' }}>
                    <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{c.title}</span>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', flexShrink: 0 }}>
                          {c.signalState && c.signalState !== 'unknown' && (
                        <span title={t('Vstupní signál ze zdrojového manifestu; nepotvrzuje běžící cluster', 'Source-manifest input; does not verify the running cluster')}
                          style={{ display: 'inline-flex', alignItems: 'center', gap: '3px', fontSize: '10px', fontWeight: 700, color: 'var(--info-text)' }}>
                          <Radio size={10} /> MANIFEST
                        </span>
                      )}
                      <span style={{
                        fontSize: '11px', fontWeight: 700, padding: '2px 9px', borderRadius: '20px',
                        background: st.bg, color: st.color, textTransform: 'uppercase', letterSpacing: '0.03em',
                      }}>
                        {statusLabel(c.status)}
                      </span>
                    </span>
                  </div>
                  <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5, marginBottom: '8px' }}>
                    {c.evidence}
                    {c.evidenceSource && (
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', marginLeft: '8px', color: 'var(--text-tertiary)', fontFamily: 'JetBrains Mono, monospace', fontSize: '11px' }}>
                        <FileText size={10} /> {c.evidenceSource}
                      </span>
                    )}
                  </div>
                  <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '7px' }}>
                    {t(`Deklarovaný stav: ${c.declaredStatus}; provozní stav: neověřen`, `Declared status: ${c.declaredStatus}; operational status: unverified`)}
                    {c.derivedFrom ? ` · ${c.derivedFrom}` : ''}
                  </div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                    {c.frameworks.map(f => (
                      <span key={f} title={fwName(f)} style={{
                        fontSize: '10px', fontWeight: 600, padding: '2px 7px', borderRadius: '4px',
                        background: 'var(--accent-bg)', color: 'var(--accent-text)',
                      }}>
                        {f}
                      </span>
                    ))}
                    {c.references.map(r => (
                      <span key={r} style={{
                        fontSize: '10px', padding: '2px 7px', borderRadius: '4px',
                        background: 'var(--surface-2)', color: 'var(--text-tertiary)', border: '1px solid var(--border)',
                      }}>
                        {r}
                      </span>
                    ))}
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      ))}
    </div>
  )
}
