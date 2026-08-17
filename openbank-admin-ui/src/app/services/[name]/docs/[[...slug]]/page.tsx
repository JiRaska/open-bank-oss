// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server component. Reads docs via the shared loader in
// src/lib/services/docs.ts — which delegates per service:
//   - "libs"        → image-baked bundle (no runnable service)
//   - runnable svc  → live fetch from /q/openbank/docs
//
// Language: read from openbank-admin-lang cookie (mirrored by LanguageContext)
// or from ?lang= query override (used by the in-page lang switcher links).

import Link from 'next/link'
import { cookies } from 'next/headers'
import { BookOpen, ChevronLeft, FileText, AlertCircle, Wifi, HardDrive, ExternalLink, FileJson, Heart, Activity, Info, Hash, Globe } from 'lucide-react'
import { MarkdownView } from '@/components/docs/MarkdownView'
import { MermaidEnhancer } from '@/components/docs/MermaidEnhancer'
import { loadDocsIndex, loadDocsDocument } from '@/lib/services/docs'
import { LANG_COOKIE } from '@/lib/i18n/LanguageContext'

interface PageProps {
  params: Promise<{ name: string; slug?: string[] }>
  searchParams: Promise<{ lang?: string }>
}

const LANG_NAMES: Record<string, string> = { cs: 'Čeština', en: 'English' }

// Display metadata for the well-known related-endpoint chips. Server publishes
// the path; we map it to an icon + label + new-tab behaviour. Unknown keys
// (forward-compat with future libs versions) fall back to a generic chip.
const LINK_META: Record<string, { label: string; icon: React.ReactNode; openInNewTab: boolean }> = {
  openapi:   { label: 'OpenAPI',     icon: <FileJson size={11} />,    openInNewTab: true },
  swagger:   { label: 'Swagger UI',  icon: <Globe size={11} />,       openInNewTab: true },
  health:    { label: 'Health',      icon: <Heart size={11} />,       openInNewTab: true },
  metrics:   { label: 'Metrics',     icon: <Activity size={11} />,    openInNewTab: true },
  info:      { label: 'Service info',icon: <Info size={11} />,        openInNewTab: true },
  docsMeta:  { label: 'Docs meta',   icon: <Hash size={11} />,        openInNewTab: true },
}

/**
 * Map the short `name` used in the URL (e.g. "account") to the service id the
 * /api/svc proxy expects (e.g. "account-service"). The proxy's SERVICE_MAP uses
 * the -service suffix; the docs page uses the short form for nicer URLs. A
 * handful of services (sepa-instant, sepa-payment, domestic-payment,
 * product-catalog, security-scanner) don't carry the suffix at all.
 */
function normaliseServiceForProxy(name: string): string {
  const noSuffix = new Set(['sepa-instant', 'sepa-payment', 'domestic-payment', 'product-catalog', 'security-scanner'])
  if (noSuffix.has(name)) return name
  if (name.endsWith('-service')) return name
  return `${name}-service`
}

export default async function ServiceDocsPage({ params, searchParams }: PageProps) {
  const { name, slug: rawSlug } = await params
  const { lang: langQuery } = await searchParams
  const slug = rawSlug?.[0] ?? 'index'

  const langCookie = (await cookies()).get(LANG_COOKIE)?.value
  const requestedLang = langQuery ?? langCookie ?? 'en'

  // UI chrome language follows the cookie (mirrored by LanguageContext); the
  // ?lang= override targets the doc *content*, not the surrounding chrome.
  const lang = langCookie === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)

  const [index, doc] = await Promise.all([
    loadDocsIndex(name, requestedLang),
    loadDocsDocument(name, slug, requestedLang),
  ])

  const items = index?.items ?? []
  const source = doc?.source ?? index?.source
  const docLangs = doc?.availableLanguages ?? []

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '240px 1fr', gap: '24px', minHeight: 'calc(100vh - 100px)' }}>
      <aside style={{
        background: 'var(--surface)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--r-lg)',
        padding: '14px',
        height: 'fit-content',
        position: 'sticky',
        top: '16px',
      }}>
        <Link
          href="/services"
          style={{
            display: 'flex', alignItems: 'center', gap: '6px',
            fontSize: '11px', color: 'var(--text-tertiary)', textDecoration: 'none',
            marginBottom: '12px',
          }}
        >
          <ChevronLeft size={12} /> {t('Zpět na služby', 'Back to services')}
        </Link>
        <div style={{
          fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.06em',
          color: 'var(--text-tertiary)', fontWeight: 600, marginBottom: '4px',
          display: 'flex', alignItems: 'center', gap: '6px',
        }}>
          <BookOpen size={12} /> {name}
        </div>
        {source && (
          <div
            title={source === 'live'
              ? t('Živé načtení ze služby /q/openbank/docs', 'Live fetch from service /q/openbank/docs')
              : t('Balíček zapečený do image (libs nemá spustitelnou službu)', 'Image-baked bundle (libs has no runnable service)')}
            style={{
              display: 'inline-flex', alignItems: 'center', gap: '4px',
              fontSize: '10px', padding: '2px 6px', borderRadius: '8px',
              background: source === 'live' ? 'var(--success-bg, #d1fae5)' : 'var(--surface-2)',
              color: source === 'live' ? 'var(--success, #047857)' : 'var(--text-tertiary)',
              marginBottom: '12px',
            }}
          >
            {source === 'live' ? <Wifi size={10} /> : <HardDrive size={10} />}
            {source === 'live' ? `live · v${index?.version ?? '?'}` : 'bundle'}
          </div>
        )}
        <nav style={{ display: 'flex', flexDirection: 'column', gap: '2px' }}>
          {items.map(item => {
            const active = item.slug === slug
            return (
              <Link
                key={item.slug}
                href={`/services/${name}/docs/${item.slug}`}
                style={{
                  display: 'flex', alignItems: 'center', gap: '6px',
                  padding: '6px 8px', borderRadius: 'var(--r-sm)',
                  fontSize: '13px', textDecoration: 'none',
                  background: active ? 'var(--accent-bg)' : 'transparent',
                  color: active ? 'var(--accent)' : 'var(--text-secondary)',
                  fontWeight: active ? 600 : 400,
                }}
              >
                <FileText size={12} style={{ opacity: 0.6, flexShrink: 0 }} />
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {item.title}
                </span>
              </Link>
            )
          })}
          {items.length === 0 && (
            <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', padding: '8px', lineHeight: 1.5 }}>
              {t('Žádné dokumenty. Služba buď nemá ', 'No documents. The service either has no ')}
              <code>src/main/resources/docs/</code>
              {t(', nebo neběží, nebo neodpovídá v limitu 2 s.', ', or is not running, or does not respond within the 2 s limit.')}
            </div>
          )}
        </nav>

        {/* Related endpoints — chips link through the admin-ui /api/svc proxy
            so they stay first-party. Service serves the path list at
            /q/openbank/docs (schema openbank.docs.v3+ adds the `links` field);
            for libs (bundle source) the field is absent and we render nothing. */}
        {index?.links && Object.keys(index.links).length > 0 && name !== 'libs' && (
          <div style={{ marginTop: '16px', paddingTop: '14px', borderTop: '1px solid var(--border)' }}>
            <div style={{
              fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.06em',
              color: 'var(--text-tertiary)', fontWeight: 600, marginBottom: '8px',
            }}>
              {t('Související', 'Related')}
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
              {Object.entries(index.links).map(([key, path]) => {
                const meta = LINK_META[key] ?? { label: key, icon: <ExternalLink size={11} />, openInNewTab: true }
                const proxied = `/api/svc/${normaliseServiceForProxy(name)}${path}`
                return (
                  <a
                    key={key}
                    href={proxied}
                    target={meta.openInNewTab ? '_blank' : undefined}
                    rel={meta.openInNewTab ? 'noreferrer' : undefined}
                    style={{
                      display: 'flex', alignItems: 'center', gap: '6px',
                      padding: '5px 8px', borderRadius: 'var(--r-sm)',
                      fontSize: '12px', textDecoration: 'none',
                      color: 'var(--text-secondary)',
                      transition: 'background 0.12s',
                    }}
                    className="docs-link-hover"
                    title={t(`${path} (přes admin-ui proxy)`, `${path} (via admin-ui proxy)`)}
                  >
                    {meta.icon}
                    <span style={{ flex: 1 }}>{meta.label}</span>
                    <ExternalLink size={10} style={{ opacity: 0.5 }} />
                  </a>
                )
              })}
            </div>
          </div>
        )}
      </aside>

      <div style={{
        background: 'var(--surface)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--r-lg)',
        padding: '32px 40px',
        minWidth: 0,
      }}>
        {/* Per-doc language switcher — shown only when multiple translations exist. */}
        {docLangs.length > 1 && (
          <div style={{
            display: 'flex', alignItems: 'center', gap: '8px',
            marginBottom: '20px', paddingBottom: '14px',
            borderBottom: '1px solid var(--border)',
          }}>
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              {t('Jazyk', 'Language')}
            </span>
            {docLangs.map(l => {
              const isActive = doc?.lang === l
              return (
                <Link
                  key={l}
                  href={`/services/${name}/docs/${slug}?lang=${l}`}
                  style={{
                    fontSize: '12px', fontWeight: isActive ? 600 : 400,
                    padding: '3px 10px', borderRadius: '12px',
                    background: isActive ? 'var(--accent-bg)' : 'transparent',
                    color: isActive ? 'var(--accent)' : 'var(--text-secondary)',
                    border: '1px solid',
                    borderColor: isActive ? 'transparent' : 'var(--border)',
                    textDecoration: 'none',
                  }}
                >
                  {LANG_NAMES[l] ?? l.toUpperCase()}
                </Link>
              )
            })}
          </div>
        )}

        {doc === null ? (
          <div style={{
            display: 'flex', alignItems: 'flex-start', gap: '10px',
            background: 'var(--warning-bg)', border: '1px solid var(--warning-border)',
            color: 'var(--warning)', padding: '14px 16px', borderRadius: 'var(--r-md)',
            fontSize: '13px',
          }}>
            <AlertCircle size={16} style={{ flexShrink: 0, marginTop: '1px' }} />
            <div>
              <div style={{ fontWeight: 600, marginBottom: '4px' }}>{t('Dokument nenalezen', 'Document not found')}</div>
              <div style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '12px' }}>
                {name}/{slug} (lang={requestedLang})
              </div>
            </div>
          </div>
        ) : (
          <MermaidEnhancer contentKey={`${name}-${slug}-${doc.lang}`}>
            <MarkdownView markdown={doc.markdown} serviceName={name} />
          </MermaidEnhancer>
        )}
      </div>
    </div>
  )
}
