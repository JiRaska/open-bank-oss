// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server component (ADR-0056). Renders a service's GitHub Releases (release-
// please output), fetched server-side via @/lib/docs/releases — the browser
// never calls GitHub. Bilingual chrome via the openbank-admin-lang cookie.

import Link from 'next/link'
import { cookies } from 'next/headers'
import { ChevronLeft, Tag, ExternalLink } from 'lucide-react'
import { MarkdownView } from '@/components/docs/MarkdownView'
import { MermaidEnhancer } from '@/components/docs/MermaidEnhancer'
import { fetchReleaseNotes, SERVICE_RE } from '@/lib/docs/releases'
import { prettyLabel } from '@/lib/discovery'
import { LANG_COOKIE } from '@/lib/i18n/LanguageContext'

interface PageProps {
  params: Promise<{ service: string }>
}

function formatDate(iso: string | null, lang: 'cs' | 'en'): string {
  if (!iso) return ''
  try {
    return new Date(iso).toLocaleDateString(lang === 'cs' ? 'cs-CZ' : 'en-GB', {
      year: 'numeric', month: 'short', day: 'numeric',
    })
  } catch {
    return iso
  }
}

export default async function ReleaseNotesPage({ params }: PageProps) {
  const { service } = await params
  const lang = (await cookies()).get(LANG_COOKIE)?.value === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)

  const valid = SERVICE_RE.test(service)
  const data = valid ? await fetchReleaseNotes(service) : null
  const label = prettyLabel(service)
  const releases = data?.releases ?? []

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/docs/api" style={{ color: 'inherit', textDecoration: 'none' }}>Docs</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Poznámky k vydání', 'Release Notes')}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Tag size={18} style={{ color: 'var(--accent)' }} />
            {label} — {t('Poznámky k vydání', 'Release Notes')}
          </h1>
          <p className="page-subtitle">
            {t(
              'Publikovaná vydání služby (release-please / GitHub Releases).',
              'Published service releases (release-please / GitHub Releases).',
            )}
          </p>
        </div>
        <Link href="/docs/api" className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <ChevronLeft size={14} />
          {t('Zpět na API katalog', 'Back to API catalog')}
        </Link>
      </div>

      {!valid ? (
        <div className="card" style={{ padding: '24px' }}>
          <p style={{ color: 'var(--danger)', fontSize: '13px' }}>
            {t('Neplatný identifikátor služby.', 'Invalid service identifier.')}
          </p>
        </div>
      ) : releases.length === 0 ? (
        <div className="card" style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
          <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
            {t(
              'Pro tuto službu zatím nejsou žádná vydání. Vzniknou automaticky při prvním release-please vydání.',
              'No releases for this service yet. They appear automatically on the first release-please release.',
            )}
          </p>
          {data?.source && (
            <a href={data.source} target="_blank" rel="noreferrer"
              style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '12px', color: 'var(--accent)' }}>
              <ExternalLink size={12} />
              {t('Zobrazit na GitHubu', 'View on GitHub')}
            </a>
          )}
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          {releases.map(rel => (
            <div key={rel.tag} className="card" style={{ padding: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '12px', flexWrap: 'wrap' }}>
                <span style={{
                  fontFamily: 'JetBrains Mono, monospace', fontSize: '13px', fontWeight: 700,
                  color: 'var(--accent)', background: 'var(--accent-bg)', padding: '2px 8px',
                  borderRadius: '6px',
                }}>{rel.tag}</span>
                <span style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)' }}>{rel.name}</span>
                {rel.publishedAt && (
                  <span style={{ marginLeft: 'auto', fontSize: '12px', color: 'var(--text-tertiary)' }}>
                    {formatDate(rel.publishedAt, lang)}
                  </span>
                )}
                <a href={rel.url} target="_blank" rel="noreferrer"
                  style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  <ExternalLink size={11} /> GitHub
                </a>
              </div>
              {rel.body
                ? (
                  <MermaidEnhancer contentKey={`release-${service}-${rel.tag}`}>
                    <MarkdownView markdown={rel.body} serviceName={service} />
                  </MermaidEnhancer>
                )
                : <p style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{t('Bez popisu.', 'No description.')}</p>}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
