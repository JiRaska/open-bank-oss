// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// Server component (ADR-0056). Renders a single service threat model from the
// image-baked governance bundle (docs/threat-models/openbank-<service>.md).

import Link from 'next/link'
import { cookies } from 'next/headers'
import { ChevronLeft, ShieldAlert } from 'lucide-react'
import { MarkdownView } from '@/components/docs/MarkdownView'
import { MermaidEnhancer } from '@/components/docs/MermaidEnhancer'
import { loadThreatModel } from '@/lib/governance/docs'

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
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>Docs</Link>
            <span className="breadcrumb-sep">/</span>
            <Link href="/docs/threat-models" style={{ color: 'inherit', textDecoration: 'none' }}>{t('Threat modely', 'Threat models')}</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{service}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <ShieldAlert size={18} style={{ color: 'var(--accent)' }} />
            {t('Threat model', 'Threat model')} — {service}
            {model?.moneyPath && (
              <span style={{
                fontSize: '11px', fontWeight: 700, padding: '2px 8px', borderRadius: '20px',
                background: 'var(--danger-bg)', color: 'var(--danger)',
                border: '1px solid var(--danger)30', textTransform: 'uppercase', letterSpacing: '0.04em',
              }}>
                {t('Peněžní cesta', 'Money path')}
              </span>
            )}
          </h1>
        </div>
        <Link href="/docs/threat-models" className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <ChevronLeft size={14} />
          {t('Zpět na registr', 'Back to registry')}
        </Link>
      </div>

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
