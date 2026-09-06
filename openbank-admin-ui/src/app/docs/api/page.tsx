// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useEffect, useState, useCallback } from 'react'
import { FileCode, RefreshCw, CheckCircle2, XCircle, MinusCircle, ChevronDown, ChevronRight, Zap } from 'lucide-react'
import { svcUrl } from '@/lib/services/bff'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

// UI short-id → Kubernetes Deployment/Service name (the BFF's canonical key).
// All but `catalog` carry a `specId` of the form `openbank-<k8s-name>`, so we
// derive the key from it and special-case the catalog (port 8104). See ADR-0056.
// `k8sName` overrides that derivation for the one service where it does not
// hold — `specId` names the repo module directory (so the code-derived catalog
// lookup below can find it), and `security-scanner` deploys under a different
// k8s workload name than its directory (see src/lib/services/registry.ts).
function k8sName(svc: { specId: string | null; k8sName?: string }): string {
  if (svc.k8sName) return svc.k8sName
  return svc.specId ? svc.specId.replace(/^openbank-/, '') : 'product-catalog'
}

// A catalog card. The editorial entries below carry rich presentation metadata
// (port, group, hand-written description); services discovered ONLY from the
// code-derived catalog (ADR-0029 D3) are rendered with `derived: true` and the
// facts the catalog actually knows (api title, versions) — no hardcoded port/group.
interface Service {
  id: string
  name: string
  port: number | null
  group: string
  version: string
  desc: string
  specId: string | null
  k8sName?: string
  derived?: boolean
}

// A Kafka topic row, as derived by scripts/generate-events.mjs from
// docs/asyncapi/openbank-events.yaml + each service's application.yaml.
interface EventTopic {
  channel: string
  topic: string
  description: string | null
  publishers: string[]
  consumers: string[]
  color: string
}

// Title-case a k8s short name for display, e.g. `lending-service` → `Lending Service`.
function prettyName(short: string): string {
  return short
    .replace(/^openbank-/, '')
    .split('-')
    .map(w => (w ? w[0].toUpperCase() + w.slice(1) : w))
    .join(' ')
}

const SERVICES: Service[] = [
  { id: 'account',      name: 'Account Service',       port: 8100, group: 'Core Banking',    version: 'v1', desc: 'Account lifecycle, IBAN, authorizations (Core system for bank accounts)',    specId: 'openbank-account-service' },
  { id: 'ledger',       name: 'Ledger Service',         port: 8101, group: 'Core Banking',    version: 'v1', desc: 'GL accounts, journal entries, double-entry (Financial book of record)',          specId: 'openbank-ledger-service' },
  { id: 'transaction',  name: 'Transaction Service',    port: 8102, group: 'Core Banking',    version: 'v1', desc: 'Transaction processing, partitioned storage (Payment execution engine)',          specId: 'openbank-transaction-service' },
  { id: 'balance',      name: 'Balance Service',        port: 8103, group: 'Core Banking',    version: 'v1', desc: 'Real-time balances, holds management (Current funds availability)',              specId: 'openbank-balance-service' },
  { id: 'catalog',      name: 'Product Catalog',        port: 8104, group: 'Core Banking',    version: 'v1', desc: 'Banking products, pricing, limits (Product definitions and fees)',               specId: null },
  { id: 'pid',          name: 'PID Service',            port: 8105, group: 'Identity',        version: 'v1', desc: 'Party identity documents, external IDs (Identity resolution)',                   specId: 'openbank-pid-service' },
  { id: 'consent',      name: 'Consent Service',        port: 8106, group: 'PSD2',            version: 'v1', desc: 'PSD2 consent management, AIS/PIS/CBPII (Open Banking user approvals)',           specId: 'openbank-consent-service' },
  { id: 'psd2',         name: 'PSD2 Service',           port: 8107, group: 'PSD2',            version: 'v1', desc: 'Berlin Group NextGenPSD2 API gateway (Regulated API interface)',                 specId: 'openbank-psd2-service' },
  { id: 'tpp',          name: 'TPP Registry',           port: 8108, group: 'PSD2',            version: 'v1', desc: 'Third Party Provider registry, EBA sync (Verified external consumers)',          specId: 'openbank-tpp-registry-service' },
  { id: 'agent',        name: 'Agent Service (MCP)',    port: 8109, group: 'Platform',        version: 'v1', desc: 'AI agent MCP server, OpenBank tools (Intelligent assistant backend)',            specId: 'openbank-agent-service' },
  { id: 'sca',          name: 'SCA Service',            port: 8110, group: 'PSD2',            version: 'v1', desc: 'Strong Customer Authentication, OTP (Multi-factor auth provider)',               specId: 'openbank-sca-service' },
  { id: 'party',        name: 'Party Service',          port: 8111, group: 'Identity',        version: 'v1', desc: 'Customer/company master data, PEP flags (Central customer record)',              specId: 'openbank-party-service' },
  { id: 'notification', name: 'Notification Service',   port: 8112, group: 'Platform',        version: 'v1', desc: 'Email/SMS/Push notifications (Omnichannel delivery)',                           specId: 'openbank-notification-service' },
  { id: 'audit',        name: 'Audit Service',          port: 8113, group: 'Compliance',      version: 'v1', desc: 'Immutable audit trail, EBA ICT Risk (Regulated action logging)',                 specId: 'openbank-audit-service' },
  { id: 'kyc',          name: 'KYC Service',            port: 8114, group: 'Compliance',      version: 'v1', desc: 'KYC/CDD/EDD case management (Know Your Customer reviews)',                      specId: 'openbank-kyc-service' },
  { id: 'sepa',         name: 'SEPA Payment',           port: 8115, group: 'Payments',        version: 'v1', desc: 'SEPA Credit Transfer, SCT Inst (Euro-zone standard payments)',                   specId: 'openbank-sepa-payment' },
  { id: 'domestic',     name: 'Domestic Payment',       port: 8116, group: 'Payments',        version: 'v1', desc: 'Czech domestic payments (Local clearing network)',                               specId: 'openbank-domestic-payment' },
  { id: 'aml',          name: 'AML Service',            port: 8117, group: 'Compliance',      version: 'v1', desc: 'AML screening, sanctions, SAR filing (Anti-Money Laundering engine)',            specId: 'openbank-aml-service' },
  { id: 'card-issuance',name: 'Card Issuance Service',  port: 8118, group: 'Cards',           version: 'v1', desc: 'Card issuance and lifecycle management (Physical and virtual cards)',            specId: 'openbank-card-issuance-service' },
  { id: 'fx',           name: 'FX Service',             port: 8119, group: 'Core Banking',    version: 'v1', desc: 'Foreign exchange rates and conversion (Currency trading engine)',                 specId: 'openbank-fx-service' },
  { id: 'security-scanner',name: 'Security Scanner',    port: 8120, group: 'Platform',        version: 'v1', desc: 'Continuous vulnerability scanning (Infrastructure security)',                    specId: 'openbank-security-scanner', k8sName: 'security-scanner-service' },
  { id: 'standing-order',name:'Standing Order Service', port: 8121, group: 'Payments',        version: 'v1', desc: 'Recurring payments and scheduled transfers (Automated clearing)',                specId: 'openbank-standing-order-service' },
  { id: 'swift',        name: 'SWIFT Service',          port: 8122, group: 'Payments',        version: 'v1', desc: 'SWIFT MT/MX messaging (International wire transfers)',                           specId: 'openbank-swift-service' },
  { id: 'sanctions',    name: 'Sanctions Service',      port: 8123, group: 'Compliance',      version: 'v1', desc: 'Real-time sanctions list screening (Embargo & blocklist checks)',                specId: 'openbank-sanctions-service' },
  { id: 'clearing',     name: 'Clearing Service',       port: 8124, group: 'Payments',        version: 'v1', desc: 'Interbank clearing and settlement (Payment finality)',                           specId: 'openbank-clearing-service' },
  { id: 'interest',     name: 'Interest Service',       port: 8125, group: 'Core Banking',    version: 'v1', desc: 'Interest calculation and accrual (Yield and fee engine)',                        specId: 'openbank-interest-service' },
  { id: 'dispute',      name: 'Dispute Service',        port: 8135, group: 'Cards',           version: 'v1', desc: 'Card disputes and chargebacks (Fraud recovery process)',                         specId: 'openbank-dispute-service' },
  { id: 'sepa-instant', name: 'SEPA Instant Service',   port: 8127, group: 'Payments',        version: 'v1', desc: 'Real-time EUR payment processing (SCT Inst clearing)',                           specId: 'openbank-sepa-instant' },
]

// Czech translations for the editorial SERVICES `desc` prose, keyed by service id.
// Kept here as data so the English original can stay verbatim in SERVICES above;
// the actual t() call happens at the render site (hooks can't run at module scope).
const SERVICE_DESC_CS: Record<string, string> = {
  account:           'Životní cyklus účtu, IBAN, autorizace (Jádrový systém pro bankovní účty)',
  ledger:            'GL účty, účetní zápisy, podvojné účetnictví (Finanční kniha záznamů)',
  transaction:       'Zpracování transakcí, partitionované úložiště (Engine pro provádění plateb)',
  balance:           'Zůstatky v reálném čase, správa blokací (Aktuální dostupnost prostředků)',
  catalog:           'Bankovní produkty, ceny, limity (Definice produktů a poplatků)',
  pid:               'Identifikační doklady klienta, externí ID (Rozlišení identity)',
  consent:           'Správa souhlasů PSD2, AIS/PIS/CBPII (Schválení uživatele v Open Bankingu)',
  psd2:              'API brána Berlin Group NextGenPSD2 (Regulované API rozhraní)',
  tpp:               'Registr poskytovatelů třetích stran, synchronizace s EBA (Ověření externí konzumenti)',
  agent:             'AI agent MCP server, nástroje OpenBank (Backend inteligentního asistenta)',
  sca:               'Silné ověření klienta, OTP (Poskytovatel vícefaktorového ověření)',
  party:             'Kmenová data klientů/firem, příznaky PEP (Centrální záznam klienta)',
  notification:      'E-mailové/SMS/Push notifikace (Omnikanálové doručování)',
  audit:             'Neměnná auditní stopa, EBA ICT Risk (Logování regulovaných akcí)',
  kyc:               'Správa případů KYC/CDD/EDD (Prověření Poznej svého klienta)',
  sepa:              'SEPA úhrada, SCT Inst (Standardní platby v eurozóně)',
  domestic:          'České tuzemské platby (Lokální clearingová síť)',
  aml:               'AML screening, sankce, podání SAR (Engine proti praní špinavých peněz)',
  'card-issuance':   'Vydávání a správa životního cyklu karet (Fyzické a virtuální karty)',
  fx:                'Devizové kurzy a konverze (Engine pro obchodování s měnami)',
  'security-scanner':'Průběžné skenování zranitelností (Bezpečnost infrastruktury)',
  'standing-order':  'Opakované platby a naplánované převody (Automatizovaný clearing)',
  swift:             'Zprávy SWIFT MT/MX (Mezinárodní bankovní převody)',
  sanctions:         'Screening sankčních seznamů v reálném čase (Kontroly embarg a blocklistů)',
  clearing:          'Mezibankovní clearing a zúčtování (Finalita plateb)',
  interest:          'Výpočet a akruál úroků (Engine pro výnosy a poplatky)',
  dispute:           'Reklamace karet a chargebacky (Proces vymáhání při podvodu)',
  'sepa-instant':    'Zpracování okamžitých EUR plateb v reálném čase (Clearing SCT Inst)',
}

// Czech labels for the fixed set of service groups (also used as GROUP_COLORS keys
// and filter values, so the English string stays the canonical key).
const GROUP_LABELS_CS: Record<string, string> = {
  'Core Banking': 'Jádrové bankovnictví',
  'Identity':     'Identita',
  'Compliance':   'Compliance',
  'Payments':     'Platby',
  'PSD2':         'PSD2',
  'Platform':     'Platforma',
  'Cards':        'Karty',
  'Other':        'Ostatní',
}

const SPEC_BASE_URL = 'https://raw.githubusercontent.com/JiRaska/open-bank-oss/main'

const GROUP_COLORS: Record<string, string> = {
  'Core Banking': '#2563eb',
  'Identity':     '#059669',
  'Compliance':   '#dc2626',
  'Payments':     '#7c3aed',
  'PSD2':         '#d97706',
  'Platform':     '#6b7280',
  'Cards':        '#db2777',
  'Other':        '#64748b',
}

const METHOD_COLORS: Record<string, { bg: string, text: string, border: string }> = {
  get: { bg: '#eff6ff', text: '#2563eb', border: '#bfdbfe' },
  post: { bg: '#f0fdf4', text: '#16a34a', border: '#bbf7d0' },
  put: { bg: '#fffbeb', text: '#d97706', border: '#fef08a' },
  patch: { bg: '#fffbeb', text: '#d97706', border: '#fef08a' },
  delete: { bg: '#fef2f2', text: '#dc2626', border: '#fecaca' },
  default: { bg: '#f3f4f6', text: '#4b5563', border: '#e5e7eb' },
}


type JSONValue = string | number | boolean | null | JSONObject | JSONValue[];
interface JSONObject { [key: string]: JSONValue }

interface OpenAPISchema {
  type?: string
  format?: string
  $ref?: string
  items?: OpenAPISchema
  properties?: Record<string, OpenAPISchema>
  required?: string[]
  maxLength?: number
  minLength?: number
  nullable?: boolean
}

interface OpenAPIParameter {
  name: string
  in: string
  required?: boolean
  schema?: OpenAPISchema
}

interface OpenAPIResponse {
  description: string
  content?: Record<string, { schema?: OpenAPISchema }>
}

interface OpenAPIRequestBody {
  content?: Record<string, { schema?: OpenAPISchema }>
  required?: boolean
}

interface OpenAPIOperation {
  summary?: string
  description?: string
  parameters?: OpenAPIParameter[]
  requestBody?: OpenAPIRequestBody
  responses?: Record<string, OpenAPIResponse>
}

interface OpenAPIPathItem {
  get?: OpenAPIOperation
  post?: OpenAPIOperation
  put?: OpenAPIOperation
  patch?: OpenAPIOperation
  delete?: OpenAPIOperation
}

interface OpenAPIDoc {
  openapi: string
  paths: Record<string, OpenAPIPathItem>
  components?: {
    schemas?: Record<string, OpenAPISchema>
  }
}

// Tri-state health, so the catalog can tell apart a service that simply isn't
// deployed in this environment (most of the 33-service fleet in the sandbox)
// from one that IS deployed but failed its readiness probe. Showing the former
// as a red "Offline" reads as "the app is broken" — see ADR-0056 / graceful-state.
type Health = 'up' | 'not_deployed' | 'down'

interface ServiceStatus {
  health: Health
  paths: string[]
  openapi?: OpenAPIDoc | null
  info?: { service: string; version: string; description: string }
}

// Liveness is read from the authoritative fleet snapshot (`/api/services/health`)
// — the SAME source the System Health page uses — NOT from a per-service
// `/q/health/ready` probe over the BFF. Reason: readiness/health is exposed on
// each service's *management* port (8085), while the BFF forwards only to the
// HTTP port (8100); a sibling pod cannot reach the management port either. Only
// the Kubernetes control plane can read that verdict (ADR-0051), so the snapshot
// resolves readiness from Deployment status in-cluster (and falls back to a
// static probe in local dev). Probing `/q/health/ready` via the BFF made every
// deployed service 404 → read as "down", which is exactly the bug being fixed.
interface HealthSnapshot {
  byContainer: Record<string, { status: 'UP' | 'DOWN' | 'UNKNOWN' }>
  source: string
}

async function loadHealthSnapshot(): Promise<HealthSnapshot | null> {
  try {
    const res = await fetch('/api/services/health', { cache: 'no-store', signal: AbortSignal.timeout(8000) })
    if (!res.ok) return null
    return (await res.json()) as HealthSnapshot
  } catch {
    // Same-origin server route; a failure here means we cannot determine the
    // fleet state. Degrade to the neutral "not deployed" rather than a scary
    // "down" (see ADR-0056 / graceful-state).
    return null
  }
}

// Map a service's k8s name to tri-state health. A service the discovery feed
// never listed simply isn't deployed in this environment (neutral); a listed
// service that isn't UP is genuinely down. The snapshot key differs by source
// (discovery → `account-service`, static probe → `openbank-account-service`),
// so try both forms.
function healthFor(snap: HealthSnapshot | null, k8s: string): Health {
  if (!snap) return 'not_deployed'
  const entry = snap.byContainer[k8s] ?? snap.byContainer[`openbank-${k8s}`]
  if (!entry) return 'not_deployed'
  return entry.status === 'UP' ? 'up' : 'down'
}

const resolveRef = (ref: string, openapi: OpenAPIDoc | null): OpenAPISchema | undefined => {
  if (!openapi || !openapi.components || !openapi.components.schemas || !ref.startsWith('#/components/schemas/')) return undefined
  const schemaName = ref.split('/').pop()
  if (!schemaName) return undefined
  return openapi.components.schemas[schemaName]
}

const generateSchemaSnippet = (schema: OpenAPISchema | undefined, openapi: OpenAPIDoc | null, depth = 0): JSONValue => {
  if (!schema || depth > 3) return {}
  if (schema.$ref) {
    const resolved = resolveRef(schema.$ref, openapi)
    return generateSchemaSnippet(resolved, openapi, depth + 1)
  }
  if (schema.type === 'object' && schema.properties) {
    const obj: JSONObject = {}
    for (const [k, v] of Object.entries(schema.properties)) {
      obj[k] = generateSchemaSnippet(v, openapi, depth + 1)
    }
    return obj
  }
  if (schema.type === 'array' && schema.items) {
    return [generateSchemaSnippet(schema.items, openapi, depth + 1)]
  }
  if (schema.type === 'string') return schema.format === 'uuid' ? '00000000-0000-0000-0000-000000000000' : 'string'
  if (schema.type === 'integer' || schema.type === 'number') return 0
  if (schema.type === 'boolean') return false
  return {}
}

const renderSchemaType = (schema: OpenAPISchema | undefined, openapi: OpenAPIDoc | null): string => {
  if (!schema) return 'any'
  if (schema.$ref) {
    const name = schema.$ref.split('/').pop()
    return name || 'object'
  }
  if (schema.type === 'array') {
    return `${renderSchemaType(schema.items, openapi)}[]`
  }
  return schema.type || 'any'
}

function MethodDetailView({ path, method, operation, openapi }: { path: string, method: string, operation: OpenAPIOperation, openapi: OpenAPIDoc | null }) {
  const { t } = useLanguage()
  const methodColor = METHOD_COLORS[method] || METHOD_COLORS.default

  // Fallback defaults if openapi info missing
  const desc = operation.summary || operation.description || t(`Provést ${method.toUpperCase()} na ${path}`, `Execute ${method.toUpperCase()} on ${path}`)
  const params = operation.parameters || []
  const hasBody = !!operation.requestBody
  const responses = operation.responses || {
    '200': { description: t('Úspěch', 'Success') },
    '400': { description: t('Chybný požadavek', 'Bad Request') },
    '401': { description: t('Neautorizováno', 'Unauthorized') },
    '403': { description: t('Zakázáno', 'Forbidden') },
    '404': { description: t('Nenalezeno', 'Not Found') },
    '500': { description: t('Interní chyba serveru', 'Internal Server Error') }
  }

  let bodySchemaSnippet: JSONValue = null
  let bodySchemaRefName = ''
  if (operation.requestBody?.content?.['application/json']?.schema) {
    const s = operation.requestBody.content['application/json'].schema
    bodySchemaSnippet = generateSchemaSnippet(s, openapi)
    if (s.$ref) bodySchemaRefName = s.$ref.split('/').pop() || ''
  } else if (hasBody && ['post', 'put', 'patch'].includes(method)) {
    bodySchemaSnippet = { example: "payload" }
  }

  return (
    <div style={{ marginTop: '8px', padding: '16px', background: '#fff', border: `1px solid ${methodColor.border}`, borderRadius: '6px' }}>
      <div style={{ marginBottom: '12px' }}>
        <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '4px' }}>
          {desc}
        </div>
        <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
          {t(`Operace ${method.toUpperCase()} pro ${path}`, `${method.toUpperCase()} operation for ${path}`)}
        </div>
      </div>

      {params.length > 0 && (
        <div style={{ marginBottom: '16px' }}>
          <div style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', color: 'var(--text-tertiary)', marginBottom: '8px' }}>{t('Parametry', 'Parameters')}</div>
          <table style={{ width: '100%', fontSize: '12px', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)', textAlign: 'left', color: 'var(--text-tertiary)' }}>
                <th style={{ padding: '4px 8px', fontWeight: 500 }}>{t('Název', 'Name')}</th>
                <th style={{ padding: '4px 8px', fontWeight: 500 }}>{t('Kde', 'In')}</th>
                <th style={{ padding: '4px 8px', fontWeight: 500 }}>{t('Typ', 'Type')}</th>
                <th style={{ padding: '4px 8px', fontWeight: 500 }}>{t('Omezení', 'Constraints')}</th>
              </tr>
            </thead>
            <tbody>
              {params.map((p, i) => (
                <tr key={i} style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '6px 8px', fontFamily: 'JetBrains Mono, monospace', fontWeight: p.required ? 700 : 400 }}>
                    {p.name} {p.required && <span style={{ color: 'var(--danger)' }}>*</span>}
                  </td>
                  <td style={{ padding: '6px 8px', color: 'var(--text-secondary)' }}>{p.in}</td>
                  <td style={{ padding: '6px 8px', color: 'var(--text-secondary)' }}>{renderSchemaType(p.schema, openapi)}</td>
                  <td style={{ padding: '6px 8px', color: 'var(--text-tertiary)', fontSize: '11px' }}>
                    {p.schema?.maxLength ? `maxLen:${p.schema.maxLength} ` : ''}
                    {p.schema?.format ? `fmt:${p.schema.format} ` : ''}
                    {p.schema?.nullable ? `nullable ` : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {hasBody && (
        <div style={{ marginBottom: '16px' }}>
          <div style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', color: 'var(--text-tertiary)', marginBottom: '8px' }}>
            {t('Tělo požadavku', 'Request Body')} {operation.requestBody?.required && <span style={{ color: 'var(--danger)' }}>*</span>} {bodySchemaRefName && <span style={{ textTransform: 'none', fontWeight: 400, marginLeft: '8px' }}>({bodySchemaRefName})</span>}
          </div>
          <pre style={{
            background: 'var(--surface)', padding: '12px', borderRadius: '4px',
            fontSize: '11px', fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-primary)',
            overflowX: 'auto', border: '1px solid var(--border)', margin: 0
          }}>
            {bodySchemaSnippet ? JSON.stringify(bodySchemaSnippet, null, 2) : t('// JSON schéma není k dispozici', '// No JSON schema available')}
          </pre>
        </div>
      )}

      <div>
        <div style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', color: 'var(--text-tertiary)', marginBottom: '8px' }}>{t('Odpovědi', 'Responses')}</div>
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
          {Object.entries(responses).map(([code, resp]) => {
            const isSuccess = code.startsWith('2')
            const isClientErr = code.startsWith('4')
            const isServerErr = code.startsWith('5')
            const color = isSuccess ? 'var(--success)' : isClientErr ? 'var(--warning)' : isServerErr ? 'var(--danger)' : 'var(--text-secondary)'
            const bg = isSuccess ? '#f0fdf4' : isClientErr ? '#fffbeb' : isServerErr ? '#fef2f2' : 'var(--surface)'
            const border = isSuccess ? '#bbf7d0' : isClientErr ? '#fef08a' : isServerErr ? '#fecaca' : 'var(--border)'
            return (
              <div key={code} style={{
                padding: '4px 8px', borderRadius: '4px', border: `1px solid ${border}`,
                background: bg, display: 'flex', gap: '6px', alignItems: 'center', fontSize: '11px'
              }}>
                <span style={{ fontWeight: 700, color }}>{code}</span>
                <span style={{ color: 'var(--text-secondary)' }}>{resp.description}</span>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

export default function ApiCatalogPage() {
  const { t } = useLanguage()
  const [statuses, setStatuses] = useState<Record<string, ServiceStatus>>({})
  const [loading, setLoading] = useState(true)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [expandedMethod, setExpandedMethod] = useState<{svc: string, path: string, method: string} | null>(null)
  const [groupFilter, setGroupFilter] = useState('all')
  const [query, setQuery] = useState('')
  const [activeTab, setActiveTab] = useState<'rest' | 'async'>('rest')
  // Code-derived catalog (ADR-0029 D3) — authoritative release/api versions,
  // money-path flag and governance gaps, keyed by module name. Overlays the
  // editorial SERVICES presentation below so the displayed version is the real
  // one (not the stale hardcoded "v1"). Falls back silently if not bundled.
  const [catalog, setCatalog] = useState<Record<string, { releaseVersion: string | null; apiVersion: string | null; moneyPath: boolean; gaps: string[] }>>({})
  // Services present in the code-derived catalog but NOT in the editorial list
  // above — rendered as cards so the catalog is COMPLETE (all ~33 services), not
  // silently truncated to the hand-maintained subset.
  const [derived, setDerived] = useState<Service[]>([])

  const load = useCallback(async () => {
    setLoading(true)
    // One authoritative liveness read for the whole fleet (see HealthSnapshot
    // note above) — then per-service OpenAPI/info enrichment over the BFF.
    const snapshot = await loadHealthSnapshot()
    const results = await Promise.allSettled(
      [...SERVICES, ...derived].map(async svc => {
        const k8s = k8sName(svc)
        const healthState = healthFor(snapshot, k8s)
        try {
          // OpenAPI comes from the image-baked committed spec, served server-side
          // by /api/catalog/openapi/<k8s> — NOT from the live `/q/openapi`. The
          // latter lives on each service's management port (8085), which the BFF
          // (HTTP port only) cannot reach, so a live fetch 404s for every deployed
          // service — that was the "0 endpoints" bug. The committed spec is the
          // governance source of truth (info.version == version.txt) and is
          // available for ALL services regardless of deploy state. `/api/v1/info`
          // IS on the HTTP port, so live version/info still goes via the BFF.
          const [specRes, info] = await Promise.allSettled([
            fetch(`/api/catalog/openapi/${encodeURIComponent(k8s)}`, { signal: AbortSignal.timeout(5000) }),
            fetch(svcUrl(k8s, '/api/v1/info'), { signal: AbortSignal.timeout(3000) }),
          ])

          let paths: string[] = []
          let openapiDoc: OpenAPIDoc | null = null

          if (specRes.status === 'fulfilled' && specRes.value.ok) {
            try {
              openapiDoc = await specRes.value.json()
              if (openapiDoc && openapiDoc.paths) {
                paths = Object.keys(openapiDoc.paths)
              }
            } catch {
              // spec unparseable — leave paths empty, page shows calm empty state
            }
          }

          let infoData
          if (info.status === 'fulfilled' && info.value.ok) {
            infoData = await info.value.json()
          }
          return { id: svc.id, status: { health: healthState, paths, openapi: openapiDoc, info: infoData } }
        } catch {
          // OpenAPI/info enrichment failed; liveness is independent of it.
          return { id: svc.id, status: { health: healthState, paths: [], openapi: null } }
        }
      })
    )
    const map: Record<string, ServiceStatus> = {}
    results.forEach(r => {
      if (r.status === 'fulfilled') map[r.value.id] = r.value.status
    })
    setStatuses(map)
    setLoading(false)
  }, [derived])

  useEffect(() => { load() }, [load])

  // Pull the code-derived catalog once (ADR-0029 D3). Keyed by module name so a
  // card can show its REAL release/api version + money-path + gaps. Also detect
  // drift: catalog modules that this editorial list does not mention.
  useEffect(() => {
    let alive = true
    fetch('/api/catalog/services', { cache: 'no-store' })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (!alive || !data?.available || !Array.isArray(data.services)) return
        const map: Record<string, { releaseVersion: string | null; apiVersion: string | null; moneyPath: boolean; gaps: string[] }> = {}
        for (const s of data.services) {
          map[s.name] = { releaseVersion: s.releaseVersion ?? null, apiVersion: s.apiVersion ?? null, moneyPath: !!s.moneyPath, gaps: s.gaps ?? [] }
        }
        setCatalog(map)
        const known = new Set(SERVICES.map(s => s.specId ?? 'openbank-product-catalog'))
        // Build full cards for catalog services the editorial list omits, using
        // only what the catalog actually knows (no fabricated port/group).
        setDerived(
          data.services
            .filter((s: { name: string; kind: string }) => s.kind === 'service' && !known.has(s.name))
            .map((s: { name: string; short: string; apiVersion: string | null; apiTitle: string | null }): Service => ({
              id: s.short,
              name: prettyName(s.short),
              port: null,
              group: 'Other',
              version: s.apiVersion ? `v${String(s.apiVersion).split('.')[0]}` : 'v1',
              desc: s.apiTitle ?? '',
              specId: s.name,
              derived: true,
            })),
        )
      })
      .catch(() => { /* catalog snapshot absent — cards keep editorial defaults */ })
    return () => { alive = false }
  }, [])

  // Code-derived Kafka topic table (ADR-0029 D3 pattern) — derived from
  // docs/asyncapi/openbank-events.yaml cross-referenced with each service's own
  // application.yaml (scripts/generate-events.mjs), replacing a hand-maintained
  // array that drifted the same way the AsyncAPI document itself drifted (#4761:
  // 15 of ~23 topic names were fiction). Falls back to an empty, honest list.
  const [events, setEvents] = useState<EventTopic[]>([])
  useEffect(() => {
    let alive = true
    fetch('/api/events', { cache: 'no-store' })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (!alive || !data?.available || !Array.isArray(data.topics)) return
        setEvents(data.topics)
      })
      .catch(() => { /* events snapshot absent — async tab shows the empty state */ })
    return () => { alive = false }
  }, [])

  // Editorial cards first (rich metadata, port-ordered), catalog-derived extras last.
  const allServices = [...SERVICES, ...derived]
  const groups = ['all', ...Array.from(new Set(allServices.map(s => s.group)))]
  const normalizedQuery = query.trim().toLocaleLowerCase()
  const filtered = allServices.filter(svc => {
    if (groupFilter !== 'all' && svc.group !== groupFilter) return false
    if (!normalizedQuery) return true
    const status = statuses[svc.id]
    return [svc.name, svc.id, svc.desc, ...(status?.paths ?? [])]
      .some(value => value.toLocaleLowerCase().includes(normalizedQuery))
  })

  // Translate a group key (kept canonical/English as the GROUP_COLORS + filter key).
  const groupLabel = (g: string) => g === 'all' ? t('Vše', 'All') : t(GROUP_LABELS_CS[g] ?? g, g)

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span>{t('Dokumentace', 'Docs')}</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('API Katalog', 'API Catalog')}</span>
          </>}
        title={t('API Katalog', 'API Catalog')}
        subtitle={t(`Swagger/OpenAPI dokumentace ${allServices.length} služeb · live status · proklik na Swagger UI`, `Swagger/OpenAPI documentation for ${allServices.length} services · live status · link to Swagger UI`)}
        icon={<FileCode aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<button type="button" className="btn btn-secondary" onClick={load} disabled={loading} aria-busy={loading}>
            <RefreshCw aria-hidden="true" size={13} className={loading ? 'animate-spin' : ''} />
            {t('Obnovit', 'Refresh')}
          </button>}
      />

      {/* Tabs */}
      <div role="group" aria-label={t('Typ API dokumentace', 'API documentation type')} style={{ display: 'flex', gap: '4px', marginBottom: '16px', borderBottom: '1px solid var(--border)', paddingBottom: '0' }}>
        {(['rest', 'async'] as const).map(tab => (
          <button key={tab} type="button" aria-pressed={activeTab === tab} onClick={() => setActiveTab(tab)} style={{
            padding: '8px 16px', fontSize: '13px', fontWeight: 600,
            background: 'transparent', border: 'none',
            borderBottom: activeTab === tab ? '2px solid #6366f1' : '2px solid transparent',
            color: activeTab === tab ? '#6366f1' : 'var(--text-secondary)',
            cursor: 'pointer', fontFamily: 'inherit', marginBottom: '-1px',
          }}>
            <span aria-hidden="true">{tab === 'rest' ? '⚡' : '📨'}</span>{tab === 'rest' ? ' REST APIs' : ' AsyncAPI / Kafka'}
          </button>
        ))}
      </div>

      {activeTab === 'rest' && (
        <>
      <div style={{ display: 'flex', gap: '12px', alignItems: 'end', flexWrap: 'wrap', marginBottom: '12px' }}>
        <label htmlFor="api-catalog-search" style={{ flex: '1 1 280px', minWidth: '220px', fontSize: '12px', color: 'var(--text-secondary)' }}>
          {t('Hledat službu nebo endpoint', 'Search service or endpoint')}
          <input
            id="api-catalog-search"
            type="search"
            value={query}
            onChange={event => setQuery(event.target.value)}
            placeholder={t('Např. payments, /api/v1/accounts', 'e.g. payments, /api/v1/accounts')}
            className="input"
            style={{ display: 'block', width: '100%', marginTop: '5px' }}
          />
        </label>
        <span aria-live="polite" style={{ fontSize: '12px', color: 'var(--text-tertiary)', paddingBottom: '8px' }}>
          {t(`${filtered.length} služeb`, `${filtered.length} services`)}
        </span>
      </div>
      {/* Group filter */}
      <div role="group" aria-label={t('Filtrovat podle domény', 'Filter by domain')} style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', marginBottom: '16px' }}>
        {groups.map(g => (
          <button key={g} type="button" aria-pressed={groupFilter === g} onClick={() => setGroupFilter(g)}
            style={{
              padding: '5px 12px', fontSize: '12px', fontWeight: 600, borderRadius: '20px',
              border: `1px solid ${groupFilter === g ? (GROUP_COLORS[g] || 'var(--accent)') : 'var(--border)'}`,
              background: groupFilter === g ? (GROUP_COLORS[g] || 'var(--accent)') : 'var(--surface)',
              color: groupFilter === g ? '#fff' : 'var(--text-secondary)',
              cursor: 'pointer', fontFamily: 'inherit',
            }}>{groupLabel(g)}</button>
        ))}
      </div>

      {!loading && (
        <div style={{ display: 'flex', gap: '16px', marginBottom: '16px', flexWrap: 'wrap' }}>
          {[
            { label: t('Online', 'Online'), value: Object.values(statuses).filter(s => s.health === 'up').length, color: 'var(--success)' },
            { label: t('Nenasazeno', 'Not deployed'), value: Object.values(statuses).filter(s => s.health === 'not_deployed').length, color: 'var(--text-tertiary)' },
            { label: t('Offline', 'Offline'), value: Object.values(statuses).filter(s => s.health === 'down').length, color: 'var(--danger)' },
            { label: t('Endpointů celkem', 'Total endpoints'), value: Object.values(statuses).reduce((a, s) => a + s.paths.length, 0), color: 'var(--accent)' },
          ].map(stat => (
            <div key={stat.label} style={{
              padding: '10px 16px', background: 'var(--surface)', border: '1px solid var(--border)',
              borderRadius: 'var(--r-lg)', display: 'flex', gap: '8px', alignItems: 'center',
            }}>
              <span style={{ fontSize: '20px', fontWeight: 700, color: stat.color }}>{stat.value}</span>
              <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{stat.label}</span>
            </div>
          ))}
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        {groupFilter === 'all' && derived.length > 0 && (
          <div style={{
            display: 'flex', alignItems: 'center', gap: '8px', padding: '10px 14px', marginBottom: '12px',
            background: 'var(--accent-bg)', border: '1px solid var(--border)',
            borderRadius: '8px', fontSize: '12px', color: 'var(--text-secondary)',
          }}>
            <span>ℹ</span>
            <span>
              {t(
                `Zobrazeno ${allServices.length} služeb — z toho ${derived.length} odvozeno z code-derived katalogu (ADR-0029), bez ručního zápisu.`,
                `Showing ${allServices.length} services — ${derived.length} of them derived from the code-derived catalog (ADR-0029), with no hand-maintained entry.`,
              )}
            </span>
          </div>
        )}
        {filtered.map(svc => {
          const status = statuses[svc.id]
          const isExpanded = expanded === svc.id
          const groupColor = GROUP_COLORS[svc.group] || '#6b7280'
          const k8s = k8sName(svc)
          // Real, code-derived facts (ADR-0029 D3) overlaid on the editorial card.
          const cat = catalog[svc.specId ?? 'openbank-product-catalog']

          return (
            <div key={svc.id} className="card" style={{ overflow: 'hidden' }}>
              <div style={{
                display: 'flex', alignItems: 'center', gap: '12px', padding: '14px 16px',
                borderLeft: `3px solid ${groupColor}`,
                cursor: 'pointer',
              }} onClick={() => setExpanded(e => e === svc.id ? null : svc.id)}>
                {loading ? (
                  <RefreshCw size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} className="animate-spin" />
                ) : status?.health === 'up' ? (
                  <CheckCircle2 size={14} style={{ color: 'var(--success)', flexShrink: 0 }} aria-label={t('Online', 'Online')} />
                ) : status?.health === 'not_deployed' ? (
                  <MinusCircle size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} aria-label={t('Není nasazeno v tomto prostředí', 'Not deployed in this environment')} />
                ) : (
                  <XCircle size={14} style={{ color: 'var(--danger)', flexShrink: 0 }} aria-label={t('Offline', 'Offline')} />
                )}

                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                    <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{svc.name}</span>
                    <span style={{
                      fontSize: '10px', fontWeight: 600, padding: '2px 6px',
                      background: `${groupColor}15`, color: groupColor,
                      borderRadius: '4px', border: `1px solid ${groupColor}30`,
                    }}>{groupLabel(svc.group)}</span>
                    <span style={{
                      fontSize: '10px', fontFamily: 'JetBrains Mono, monospace',
                      color: 'var(--text-tertiary)',
                    }} title={cat ? t('Z code-derived katalogu (ADR-0029)', 'From the code-derived catalog (ADR-0029)') : undefined}>
                      {/* Real API contract version from the catalog (ADR-0048 axis),
                          plus the release version where they differ; falls back to
                          the editorial default until the catalog snapshot loads. */}
                      API {cat?.apiVersion ?? svc.version}
                      {cat?.releaseVersion && cat.releaseVersion !== cat.apiVersion ? ` · rel ${cat.releaseVersion}` : ''}{svc.port ? ` · :${svc.port}` : ''}
                    </span>
                    {cat?.moneyPath && (
                      <span style={{
                        fontSize: '10px', fontWeight: 700, padding: '2px 6px',
                        background: '#ede9fe', color: '#6d28d9',
                        borderRadius: '4px', border: '1px solid #ddd6fe',
                      }} title={t('Money-path služba (rules.yaml)', 'Money-path service (rules.yaml)')}>money-path</span>
                    )}
                    {cat && cat.gaps.length > 0 && (
                      <span style={{
                        fontSize: '10px', fontWeight: 600, padding: '2px 6px',
                        background: 'var(--warning-bg, #fef9c3)', color: 'var(--warning-text, #92400e)',
                        borderRadius: '4px', border: '1px solid var(--warning-border, #fde047)',
                      }} title={cat.gaps.join('; ')}>⚠ {cat.gaps.length} {t('mezera', 'gap')}{cat.gaps.length > 1 ? (t('y', 's')) : ''}</span>
                    )}
                    {!loading && status?.health === 'not_deployed' && (
                      <span style={{
                        fontSize: '10px', fontWeight: 600, padding: '2px 6px',
                        background: 'var(--surface-2)', color: 'var(--text-tertiary)',
                        borderRadius: '4px', border: '1px solid var(--border)',
                      }}>{t('Nenasazeno', 'Not deployed')}</span>
                    )}
                  </div>
                  <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '2px' }}>{t(SERVICE_DESC_CS[svc.id] ?? svc.desc, svc.desc)}</div>
                </div>

                {status && (
                  <span style={{ fontSize: '12px', color: 'var(--text-tertiary)', flexShrink: 0 }}>
                    {status.paths.length} {t('endpointů', 'endpoints')}
                  </span>
                )}
                <a href={`/docs/changelog/${k8s}`}
                  onClick={e => e.stopPropagation()}
                  style={{
                    display: 'flex', alignItems: 'center', gap: '4px',
                    padding: '5px 10px', fontSize: '11px', fontWeight: 600,
                    background: '#eff6ff', border: '1px solid #bfdbfe',
                    borderRadius: '6px', color: '#2563eb', textDecoration: 'none',
                    flexShrink: 0,
                  }}>
                  <FileCode size={11} />
                  Changelog
                </a>
                <a href={`/docs/release-notes/${k8s}`}
                  onClick={e => e.stopPropagation()}
                  style={{
                    display: 'flex', alignItems: 'center', gap: '4px',
                    padding: '5px 10px', fontSize: '11px', fontWeight: 600,
                    background: '#fdf4ff', border: '1px solid #fbcfe8',
                    borderRadius: '6px', color: '#db2777', textDecoration: 'none',
                    flexShrink: 0,
                  }}>
                  <FileCode size={11} />
                  {t('Poznámky k vydání', 'Release Notes')}
                </a>
                <a href={`/api/catalog/openapi/${encodeURIComponent(k8s)}?format=yaml`} target="_blank" rel="noreferrer"
                  onClick={e => e.stopPropagation()}
                  style={{
                    display: 'flex', alignItems: 'center', gap: '4px',
                    padding: '5px 10px', fontSize: '11px', fontWeight: 600,
                    background: '#fef9c3', border: '1px solid #fde047',
                    borderRadius: '6px', color: '#a16207', textDecoration: 'none',
                    flexShrink: 0,
                  }}>
                  <FileCode size={11} />
                  {t('OpenAPI spec', 'OpenAPI Spec')}
                </a>
                <span style={{ color: 'var(--text-tertiary)', flexShrink: 0 }}>
                  {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                </span>
              </div>

              {isExpanded && status && (
                <div style={{ padding: '16px', borderTop: '1px solid var(--border)', background: 'var(--surface-2)' }}>
                  <div style={{ display: 'grid', gridTemplateColumns: status.info ? '1fr 280px' : '1fr', gap: '16px' }}>
                    <div>
                      <div style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-tertiary)', marginBottom: '8px' }}>
                        {t('Endpointy', 'Endpoints')} ({status.paths.length})
                      </div>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                        {status.paths.length === 0 ? (
                          <div style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>
                            {status.health === 'not_deployed'
                              ? t('Služba není v tomto prostředí nasazená — OpenAPI spec se načte, jakmile poběží.', 'Service is not deployed in this environment — the OpenAPI spec loads once it is running.')
                              : t('Žádné endpointy (služba offline nebo OpenAPI nedostupné).', 'No endpoints (service offline or OpenAPI unavailable).')}
                          </div>
                        ) : status.paths.map((path, i) => {
                          const docPaths = status.openapi?.paths || {}
                          const ops = docPaths[path] || {}
                          const methodKeys = ['get', 'post', 'put', 'patch', 'delete'] as const;
                          const methods = methodKeys.filter(m => !!ops[m])

                          if (methods.length === 0) {
                            return (
                              <div key={i} style={{
                                fontFamily: 'JetBrains Mono, monospace', fontSize: '12px',
                                color: 'var(--text-secondary)', padding: '6px 10px',
                                background: 'var(--surface)', borderRadius: '4px',
                                border: '1px solid var(--border)',
                              }}>{path}</div>
                            )
                          }

                          return methods.map(method => {
                            const op = ops[method]!
                            const methodColor = METHOD_COLORS[method] || METHOD_COLORS.default
                            const isMethodExpanded = expandedMethod?.svc === svc.id && expandedMethod?.path === path && expandedMethod?.method === method

                            return (
                              <div key={`${path}-${method}`} style={{ display: 'flex', flexDirection: 'column' }}>
                                <div
                                  onClick={() => setExpandedMethod(e => e?.svc === svc.id && e?.path === path && e?.method === method ? null : {svc: svc.id, path, method})}
                                  style={{
                                    display: 'flex', alignItems: 'center', gap: '10px',
                                    fontFamily: 'JetBrains Mono, monospace', fontSize: '12px',
                                    padding: '6px 10px', background: 'var(--surface)', borderRadius: '4px',
                                    border: '1px solid var(--border)', cursor: 'pointer',
                                    userSelect: 'none'
                                  }}>
                                  <span style={{
                                    textTransform: 'uppercase', fontWeight: 800, width: '50px',
                                    color: methodColor.text, fontSize: '11px'
                                  }}>{method}</span>
                                  <span style={{ color: 'var(--text-primary)', flex: 1 }}>{path}</span>
                                  <span style={{ color: 'var(--text-tertiary)' }}>
                                    {isMethodExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                                  </span>
                                </div>
                                {isMethodExpanded && (
                                  <MethodDetailView path={path} method={method} operation={op} openapi={status.openapi || null} />
                                )}
                              </div>
                            )
                          })
                        })}
                      </div>
                    </div>

                    {status.info && (
                      <div>
                        <div style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-tertiary)', marginBottom: '8px' }}>
                          {t('Informace o službě', 'Service Info')}
                        </div>
                        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                          {Object.entries(status.info).map(([k, v]) => (
                            <div key={k}>
                              <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', textTransform: 'uppercase' }}>{k}</div>
                              <div style={{ fontSize: '12px', color: 'var(--text-primary)', fontWeight: 500 }}>{String(v)}</div>
                            </div>
                          ))}
                        </div>
                        <div style={{ marginTop: '12px', display: 'flex', flexDirection: 'column', gap: '6px' }}>
                          <a href={`/api/catalog/openapi/${encodeURIComponent(k8s)}`} target="_blank" rel="noreferrer"
                            className="btn btn-primary" style={{ textAlign: 'center', textDecoration: 'none', fontSize: '12px' }}>
                            OpenAPI JSON →
                          </a>
                          <a href={`/api/catalog/openapi/${encodeURIComponent(k8s)}?format=yaml`} target="_blank" rel="noreferrer"
                            className="btn btn-secondary" style={{ textAlign: 'center', textDecoration: 'none', fontSize: '12px' }}>
                            OpenAPI YAML →
                          </a>
                          <a href="/system/health"
                            className="btn btn-secondary" style={{ textAlign: 'center', textDecoration: 'none', fontSize: '12px' }}>
                            {t('Stav systému', 'System Health')} →
                          </a>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          )
        })}
        {!loading && filtered.length === 0 && (
          <div role="status" style={{ padding: '28px 18px', textAlign: 'center', border: '1px dashed var(--border)', borderRadius: 'var(--r-lg)', color: 'var(--text-secondary)' }}>
            <strong>{t('Žádná služba neodpovídá filtru', 'No services match this filter')}</strong>
            <div style={{ marginTop: '6px', fontSize: '12px' }}>{t('Zkuste jiný název, endpoint nebo doménu.', 'Try another service name, endpoint, or domain.')}</div>
          </div>
        )}
      </div>
        </>
      )}

      {activeTab === 'async' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
          <div className="card" style={{ padding: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
              <Zap size={16} style={{ color: '#7c3aed' }} />
              <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{t('AsyncAPI 3.0 — Kafka event streamy', 'AsyncAPI 3.0 — Kafka Event Streams')}</span>
              <a href={`${SPEC_BASE_URL}/docs/asyncapi/openbank-events.yaml`} target="_blank" rel="noreferrer"
                style={{
                  marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '4px',
                  padding: '5px 12px', fontSize: '11px', fontWeight: 600,
                  background: '#fef9c3', border: '1px solid #fde047',
                  borderRadius: '6px', color: '#a16207', textDecoration: 'none',
                }}>
                <FileCode size={11} />
                AsyncAPI YAML
              </a>
            </div>
            <p style={{ fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '16px' }}>
              {t('Všechny Kafka topics a event payloady. Broker:', 'All Kafka topics and event payloads. Broker:')} <code style={{ fontFamily: 'JetBrains Mono, monospace', background: 'var(--surface)', padding: '1px 4px', borderRadius: '3px' }}>kafka:9092</code>
            </p>
            {events.length === 0 && (
              <p style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>
                {t(
                  'Tabulka topics se generuje z docs/asyncapi/openbank-events.yaml při buildu — v tomto prostředí není k dispozici.',
                  'The topic table is generated from docs/asyncapi/openbank-events.yaml at build time — not bundled in this environment.',
                )}
              </p>
            )}
            {events.map(item => (
              <div key={item.topic} style={{
                display: 'flex', alignItems: 'flex-start', gap: '12px', padding: '10px 12px',
                background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '6px',
                borderLeft: `3px solid ${item.color}`, marginBottom: '6px',
              }}>
                <code style={{
                  fontFamily: 'JetBrains Mono, monospace', fontSize: '11px',
                  color: 'var(--text-primary)', flex: 1, wordBreak: 'break-all',
                }}>{item.topic}</code>
                <div style={{ display: 'flex', gap: '6px', flexShrink: 0, alignItems: 'center', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                  {item.publishers.length === 0 ? (
                    <span style={{
                      fontSize: '10px', padding: '2px 6px', borderRadius: '4px',
                      background: '#6b728015', color: '#6b7280', border: '1px solid #6b728030',
                      fontWeight: 600,
                    }}>↑ {t('neznámý publisher', 'unknown publisher')}</span>
                  ) : item.publishers.map(p => (
                    <span key={p} style={{
                      fontSize: '10px', padding: '2px 6px', borderRadius: '4px',
                      background: `${item.color}15`, color: item.color, border: `1px solid ${item.color}30`,
                      fontWeight: 600,
                    }}>↑ {p}</span>
                  ))}
                  {item.consumers.map(c => (
                    <span key={c} style={{
                      fontSize: '10px', padding: '2px 6px', borderRadius: '4px',
                      background: '#f3f4f6', color: '#4b5563', border: '1px solid #e5e7eb',
                    }}>↓ {c}</span>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
