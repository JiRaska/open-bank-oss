// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server component (ADR-0056). Renders a single service threat model from the
// image-baked governance bundle (docs/threat-models/openbank-<service>.md).

import Link from 'next/link'
import { cookies } from 'next/headers'
import { ChevronLeft, ShieldAlert } from 'lucide-react'
import { MarkdownView } from '@/components/docs/MarkdownView'
import { MermaidEnhancer } from '@/components/docs/MermaidEnhancer'
import { loadThreatModel } from '@/lib/governance/docs'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import { PrintDocumentButton } from '@/components/docs/PrintDocumentButton'

export const dynamic = 'force-dynamic'

interface PageProps {
  params: Promise<{ service: string }>
}

export default async function ThreatModelDetailPage({ params }: PageProps) {
  const { service } = await params
  const lang = (await cookies()).get('openbank-admin-lang')?.value === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)

  const model = await loadThreatModel(service)

  return (
    <div className="docs-printable">
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>Docs</Link>
            <span className="breadcrumb-sep">/</span>
            <Link href="/docs/threat-models" style={{ color: 'inherit', textDecoration: 'none' }}>{t('Threat modely', 'Threat models')}</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{service}</span>
          </>}
        title={<>{t('Threat model', 'Threat model')} — {service}
            {model?.moneyPath && (
              <span style={{
                fontSize: '11px', fontWeight: 700, padding: '2px 8px', borderRadius: '20px',
                background: 'var(--danger-bg)', color: 'var(--danger)',
                border: '1px solid var(--danger)30', textTransform: 'uppercase', letterSpacing: '0.04em',
              }}>
                {t('Peněžní cesta', 'Money path')}
              </span>
            )}
          </>}
        icon={<ShieldAlert aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<div className="docs-header-actions">
          <PrintDocumentButton />
          <Link href="/docs/threat-models" className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <ChevronLeft aria-hidden="true" size={14} />
            {t('Zpět na registr', 'Back to registry')}
          </Link>
        </div>}
      />

      <div className="card" style={{ padding: '24px' }}>
        {!model ? (
          <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
            {t(
              `Pro službu „${service}" zatím není publikovaný threat model, nebo governance bundle není v tomto prostředí dostupný.`,
              `No threat model has been published for "${service}" yet, or the governance bundle is not available in this environment.`,
            )}
          </p>
        ) : (
          <MermaidEnhancer contentKey={`threat-${model.service}-${lang}`}>
            <MarkdownView markdown={model.markdown} serviceName="threat-models" />
          </MermaidEnhancer>
        )}
      </div>
    </div>
  )
}
