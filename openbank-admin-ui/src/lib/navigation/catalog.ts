// SPDX-License-Identifier: Apache-2.0
import type React from 'react'
import {
  Activity,
  FilePlus,
  FileSearch,
  Gauge,
  Handshake,
  Inbox,
  Landmark,
  Wallet,
  AlertOctagon,
  ArrowLeftRight,
  Banknote,
  BarChart3,
  Bell,
  Bluetooth,
  QrCode,
  BookOpen,
  Bot,
  Boxes,
  Bug,
  ClipboardCheck,
  ClipboardList,
  Cloud,
  CreditCard,
  DollarSign,
  FileCode,
  FileSignature,
  FileText,
  Fingerprint,
  Flag,
  FlaskConical,
  GitBranch,
  Globe,
  HeartPulse,
  Layers,
  LayoutDashboard,
  Leaf,
  Library,
  Lock,
  Map,
  Megaphone,
  MessageSquareWarning,
  MessagesSquare,
  Network,
  Package,
  PiggyBank,
  Radar,
  Receipt,
  Repeat,
  Scale,
  ScanLine,
  ScrollText,
  Server,
  Settings,
  Share2,
  Shield,
  ShieldAlert,
  ShieldCheck,
  SlidersHorizontal,
  Smartphone,
  Store,
  Target,
  TrendingUp,
  Users,
  Waypoints,
  Workflow,
  Zap,
  Building2,
  Gift,
  Gamepad2,
  Undo2,
} from 'lucide-react'
import { hasPermission, type Permission } from '@/lib/auth/roles'
import { DOMAIN_GROUPS, domainLabel } from '@/lib/governance/domains'
import type { DomainOwnership } from '@/components/layout/DomainOwnershipContext'

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
/** owner references an existing ServiceEntry.id; fleet membership and domains come from the catalog. */
export type NavItem = { owner?: string; nameCs: string; nameEn: string; href: string; icon: React.ElementType; permission?: Permission; lockedPermission?: Permission; deniedRole?: string; badge?: string; external?: boolean }

// Static inventory chunks support route auditing; they do not assign business domains.
// Rendering groups ALL_NAV by each destination owner in reviewed governance.
export const coreNav: NavItem[] = [
  { nameCs: 'Přehled',      nameEn: 'Dashboard',    href: '/dashboard',    icon: LayoutDashboard },
  { nameCs: 'Účty',         nameEn: 'Accounts',     href: '/accounts',     icon: CreditCard,      permission: 'accounts:view', owner: 'account' },
  { nameCs: 'Transakce',    nameEn: 'Transactions', href: '/transactions', icon: ArrowLeftRight,  permission: 'transactions:view', owner: 'transaction' },
  { nameCs: 'Hlavní kniha', nameEn: 'Ledger',       href: '/ledger',       icon: BookOpen,        permission: 'accounts:view', owner: 'ledger' },
  { nameCs: 'Závěrky',      nameEn: 'Closings',     href: '/day-end',      icon: Scale,           permission: 'accounts:view', owner: 'ledger' },
]

const revenueNav: NavItem[] = [
  { nameCs: 'Úvěry',        nameEn: 'Lending',      href: '/lending',      icon: TrendingUp,    permission: 'payments:view', owner: 'lending' },
  { nameCs: 'Kreditní riziko', nameEn: 'Credit risk', href: '/lending/risk', icon: ShieldAlert, permission: 'lending:risk:view', owner: 'lending' },
  { nameCs: 'Poplatky',     nameEn: 'Fees',         href: '/fees',         icon: Receipt,         permission: 'payments:view', owner: 'product-catalog' },
]

// #10618: risk-engine snapshots/curves and the lending ledger backfill, for the risk and finance
// departments. Each entry carries the permission of the page it opens (roles.ts).
const balanceSheetNav: NavItem[] = [
  { nameCs: 'Snímky rozvahy',   nameEn: 'Balance-sheet snapshots', href: '/balance-sheet/snapshots',       icon: Scale,       permission: 'balance-sheet:view', owner: 'risk-engine' },
  { nameCs: 'Výnosové křivky',  nameEn: 'Curve sets',              href: '/balance-sheet/curve-sets',      icon: TrendingUp,  permission: 'balance-sheet:view', owner: 'risk-engine' },
  { nameCs: 'Doúčtování úvěrů', nameEn: 'Ledger backfill',         href: '/balance-sheet/ledger-backfill', icon: BookOpen,    permission: 'ledger-backfill:view', owner: 'lending' },
  { nameCs: 'Storno doúčtování', nameEn: 'Backfill void',         href: '/balance-sheet/ledger-backfill/voids', icon: Undo2, permission: 'ledger-backfill:view', owner: 'lending' },
]

// ADR-0334 / #12350: the pension back office — participant contracts and their queues
// (openbank-pension-service) and fund administration (openbank-pension-fund-service).
const pensionNav: NavItem[] = [
  { nameCs: 'Penzijní smlouvy',     nameEn: 'Pension contracts',    href: '/pension',               icon: PiggyBank,  permission: 'pension:view', owner: 'pension' },
  { nameCs: 'Sjednání a převody',   nameEn: 'Onboarding & transfers', href: '/pension/queues',      icon: Inbox,      permission: 'pension:view', owner: 'pension' },
  { nameCs: 'Nespárované příspěvky', nameEn: 'Unmatched contributions', href: '/pension/contributions', icon: FileSearch, permission: 'pension:view', owner: 'pension' },
  { nameCs: 'Státní příspěvky',     nameEn: 'State incentives',     href: '/pension/incentives',    icon: Gift,       permission: 'pension:view', owner: 'pension' },
  { nameCs: 'Příkazy k úhradě',     nameEn: 'Contribution mandates', href: '/pension/mandates',    icon: Repeat,     permission: 'pension:view', owner: 'pension' },
  { nameCs: 'Výplaty a úmrtí',      nameEn: 'Payouts & death claims', href: '/pension/payouts',     icon: Wallet,     permission: 'pension:view', owner: 'pension' },
  { nameCs: 'Anuitní partneři',     nameEn: 'Annuity partners',     href: '/pension/annuity',       icon: Handshake,  permission: 'pension:view', owner: 'pension' },
  { nameCs: 'Fondy a NAV',          nameEn: 'Funds & NAV',          href: '/pension/funds',         icon: Landmark,   permission: 'pension:view', owner: 'pension-fund' },
  { nameCs: 'Strategie',            nameEn: 'Strategies',           href: '/pension/strategies',    icon: Scale,      permission: 'pension:view', owner: 'pension-fund' },
  { nameCs: 'Podílové jednotky',    nameEn: 'Unit holdings',        href: '/pension/holdings',      icon: Layers,     permission: 'pension:view', owner: 'pension-fund' },
]

// ADR-0315 / #10618: the treasury desk (openbank-treasury-service). Each entry carries the
// permission of the page it opens (roles.ts): a dealer sees no approval inbox, an approver no form.
const treasuryNav: NavItem[] = [
  { nameCs: 'Obchody',          nameEn: 'Deal blotter',     href: '/treasury/deals',          icon: Landmark,  permission: 'treasury:view', owner: 'treasury' },
  { nameCs: 'Nový obchod',      nameEn: 'New deal',         href: '/treasury/deals/new',      icon: FilePlus,  permission: 'treasury:deal:create', owner: 'treasury' },
  { nameCs: 'Ke schválení',     nameEn: 'Approval inbox',   href: '/treasury/approvals',      icon: Inbox,     permission: 'treasury:deal:approve', owner: 'treasury' },
  { nameCs: 'Limity protistran', nameEn: 'Counterparty limits', href: '/treasury/counterparties', icon: Handshake, permission: 'treasury:view', owner: 'treasury' },
  { nameCs: 'Čerpání limitů',   nameEn: 'Limit utilisation', href: '/treasury/limits',         icon: Gauge,     permission: 'treasury:view', owner: 'treasury' },
  { nameCs: 'Denní pozice',     nameEn: 'Daily position',   href: '/treasury/positions',      icon: Wallet,    permission: 'treasury:view', owner: 'treasury' },
  { nameCs: 'Nostro rekonciliace', nameEn: 'Nostro reconciliation', href: '/treasury/nostro',  icon: FileSearch, permission: 'treasury:nostro:read', owner: 'treasury' },
]

const customerNav: NavItem[] = [
  { nameCs: 'Strany',      nameEn: 'Parties',    href: '/parties',    icon: Users,          permission: 'parties:view', owner: 'party' },
  { nameCs: 'KYC',         nameEn: 'KYC',         href: '/kyc',        icon: ShieldCheck,    permission: 'kyc:view', owner: 'kyc' },
  { nameCs: 'Onboarding',  nameEn: 'Onboarding',  href: '/onboarding', icon: ClipboardList,  permission: 'onboarding:view', owner: 'onboarding' },
  { nameCs: 'Ověření identity', nameEn: 'Identity Cases', href: '/identity-cases', icon: Fingerprint, permission: 'identity-cases:view', owner: 'pid' },
  { nameCs: 'Firemní onboarding', nameEn: 'Business Onboarding', href: '/business-onboarding', icon: Building2, permission: 'business-onboarding:view', owner: 'kyb' },
  { nameCs: 'Delegovaný přístup', nameEn: 'Delegated Access', href: '/delegations', icon: Share2, permission: 'delegations:view', owner: 'delegation' },
]

const paymentsNav: NavItem[] = [
  { nameCs: 'Katalog produktů',  nameEn: 'Product Catalog',  href: '/product-catalog',   icon: Package,   permission: 'payments:view', owner: 'product-catalog' },
  { nameCs: 'Produktové studio', nameEn: 'Product Studio',   href: '/product-studio',    icon: Boxes,     permission: 'catalog:read', owner: 'product-catalog' },
  { nameCs: 'Lípa (věrnost)',    nameEn: 'Lípa (Loyalty)',   href: '/loyalty',           icon: Leaf,      permission: 'loyalty:view', owner: 'loyalty' },
  { nameCs: 'Lví stezka — správa hry', nameEn: 'Lion Quest — game management', href: '/loyalty/game', icon: Gamepad2, permission: 'game:view', owner: 'loyalty' },
  { nameCs: 'Platby',            nameEn: 'Payments',         href: '/payments',          icon: Banknote,  permission: 'payments:view', owner: 'sepa-payment' },
  { nameCs: 'Trvalé příkazy',    nameEn: 'Standing Orders',  href: '/standing-orders',   icon: Repeat,    permission: 'payments:view', owner: 'standing-order' },
  { nameCs: 'Inkasa (SDD)',      nameEn: 'Direct Debits',    href: '/sdd',               icon: Repeat,    permission: 'payment-rails:view', owner: 'sdd' },
  { nameCs: 'FX',                nameEn: 'FX',               href: '/fx',                icon: DollarSign,permission: 'payments:view', owner: 'fx' },
  { nameCs: 'SWIFT',             nameEn: 'SWIFT',            href: '/swift',             icon: Globe,     permission: 'payment-rails:view', owner: 'swift' },
  { nameCs: 'Karty',             nameEn: 'Cards',            href: '/cards',             icon: CreditCard,permission: 'cards:view', owner: 'card-issuance' },
  { nameCs: 'Obchodníci',        nameEn: 'Merchants',        href: '/merchants',         icon: Store,     permission: 'transactions:view', owner: 'transaction' },
  { nameCs: 'Clearing',          nameEn: 'Clearing',         href: '/clearing',          icon: Layers,    permission: 'payment-rails:view', owner: 'clearing' },
  { nameCs: 'Stav settlementu',  nameEn: 'Settlement status', href: '/settlements',     icon: Layers,    permission: 'settlements:view', owner: 'settlement' },
  { nameCs: 'Úroky',             nameEn: 'Interest',         href: '/interest',          icon: TrendingUp,permission: 'interest:view', owner: 'interest' },
  { nameCs: 'Šablony dokumentů', nameEn: 'Document Templates', href: '/document-templates', icon: FileSignature, permission: 'templates:view', owner: 'document' },

]

export const complianceNav: NavItem[] = [
  { nameCs: 'AML',                nameEn: 'AML',              href: '/aml',               icon: AlertOctagon,          permission: 'compliance:view', owner: 'aml' },
  { nameCs: 'Fraud',              nameEn: 'Fraud',            href: '/fraud',             icon: ShieldAlert,           permission: 'compliance:view', owner: 'fraud' },
  { nameCs: 'Sankce',             nameEn: 'Sanctions',        href: '/sanctions',         icon: Shield,                permission: 'sanctions:view', owner: 'sanctions' },
  { nameCs: 'Spory',              nameEn: 'Disputes',         href: '/disputes',          icon: MessageSquareWarning,  permission: 'compliance:view', owner: 'dispute' },
  { nameCs: 'Customer 360',        nameEn: 'Customer 360',     href: '/customer-360',      icon: Users,                 permission: 'compliance:view' },
  { nameCs: 'Kampaně',            nameEn: 'Campaigns',        href: '/campaigns',         icon: Megaphone,             permission: 'campaign:view', owner: 'campaign' },
  { nameCs: 'MGM programy',        nameEn: 'MGM Programs',     href: '/campaigns/referrals', icon: Gift,                  permission: 'campaign:view', owner: 'referral' },
  { nameCs: 'Segmenty',           nameEn: 'Segments',         href: '/segments',          icon: Target,                permission: 'campaign:view', owner: 'campaign' },
  { nameCs: 'Souhlasy',           nameEn: 'Consents',         href: '/consents',          icon: FileSignature,           permission: 'compliance:view', owner: 'consent' },
  // ADR-0212 D4: four-eyes activation of jurisdictional credit compliance packs in lending.
  { nameCs: 'Úvěrové compliance packy', nameEn: 'Credit Compliance Packs', href: '/lending/compliance-packs', icon: ShieldCheck, permission: 'lending:compliance:view', owner: 'lending' },
  { nameCs: 'Auditní záznamy',    nameEn: 'Audit Log',        href: '/audit',             icon: ScrollText,            permission: 'audit:view', owner: 'audit' },
  { nameCs: 'Regulatorní',        nameEn: 'Regulatory',       href: '/regulatory',        icon: FileText,              permission: 'regulatory:view', owner: 'finrep' },
  // ADR-0286: warehouse-backed risk reporting via the governed query registry + embedded Grafana.
  { nameCs: 'Reporting (DWH)',    nameEn: 'Reporting (DWH)',  href: '/reporting',         icon: BarChart3,             permission: 'compliance:view' },
]

export const opsNav: NavItem[] = [
  { nameCs: 'PID',                   nameEn: 'PID',              href: '/pid',               icon: Map,          permission: 'pid:view', owner: 'pid' },
  { nameCs: 'Oznámení',              nameEn: 'Notifications',    href: '/notifications',     icon: Bell,         permission: 'notifications:view', owner: 'notification' },
  // ADR-0285 D7 — how the bank speaks, for bots and staff alike. Inventory entries beside
  // Notifications (what we send): both resolve to customer engagement through governance.
  // The audience is the contact centre and back-office, not agent operators.
  { nameCs: 'Komunikační studio',    nameEn: 'Communication Studio', href: '/communication', icon: MessagesSquare, permission: 'communication:view', owner: 'communication' },
  { nameCs: 'Šablony zpráv',          nameEn: 'Message templates', href: '/communication/notification-templates', icon: Bell, permission: 'communication:templates:view', owner: 'notification' },
  { nameCs: 'Security Excellence',   nameEn: 'Security Excellence', href: '/security/excellence', icon: Scale,   permission: 'system:view' },
  { nameCs: 'Bezpečnostní kontrola', nameEn: 'Security Scan',    href: '/security',          icon: ScanLine,     permission: 'system:view' },
]

export const docsNav: NavItem[] = [
  { nameCs: 'Dokumentace',    nameEn: 'Docs Home',          href: '/docs',             icon: Library,     permission: 'docs:view' },
  { nameCs: 'Dokumentace služeb', nameEn: 'Service Docs',   href: '/services',         icon: BookOpen,    permission: 'docs:view' },
  { nameCs: 'Aplikace',       nameEn: 'Customer App',       href: '/docs/customer-app', icon: Smartphone, permission: 'docs:view' },
  { nameCs: 'Identita & dedup', nameEn: 'Identity & Dedup',  href: '/docs/identity-dedup', icon: Fingerprint, permission: 'docs:view' },
  { nameCs: 'QRlessPay',      nameEn: 'QRlessPay',          href: '/docs/qrlesspay',   icon: Bluetooth,  permission: 'docs:view' },
  { nameCs: 'QR fotoaparátem', nameEn: 'Camera QR',        href: '/docs/spayd-uri',   icon: QrCode,     permission: 'docs:view' },
  { nameCs: 'Senzory',        nameEn: 'Sensors',            href: '/docs/sensors',     icon: Radar,      permission: 'docs:view' },
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

export const platformNav: NavItem[] = [
  // Test evidence is a first-class platform capability. Keeping the only link
  // near the bottom of System made an implemented route effectively invisible.
  { nameCs: 'Test Intelligence', nameEn: 'Test Intelligence', href: '/system/tests', icon: FlaskConical, permission: 'system:view', badge: 'LIVE' },
  { nameCs: 'FinOps',   nameEn: 'FinOps',   href: '/finops',   icon: PiggyBank,  permission: 'system:view' },
  { nameCs: 'SDLC / CI·CD', nameEn: 'SDLC / CI·CD', href: '/devops/sdlc', icon: Workflow, permission: 'system:view', badge: 'GUIDE' },
  { nameCs: 'DevOps',   nameEn: 'DevOps',   href: '/devops',   icon: GitBranch,  permission: 'system:view' },
  { nameCs: 'Řídicí centrum agentů', nameEn: 'Agent Control Room', href: '/iaops', icon: Bot, permission: 'system:view' },
  { nameCs: 'Živé agentní případy', nameEn: 'Live Agent Cases', href: '/iaops/cases', icon: GitBranch, permission: 'system:view' },
  { nameCs: 'Flaky testy', nameEn: 'Flaky Tests', href: '/iaops/flaky-test-hunter', icon: Bug, permission: 'system:view' },
  { nameCs: 'Temporal', nameEn: 'Temporal', href: '/temporal', icon: Zap,        permission: 'system:view' },
  { nameCs: 'Tok workflow', nameEn: 'Workflow Flow', href: '/temporal/flow', icon: Workflow, permission: 'system:view' },
  { nameCs: 'Observability', nameEn: 'Observability', href: '/observability', icon: Activity, permission: 'system:view' },
  { nameCs: 'Schvalování', nameEn: 'Approvals', href: '/approvals', icon: ClipboardCheck, permission: 'approvals:view' },
]

// Internal tool UIs, reachable at /tools/<tool> on this same host behind the
// identity-aware edge gate (ADR-0234). The permission here MUST match the one
// `src/app/api/gate/route.ts` requires for the tool — the gate is what actually
// enforces it, this only decides whether the operator sees the link.
export const toolsNav: NavItem[] = [
  { nameCs: 'Grafana',        nameEn: 'Grafana',        href: '/tools/grafana',      icon: Activity,    permission: 'system:view', external: true },
  // Denied to the public demo account: Alertmanager has no role model, so anything
  // that can load its UI can silence alerts platform-wide (gate route.ts).
  { nameCs: 'Alerty',         nameEn: 'Alertmanager',   href: '/tools/alertmanager', icon: Bell,        permission: 'system:view', external: true, deniedRole: 'ROLE_DEMO' },
  { nameCs: 'SLO (Pyrra)',    nameEn: 'SLO (Pyrra)',    href: '/tools/pyrra',        icon: Scale,       permission: 'system:view', external: true },
]

export const sysNav: NavItem[] = [
  { nameCs: 'Zdraví systému',   nameEn: 'System Health',   href: '/system/health',    icon: HeartPulse,        permission: 'system:view' },
  { nameCs: 'Tech Inventory',   nameEn: 'Tech Inventory',  href: '/system/inventory', icon: Package,           permission: 'system:view' },
  { nameCs: 'Infrastruktura',   nameEn: 'Infrastructure',  href: '/infrastructure',   icon: Server,            permission: 'system:view' },
  { nameCs: 'Topologie infra',  nameEn: 'Infra Topology',  href: '/infrastructure/topology', icon: Network,     permission: 'system:view' },
  { nameCs: 'Připravenost prod',nameEn: 'Prod Readiness',  href: '/system/readiness', icon: ClipboardCheck,    permission: 'system:view' },
  { nameCs: 'Konfigurace',      nameEn: 'Configuration',   href: '/system/config',    icon: SlidersHorizontal, permission: 'system:config' },
  { nameCs: 'Agent (MCP)',      nameEn: 'Agent (MCP)',     href: '/system/agent',     icon: Bot,               permission: 'agent:view' },
  { nameCs: 'Nastavení',        nameEn: 'Settings',        href: '/settings',         icon: Settings, lockedPermission: 'settings:view' },
]

export const ALL_NAV: NavItem[] = [
  ...coreNav, ...revenueNav, ...balanceSheetNav, ...treasuryNav, ...pensionNav, ...customerNav, ...paymentsNav,
  ...complianceNav, ...opsNav, ...docsNav, ...platformNav, ...toolsNav, ...sysNav,
]


export function isNavLocked(item: NavItem, roles: string[]): boolean {
  return Boolean((item.lockedPermission && !hasPermission(roles, item.lockedPermission)) ||
    (item.deniedRole && roles.includes(item.deniedRole)))
}
export function visibleNavigation(roles: string[]): NavItem[] {
  return ALL_NAV.filter(item => !item.permission || hasPermission(roles, item.permission))
}
export type NavigationArea = { id: string; nameCs: string; nameEn: string; items: NavItem[] }
export function navigationAreas(roles: string[], ownership: DomainOwnership): NavigationArea[] {
  const visible = visibleNavigation(roles)
  const areas: NavigationArea[] = DOMAIN_GROUPS.map(domain => ({
    id: domain, nameCs: domainLabel(domain, 'cs'), nameEn: domainLabel(domain, 'en'),
    items: visible.filter(item => item.owner && (ownership[item.owner] ?? 'unclassified') === domain),
  }))
  const extra = [
    { id: 'cross-domain', nameCs: 'Průřezové přehledy', nameEn: 'Cross-domain views', items: [...complianceNav, ...opsNav].filter(item => !item.owner) },
    { id: 'platform-operations', nameCs: 'Řízení platformy', nameEn: 'Platform operations', items: platformNav },
    { id: 'documentation', nameCs: 'Dokumentace', nameEn: 'Documentation', items: docsNav },
    { id: 'tools', nameCs: 'Nástroje', nameEn: 'Tools', items: toolsNav },
    { id: 'system', nameCs: 'Systém', nameEn: 'System', items: sysNav },
  ]
  return [...areas, ...extra.map(area => ({ ...area, items: area.items.filter(item => visible.includes(item)) }))]
    .filter(area => area.items.length > 0)
}
export function currentNavigation(pathname: string | null): NavItem | undefined {
  if (!pathname) return undefined
  return ALL_NAV.filter(item => pathname === item.href || pathname.startsWith(`${item.href}/`))
    .sort((a, b) => b.href.length - a.href.length)[0]
}
export function normalizeSearch(text: string): string {
  return text.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim()
}
const ALIASES: Record<string, string> = {
  '/parties': 'klienti zakaznici customers clients people',
  '/ledger': 'ucetnictvi accounting', '/kyc': 'overeni klienta due diligence',
  '/system/agent': 'ai asistent assistant prompt', '/accounts': 'iban bankovni ucty',
  '/transactions': 'pohyby historie movements history', '/system/tests': 'testy testing kvalita quality',
}
export function searchNavigation(query: string, roles: string[]): NavItem[] {
  const term = normalizeSearch(query)
  if (!term) return []
  const score = (item: NavItem) => {
    const names = [normalizeSearch(item.nameCs), normalizeSearch(item.nameEn)]
    if (names.includes(term)) return 0
    if (names.some(name => name.startsWith(term))) return 1
    return 2
  }
  return visibleNavigation(roles).filter(item => !isNavLocked(item, roles))
    .filter(item => term.split(/\s+/).every(word => normalizeSearch(
      `${item.nameCs} ${item.nameEn} ${item.href} ${ALIASES[item.href] ?? ''}`,
    ).includes(word)))
    .sort((a, b) => score(a) - score(b)).slice(0, 12)
}
