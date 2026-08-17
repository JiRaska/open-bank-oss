// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server component (ADR-0056). Renders a single Architecture Decision Record
// from the image-baked governance bundle. The H1 + Date/Status/Author front
// block is stripped in the loader and re-rendered here as a styled meta bar.

import Link from 'next/link'
import { cookies } from 'next/headers'
import { ChevronLeft, ScrollText, Calendar, User } from 'lucide-react'
import { MarkdownView } from '@/components/docs/MarkdownView'
import { MermaidEnhancer } from '@/components/docs/MermaidEnhancer'
import { loadAdr, type AdrStatus } from '@/lib/governance/docs'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

export const dynamic = 'force-dynamic'

interface PageProps {
  params: Promise<{ slug: string }>
}

function statusStyle(s: AdrStatus): { color: string; bg: string } {
  switch (s) {
    case 'Accepted':   return { color: 'var(--success)', bg: 'var(--success-bg)' }
    case 'Proposed':   return { color: 'var(--info, #2563eb)', bg: 'var(--info-bg, #dbeafe)' }
    case 'Deprecated': return { color: 'var(--warning)', bg: 'var(--warning-bg)' }
    case 'Rejected':   return { color: 'var(--danger)', bg: 'var(--danger-bg)' }
    default:           return { color: 'var(--text-tertiary)', bg: 'var(--surface-2)' }
  }
}

export default async function AdrDetailPage({ params }: PageProps) {
  const { slug } = await params
  const lang = (await cookies()).get('openbank-admin-lang')?.value === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)

  const adr = await loadAdr(slug)
  const statusLabel = (s: AdrStatus) => ({
    Accepted: t('Schválené', 'Accepted'),
    Proposed: t('Navržené', 'Proposed'),
    Superseded: t('Nahrazené', 'Superseded'),
    Deprecated: t('Zastaralé', 'Deprecated'),
    Rejected: t('Zamítnuté', 'Rejected'),
    Unknown: t('Neznámý stav', 'Unknown'),
  }[s])

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>Docs</Link>
            <span className="breadcrumb-sep">/</span>
            <Link href="/docs/adr" style={{ color: 'inherit', textDecoration: 'none' }}>ADR</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{adr ? adr.numberLabel : slug}</span>
          </>}
        title={adr ? `ADR-${adr.numberLabel} — ${adr.title}` : t('Rozhodnutí nenalezeno', 'Decision not found')}
        icon={<ScrollText aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<Link href="/docs/adr" className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <ChevronLeft size={14} />
          {t('Zpět na registr', 'Back to registry')}
        </Link>}
      />

      <div className="card" style={{ padding: '24px' }}>
        {!adr ? (
          <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
            {t(
              `Rozhodnutí „${slug}" v registru neexistuje, nebo governance bundle není v tomto prostředí dostupný.`,
              `Decision "${slug}" is not in the registry, or the governance bundle is not available in this environment.`,
            )}
          </p>
        ) : (
          <>
            {/* Meta bar */}
            <div style={{
              display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '12px',
              paddingBottom: '16px', marginBottom: '20px', borderBottom: '1px solid var(--border)',
            }}>
              <span style={{
                fontSize: '12px', fontWeight: 700, padding: '4px 12px', borderRadius: '20px',
                background: statusStyle(adr.status).bg, color: statusStyle(adr.status).color,
                border: `1px solid ${statusStyle(adr.status).color}30`,
              }}>
                {statusLabel(adr.status)}
              </span>
              {adr.date && (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '5px', fontSize: '12px', color: 'var(--text-secondary)' }}>
                  <Calendar size={12} /> {adr.date}
                </span>
              )}
              {adr.authors && (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '5px', fontSize: '12px', color: 'var(--text-secondary)' }}>
                  <User size={12} /> {adr.authors}
                </span>
              )}
            </div>

            <MermaidEnhancer contentKey={`adr-${adr.slug}-${lang}`}>
              <MarkdownView markdown={adr.body} serviceName="adr" />
            </MermaidEnhancer>
          </>
        )}
      </div>
    </div>
  )
}
