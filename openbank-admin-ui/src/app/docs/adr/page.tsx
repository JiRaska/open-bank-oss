// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server component (ADR-0056). Architecture Decision Record registry — a
// read-only index of docs/adr, baked into the image (src/lib/governance/docs.ts).
// Grouped by status so an auditor sees the decision corpus at a glance.

import Link from 'next/link'
import { cookies } from 'next/headers'
import { ChevronLeft, ScrollText, FileText } from 'lucide-react'
import { loadAdrIndex, type AdrMeta, type AdrStatus } from '@/lib/governance/docs'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

export const dynamic = 'force-dynamic'

const STATUS_ORDER: AdrStatus[] = ['Accepted', 'Proposed', 'Superseded', 'Deprecated', 'Rejected', 'Unknown']

function statusStyle(s: AdrStatus): { color: string; bg: string } {
  switch (s) {
    case 'Accepted':   return { color: 'var(--success)', bg: 'var(--success-bg)' }
    case 'Proposed':   return { color: 'var(--info, #2563eb)', bg: 'var(--info-bg, #dbeafe)' }
    case 'Deprecated': return { color: 'var(--warning)', bg: 'var(--warning-bg)' }
    case 'Rejected':   return { color: 'var(--danger)', bg: 'var(--danger-bg)' }
    default:           return { color: 'var(--text-tertiary)', bg: 'var(--surface-2)' }
  }
}

export default async function AdrRegistryPage() {
  const lang = (await cookies()).get('openbank-admin-lang')?.value === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)

  const adrs = await loadAdrIndex()
  const statusLabel = (s: AdrStatus) => ({
    Accepted: t('Schválené', 'Accepted'),
    Proposed: t('Navržené', 'Proposed'),
    Superseded: t('Nahrazené', 'Superseded'),
    Deprecated: t('Zastaralé', 'Deprecated'),
    Rejected: t('Zamítnuté', 'Rejected'),
    Unknown: t('Neznámý stav', 'Unknown'),
  }[s])

  const byStatus = new Map<AdrStatus, AdrMeta[]>()
  for (const adr of adrs) {
    const list = byStatus.get(adr.status) ?? []
    list.push(adr)
    byStatus.set(adr.status, list)
  }
  const groups = STATUS_ORDER.filter(s => byStatus.has(s))

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>Docs</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Architektonická rozhodnutí', 'Architecture Decisions')}</span>
          </>}
        title={t('Registr architektonických rozhodnutí (ADR)', 'Architecture Decision Records (ADR)')}
        subtitle={t(
              `${adrs.length} rozhodnutí — proč je systém postavený tak, jak je. Každé ADR má kontext, rozhodnutí a důsledky.`,
              `${adrs.length} decisions — why the system is built the way it is. Each ADR records context, decision and consequences.`,
            )}
        icon={<ScrollText aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<Link href="/docs" className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <ChevronLeft size={14} />
          {t('Zpět na dokumentaci', 'Back to docs')}
        </Link>}
      />

      {adrs.length === 0 ? (
        <div className="card" style={{ padding: '24px' }}>
          <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
            {t(
              'Registr ADR zatím není v tomto prostředí dostupný (governance bundle chybí).',
              'The ADR registry is not available in this environment yet (governance bundle missing).',
            )}
          </p>
        </div>
      ) : (
        <>
          {/* Status summary chips */}
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', marginBottom: '20px' }}>
            {groups.map(s => {
              const st = statusStyle(s)
              return (
                <span key={s} style={{
                  fontSize: '12px', fontWeight: 600, padding: '4px 10px', borderRadius: '20px',
                  background: st.bg, color: st.color, border: `1px solid ${st.color}30`,
                }}>
                  {statusLabel(s)} · {byStatus.get(s)!.length}
                </span>
              )
            })}
          </div>

          {groups.map(s => {
            const st = statusStyle(s)
            return (
              <div key={s} style={{ marginBottom: '24px' }}>
                <div style={{
                  fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.06em',
                  color: 'var(--text-tertiary)', fontWeight: 700, marginBottom: '8px',
                }}>
                  {statusLabel(s)}
                </div>
                <div className="card" style={{ padding: '6px' }}>
                  {byStatus.get(s)!.map(adr => (
                    <Link
                      key={adr.slug}
                      href={`/docs/adr/${adr.slug}`}
                      style={{
                        display: 'flex', alignItems: 'center', gap: '12px',
                        padding: '10px 12px', borderRadius: 'var(--r-sm)',
                        textDecoration: 'none', color: 'inherit',
                      }}
                      className="docs-link-hover"
                    >
                      <span style={{
                        fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', fontWeight: 700,
                        color: st.color, minWidth: '42px',
                      }}>
                        {adr.numberLabel}
                      </span>
                      <FileText size={13} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                      <span style={{ flex: 1, fontSize: '13px', color: 'var(--text-primary)' }}>{adr.title}</span>
                      {adr.date && (
                        <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', fontFamily: 'JetBrains Mono, monospace' }}>
                          {adr.date}
                        </span>
                      )}
                    </Link>
                  ))}
                </div>
              </div>
            )
          })}
        </>
      )}
    </div>
  )
}
