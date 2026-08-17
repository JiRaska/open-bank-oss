// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server component (ADR-0056). Feature-flag registry (ADR-0067) — a read-only
// index of the fleet's flag-as-code (per-service flagd ConfigMaps), baked into
// the image (src/lib/governance/flags.ts). Grouped by service so an operator/
// auditor sees every flag, its state, and its governance classification at a
// glance. Flipping is a git change (future BFF propose→PR), never done here.

import Link from 'next/link'
import { cookies } from 'next/headers'
import { ChevronLeft, Flag, ShieldAlert } from 'lucide-react'
import { loadFlagCatalog, type FlagMeta } from '@/lib/governance/flags'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

export const dynamic = 'force-dynamic'

export default async function FlagsRegistryPage() {
  const lang = (await cookies()).get('openbank-admin-lang')?.value === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)

  const flags = await loadFlagCatalog()

  const byService = new Map<string, FlagMeta[]>()
  for (const f of flags) {
    const list = byService.get(f.service) ?? []
    list.push(f)
    byService.set(f.service, list)
  }
  const services = Array.from(byService.keys()).sort()

  const enabledCount = flags.filter(f => f.state === 'ENABLED').length
  const moneyPathCount = flags.filter(f => f.classification === 'money-path').length

  const variantSummary = (f: FlagMeta) =>
    Object.keys(f.variants).length > 0
      ? Object.entries(f.variants).map(([k, v]) => `${k}=${JSON.stringify(v)}`).join(', ')
      : '—'

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>Docs</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Feature flagy', 'Feature Flags')}</span>
          </>}
        title={t('Registr feature flagů', 'Feature Flag Registry')}
        subtitle={t(
              'Flagy na všech službách, odvozené z flag-as-code v GitOpsu (ADR-0067). Zdroj pravdy je git; přepínání je změna v gitu, ne zápis z UI.',
              'Flags across the fleet, derived from flag-as-code in GitOps (ADR-0067). Git is the source of truth; flipping is a git change, not a UI write.',
            )}
        icon={<Flag aria-hidden="true" size={20} />}
        actions={<Link href="/docs" className="btn btn-secondary btn-sm" style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
          <ChevronLeft size={14} /> {t('Zpět', 'Back')}
        </Link>}
      />

      {flags.length === 0 ? (
        <div className="card" style={{ padding: '24px', color: 'var(--text-secondary)' }}>
          {t(
            'Katalog flagů není v tomto prostředí dostupný (chybí flags-bundle v image i repo strom).',
            'Flag catalog not available in this environment (no flags-bundle in the image and no repo tree).',
          )}
        </div>
      ) : (
        <>
          <div style={{ display: 'flex', gap: '12px', marginBottom: '20px', flexWrap: 'wrap' }}>
            <span className="badge badge-info">{t('Flagů', 'Flags')}: {flags.length}</span>
            <span className="badge badge-success">{t('Zapnuto', 'Enabled')}: {enabledCount}</span>
            <span className="badge badge-info">{t('Služeb', 'Services')}: {services.length}</span>
            {moneyPathCount > 0 && (
              <span className="badge badge-danger" style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                <ShieldAlert size={12} /> money-path: {moneyPathCount}
              </span>
            )}
          </div>

          {services.map(service => {
            const serviceFlags = byService.get(service)!
            const isMoneyPath = serviceFlags[0]?.classification === 'money-path'
            return (
              <section key={service} style={{ marginBottom: '24px' }}>
                <h2 style={{ fontSize: '13px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-secondary)', marginBottom: '10px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                  {service}
                  {isMoneyPath && <span className="badge badge-danger" style={{ textTransform: 'none' }}>money-path · four-eyes</span>}
                </h2>
                <div className="card" style={{ overflow: 'hidden' }}>
                  <table className="table" style={{ margin: 0 }}>
                    <thead>
                      <tr>
                        <th>{t('Flag', 'Flag')}</th>
                        <th>{t('Stav', 'State')}</th>
                        <th>{t('Výchozí varianta', 'Default variant')}</th>
                        <th>{t('Varianty', 'Variants')}</th>
                        <th>{t('Targeting', 'Targeting')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {serviceFlags.map(f => (
                        <tr key={`${f.service}:${f.key}`}>
                          <td><code style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 600 }}>{f.key}</code></td>
                          <td>
                            <span className={`badge badge-${f.state === 'ENABLED' ? 'success' : 'warning'}`}>{f.state}</span>
                          </td>
                          <td><code style={{ fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{f.defaultVariant || '—'}</code></td>
                          <td style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-secondary)' }}>{variantSummary(f)}</td>
                          <td>{f.targeted ? <span className="badge badge-info">{t('cílené', 'targeted')}</span> : <span style={{ color: 'var(--text-tertiary)' }}>—</span>}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            )
          })}
        </>
      )}
    </div>
  )
}
