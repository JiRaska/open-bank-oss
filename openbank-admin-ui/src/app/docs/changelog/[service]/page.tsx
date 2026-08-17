// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server component (ADR-0056). Renders a service's release-please CHANGELOG.md.
// The markdown is fetched server-side via @/lib/docs/releases — the browser
// never calls GitHub. Bilingual chrome via the openbank-admin-lang cookie.

import Link from 'next/link'
import { cookies } from 'next/headers'
import { ChevronLeft, FileText, ExternalLink } from 'lucide-react'
import { MarkdownView } from '@/components/docs/MarkdownView'
import { MermaidEnhancer } from '@/components/docs/MermaidEnhancer'
import { fetchChangelog, SERVICE_RE } from '@/lib/docs/releases'
import { prettyLabel } from '@/lib/discovery'
import { LANG_COOKIE } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

interface PageProps {
  params: Promise<{ service: string }>
}

export default async function ChangelogPage({ params }: PageProps) {
  const { service } = await params
  const lang = (await cookies()).get(LANG_COOKIE)?.value === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)

  const valid = SERVICE_RE.test(service)
  const data = valid ? await fetchChangelog(service) : null
  const label = prettyLabel(service)

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/docs/api" style={{ color: 'inherit', textDecoration: 'none' }}>Docs</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Changelog', 'Changelog')}</span>
          </>}
        title={`${label} — Changelog`}
        subtitle={t(
              'Historie verzí generovaná z Conventional Commits (release-please).',
              'Version history generated from Conventional Commits (release-please).',
            )}
        icon={<FileText aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<Link href="/docs/api" className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <ChevronLeft size={14} />
          {t('Zpět na API katalog', 'Back to API catalog')}
        </Link>}
      />

      <div className="card" style={{ padding: '24px' }}>
        {!valid ? (
          <p style={{ color: 'var(--danger)', fontSize: '13px' }}>
            {t('Neplatný identifikátor služby.', 'Invalid service identifier.')}
          </p>
        ) : data?.markdown ? (
          <MermaidEnhancer contentKey={`changelog-${service}`}>
            <MarkdownView markdown={data.markdown} serviceName={service} />
          </MermaidEnhancer>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
            <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
              {t(
                'Pro tuto službu zatím není publikovaný changelog. Vznikne automaticky při prvním vydání přes release-please.',
                'No changelog has been published for this service yet. It is created automatically on the first release-please release.',
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
        )}
      </div>
    </div>
  )
}
