// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server component (ADR-0056). Threat-model registry — read-only index of
// docs/threat-models, baked into the image (src/lib/governance/docs.ts).
// Money-path services (rules.yaml) need a threat model (ADR-0030); this page
// also surfaces the ones that are still missing one, so the coverage gap is
// visible rather than implied-complete.

import Link from 'next/link'
import { cookies } from 'next/headers'
import { ChevronLeft, ShieldAlert, ShieldCheck, AlertTriangle, FileText } from 'lucide-react'
import { loadThreatModelIndex, missingThreatModels } from '@/lib/governance/docs'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

export const dynamic = 'force-dynamic'

export default async function ThreatModelRegistryPage() {
  const lang = (await cookies()).get('openbank-admin-lang')?.value === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)

  const [models, missing] = await Promise.all([loadThreatModelIndex(), missingThreatModels()])
  const moneyPathCovered = models.filter(m => m.moneyPath).length
  const moneyPathTotal = moneyPathCovered + missing.length

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>Docs</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Threat modely', 'Threat models')}</span>
          </>}
        title={t('Registr threat modelů', 'Threat Model Registry')}
        subtitle={t(
              `STRIDE threat modely pro služby na peněžní cestě (ADR-0030). Pokrytí money-path: ${moneyPathCovered}/${moneyPathTotal}.`,
              `STRIDE threat models for money-path services (ADR-0030). Money-path coverage: ${moneyPathCovered}/${moneyPathTotal}.`,
            )}
        icon={<ShieldAlert aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<Link href="/docs" className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <ChevronLeft size={14} />
          {t('Zpět na dokumentaci', 'Back to docs')}
        </Link>}
      />

      {/* Coverage gap — money-path services without a threat model yet */}
      {missing.length > 0 && (
        <div className="card" style={{
          padding: '14px 16px', marginBottom: '20px',
          borderLeft: '3px solid var(--warning)',
          background: 'var(--warning-bg)',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
            <AlertTriangle size={15} style={{ color: 'var(--warning)' }} />
            <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--warning)' }}>
              {t('Money-path služby bez threat modelu', 'Money-path services missing a threat model')}
            </span>
          </div>
          <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '8px' }}>
            {t(
              'Tyto služby jsou na peněžní cestě a podle ADR-0030 threat model vyžadují. Roadmapa, ne incident.',
              'These services are on the money path and require a threat model per ADR-0030. Roadmap, not an incident.',
            )}
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
            {missing.map(s => (
              <span key={s} style={{
                fontSize: '12px', fontFamily: 'JetBrains Mono, monospace',
                padding: '3px 8px', borderRadius: '6px',
                background: 'var(--surface)', color: 'var(--text-secondary)',
                border: '1px solid var(--border)',
              }}>
                {s}
              </span>
            ))}
          </div>
        </div>
      )}

      {models.length === 0 ? (
        <div className="card" style={{ padding: '24px' }}>
          <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
            {t(
              'Registr threat modelů zatím není v tomto prostředí dostupný (governance bundle chybí).',
              'The threat-model registry is not available in this environment yet (governance bundle missing).',
            )}
          </p>
        </div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '12px' }}>
          {models.map(m => (
            <Link key={m.slug} href={`/docs/threat-models/${m.service}`} style={{ textDecoration: 'none' }}>
              <div className="card docs-link-hover" style={{ padding: '16px', height: '100%' }}>
                <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '8px' }}>
                  <ShieldCheck size={18} style={{ color: 'var(--success)' }} />
                  {m.moneyPath && (
                    <span style={{
                      fontSize: '10px', fontWeight: 700, padding: '2px 7px', borderRadius: '20px',
                      background: 'var(--danger-bg)', color: 'var(--danger)',
                      border: '1px solid var(--danger)30', textTransform: 'uppercase', letterSpacing: '0.04em',
                    }}>
                      {t('Peněžní cesta', 'Money path')}
                    </span>
                  )}
                </div>
                <div style={{
                  fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '4px',
                  fontFamily: 'JetBrains Mono, monospace',
                }}>
                  {m.service}
                </div>
                <div style={{
                  fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.4,
                  display: 'flex', alignItems: 'center', gap: '5px',
                }}>
                  <FileText size={11} style={{ flexShrink: 0, opacity: 0.6 }} />
                  <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.title}</span>
                </div>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}
