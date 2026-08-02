// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useRef, useEffect } from 'react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useSession } from 'next-auth/react'
import {
  LayoutDashboard, CreditCard, ArrowLeftRight, BookOpen, Settings,
  HeartPulse, SlidersHorizontal, Bot, Users, ShieldCheck, Banknote,
  ScrollText, Bell, Map, FileCode, Shield, Library, FileText, Flag,
  DollarSign, Globe, Repeat, Zap, AlertOctagon, ScanLine,
  Layers, TrendingUp, MessageSquareWarning, Package, Receipt, Server, ShieldAlert, FlaskConical, Cloud,
  PiggyBank, GitBranch, Lock, ClipboardList, Scale, Smartphone,
  ClipboardCheck, Activity, Boxes, Bluetooth, Fingerprint, FileSignature, Network, Waypoints, Workflow,
  Megaphone,
  Target,
} from 'lucide-react'
import { hasPermission, Permission } from '@/lib/auth/roles'
import { personaForRoles, personaLabel, workspaceFor } from '@/lib/auth/persona'
import { useLanguage } from '@/lib/i18n/LanguageContext'

// `external: true` marks a destination that is NOT a Next.js route — today the
// internal tool UIs served as sub-paths of this same host by their own Ingress
// (ADR-0234). They must render as a plain <a>: a next/link would try a
// client-side navigation into the App Router and 404, because no page.tsx backs
// the path. Same origin, so the session cookie still rides along and the edge
// gate can read it.
// `deniedRole` mirrors the per-tool deny-list in src/app/api/gate/route.ts. The
// gate is what ENFORCES it; this only decides how the entry renders, so a denied
// tool shows as a disabled item with the demo-account tooltip instead of a live
// link that 403s. A link that visibly fails reads as a broken platform, which for
// the public demo account is the outcome worth avoiding.
type NavItem = { nameCs: string; nameEn: string; href: string; icon: React.ElementType; permission?: Permission; lockedPermission?: Permission; deniedRole?: string; badge?: string; external?: boolean }

const coreNav: NavItem[] = [
  { nameCs: 'Přehled',      nameEn: 'Dashboard',    href: '/dashboard',    icon: LayoutDashboard },
  { nameCs: 'Účty',         nameEn: 'Accounts',     href: '/accounts',     icon: CreditCard,      permission: 'accounts:view' },
  { nameCs: 'Transakce',    nameEn: 'Transactions', href: '/transactions', icon: ArrowLeftRight,  permission: 'transactions:view' },
  { nameCs: 'Hlavní kniha', nameEn: 'Ledger',       href: '/ledger',       icon: BookOpen,        permission: 'accounts:view' },
  { nameCs: 'Závěrky',      nameEn: 'Closings',     href: '/day-end',      icon: Scale,           permission: 'accounts:view' },
]

const revenueNav: NavItem[] = [
  { nameCs: 'Úvěry',        nameEn: 'Lending',      href: '/lending',      icon: TrendingUp,    permission: 'payments:view' },
  { nameCs: 'Poplatky',     nameEn: 'Fees',         href: '/fees',         icon: Receipt,         permission: 'payments:view' },
]

const customerNav: NavItem[] = [
  { nameCs: 'Strany',      nameEn: 'Parties',    href: '/parties',    icon: Users,          permission: 'parties:view' },
  { nameCs: 'KYC',         nameEn: 'KYC',         href: '/kyc',        icon: ShieldCheck,    permission: 'kyc:view' },
  { nameCs: 'Onboarding',  nameEn: 'Onboarding',  href: '/onboarding', icon: ClipboardList,  permission: 'onboarding:view' },
  { nameCs: 'Ověření identity', nameEn: 'Identity Cases', href: '/identity-cases', icon: Fingerprint, permission: 'onboarding:view' },
]

const paymentsNav: NavItem[] = [
  { nameCs: 'Katalog produktů',  nameEn: 'Product Catalog',  href: '/product-catalog',   icon: Package,   permission: 'payments:view' },
  { nameCs: 'Platby',            nameEn: 'Payments',         href: '/payments',          icon: Banknote,  permission: 'payments:view' },
  { nameCs: 'Trvalé příkazy',    nameEn: 'Standing Orders',  href: '/standing-orders',   icon: Repeat,    permission: 'payments:view' },
  { nameCs: 'Inkasa (SDD)',      nameEn: 'Direct Debits',    href: '/sdd',               icon: Repeat,    permission: 'payments:view' },
  { nameCs: 'FX',                nameEn: 'FX',               href: '/fx',                icon: DollarSign,permission: 'payments:view' },
  { nameCs: 'SWIFT',             nameEn: 'SWIFT',            href: '/swift',             icon: Globe,     permission: 'payments:view' },
  { nameCs: 'Karty',             nameEn: 'Cards',            href: '/cards',             icon: CreditCard,permission: 'payments:view' },
  { nameCs: 'Clearing',          nameEn: 'Clearing',         href: '/clearing',          icon: Layers,    permission: 'payments:view' },
  { nameCs: 'Úroky',             nameEn: 'Interest',         href: '/interest',          icon: TrendingUp,permission: 'payments:view' },
  { nameCs: 'Šablony dokumentů', nameEn: 'Document Templates', href: '/document-templates', icon: FileSignature, permission: 'templates:view' },

]

const complianceNav: NavItem[] = [
  { nameCs: 'AML',                nameEn: 'AML',              href: '/aml',               icon: AlertOctagon,          permission: 'compliance:view' },
  { nameCs: 'Fraud',              nameEn: 'Fraud',            href: '/fraud',             icon: ShieldAlert,           permission: 'compliance:view' },
  { nameCs: 'Sankce',             nameEn: 'Sanctions',        href: '/sanctions',         icon: Shield,                permission: 'compliance:view' },
  { nameCs: 'Spory',              nameEn: 'Disputes',         href: '/disputes',          icon: MessageSquareWarning,  permission: 'compliance:view' },
  { nameCs: 'Customer 360',        nameEn: 'Customer 360',     href: '/customer-360',      icon: Users,                 permission: 'compliance:view' },
  { nameCs: 'Kampaně',            nameEn: 'Campaigns',        href: '/campaigns',         icon: Megaphone,             permission: 'compliance:view' },
  { nameCs: 'Segmenty',           nameEn: 'Segments',         href: '/segments',          icon: Target,                permission: 'compliance:view' },
  { nameCs: 'Souhlasy',           nameEn: 'Consents',         href: '/consents',          icon: FileSignature,           permission: 'compliance:view' },
  // ADR-0212 D4: four-eyes activation of the jurisdictional credit compliance packs. Filed under
  // compliance, not lending, because the backing endpoints are ROLE_COMPLIANCE/ROLE_ADMIN — a
  // lending officer cannot propose or decide one.
  { nameCs: 'Úvěrové compliance packy', nameEn: 'Credit Compliance Packs', href: '/lending/compliance-packs', icon: ShieldCheck, permission: 'compliance:view' },
  { nameCs: 'Auditní záznamy',    nameEn: 'Audit Log',        href: '/audit',             icon: ScrollText,            permission: 'audit:view' },
  { nameCs: 'Regulatorní',        nameEn: 'Regulatory',       href: '/regulatory',        icon: FileText,              permission: 'regulatory:view' },
]

const opsNav: NavItem[] = [
  { nameCs: 'PID',                   nameEn: 'PID',              href: '/pid',               icon: Map,          permission: 'payments:view' },
  { nameCs: 'Oznámení',              nameEn: 'Notifications',    href: '/notifications',     icon: Bell,         permission: 'system:view' },
  { nameCs: 'Bezpečnostní kontrola', nameEn: 'Security Scan',    href: '/security',          icon: ScanLine,     permission: 'system:view' },
]

const docsNav: NavItem[] = [
  { nameCs: 'Dokumentace',    nameEn: 'Docs Home',          href: '/docs',             icon: Library,     permission: 'docs:view' },
  { nameCs: 'Dokumentace služeb', nameEn: 'Service Docs',   href: '/services',         icon: BookOpen,    permission: 'docs:view' },
  { nameCs: 'Aplikace',       nameEn: 'Customer App',       href: '/docs/customer-app', icon: Smartphone, permission: 'docs:view' },
  { nameCs: 'Identita & dedup', nameEn: 'Identity & Dedup',  href: '/docs/identity-dedup', icon: Fingerprint, permission: 'docs:view' },
  { nameCs: 'QRlessPay',      nameEn: 'QRlessPay',          href: '/docs/qrlesspay',   icon: Bluetooth,  permission: 'docs:view' },
  { nameCs: 'Správa dokumentů', nameEn: 'Document Management', href: '/docs/document-management', icon: FileSignature, permission: 'docs:view' },
  { nameCs: 'Cloud architektura', nameEn: 'Cloud Architecture', href: '/docs/cloud-architecture', icon: Cloud, permission: 'docs:view' },
  { nameCs: 'Cluster & kontejner', nameEn: 'Cluster & Container', href: '/docs/cluster', icon: Boxes, permission: 'docs:view' },
  { nameCs: 'Mapa služeb',    nameEn: 'Service Map',        href: '/docs/service-map', icon: Map,         permission: 'docs:view' },
  { nameCs: 'Datová lineage', nameEn: 'Data Lineage',       href: '/docs/lineage',     icon: Waypoints,   permission: 'docs:view' },
  { nameCs: 'BPMN',           nameEn: 'BPMN',               href: '/docs/bpmn',        icon: FileCode,    permission: 'docs:view' },
  { nameCs: 'Katalog API',    nameEn: 'API Catalog',        href: '/docs/api',         icon: FileCode,    permission: 'docs:view' },
  { nameCs: 'Feature flagy',  nameEn: 'Feature Flags',      href: '/docs/flags',       icon: Flag,        permission: 'docs:view' },
  { nameCs: 'Compliance',     nameEn: 'Compliance',         href: '/docs/compliance',  icon: Shield,      permission: 'compliance:view' },
  { nameCs: 'BCP',            nameEn: 'BCP',                href: '/docs/bcp',         icon: ShieldAlert, permission: 'compliance:view' },
]

const platformNav: NavItem[] = [
  { nameCs: 'FinOps',   nameEn: 'FinOps',   href: '/finops',   icon: PiggyBank,  permission: 'system:view' },
  { nameCs: 'DevOps',   nameEn: 'DevOps',   href: '/devops',   icon: GitBranch,  permission: 'system:view' },
  { nameCs: 'IAOps',    nameEn: 'IAOps',    href: '/iaops',    icon: Bot,        permission: 'system:view' },
  { nameCs: 'Temporal', nameEn: 'Temporal', href: '/temporal', icon: Zap,        permission: 'system:view' },
  { nameCs: 'Tok workflow', nameEn: 'Workflow Flow', href: '/temporal/flow', icon: Workflow, permission: 'system:view' },
  { nameCs: 'Observability', nameEn: 'Observability', href: '/observability', icon: Activity, permission: 'system:view' },
  { nameCs: 'Schvalování', nameEn: 'Approvals', href: '/approvals', icon: ClipboardCheck, permission: 'system:view' },
]

// Internal tool UIs, reachable at /tools/<tool> on this same host behind the
// identity-aware edge gate (ADR-0234). The permission here MUST match the one
// `src/app/api/gate/route.ts` requires for the tool — the gate is what actually
// enforces it, this only decides whether the operator sees the link.
const toolsNav: NavItem[] = [
  { nameCs: 'Grafana',        nameEn: 'Grafana',        href: '/tools/grafana',      icon: Activity,    permission: 'system:view', external: true },
  // Denied to the public demo account: Alertmanager has no role model, so anything
  // that can load its UI can silence alerts platform-wide (gate route.ts).
  { nameCs: 'Alerty',         nameEn: 'Alertmanager',   href: '/tools/alertmanager', icon: Bell,        permission: 'system:view', external: true, deniedRole: 'ROLE_DEMO' },
  { nameCs: 'SLO (Pyrra)',    nameEn: 'SLO (Pyrra)',    href: '/tools/pyrra',        icon: Scale,       permission: 'system:view', external: true },
]

const sysNav: NavItem[] = [
  { nameCs: 'Zdraví systému',   nameEn: 'System Health',   href: '/system/health',    icon: HeartPulse,        permission: 'system:view' },
  { nameCs: 'Tech Inventory',   nameEn: 'Tech Inventory',  href: '/system/inventory', icon: Package,           permission: 'system:view' },
  { nameCs: 'Infrastruktura',   nameEn: 'Infrastructure',  href: '/infrastructure',   icon: Server,            permission: 'system:view' },
  { nameCs: 'Topologie infra',  nameEn: 'Infra Topology',  href: '/infrastructure/topology', icon: Network,     permission: 'system:view' },
  { nameCs: 'Test Coverage',    nameEn: 'Test Coverage',   href: '/system/tests',     icon: FlaskConical,      permission: 'system:view' },
  { nameCs: 'Připravenost prod',nameEn: 'Prod Readiness',  href: '/system/readiness', icon: ClipboardCheck,    permission: 'system:view' },
  { nameCs: 'Konfigurace',      nameEn: 'Configuration',   href: '/system/config',    icon: SlidersHorizontal, permission: 'system:config' },
  { nameCs: 'Agent (MCP)',      nameEn: 'Agent (MCP)',     href: '/system/agent',     icon: Bot,               permission: 'system:view' },
  { nameCs: 'Nastavení',        nameEn: 'Settings',        href: '/settings',         icon: Settings, lockedPermission: 'settings:view' },
]

const SCROLL_KEY = 'ob.sidebar.scroll'

const ALL_NAV: NavItem[] = [
  ...coreNav, ...revenueNav, ...customerNav, ...paymentsNav,
  ...complianceNav, ...opsNav, ...docsNav, ...platformNav, ...toolsNav, ...sysNav,
]

export function Sidebar() {
  const pathname = usePathname()
  const { data: session } = useSession()
  const { t, language } = useLanguage()
  const roles: string[] = session?.user?.roles ?? []
  const filter = (items: NavItem[]) => items.filter(i => !i.permission || hasPermission(roles, i.permission))
  const isLocked = (item: NavItem) =>
    (!!item.lockedPermission && !hasPermission(roles, item.lockedPermission)) ||
    (!!item.deniedRole && roles.includes(item.deniedRole))

  // ADR-0229 D4 (first cut): the persona's quick links pinned at the top — the full menu below
  // is untouched. Each link inherits its permission from the same destination's nav entry.
  const persona = personaForRoles(roles)
  // A workspace link inherits its permission from the nav entry with the same href. A link with
  // NO matching entry would inherit `permission: undefined` and so render for every role — so a
  // renamed or removed destination drops out of the workspace instead of becoming an ungated
  // shortcut. `filter` below only removes items that HAVE a permission the operator lacks.
  const workspace = workspaceFor(persona).flatMap(link => {
    const nav = ALL_NAV.find(n => n.href === link.href)
    if (!nav) return []
    return [{
      nameCs: link.nameCs, nameEn: link.nameEn, href: link.href,
      icon: nav.icon, permission: nav.permission,
    }]
  })

  // Persist the nav scroll position across route changes. App-Router keeps this
  // component mounted, but a hard reload or a remount would otherwise snap the
  // list back to the top — annoying when the active item is far down. We restore
  // from sessionStorage on mount and save on every scroll (rAF-throttled).
  const navRef = useRef<HTMLElement>(null)
  useEffect(() => {
    const el = navRef.current
    if (!el) return
    const saved = sessionStorage.getItem(SCROLL_KEY)
    if (saved) el.scrollTop = Number(saved)
    let raf = 0
    const onScroll = () => {
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(() => sessionStorage.setItem(SCROLL_KEY, String(el.scrollTop)))
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => { cancelAnimationFrame(raf); el.removeEventListener('scroll', onScroll) }
  }, [])

  return (
    <aside style={{
      width: '240px', flexShrink: 0,
      background: 'var(--sidebar-bg)',
      borderRight: '1px solid var(--sidebar-border)',
      display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden',
      boxShadow: '1px 0 10px rgba(0,0,0,0.1)'
    }}>
      {/* Brand */}
      <div style={{ padding: '24px 20px 20px', borderBottom: '1px solid var(--sidebar-border)', flexShrink: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <div style={{
            width: '32px', height: '32px', background: 'linear-gradient(135deg, #6366f1 0%, #4f46e5 100%)',
            borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
            boxShadow: '0 2px 4px rgba(99,102,241,0.3)'
          }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="3"/><path d="M3 9h18M9 21V9"/>
            </svg>
          </div>
          <div>
            <div style={{ fontSize: '15px', fontWeight: 800, color: '#ffffff', letterSpacing: '-0.02em' }}>OpenBank</div>
            <div style={{ fontSize: '11px', color: 'var(--sidebar-text)', marginTop: '2px', fontWeight: 500 }}>{t('Administrace', 'Admin Portal')}</div>
          </div>
        </div>
      </div>

      {/* Nav — the only scrollable region; brand + footer stay pinned. */}
      <nav ref={navRef} className="ob-sidebar-nav" style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '16px 12px', display: 'flex', flexDirection: 'column', gap: '4px' }}>
        <SectionLabel>{t('Můj přehled', 'My workspace')} · {personaLabel(persona, language === 'cs' ? 'cs' : 'en')}</SectionLabel>
        <NavSection items={filter(workspace)} pathname={pathname} />
        <NavSection items={filter(coreNav)} pathname={pathname} />
        <SectionLabel>{t('Výnosy', 'Revenue')}</SectionLabel>
        <NavSection items={filter(revenueNav)} pathname={pathname} />
        <SectionLabel>{t('Klienti', 'Customers')}</SectionLabel>
        <NavSection items={filter(customerNav)} pathname={pathname} />
        <SectionLabel>{t('Platby', 'Payments')}</SectionLabel>
        <NavSection items={filter(paymentsNav)} pathname={pathname} />
        <SectionLabel>{t('Compliance', 'Compliance')}</SectionLabel>
        <NavSection items={filter(complianceNav)} pathname={pathname} />
        <SectionLabel>{t('Operace', 'Operations')}</SectionLabel>
        <NavSection items={filter(opsNav)} pathname={pathname} />
        <SectionLabel>{t('Platforma', 'Platform')}</SectionLabel>
        <NavSection items={filter(platformNav)} pathname={pathname} />
        <SectionLabel>{t('Dokumentace', 'Documentation')}</SectionLabel>
        <NavSection items={filter(docsNav)} pathname={pathname} />
        <SectionLabel>{t('Nástroje', 'Tools')}</SectionLabel>
        <NavSection items={filter(toolsNav)} pathname={pathname} isLocked={isLocked} />
        <SectionLabel>{t('Systém', 'System')}</SectionLabel>
        <NavSection items={filter(sysNav)} pathname={pathname} isLocked={isLocked} />
      </nav>

      {/* Footer */}
      <div style={{ padding: '16px 20px', borderTop: '1px solid var(--sidebar-border)', flexShrink: 0, background: 'rgba(0,0,0,0.1)' }}>
        <div style={{ fontSize: '11px', color: 'var(--sidebar-text)', fontWeight: 600 }}>OpenBank v2.0</div>
        <div style={{ fontSize: '10px', color: 'var(--sidebar-text-muted)', marginTop: '4px', letterSpacing: '0.02em' }}>EBA · PSD2 · CNB · GDPR</div>
      </div>
    </aside>
  )
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ fontSize: '10px', fontWeight: 700, letterSpacing: '0.1em', color: 'var(--sidebar-text-muted)',
      padding: '16px 8px 6px', textTransform: 'uppercase' }}>
      {children}
    </div>
  )
}

function NavSection({ items, pathname, isLocked }: { items: NavItem[]; pathname: string; isLocked?: (item: NavItem) => boolean }) {
  const { language } = useLanguage()

  if (!items.length) return null
  return (
    <>
      {items.map(item => {
        const active = pathname === item.href || (item.href !== '/dashboard' && pathname.startsWith(item.href))
        const locked = isLocked?.(item) ?? false
        const Icon = item.icon
        const displayName = language === 'cs' ? item.nameCs : item.nameEn

        if (locked) {
          return (
            <div key={item.href} title={language === 'cs' ? 'Přístup není povolen pro demo účet' : 'Not available for demo account'} style={{
              display: 'flex', alignItems: 'center', gap: '10px',
              padding: '8px 12px', borderRadius: '8px',
              color: 'var(--sidebar-text-muted)',
              fontSize: '13px', fontWeight: 500,
              opacity: 0.45,
              cursor: 'not-allowed',
              userSelect: 'none',
            }}>
              <Icon size={16} style={{ flexShrink: 0 }} />
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>{displayName}</span>
              <Lock size={11} style={{ flexShrink: 0, opacity: 0.7 }} />
            </div>
          )
        }

        const row = (
            <div style={{
              display: 'flex', alignItems: 'center', gap: '10px',
              padding: '8px 12px', borderRadius: '8px', cursor: 'pointer',
              background: active ? 'var(--sidebar-active-bg)' : 'transparent',
              color: active ? 'var(--sidebar-active-text)' : 'var(--sidebar-text)',
              fontSize: '13px', fontWeight: active ? 600 : 500,
              transition: 'all 0.15s ease',
              position: 'relative'
            }}
              onMouseEnter={e => { if (!active) { e.currentTarget.style.background = 'var(--sidebar-hover-bg)'; e.currentTarget.style.color = '#fff'; } }}
              onMouseLeave={e => { if (!active) { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--sidebar-text)'; } }}
            >
              {active && (
                <div style={{
                  position: 'absolute', left: 0, top: '50%', transform: 'translateY(-50%)',
                  width: '3px', height: '16px', background: 'var(--sidebar-accent)',
                  borderRadius: '0 4px 4px 0'
                }} />
              )}
              <Icon size={16} style={{ flexShrink: 0, opacity: active ? 1 : 0.7 }} />
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>{displayName}</span>
              {item.badge && (
                <span style={{ fontSize: '10px', fontWeight: 800, padding: '2px 6px', borderRadius: '10px',
                  background: 'var(--sidebar-accent)', color: '#fff' }}>{item.badge}</span>
              )}
            </div>
        )

        // A tool path is served by its own Ingress, not by the App Router — a
        // next/link would client-side navigate and 404 (ADR-0234). Plain <a>,
        // same origin, so the session cookie reaches the edge gate.
        return item.external ? (
          <a key={item.href} href={item.href} style={{ textDecoration: 'none' }}>{row}</a>
        ) : (
          <Link key={item.href} href={item.href} style={{ textDecoration: 'none' }}>{row}</Link>
        )
      })}
    </>
  )
}
