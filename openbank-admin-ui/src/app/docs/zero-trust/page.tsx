// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server component (ADR-0056). Zero-Trust Security Map — a defense-in-depth view
// derived from the real platform manifests (src/lib/governance/security.ts,
// governance-as-code per ADR-0029). Renders the request path as nested
// perimeters wrapping the data plane, plus the "what happens to an unauthorized
// call" walkthrough and the supply-chain maturity panel. Every status badge is
// read from the manifest, not asserted.

import Link from 'next/link'
import { cookies } from 'next/headers'
import {
  ChevronLeft, ShieldCheck, Lock, KeyRound, Network, ScanLine, Database,
  AlertTriangle, Ban, CheckCircle2, Globe,
} from 'lucide-react'
import { loadSecurityPosture } from '@/lib/governance/security'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

export const dynamic = 'force-dynamic'

type Status = 'enforced' | 'exception' | 'audit' | 'unknown'

function statusStyle(s: Status): { color: string; bg: string } {
  switch (s) {
    case 'enforced':  return { color: 'var(--success)', bg: 'var(--success-bg)' }
    case 'exception': return { color: 'var(--warning)', bg: 'var(--warning-bg)' }
    case 'audit':     return { color: 'var(--warning)', bg: 'var(--warning-bg)' }
    default:          return { color: 'var(--text-tertiary)', bg: 'var(--surface-2)' }
  }
}

export default async function ZeroTrustPage() {
  const lang = (await cookies()).get('openbank-admin-lang')?.value === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)
  const statusLabel = (s: Status) => ({
    enforced: t('Vynuceno', 'Enforced'),
    exception: t('Výjimka', 'Exception'),
    audit: t('Audit (roadmapa)', 'Audit (roadmap)'),
    unknown: t('Neznámé', 'Unknown'),
  }[s])

  const posture = await loadSecurityPosture()

  if (!posture) {
    return (
      <div>
        <DocsPageHeader
          crumbs={<>
              <span>OpenBank</span><span className="breadcrumb-sep">/</span>
              <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>Docs</Link>
              <span className="breadcrumb-sep">/</span>
              <span className="breadcrumb-current">{t('Zero-Trust mapa', 'Zero-Trust map')}</span>
          </>}
          title={t('Zero-Trust bezpečnostní mapa', 'Zero-Trust Security Map')}
          icon={<ShieldCheck aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        />
        <div className="card" style={{ padding: '24px' }}>
          <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
            {t(
              'Snímek bezpečnostní pozice není v tomto prostředí dostupný (security-graph.json chybí).',
              'The security-posture snapshot is not available in this environment (security-graph.json missing).',
            )}
          </p>
        </div>
      </div>
    )
  }

  const net = posture.network
  const sc = posture.supplyChain
  const meshDeployed = Boolean(posture.istio?.available && posture.istio?.deployed)
  const meshNote = posture.istio?.note ?? t('stav neznámý', 'status unknown')
  const netGaps = net?.coverage?.gaps ?? []
  // Partial fleet coverage reads as an "exception" (a known, bounded gap), not
  // a flat "unknown" — we DO know the coverage number, we're just honest that
  // it isn't 100%.
  const netpolStatus: Status = !net?.coverage
    ? 'unknown'
    : netGaps.length === 0 ? 'enforced' : (net.coverage.covered > 0 ? 'exception' : 'unknown')

  // Perimeters, OUTER → INNER (the path a request travels toward the data plane).
  // Each status flag is read from the derived posture, never asserted.
  const perimeters: {
    key: string; icon: React.ReactNode; color: string; title: string; tech: string
    status: Status; fact: string; regs: string[]
  }[] = [
    {
      key: 'netpol', icon: <Network size={15} />, color: '#0891b2',
      title: t('Síťová segmentace (L3/L4)', 'Network segmentation (L3/L4)'),
      tech: 'Kubernetes NetworkPolicy (gen-network-policies.py)',
      status: netpolStatus,
      fact: net?.coverage
        ? t(`default-deny allow-list na ${net.coverage.covered}/${net.coverage.total} workloadech`
            + (netGaps.length ? ` · mezery: ${netGaps.slice(0, 4).map(g => `${g.namespace}/${g.service}`).join(', ')}${netGaps.length > 4 ? '…' : ''}` : ''),
            `default-deny allow-list on ${net.coverage.covered}/${net.coverage.total} workloads`
            + (netGaps.length ? ` · gaps: ${netGaps.slice(0, 4).map(g => `${g.namespace}/${g.service}`).join(', ')}${netGaps.length > 4 ? '…' : ''}` : ''))
        : t('stav neznámý', 'status unknown'),
      regs: ['NIS2 Art. 21', 'DORA Art. 9'],
    },
    {
      key: 'mtls', icon: <Lock size={15} />, color: '#2563eb',
      title: t('Šifrovaný transport (mTLS)', 'Encrypted transport (mTLS)'),
      tech: 'Istio PeerAuthentication (not deployed)',
      status: meshDeployed ? 'enforced' : 'unknown',
      fact: meshDeployed ? t('STRICT mTLS pro veškerý east-west provoz', 'STRICT mTLS for all east-west traffic') : meshNote,
      regs: ['NIS2 Art. 21', 'DORA Art. 9'],
    },
    {
      key: 'jwt', icon: <KeyRound size={15} />, color: '#7c3aed',
      title: t('Ověření identity (JWT)', 'Identity authentication (JWT)'),
      tech: 'Keycloak · quarkus-oidc (per-service, not a mesh edge)',
      status: 'unknown',
      fact: t('Keycloak JWT je ověřován per-service přes quarkus-oidc, ne na mesh hraně (žádný mesh není nasazen)',
        'Keycloak JWT is validated per-service via quarkus-oidc, not at a mesh edge (no mesh is deployed)'),
      regs: ['PSD2 SCA', 'EBA ICT'],
    },
    {
      key: 'l7', icon: <ShieldCheck size={15} />, color: '#059669',
      title: t('Autorizace (L7 default-deny)', 'Authorization (L7 default-deny)'),
      tech: 'Istio AuthorizationPolicy (not deployed)',
      status: meshDeployed ? 'enforced' : 'unknown',
      fact: meshDeployed
        ? t('projde jen platný JWT principal nebo in-mesh service account', 'only a valid JWT principal or in-mesh service account passes')
        : meshNote,
      regs: ['EBA ICT'],
    },
  ]

  // Fold OUTER→INNER: render the innermost (data plane) first, then wrap.
  const core = (
    <div style={{
      border: '1px dashed var(--border)', borderRadius: 'var(--r-md)',
      background: 'var(--surface-2)', padding: '16px', textAlign: 'center',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px', marginBottom: '8px' }}>
        <Database size={15} style={{ color: 'var(--text-secondary)' }} />
        <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
          {t('Data plane', 'Data plane')}
        </span>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', justifyContent: 'center' }}>
        {(net?.egressTargets ?? []).filter(e => !e.target.startsWith('internet:')).map(e => (
          <span key={e.target} style={{
            fontSize: '11px', fontFamily: 'JetBrains Mono, monospace',
            padding: '2px 7px', borderRadius: '6px',
            background: 'var(--surface)', color: 'var(--text-secondary)', border: '1px solid var(--border)',
          }}>
            {e.target}:{e.ports.join('/')}
          </span>
        ))}
      </div>
    </div>
  )

  const nested = perimeters.reduceRight((inner, p) => {
    const st = statusStyle(p.status)
    return (
      <div style={{
        border: `2px solid ${p.color}`, borderRadius: 'var(--r-lg)',
        padding: '14px', background: `${p.color}08`,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '8px', marginBottom: '4px' }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '7px', fontSize: '13px', fontWeight: 700, color: p.color }}>
            {p.icon} {p.title}
          </span>
          <span style={{
            fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '20px',
            background: st.bg, color: st.color, textTransform: 'uppercase', letterSpacing: '0.04em',
          }}>
            {statusLabel(p.status)}
          </span>
        </div>
        <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '3px', fontFamily: 'JetBrains Mono, monospace' }}>
          {p.tech}
        </div>
        <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '8px', lineHeight: 1.4 }}>
          {p.fact}
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px', marginBottom: '12px' }}>
          {p.regs.map(r => (
            <span key={r} style={{
              fontSize: '10px', padding: '1px 6px', borderRadius: '4px',
              background: 'var(--surface-2)', color: 'var(--text-tertiary)', border: '1px solid var(--border)',
            }}>
              {r}
            </span>
          ))}
        </div>
        {inner}
      </div>
    )
  }, core)

  // The "unauthorized call" walkthrough — each attack and where the stack stops it.
  const bounce = [
    {
      icon: <Network size={14} />,
      attack: t('Cizí pod zkusí přímé L3/L4 spojení', 'Foreign pod attempts a direct L3/L4 connection'),
      stop: net?.defaultDeny
        ? t('Zahozeno — NetworkPolicy default-deny', 'Dropped — NetworkPolicy default-deny')
        : t(`Síťová vrstva — zahozeno na ${net?.coverage?.covered ?? 0}/${net?.coverage?.total ?? 0} workloadech s allow-listem, jinde otevřeno`,
            `Network layer — dropped on ${net?.coverage?.covered ?? 0}/${net?.coverage?.total ?? 0} workloads with an allow-list, open elsewhere`),
    },
    {
      icon: <Lock size={14} />,
      attack: t('Clear-text east-west volání mezi službami', 'Clear-text east-west call between services'),
      stop: meshDeployed
        ? t('Odmítnuto — vyžadováno STRICT mTLS', 'Rejected — STRICT mTLS required')
        : t('Transportní vrstva (žádný mesh — east-west je dnes plaintext)', 'Transport layer (no mesh — east-west is plaintext today)'),
    },
    {
      icon: <KeyRound size={14} />,
      attack: t('Požadavek bez platného JWT na gateway', 'Request without a valid JWT at the gateway'),
      stop: t('Zamítnuto per-service — quarkus-oidc (Keycloak JWT), ne mesh L7', 'Denied per-service — quarkus-oidc (Keycloak JWT), not mesh L7'),
    },
  ]

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>Docs</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Zero-Trust mapa', 'Zero-Trust map')}</span>
          </>}
        title={t('Zero-Trust bezpečnostní mapa', 'Zero-Trust Security Map')}
        subtitle={t(
              'Obrana do hloubky odvozená z reálných, gitops-nasazených manifestů (NetworkPolicy, Kyverno). Žádný service mesh v sandboxu neběží — mTLS/JWT/L7 řádky níže to říkají otevřeně, ne jako "vynuceno".',
              'Defense in depth derived from the real, gitops-deployed manifests (NetworkPolicy, Kyverno). No service mesh runs in the sandbox — the mTLS/JWT/L7 rows below say so plainly, not "enforced".',
            )}
        icon={<ShieldCheck aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<Link href="/docs" className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <ChevronLeft size={14} />
          {t('Zpět na dokumentaci', 'Back to docs')}
        </Link>}
      />

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1.6fr) minmax(0, 1fr)', gap: '20px', alignItems: 'start' }}>
        {/* Left: nested perimeters */}
        <div className="card" style={{ padding: '20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '7px', fontSize: '12px', color: 'var(--text-tertiary)', marginBottom: '14px' }}>
            <Globe size={13} />
            {t('Internet → hrana (ingress-nginx, TLS) → vrstvy ↓', 'Internet → edge (ingress-nginx, TLS) → layers ↓')}
          </div>
          {nested}
        </div>

        {/* Right: bounce path + supply chain */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
          <div className="card" style={{ padding: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '7px', marginBottom: '12px' }}>
              <Ban size={15} style={{ color: 'var(--danger)' }} />
              <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
                {t('Co se stane s neautorizovaným voláním', 'What happens to an unauthorized call')}
              </span>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {bounce.map((b, i) => (
                <div key={i} style={{
                  display: 'flex', alignItems: 'flex-start', gap: '10px',
                  padding: '10px 12px', borderRadius: 'var(--r-md)',
                  background: 'var(--surface-2)', borderLeft: '3px solid var(--danger)',
                }}>
                  <span style={{ color: 'var(--text-tertiary)', marginTop: '1px' }}>{b.icon}</span>
                  <div>
                    <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '3px' }}>{b.attack}</div>
                    <div style={{ display: 'inline-flex', alignItems: 'center', gap: '5px', fontSize: '12px', fontWeight: 600, color: 'var(--danger)' }}>
                      <Ban size={11} /> {b.stop}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Supply chain — framed as maturity/roadmap, not an exploitable gap. */}
          {sc?.available && (
            <div className="card" style={{ padding: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '8px', marginBottom: '10px' }}>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '7px', fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
                  <ScanLine size={15} style={{ color: '#d97706' }} />
                  {t('Dodavatelský řetězec (admission)', 'Supply chain (admission)')}
                </span>
                <span style={{
                  fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '20px',
                  background: statusStyle(sc.enforced ? 'enforced' : 'audit').bg,
                  color: statusStyle(sc.enforced ? 'enforced' : 'audit').color,
                  textTransform: 'uppercase', letterSpacing: '0.04em',
                }}>
                  {sc.enforced ? statusLabel('enforced') : statusLabel('audit')}
                </span>
              </div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5, marginBottom: '10px' }}>
                {t(
                  `${sc.engine} ověřuje Cosign podpisy obrazů (${sc.policy}). Dnes v režimu Audit — nepodepsané obrazy se reportují, nic neblokuje. Roadmapa: podepisovat v CI → přepnout na Enforce.`,
                  `${sc.engine} verifies Cosign image signatures (${sc.policy}). In Audit mode today — unsigned images are reported, nothing is blocked. Roadmap: sign in CI → flip to Enforce.`,
                )}
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                {sc.rekor && (
                  <span style={{ fontSize: '10px', padding: '1px 6px', borderRadius: '4px', background: 'var(--surface-2)', color: 'var(--text-tertiary)', border: '1px solid var(--border)' }}>
                    Rekor · {t('transparenční log', 'transparency log')}
                  </span>
                )}
                <span style={{ fontSize: '10px', padding: '1px 6px', borderRadius: '4px', background: 'var(--surface-2)', color: 'var(--text-tertiary)', border: '1px solid var(--border)' }}>
                  ADR-0030
                </span>
                <span style={{ fontSize: '10px', padding: '1px 6px', borderRadius: '4px', background: 'var(--surface-2)', color: 'var(--text-tertiary)', border: '1px solid var(--border)' }}>
                  {t('EBA ICT', 'EBA ICT')}
                </span>
              </div>
            </div>
          )}

          {/* Internet egress posture */}
          <div className="card" style={{ padding: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '7px', marginBottom: '10px' }}>
              {net?.internetEgress === 'opt-in'
                ? <CheckCircle2 size={15} style={{ color: 'var(--success)' }} />
                : <AlertTriangle size={15} style={{ color: 'var(--warning)' }} />}
              <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
                {t('Egress do internetu', 'Internet egress')}
              </span>
            </div>
            <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
              {net?.internetEgress === 'opt-in'
                ? t('Default odepřen. Službа se musí explicitně přihlásit labelem openbank.io/allow-internet-egress=true (např. FX kurzy, import sankčních seznamů).',
                    'Denied by default. A service must explicitly opt in via the openbank.io/allow-internet-egress=true label (e.g. FX rates, sanctions-list import).')
                : t('Otevřený egress.', 'Open egress.')}
            </div>
          </div>
        </div>
      </div>

      <div style={{ marginTop: '14px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
        {t('Odvozeno z: ', 'Derived from: ')}
        <span style={{ fontFamily: 'JetBrains Mono, monospace' }}>{posture.source.replace(/^derived from /, '')}</span>
      </div>
    </div>
  )
}
