// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect } from 'react'
import { Network, RefreshCw, CheckCircle2, XCircle, HelpCircle, Database, ArrowRight, ArrowLeft, Layers, BookOpen } from 'lucide-react'
import type { GovernanceManifestEntry } from '@/lib/governance/manifest'
import { svcUrl } from '@/lib/services/bff'
import { CatalogDriftBanner } from '@/components/governance/CatalogDriftBanner'
import { useLanguage } from '@/lib/i18n/LanguageContext'

// Service definitions with positions for the map
const SERVICES = [
  // Core Banking
  { id: 'account',     name: 'Account Service',      port: 8100, group: 'core',    x: 125, y: 100,  color: '#2563eb', desc: 'Account lifecycle management, IBAN assignment' },
  { id: 'ledger',      name: 'Ledger Service',       port: 8101, group: 'core',    x: 250, y: 100,  color: '#2563eb', desc: 'Double-entry bookkeeping, GL accounts, journal entries' },
  { id: 'transaction', name: 'Transaction Service',  port: 8102, group: 'core',    x: 375, y: 100,  color: '#2563eb', desc: 'Transaction processing, partitioned by booking_date' },
  { id: 'catalog',     name: 'Product Catalog',      port: 8104, group: 'core',    x: 500, y: 100,  color: '#2563eb', desc: 'Banking products, pricing, limits' },
  { id: 'balance',     name: 'Balance Service',      port: 8103, group: 'core',    x: 125, y: 220,  color: '#2563eb', desc: 'Real-time balance tracking, holds management' },
  { id: 'interest',    name: 'Interest Service',     port: 8125, group: 'core',    x: 250, y: 220,  color: '#2563eb', desc: 'Interest calculation and accrual' },
  { id: 'fx',          name: 'FX Service',           port: 8119, group: 'core',    x: 375, y: 220,  color: '#2563eb', desc: 'Foreign exchange rates and conversion' },
  // Identity
  { id: 'pid',         name: 'PID Service',          port: 8105, group: 'identity', x: 650, y: 100,  color: '#059669', desc: 'Party identity documents, external IDs' },
  { id: 'party',       name: 'Party Service',        port: 8111, group: 'identity', x: 750, y: 100,  color: '#059669', desc: 'Customer/company master data, PEP/sanctions flags' },
  // Platform
  { id: 'agent',       name: 'Agent Service',        port: 8109, group: 'platform', x: 900, y: 100,  color: '#6b7280', desc: 'AI agent MCP server, OpenBank tools for LLMs' },
  { id: 'notification',name: 'Notification Service', port: 8112, group: 'platform', x: 1000,y: 100,  color: '#6b7280', desc: 'Email/SMS/Push notifications, template engine' },
  { id: 'security-scanner',name: 'Security Scanner', port: 8120, group: 'platform', x: 1100,y: 100,  color: '#6b7280', desc: 'Continuous vulnerability scanning' },
  // Cards
  { id: 'card-issuance',name: 'Card Issuance',       port: 8118, group: 'cards',    x: 650, y: 260,  color: '#db2777', desc: 'Card issuance and lifecycle management' },
  { id: 'dispute',     name: 'Dispute Service',      port: 8135, group: 'cards',    x: 750, y: 260,  color: '#db2777', desc: 'Card disputes and chargebacks' },
  // Compliance
  { id: 'audit',       name: 'Audit Service',        port: 8113, group: 'compliance', x: 920, y: 260,  color: '#dc2626', desc: 'Immutable audit trail, EBA ICT Risk compliance' },
  { id: 'kyc',         name: 'KYC Service',          port: 8114, group: 'compliance', x: 1080,y: 260,  color: '#dc2626', desc: 'KYC/CDD/EDD case management, document verification' },
  { id: 'aml',         name: 'AML Service',          port: 8117, group: 'compliance', x: 920, y: 380,  color: '#dc2626', desc: 'AML screening, SAR filing' },
  { id: 'sanctions',   name: 'Sanctions Service',    port: 8123, group: 'compliance', x: 1080,y: 380,  color: '#dc2626', desc: 'Real-time sanctions list screening' },
  { id: 'fraud',       name: 'Fraud Service',        port: 8115, group: 'compliance', x: 920, y: 500,  color: '#dc2626', desc: 'Real-time fraud detection & transaction monitoring (ADR-0084)' },
  // Payments
  { id: 'sepa',        name: 'SEPA Payment',         port: 8115, group: 'payment', x: 125, y: 380,  color: '#7c3aed', desc: 'SEPA Credit Transfer, SCT Inst, SEPA Direct Debit' },
  { id: 'sepa-instant',name: 'SEPA Instant',         port: 8127, group: 'payment', x: 250, y: 380,  color: '#7c3aed', desc: 'Real-time EUR payment processing' },
  { id: 'domestic',    name: 'Domestic Payment',     port: 8116, group: 'payment', x: 375, y: 380,  color: '#7c3aed', desc: 'Czech domestic payments' },
  { id: 'standing-order',name:'Standing Order',      port: 8121, group: 'payment', x: 500, y: 380,  color: '#7c3aed', desc: 'Recurring payments and scheduled transfers' },
  { id: 'swift',       name: 'SWIFT Service',        port: 8122, group: 'payment', x: 125, y: 500,  color: '#7c3aed', desc: 'SWIFT MT/MX messaging' },
  { id: 'clearing',    name: 'Clearing Service',     port: 8124, group: 'payment', x: 250, y: 500,  color: '#7c3aed', desc: 'Interbank clearing and settlement' },
  // PSD2
  { id: 'consent',     name: 'Consent Service',      port: 8106, group: 'psd2',   x: 125, y: 660,  color: '#d97706', desc: 'PSD2 consent management' },
  { id: 'sca',         name: 'SCA Service',          port: 8110, group: 'psd2',   x: 250, y: 660,  color: '#d97706', desc: 'Strong Customer Authentication' },
  { id: 'psd2',        name: 'PSD2 Service',         port: 8107, group: 'psd2',   x: 375, y: 660,  color: '#d97706', desc: 'PSD2 API gateway' },
  { id: 'tpp',         name: 'TPP Registry',         port: 8108, group: 'psd2',   x: 500, y: 660,  color: '#d97706', desc: 'Third Party Provider registry' },
  // Extended / reporting — were silently missing from the map before ADR-0071.
  { id: 'lending',     name: 'Lending Service',      port: 8128, group: 'core',    x: 500, y: 220,  color: '#2563eb', desc: 'Loan products and origination' },
  { id: 'statement',   name: 'Statement Service',    port: 8136, group: 'core',    x: 625, y: 220,  color: '#2563eb', desc: 'Account statement generation (EoM)' },
  { id: 'onboarding',  name: 'Onboarding Service',   port: 8130, group: 'identity', x: 750, y: 220,  color: '#059669', desc: 'Customer onboarding journey (ADR-0069)' },
  { id: 'anacredit',   name: 'AnaCredit Service',    port: 8137, group: 'compliance', x: 1080, y: 500, color: '#dc2626', desc: 'AnaCredit regulatory reporting (ECB)' },
  { id: 'sdd',         name: 'SEPA Direct Debit',    port: 8132, group: 'payment', x: 375, y: 500,  color: '#7c3aed', desc: 'SEPA Direct Debit mandates and collections' },
]

// Service dependencies (edges)
const SERVICE_ID_TO_NAME: Record<string, string> = {
  'account': 'account-service',
  'ledger': 'ledger-service',
  'transaction': 'transaction-service',
  'catalog': 'product-catalog',
  'balance': 'balance-service',
  'interest': 'interest-service',
  'fx': 'fx-service',
  'pid': 'pid-service',
  'party': 'party-service',
  'agent': 'agent-service',
  'notification': 'notification-service',
  'security-scanner': 'security-scanner',
  'card-issuance': 'card-issuance-service',
  'dispute': 'dispute-service',
  'audit': 'audit-service',
  'kyc': 'kyc-service',
  'aml': 'aml-service',
  'sanctions': 'sanctions-service',
  'fraud': 'fraud-service',
  'sepa': 'sepa-payment',
  'sepa-instant': 'sepa-instant',
  'domestic': 'domestic-payment',
  'standing-order': 'standing-order-service',
  'swift': 'swift-service',
  'clearing': 'clearing-service',
  'consent': 'consent-service',
  'sca': 'sca-service',
  'psd2': 'psd2-service',
  'tpp': 'tpp-registry-service',
  'lending': 'lending-service',
  'statement': 'statement-service',
  'onboarding': 'onboarding-service',
  'anacredit': 'anacredit-service',
  'sdd': 'sdd-service',
}

const SERVICE_NAME_TO_ID = Object.fromEntries(Object.entries(SERVICE_ID_TO_NAME).map(([k, v]) => [v, k]))
// Stable reference for the drift banner (avoids re-fetch on every render).
const CATALOG_PRESENT = Object.values(SERVICE_ID_TO_NAME)

const GROUP_LABELS: Record<string, { label: string; color: string }> = {
  core:       { label: 'Core Banking',    color: '#2563eb' },
  payment:    { label: 'Payments',        color: '#7c3aed' },
  compliance: { label: 'Compliance',      color: '#dc2626' },
  identity:   { label: 'Identity',        color: '#059669' },
  psd2:       { label: 'PSD2 / Open Banking', color: '#d97706' },
  platform:   { label: 'Platform',        color: '#6b7280' },
  cards:      { label: 'Cards',           color: '#db2777' },
}
type HealthStatus = 'UP' | 'DOWN' | 'UNKNOWN'

// ---------------------------------------------------------------------------
// Auto-layout — a clean, deterministic grid per group so nodes never overlap
// and every group box fits its contents. Positions are computed once (SERVICES
// is static), not hand-placed, so adding a service never collides.
// ---------------------------------------------------------------------------
const CELL_W = 138
const CELL_H = 116
const PAD_X = 24
const PAD_TOP = 50
const PAD_BOT = 30
const GROUP_GAP = 32
const COL_GAP = 52
const CANVAS_PAD = 44

// Which groups stack in which column, and how many node-columns each group uses.
const GROUP_COLUMNS: string[][] = [
  ['core', 'payment', 'psd2'],
  ['identity', 'cards', 'compliance'],
  ['platform'],
]
const GROUP_GRID_COLS: Record<string, number> = {
  core: 4, payment: 4, psd2: 4, identity: 3, cards: 2, compliance: 3, platform: 3,
}

type Pt = { cx: number; cy: number }
type GroupBox = { key: string; label: string; color: string; x: number; y: number; w: number; h: number }

const LAYOUT: { nodes: Record<string, Pt>; groups: GroupBox[]; width: number; height: number } = (() => {
  const byGroup: Record<string, typeof SERVICES> = {}
  for (const s of SERVICES) (byGroup[s.group] ||= []).push(s)

  const nodes: Record<string, Pt> = {}
  const groups: GroupBox[] = []

  const colWidth = GROUP_COLUMNS.map(col =>
    PAD_X * 2 + Math.max(...col.map(g => GROUP_GRID_COLS[g] || 1)) * CELL_W,
  )
  const colX: number[] = []
  let acc = CANVAS_PAD
  for (let c = 0; c < GROUP_COLUMNS.length; c++) { colX[c] = acc; acc += colWidth[c] + COL_GAP }

  let maxY = 0
  GROUP_COLUMNS.forEach((col, c) => {
    let y = CANVAS_PAD
    for (const g of col) {
      const list = byGroup[g] || []
      const cols = GROUP_GRID_COLS[g] || 1
      const rows = Math.max(1, Math.ceil(list.length / cols))
      const w = colWidth[c]
      const h = PAD_TOP + rows * CELL_H + PAD_BOT
      const meta = GROUP_LABELS[g]
      groups.push({ key: g, label: meta?.label ?? g, color: meta?.color ?? '#64748b', x: colX[c], y, w, h })
      const gridW = cols * CELL_W
      const startX = colX[c] + (w - gridW) / 2 + CELL_W / 2
      list.forEach((s, i) => {
        nodes[s.id] = {
          cx: startX + (i % cols) * CELL_W,
          cy: y + PAD_TOP + Math.floor(i / cols) * CELL_H + CELL_H / 2 - 8,
        }
      })
      y += h + GROUP_GAP
    }
    maxY = Math.max(maxY, y)
  })

  return {
    nodes,
    groups,
    width: colX[colX.length - 1] + colWidth[colWidth.length - 1] + CANVAS_PAD,
    height: maxY - GROUP_GAP + CANVAS_PAD,
  }
})()

// Curved, boundary-trimmed edge path between two node centres. The control point
// is offset perpendicular to the line so parallel edges fan out instead of
// overlapping, and the apex (mx,my) is where a label sits when the edge is active.
type Half = { hw: number; hh: number }
// Trim each endpoint to the brick's bounding box (not a fixed circle) so the line
// meets the brick edge cleanly regardless of how long the brick is.
function edgeGeometry(a: Pt, b: Pt, aHalf: Half, bHalf: Half) {
  const dx = b.cx - a.cx, dy = b.cy - a.cy
  const dist = Math.hypot(dx, dy) || 1
  const ux = dx / dist, uy = dy / dist
  const boxT = (hw: number, hh: number) => {
    const tx = Math.abs(ux) < 1e-6 ? Infinity : hw / Math.abs(ux)
    const ty = Math.abs(uy) < 1e-6 ? Infinity : hh / Math.abs(uy)
    return Math.min(tx, ty)
  }
  const ta = boxT(aHalf.hw, aHalf.hh) + 2
  const tb = boxT(bHalf.hw, bHalf.hh) + 9 // extra gap for the arrowhead
  const sx = a.cx + ux * ta, sy = a.cy + uy * ta
  const ex = b.cx - ux * tb, ey = b.cy - uy * tb
  const mx = (sx + ex) / 2, my = (sy + ey) / 2
  const curve = Math.min(46, dist * 0.14)
  const cpx = mx - uy * curve, cpy = my + ux * curve
  // Label sits ~62% of the way toward the target (a point on the quadratic), not
  // at the apex — so several edges leaving the same hub node spread their labels
  // toward their distinct targets instead of stacking near the source.
  const t = 0.62, mt = 1 - t
  const lx = mt * mt * sx + 2 * mt * t * cpx + t * t * ex
  const ly = mt * mt * sy + 2 * mt * t * cpy + t * t * ey
  return { d: `M ${sx} ${sy} Q ${cpx} ${cpy} ${ex} ${ey}`, lx, ly }
}

// Shorten edge labels so they stay legible: drop the openbank- / openbank. noise
// and the -service suffix. Topics keep their dotted tail (account.created).
function prettyEdgeLabel(raw: string, type: string): string {
  if (!raw) return ''
  return type === 'async'
    ? raw.replace(/^openbank\./, '')
    : raw.replace(/^openbank-/, '').replace(/-service$/, '')
}

// ---------------------------------------------------------------------------
// LEGO bricks — each service is a little brick whose footprint (stud grid) grows
// with how connected the service is (degree = upstream + downstream from the
// dependency graph). Central services are big bricks, leaf services small ones.
// ---------------------------------------------------------------------------
// Side-view LEGO brick: a body with studs (bumps) on the top edge — the classic
// brick silhouette. Like real LEGO, every brick is the SAME HEIGHT and the studs
// are all the same size; a more-connected service just makes a LONGER brick (more
// studs in the single row). The studs sit ABOVE the body, so callers offset labels
// by STUD_H when placing things relative to the brick.
// Real LEGO proportions (module = 1 stud pitch): body height ≈ 1.3×pitch (a brick,
// not a thin plate), stud width ≈ 0.6×pitch, body length = N × pitch with studs
// centred in each module. All bricks the same height; degree only sets N.
const STUD_PITCH = 16
const STUD_W = 10
const STUD_H = 6
const BRICK_H = 21

function mixHex(hex: string, target: string, r: number): string {
  const a = parseInt(hex.slice(1), 16), b = parseInt(target.slice(1), 16)
  const ch = (s: number) => Math.round(((a >> s) & 255) + (((b >> s) & 255) - ((a >> s) & 255)) * r)
  return '#' + ((1 << 24) + (ch(16) << 16) + (ch(8) << 8) + ch(0)).toString(16).slice(1)
}
const lightFace = (c: string) => mixHex(c, '#ffffff', 0.72)
const studTop = (c: string) => mixHex(c, '#ffffff', 0.84)
const studHi = (c: string) => mixHex(c, '#ffffff', 0.45)
const shadeFace = (c: string) => mixHex(c, '#000000', 0.12)

// Number of studs (brick length) from the service's connection degree.
function brickStuds(degree: number): number {
  if (degree >= 7) return 6
  if (degree >= 5) return 5
  if (degree >= 3) return 4
  if (degree >= 1) return 3
  return 2
}
function brickSize(degree: number) {
  const n = brickStuds(degree)
  return { n, w: n * STUD_PITCH, h: BRICK_H }
}

function LegoBrick({ cx, cy, color, degree, selected }: { cx: number; cy: number; color: string; degree: number; selected: boolean }) {
  const { n, w, h } = brickSize(degree)
  const x = cx - w / 2
  const y = cy - h / 2 + STUD_H / 2 // body top; studs protrude above it
  const face = selected ? color : lightFace(color)
  const studFill = selected ? studHi(color) : studTop(color)
  const studs: React.ReactNode[] = []
  for (let c = 0; c < n; c++) {
    const x0 = x + (c + 0.5) * STUD_PITCH - STUD_W / 2
    studs.push(
      <g key={c}>
        <path d={`M ${x0} ${y} L ${x0} ${y - STUD_H + 2.5} Q ${x0} ${y - STUD_H} ${x0 + 2.5} ${y - STUD_H} L ${x0 + STUD_W - 2.5} ${y - STUD_H} Q ${x0 + STUD_W} ${y - STUD_H} ${x0 + STUD_W} ${y - STUD_H + 2.5} L ${x0 + STUD_W} ${y} Z`}
          fill={studFill} stroke={color} strokeWidth={1} />
        <line x1={x0 + 2} y1={y - STUD_H + 2} x2={x0 + STUD_W - 2} y2={y - STUD_H + 2} stroke="#fff" strokeWidth={1.2} opacity={0.6} />
      </g>,
    )
  }
  return (
    <g filter="url(#node-shadow)">
      {studs}
      <rect x={x} y={y} width={w} height={h} rx={3} fill={face} stroke={color} strokeWidth={1.4} />
      <rect x={x + 2} y={y + 2} width={w - 4} height={h * 0.32} rx={2} fill="#ffffff" opacity={0.22} />
      <rect x={x + 2} y={y + h - h * 0.3} width={w - 4} height={h * 0.28} rx={2} fill={selected ? '#000000' : shadeFace(color)} opacity={selected ? 0.12 : 0.18} />
    </g>
  )
}

// Governance data is code-derived since ADR-0071: it arrives from the
// /api/services/governance fetch (now backed by governance.json), not a build-time
// import of the hand-edited manifest. Seed empty; the fetch fills it.
const initialGovData: Record<string, GovernanceManifestEntry> = {}

export default function ServiceMapPage() {
  const { t } = useLanguage()
  const [selected, setSelected] = useState<string | null>(null)
  const [hovered, setHovered] = useState<string | null>(null)
  const [filter, setFilter] = useState<string>('all')
  const [healthStatuses, setHealthStatuses] = useState<Record<string, HealthStatus>>({})
  const [governanceData, setGovernanceData] = useState<Record<string, GovernanceManifestEntry>>(initialGovData)
  // Inter-service edges come from the code-derived dependency graph (ADR-0029 D1),
  // not the sparse curatorial governance lineage. Seed empty; the fetch fills it.
  const [graphEdges, setGraphEdges] = useState<{ from: string; to: string; via: string; type: 'rest' | 'kafka' }[]>([])
  // Per-service degree (upstream + downstream) from the graph → drives brick size.
  const [degrees, setDegrees] = useState<Record<string, number>>({})
  const [isChecking, setIsChecking] = useState(false)

  const checkHealth = async () => {
    setIsChecking(true)
    try {
      const [res, govRes, graphRes] = await Promise.all([
        fetch('/api/services/health'),
        fetch('/api/services/governance'),
        fetch('/api/catalog/graph'),
      ])

      if (res.ok) {
        const data = await res.json() as { services: { port: number; status: string }[] }
        const newStatuses: Record<string, HealthStatus> = {}
        for (const svc of SERVICES) {
          const entry = data.services.find(s => s.port === svc.port)
          if (!entry) { newStatuses[svc.id] = 'UNKNOWN'; continue }
          newStatuses[svc.id] = entry.status === 'UP' ? 'UP' : entry.status === 'DOWN' ? 'DOWN' : 'UNKNOWN'
        }
        setHealthStatuses(newStatuses)
      }

      if (govRes.ok) {
        const govData = await govRes.json() as { byService?: Record<string, GovernanceManifestEntry> }
        setGovernanceData(govData.byService ?? {})
      }

      if (graphRes.ok) {
        const graph = await graphRes.json() as {
          edges?: { from: string; to: string; via: string; type: 'rest' | 'kafka' }[]
          nodes?: { name: string; dependsOn?: number; dependedOnBy?: number }[]
        }
        setGraphEdges(Array.isArray(graph.edges) ? graph.edges : [])
        const deg: Record<string, number> = {}
        for (const n of graph.nodes ?? []) {
          const id = SERVICE_NAME_TO_ID[n.name.replace(/^openbank-/, '')]
          if (id) deg[id] = (n.dependsOn ?? 0) + (n.dependedOnBy ?? 0)
        }
        setDegrees(deg)
      }
    } catch {
    } finally {
      setIsChecking(false)
    }
  }

  useEffect(() => {
    checkHealth()
  }, [])

  const selectedSvc = SERVICES.find(s => s.id === selected)
  // Deterministically resolve the manifest service-name from the UI node id
  // This prevents mismatches where the UI human-readable name doesn't match the internal serviceName.
  const govEntry = selectedSvc ? governanceData[SERVICE_ID_TO_NAME[selectedSvc.id]] : null;
  const visibleServices = filter === 'all' ? SERVICES : SERVICES.filter(s => s.group === filter)
  const visibleIds = new Set(visibleServices.map(s => s.id))
  
  // Edges from the code-derived dependency graph (ADR-0029 D1): real REST + Kafka
  // topology walked from each service's application.yaml. Map graph node names
  // (openbank-<short>) onto the map's node ids; drop edges touching non-service
  // modules (admin-ui, libs, customer-edge…) that have no node on this map.
  // This both fixes the missing edges AND removes the previous crash, which read
  // gov.lineage.downstream (undefined for services whose lineage omits downstream).
  const stripPrefix = (n: string) => n.replace(/^openbank-/, '')
  const EDGES = graphEdges.map(e => {
    const fromId = SERVICE_NAME_TO_ID[stripPrefix(e.from)]
    const toId = SERVICE_NAME_TO_ID[stripPrefix(e.to)]
    if (!fromId || !toId || fromId === toId) return null
    return { from: fromId, to: toId, label: e.via || e.type, type: e.type === 'kafka' ? 'async' : 'sync' }
  }).filter(Boolean) as { from: string; to: string; label: string; type: string }[]
  const visibleEdges = EDGES.filter(e => visibleIds.has(e.from) && visibleIds.has(e.to))


  const svcMap = Object.fromEntries(SERVICES.map(s => [s.id, s]))

  // Render-site translations for strings that live in the module-scope data
  // arrays (GROUP_LABELS, SERVICES.desc) — the hook can't run at module scope.
  const groupLabelCs: Record<string, string> = {
    core: 'Jádrový banking',
    payment: 'Platby',
    compliance: 'Compliance',
    identity: 'Identita',
    psd2: 'PSD2 / Open Banking',
    platform: 'Platforma',
    cards: 'Karty',
  }
  const groupLabel = (key: string) => t(groupLabelCs[key] ?? GROUP_LABELS[key]?.label ?? key, GROUP_LABELS[key]?.label ?? key)
  const descCs: Record<string, string> = {
    account: 'Správa životního cyklu účtu, přidělování IBAN',
    ledger: 'Podvojné účetnictví, GL účty, účetní zápisy',
    transaction: 'Zpracování transakcí, partiční dělení podle booking_date',
    catalog: 'Bankovní produkty, cenotvorba, limity',
    balance: 'Sledování zůstatku v reálném čase, správa blokací',
    interest: 'Výpočet a časové rozlišení úroků',
    fx: 'Kurzy a převody cizích měn',
    pid: 'Identifikační doklady party, externí ID',
    party: 'Kmenová data klienta/firmy, příznaky PEP/sankce',
    agent: 'AI agent MCP server, OpenBank nástroje pro LLM',
    notification: 'E-mail/SMS/Push notifikace, šablonovací engine',
    'security-scanner': 'Průběžné skenování zranitelností',
    'card-issuance': 'Vydávání karet a správa jejich životního cyklu',
    dispute: 'Reklamace karet a chargebacky',
    audit: 'Neměnná auditní stopa, compliance EBA ICT Risk',
    kyc: 'Správa KYC/CDD/EDD případů, ověřování dokladů',
    aml: 'AML screening, podávání SAR',
    sanctions: 'Screening sankčních seznamů v reálném čase',
    sepa: 'SEPA Credit Transfer, SCT Inst, SEPA Direct Debit',
    'sepa-instant': 'Zpracování okamžitých EUR plateb',
    domestic: 'Tuzemské české platby',
    'standing-order': 'Opakované platby a naplánované převody',
    swift: 'SWIFT MT/MX zprávy',
    clearing: 'Mezibankovní clearing a zúčtování',
    consent: 'Správa souhlasů PSD2',
    sca: 'Silné ověření klienta',
    psd2: 'API brána PSD2',
    tpp: 'Registr poskytovatelů třetích stran',
    lending: 'Úvěrové produkty a jejich poskytování',
    statement: 'Generování výpisů z účtu (EoM)',
    onboarding: 'Onboardingová cesta klienta (ADR-0069)',
    anacredit: 'Regulatorní reporting AnaCredit (ECB)',
    sdd: 'SEPA Direct Debit mandáty a inkasa',
  }

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span>{t('Dokumentace', 'Docs')}</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Mapa služeb', 'Service Map')}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Network size={18} style={{ color: 'var(--accent)' }} />
            {t('Mapa architektury služeb', 'Service Architecture Map')}
          </h1>
          <p className="page-subtitle">{t(`Interaktivní mapa ${SERVICES.length} mikroslužeb a jejich závislostí · klikněte na službu pro detail`, `Interactive map of ${SERVICES.length} microservices and their dependencies · click a service for detail`)}</p>
        </div>
      </div>

      <CatalogDriftBanner present={CATALOG_PRESENT} />

      {/* Filter tabs and Controls */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', flexWrap: 'wrap', gap: '16px' }}>
        <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
          {[['all', t('Vše', 'All')], ...Object.entries(GROUP_LABELS).map(([k]) => [k, groupLabel(k)])].map(([key, label]) => (
            <button key={key} onClick={() => setFilter(key)}
              style={{
                padding: '5px 12px', fontSize: '12px', fontWeight: 600, borderRadius: '20px',
                border: `1px solid ${filter === key ? 'var(--accent)' : 'var(--border)'}`,
                background: filter === key ? 'var(--accent)' : 'var(--surface)',
                color: filter === key ? '#fff' : 'var(--text-secondary)',
                cursor: 'pointer', fontFamily: 'inherit',
              }}>{label}</button>
          ))}
        </div>
        <button 
          onClick={checkHealth}
          disabled={isChecking}
          className="btn btn-secondary"
          style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px' }}
        >
          <RefreshCw size={14} className={isChecking ? 'animate-spin' : ''} />
          {isChecking ? t('Zjišťuji…', 'Checking...') : t('Obnovit stav', 'Refresh Status')}
        </button>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: selectedSvc ? '1fr 320px' : '1fr', gap: '16px' }}>
        {/* SVG Map */}
        <div className="card" style={{ padding: '0', overflow: 'hidden' }}>
          <svg viewBox={`0 0 ${LAYOUT.width} ${LAYOUT.height}`} style={{ width: '100%', height: 'auto', display: 'block', background: 'var(--surface)' }}>
            <defs>
              <marker id="arrow-sync" markerWidth="7" markerHeight="7" refX="5.5" refY="3" orient="auto">
                <path d="M0,0 L0,6 L7,3 z" fill="#94a3b8" />
              </marker>
              <marker id="arrow-sync-hi" markerWidth="7" markerHeight="7" refX="5.5" refY="3" orient="auto">
                <path d="M0,0 L0,6 L7,3 z" fill="#475569" />
              </marker>
              <marker id="arrow-async" markerWidth="7" markerHeight="7" refX="5.5" refY="3" orient="auto">
                <path d="M0,0 L0,6 L7,3 z" fill="#c4b5fd" />
              </marker>
              <marker id="arrow-async-hi" markerWidth="7" markerHeight="7" refX="5.5" refY="3" orient="auto">
                <path d="M0,0 L0,6 L7,3 z" fill="#8b5cf6" />
              </marker>
              <filter id="node-shadow" x="-40%" y="-40%" width="180%" height="180%">
                <feDropShadow dx="0" dy="1.5" stdDeviation="2.5" floodColor="#0f172a" floodOpacity="0.14" />
              </filter>
            </defs>

            {/* Group backgrounds — sized to fit their nodes */}
            {LAYOUT.groups.map(g => (
              <g key={g.key}>
                <rect x={g.x} y={g.y} width={g.w} height={g.h} rx="16"
                  fill={`${g.color}0a`} stroke={`${g.color}33`} strokeWidth="1" />
                <text x={g.x + 18} y={g.y + 26} fontSize="11" fill={g.color} fontWeight="700" letterSpacing="0.08em">
                  {groupLabel(g.key).toUpperCase()}
                </text>
              </g>
            ))}

            {(() => {
              const active = selected ?? hovered
              const isNeighbor = (id: string) =>
                !!active && (active === id || EDGES.some(e =>
                  (e.from === active && e.to === id) || (e.to === active && e.from === id)))

              return (
                <>
                  {/* Edges — curved, boundary-trimmed; labels only when active */}
                  {visibleEdges.map((e, i) => {
                    const a = LAYOUT.nodes[e.from], b = LAYOUT.nodes[e.to]
                    if (!a || !b) return null
                    const isAsync = e.type === 'async'
                    const touches = !!active && (e.from === active || e.to === active)
                    const dim = !!active && !touches
                    const hhAll = (BRICK_H + STUD_H) / 2
                    const aHalf = { hw: brickSize(degrees[e.from] ?? 0).w / 2, hh: hhAll }
                    const bHalf = { hw: brickSize(degrees[e.to] ?? 0).w / 2, hh: hhAll }
                    const { d, lx, ly } = edgeGeometry(a, b, aHalf, bHalf)
                    const baseColor = isAsync ? '#c4b5fd' : '#cbd5e1'
                    const hiColor = isAsync ? '#8b5cf6' : '#64748b'
                    return (
                      <g key={i} opacity={dim ? 0.08 : touches ? 1 : 0.5} style={{ transition: 'opacity 0.15s' }}>
                        <path d={d} fill="none"
                          stroke={touches ? hiColor : baseColor}
                          strokeWidth={touches ? 2 : 1.4}
                          strokeDasharray={isAsync ? '5,4' : undefined}
                          markerEnd={`url(#arrow-${e.type}${touches ? '-hi' : ''})`} />
                        {touches && (() => {
                          const lbl = prettyEdgeLabel(e.label, e.type)
                          if (!lbl) return null
                          return (
                            <g>
                              <rect x={lx - lbl.length * 3.1 - 5} y={ly - 9} width={lbl.length * 6.2 + 10} height="15"
                                rx="7.5" fill="var(--surface)" stroke={`${hiColor}55`} strokeWidth="0.75" opacity="0.97" />
                              <text x={lx} y={ly + 2} fontSize="9" fill={hiColor} textAnchor="middle" fontWeight="600">{lbl}</text>
                            </g>
                          )
                        })()}
                      </g>
                    )
                  })}

                  {/* Nodes */}
                  {visibleServices.map(svc => {
                    const p = LAYOUT.nodes[svc.id]
                    if (!p) return null
                    const isSelected = selected === svc.id
                    const emphasized = !active || isNeighbor(svc.id)
                    const health = healthStatuses[svc.id]
                    const label = svc.name.replace(/ Service$/, '')
                    const { w: bw, h: bh } = brickSize(degrees[svc.id] ?? 0)
                    return (
                      <g key={svc.id}
                        style={{ cursor: 'pointer', transition: 'opacity 0.15s' }}
                        opacity={emphasized ? 1 : 0.25}
                        onClick={() => setSelected(s => s === svc.id ? null : svc.id)}
                        onMouseEnter={() => setHovered(svc.id)}
                        onMouseLeave={() => setHovered(h => h === svc.id ? null : h)}>
                        <LegoBrick cx={p.cx} cy={p.cy} color={svc.color} degree={degrees[svc.id] ?? 0} selected={isSelected} />
                        <text x={p.cx} y={p.cy + bh / 2 + STUD_H / 2 + 14} textAnchor="middle" fontSize="11" fontWeight="600"
                          fill="var(--text-primary)">{label}</text>

                        {/* Health badge — sits at the brick's top-right, above the studs */}
                        {health && (
                          <g transform={`translate(${p.cx + bw / 2 - 1}, ${p.cy - (bh + STUD_H) / 2 + 2})`}>
                            <circle cx="0" cy="0" r="7"
                              fill={health === 'UP' ? '#10b981' : health === 'DOWN' ? '#ef4444' : '#94a3b8'}
                              stroke="var(--surface)" strokeWidth="1.5" />
                            {health === 'UP' && <path d="M-3,0 L-1,2 L3,-2.5" fill="none" stroke="#fff" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />}
                            {health === 'DOWN' && <path d="M-2,-2 L2,2 M-2,2 L2,-2" fill="none" stroke="#fff" strokeWidth="1.5" strokeLinecap="round" />}
                            {health === 'UNKNOWN' && <text x="0" y="2.5" fontSize="8" fill="#fff" textAnchor="middle" fontWeight="bold">?</text>}
                          </g>
                        )}
                      </g>
                    )
                  })}
                </>
              )
            })()}
          </svg>

          {/* Legend */}
          <div style={{ padding: '12px 16px', borderTop: '1px solid var(--border)', display: 'flex', gap: '20px', flexWrap: 'wrap' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
              <svg width="30" height="10"><line x1="0" y1="5" x2="30" y2="5" stroke="#94a3b8" strokeWidth="1.5" markerEnd="url(#arrow-sync)" /></svg>
              {t('Synchronní volání', 'Synchronous call')}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
              <svg width="30" height="10"><line x1="0" y1="5" x2="30" y2="5" stroke="#c4b5fd" strokeWidth="1.5" strokeDasharray="5,3" /></svg>
              {t('Async (události Kafka)', 'Async (Kafka events)')}
            </div>
          </div>
        </div>

        {/* Detail panel */}
        {selectedSvc && (
          <div className="card" style={{ padding: '20px', alignSelf: 'start' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
              <div style={{ width: '12px', height: '12px', borderRadius: '50%', background: selectedSvc.color }} />
              <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{selectedSvc.name}</div>
              {healthStatuses[selectedSvc.id] && (
                <div style={{ 
                  marginLeft: 'auto', 
                  display: 'flex', 
                  alignItems: 'center', 
                  gap: '4px',
                  padding: '2px 8px',
                  borderRadius: '12px',
                  fontSize: '11px',
                  fontWeight: 600,
                  background: healthStatuses[selectedSvc.id] === 'UP' ? '#d1fae5' : healthStatuses[selectedSvc.id] === 'DOWN' ? '#fee2e2' : '#f3f4f6',
                  color: healthStatuses[selectedSvc.id] === 'UP' ? '#059669' : healthStatuses[selectedSvc.id] === 'DOWN' ? '#dc2626' : '#4b5563'
                }}>
                  {healthStatuses[selectedSvc.id] === 'UP' && <CheckCircle2 size={12} />}
                  {healthStatuses[selectedSvc.id] === 'DOWN' && <XCircle size={12} />}
                  {healthStatuses[selectedSvc.id] === 'UNKNOWN' && <HelpCircle size={12} />}
                  {healthStatuses[selectedSvc.id]}
                </div>
              )}
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              <div>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '3px' }}>PORT</div>
                <div style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '13px', color: 'var(--text-primary)' }}>:{selectedSvc.port}</div>
              </div>
              <div>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('SKUPINA', 'GROUP')}</div>
                <div style={{ fontSize: '12px', color: GROUP_LABELS[selectedSvc.group]?.color, fontWeight: 600 }}>
                  {groupLabel(selectedSvc.group)}
                </div>
              </div>
              <div>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('POPIS', 'DESCRIPTION')}</div>
                <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>{t(descCs[selectedSvc.id] ?? selectedSvc.desc, selectedSvc.desc)}</div>
              </div>

              <a
                href={`/services/${selectedSvc.id}/docs`}
                style={{
                  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px',
                  padding: '8px 12px', background: 'var(--accent-bg)', color: 'var(--accent)',
                  borderRadius: 'var(--r-md)', fontSize: '12px', fontWeight: 600,
                  textDecoration: 'none', marginTop: '4px',
                  border: '1px solid var(--accent-border, transparent)',
                }}
              >
                <BookOpen size={14} /> {t('Otevřít dokumentaci služby', 'Open service documentation')}
              </a>

              {govEntry && (
                <div style={{ marginTop: '8px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <div style={{ padding: '12px', background: 'var(--surface-2)', borderRadius: 'var(--r-md)', border: '1px solid var(--border)' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-primary)', marginBottom: '8px' }}>
                      <Database size={12} />
                      {t('Data Governance MVP', 'Data Governance MVP')}
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px', fontSize: '12px' }}>
                      <div>
                        <div style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>{t('Úložiště dat', 'Datastore')}</div>
                        <div style={{ fontWeight: 500, color: 'var(--text-secondary)' }}>{govEntry.primaryDatastore}</div>
                      </div>
                      <div>
                        <div style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>{t('Schéma', 'Schema')}</div>
                        <div style={{ fontWeight: 500, color: 'var(--text-secondary)' }}>{govEntry.schemaName}</div>
                      </div>
                      <div>
                        <div style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>{t('Role v lineage', 'Lineage Role')}</div>
                        <div style={{ fontWeight: 500, color: 'var(--text-secondary)' }}>{govEntry.dataLineageRole}</div>
                      </div>
                      <div>
                        <div style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>{t('Flyway deklarováno', 'Flyway Declared')}</div>
                        <div style={{ fontWeight: 500, color: 'var(--text-secondary)' }}>{govEntry.flywayDeclaredVersion}</div>
                      </div>
                      <div>
                        <div style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>{t('Flyway aktuálně', 'Flyway Current')}</div>
                        <div style={{ fontWeight: 500, color: 'var(--text-secondary)' }}>{govEntry.flywayCurrentVersion || 'N/A'}</div>
                      </div>
                      <div>
                        <div style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>{t('Drift', 'Drift')}</div>
                        <div style={{ 
                          fontWeight: 500, 
                          color: govEntry.flywayDrift === true 
                            ? 'var(--danger)' 
                            : govEntry.flywayDrift === false 
                              ? 'var(--success)' 
                              : 'var(--warning)' 
                        }}>
                          {govEntry.flywayDrift === true ? t('Ano', 'Yes') : govEntry.flywayDrift === false ? t('Ne', 'No') : t('Neznámé', 'Unknown')}
                        </div>
                      </div>
                      <div>
                        <div style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>{t('Klasifikace dat', 'Data Classification')}</div>
                        <div style={{ fontWeight: 500, color: 'var(--text-secondary)' }}>
                          {govEntry.dataClassification || t('neznámé', 'unknown')}
                        </div>
                      </div>
                      <div>
                        <div style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>{t('Politika retence', 'Retention Policy')}</div>
                        <div style={{ fontWeight: 500, color: 'var(--text-secondary)' }}>
                          {govEntry.retentionPolicy || 'N/A'}
                        </div>
                      </div>
                      <div>
                        <div style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>{t('Evidence exportována', 'Evidence Exported')}</div>
                        <div style={{ fontWeight: 500, color: govEntry.evidenceExported ? 'var(--success)' : 'var(--warning)' }}>
                          {govEntry.evidenceExported ? t('Ano', 'Yes') : t('Ne', 'No')}
                        </div>
                      </div>
                    </div>
                  </div>

                  {govEntry.lineage && (
                    <div style={{ padding: '12px', background: 'var(--surface-2)', borderRadius: 'var(--r-md)', border: '1px solid var(--border)' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-primary)', marginBottom: '8px' }}>
                        <Network size={12} />
                        {t('Graf lineage', 'Lineage Graph')}
                      </div>
                      
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '12px' }}>
                        {(govEntry.lineage?.upstream?.length ?? 0) > 0 && (
                          <div>
                            <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginBottom: '4px', display: 'flex', alignItems: 'center', gap: '4px' }}>
                              <ArrowLeft size={10} /> {t('Závislosti směrem nahoru', 'Upstream Dependencies')}
                            </div>
                            <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                              {govEntry.lineage?.upstream?.map((node, i) => (
                                <div key={i} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: 'var(--surface)', padding: '4px 8px', borderRadius: '4px', border: '1px solid var(--border)' }}>
                                  <span style={{ fontWeight: 500, color: 'var(--text-secondary)' }}>{node.serviceName}</span>
                                  <span style={{ fontSize: '10px', color: 'var(--text-tertiary)', background: 'var(--surface-2)', padding: '2px 4px', borderRadius: '4px' }}>{node.relationType}</span>
                                </div>
                              ))}
                            </div>
                          </div>
                        )}

                        {(govEntry.lineage?.downstream?.length ?? 0) > 0 && (
                          <div>
                            <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginBottom: '4px', display: 'flex', alignItems: 'center', gap: '4px' }}>
                              <ArrowRight size={10} /> {t('Odběratelé směrem dolů', 'Downstream Consumers')}
                            </div>
                            <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                              {govEntry.lineage?.downstream?.map((node, i) => (
                                <div key={i} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: 'var(--surface)', padding: '4px 8px', borderRadius: '4px', border: '1px solid var(--border)' }}>
                                  <span style={{ fontWeight: 500, color: 'var(--text-secondary)' }}>{node.serviceName}</span>
                                  <span style={{ fontSize: '10px', color: 'var(--text-tertiary)', background: 'var(--surface-2)', padding: '2px 4px', borderRadius: '4px' }}>{node.relationType}</span>
                                </div>
                              ))}
                            </div>
                          </div>
                        )}

                        <div>
                          <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginBottom: '4px', display: 'flex', alignItems: 'center', gap: '4px' }}>
                            <Layers size={10} /> {t('Rozhraní', 'Interfaces')}
                          </div>
                          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                            {govEntry.lineage?.interfaces?.apis?.map((api, i) => (
                              <span key={`api-${i}`} style={{ fontSize: '10px', background: '#dbeafe', color: '#1e40af', padding: '2px 6px', borderRadius: '4px', border: '1px solid #bfdbfe' }}>{t('API', 'API')}: {api}</span>
                            ))}
                            {govEntry.lineage?.interfaces?.topics?.map((topic, i) => (
                              <span key={`topic-${i}`} style={{ fontSize: '10px', background: '#f3e8ff', color: '#6b21a8', padding: '2px 6px', borderRadius: '4px', border: '1px solid #e9d5ff' }}>{t('Téma', 'Topic')}: {topic}</span>
                            ))}
                            {govEntry.lineage?.interfaces?.datastores?.map((ds, i) => (
                              <span key={`ds-${i}`} style={{ fontSize: '10px', background: '#dcfce7', color: '#166534', padding: '2px 6px', borderRadius: '4px', border: '1px solid #bbf7d0' }}>{t('DB', 'DB')}: {ds}</span>
                            ))}
                          </div>
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              )}

              <div>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '6px' }}>{t('ZÁVISLOSTI', 'DEPENDENCIES')}</div>
                {EDGES.filter(e => e.from === selectedSvc.id || e.to === selectedSvc.id).map((e, i) => {
                  const other = svcMap[e.from === selectedSvc.id ? e.to : e.from]
                  const dir = e.from === selectedSvc.id ? '→' : '←'
                  return (
                    <div key={i} style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '4px', fontSize: '12px' }}>
                      <span style={{ color: 'var(--text-tertiary)', fontFamily: 'monospace' }}>{dir}</span>
                      <span style={{ color: other?.color, fontWeight: 600 }}>{other?.name}</span>
                      <span style={{ color: 'var(--text-tertiary)', fontSize: '11px' }}>({e.label})</span>
                    </div>
                  )
                })}
              </div>

              {/* Same-origin BFF only (ADR-0056) — never http://localhost:<port>. */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', marginTop: '4px' }}>
                <a href={svcUrl(SERVICE_ID_TO_NAME[selectedSvc.id], '/q/openapi', { format: 'json' })} target="_blank" rel="noreferrer"
                  className="btn btn-primary" style={{ textAlign: 'center', textDecoration: 'none', fontSize: '12px' }}>
                  {t('OpenAPI specifikace', 'OpenAPI Spec')} →
                </a>
                <a href={svcUrl(SERVICE_ID_TO_NAME[selectedSvc.id], '/q/health')} target="_blank" rel="noreferrer"
                  className="btn btn-secondary" style={{ textAlign: 'center', textDecoration: 'none', fontSize: '12px' }}>
                  {t('Kontrola zdraví', 'Health Check')} →
                </a>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
