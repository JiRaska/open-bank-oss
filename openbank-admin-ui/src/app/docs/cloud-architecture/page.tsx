// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect, useCallback, useMemo } from 'react'
import { Cloud, Info, CheckCircle2, CircleDashed, Circle, X, RefreshCw, Wifi, WifiOff, Minus } from 'lucide-react'
import { recentArchitectureProbe } from '@/lib/cloud-architecture-evidence'
import type { InfraStatusResult } from '@/lib/infra/probes'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { eksVersion, platformVersions } from '@/lib/platform-versions'
import { LIVE_UNAVAILABLE_NOTE, nodesText, versionText, type Inventory, type PlatformView } from '@/lib/platform-view'
import { ContentSourceNote } from '@/components/docs/ContentSourceNote'
import { usePlatformView } from '@/lib/use-platform-view'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

// Bilingual string tuple: [Czech, English] — spread into t(cs, en) at render.
type Bilingual = [string, string]

// ---------------------------------------------------------------------------
// Architecture comes from source-controlled declarations. Only a recent probe
// establishes reachability; it does not establish GitOps sync or business readiness.

type Status = 'live' | 'partial' | 'planned' | 'declared'

const STATUS_META: Record<Status, { label: Bilingual; color: string; bg: string; border: string; Icon: React.ElementType }> = {
  live:    { label: ['Sonda dostupná', 'Probe reachable'],            color: 'var(--success-text)', bg: 'var(--success-bg)', border: 'var(--success-border)', Icon: CheckCircle2 },
  partial: { label: ['Sonda hlásí problém', 'Probe reports a problem'], color: 'var(--warning-text)', bg: 'var(--warning-bg)', border: 'var(--warning-border)', Icon: CircleDashed },
  declared: { label: ['Deklarováno v IaC/GitOps (běh neověřen)', 'Declared in IaC/GitOps (running not verified)'], color: 'var(--text-secondary)', bg: 'var(--surface-2)', border: 'var(--border-default)', Icon: CircleDashed },
  planned: { label: ['Plánováno (ADR-0027)', 'Planned (ADR-0027)'],          color: 'var(--text-primary)', bg: 'var(--surface-3)', border: 'var(--border-strong)', Icon: Circle },
}

// Maps architecture node IDs → infra probe IDs from /api/infra/status
const INFRA_PROBE_MAP: Record<string, string> = {
  kafka:      'kafka',
  keycloak:   'keycloak',
  vault:      'openbao',
  valkey:     'valkey',
  apicurio:   'schema-registry',
  grafana:    'grafana',
  prometheus: 'prometheus',
  loki:       'loki',
  tempo:      'tempo',
  postgres: 'postgres',
  temporal: 'temporal',
  argocd: 'argocd',
  'cert-manager': 'cert-manager',
  karpenter: 'karpenter',
  kyverno: 'kyverno',
  alertmanager: 'alertmanager',
  pyrra: 'pyrra',
  pyroscope: 'pyroscope',
  alloy: 'alloy',
  otel: 'otel-collector',
  glitchtip: 'glitchtip',
  goalert: 'goalert',
  ntfy: 'ntfy',
}

const PROBE_META = {
  UP:      { color: 'var(--success-text)', bg: 'var(--success-bg)', border: 'var(--success-border)', Icon: Wifi,    label: 'UP' },
  DOWN:    { color: 'var(--danger-text)', bg: 'var(--danger-bg)', border: 'var(--danger-border)', Icon: WifiOff, label: 'DOWN' },
  UNKNOWN: { color: 'var(--text-primary)', bg: 'var(--surface-3)', border: 'var(--border-strong)', Icon: Minus,   label: '?' },
}

type Node = { id: string; name: Bilingual; status: Status; desc: Bilingual }

const podsText = (view: PlatformView | null, ns: string, cs: boolean): string => {
  const n = view?.podsRunningByNamespace?.[ns]
  if (n === undefined) return cs ? `Počet podů v ns ${ns}: neznámý (Prometheus nedostupný).` : `Pod count in ns ${ns}: unknown (Prometheus unavailable).`
  return cs ? `Živě: ${n} podů Running v ns ${ns}.` : `Live: ${n} pods Running in ns ${ns}.`
}

const dsText = (view: PlatformView | null, key: string, cs: boolean): string => {
  const n = view?.daemonSetsReady?.[key]
  if (n === undefined) return cs ? 'Počet připravených podů: neznámý (Prometheus nedostupný).' : 'Ready pods: unknown (Prometheus unavailable).'
  return cs ? `Živě: ${n} připravených podů.` : `Live: ${n} ready pods.`
}

const node = (id: string, name: Bilingual, status: Status, desc: Bilingual): Node => ({ id, name, status, desc })

const iacNode = (inv: Inventory | null, key: keyof Inventory['iac'], id: string, name: string, what: string): Node[] => {
  const envs = inv?.iac[key] ?? []
  if (envs.length === 0) return []   // not defined/instantiated in IaC: no claim is made
  return [node(id, [name, name], 'declared', [`${what} Definováno v IaC, instancováno v: ${envs.join(', ')}. Stav v AWS se zde neověřuje.`, `${what} Defined in IaC, instantiated in: ${envs.join(', ')}. Applied state in AWS is not verified here.`])]
}

// --- Edge / internet: derived from openbank-infra/aws (platform-versions.json inventory) -----------------
const edgeNodes = (inv: Inventory | null): Node[] => [
  ...iacNode(inv, 'route53', 'route53', 'Route 53', 'DNS zóna a záznamy.'),
  ...iacNode(inv, 'cloudfront', 'cloudfront', 'CloudFront', 'CDN distribuce.'),
  ...iacNode(inv, 'alb', 'alb', 'ALB', 'Aplikační load balancer.'),
  ...iacNode(inv, 'acm', 'acm', 'ACM (TLS certifikáty)', 'Spravované TLS certifikáty.'),
]

// --- AWS substrate (only AWS-managed layer per ADR-0027) --------------------
// EKS / node-group rows depend on LIVE data (Prometheus) + declared (terraform), so they are built per render.
const substrateNodes = (view: PlatformView | null, inv: Inventory | null): Node[] => {
  const kubelets = view ? versionText(view.items.kubelet) : 'unknown (live unavailable)'
  const k8s = view ? versionText(view.items.kubernetes) : `${eksVersion()} (${LIVE_UNAVAILABLE_NOTE})`
  return [
  node('s3-state', ['S3 — tofu state', 'S3 — tofu state'], 'declared', ["Verzovaný stav OpenTofu. Stav úložiště zde není měřen.", "Versioned OpenTofu state. Storage health is not measured here."]),
  node('runner', ['EC2 / Mac mini CI runner', 'EC2 / Mac mini CI runner'], 'declared', ["Pool CI runnerů pro GitHub Actions. Dostupnost runnerů ověřuje CI.", "GitHub Actions CI runner pool. Runner availability is observed by CI."]),
  node('vpc', ['VPC — 3 AZ', 'VPC — 3 AZ'], 'declared', ["Síťový základ definovaný v IaC; aktuální topologie vyžaduje inventář poskytovatele.", "Network substrate defined in IaC; current topology requires provider inventory."]),
  node('eks', [`EKS control plane (${k8s})`, `EKS control plane (${k8s})`], 'declared', [`aws_eks_cluster v${k8s} (control plane); node kubelets: ${kubelets}. OIDC/IRSA, EKS Pod Identity, authentication_mode=API (žádný aws-auth configmap), logy control-plane do CloudWatch. Addony: vpc-cni, kube-proxy, coredns, pod-identity.`, `aws_eks_cluster v${k8s} (control plane); node kubelets: ${kubelets}. OIDC/IRSA, EKS Pod Identity, authentication_mode=API (no aws-auth configmap), control-plane logs to CloudWatch. Addons: vpc-cni, kube-proxy, coredns, pod-identity.`]),
  node('nodegroup', ['Bootstrap node group (Graviton)', 'Bootstrap node group (Graviton)'], 'declared', [`AL2023 spravovaná node group (${nodesText(view)}). Nese systémové pody, Karpenter controller a ArgoCD; zbytek provisionuje Karpenter.`, `AL2023 managed node group (${nodesText(view)}). Carries system pods, Karpenter controller and ArgoCD; Karpenter provisions the rest.`]),
  node('kms', ['KMS CMK', 'KMS CMK'], 'declared', ["Správa šifrovacích klíčů dle IaC. Dostupnost ani oprávnění tato stránka netestuje.", "Encryption key management declared in IaC. This page does not test availability or permissions."]),
  node('iam', ['IAM (cluster/node, OIDC, Karpenter)', 'IAM (cluster/node, OIDC, Karpenter)'], 'declared', ["Identity a oprávnění platformy dle IaC.", "Platform identities and permissions declared in IaC."]),
  node('ecr', ['ECR', 'ECR'], 'declared', ["Registr kontejnerových obrazů používaný delivery pipeline.", "Container image registry used by the delivery pipeline."]),
  ...iacNode(inv, 'cloudtrail', 'cloudtrail', 'CloudTrail', 'Audit na úrovni účtu.'),
  ...iacNode(inv, 'awsConfig', 'aws-config', 'AWS Config', 'Config recorder.'),
  ...iacNode(inv, 'objectLock', 'worm', 'S3 Object Lock', 'Write-once archiv.'),
  ]
}

// --- EKS platform bootstrap (sandbox-platform root, day-2) ------------------
const bootstrapNodes = (view: PlatformView | null): Node[] => [
  node('cert-manager', ['cert-manager', 'cert-manager'], 'declared', [`Webhook/serving certifikáty pro ostatní operátory. ${podsText(view, 'cert-manager', true)}`, `Webhook/serving certs for other operators. ${podsText(view, 'cert-manager', false)}`]),
  node('karpenter', ['Karpenter (Graviton / Spot)', 'Karpenter (Graviton / Spot)'], 'declared', ["Automatické plánování kapacity uzlů. Sonda nepotvrzuje stav jednotlivých NodePoolů.", "Automatic node capacity provisioning. The probe does not establish individual NodePool readiness."]),
  node('argocd', ['ArgoCD (vlastník app-of-apps)', 'ArgoCD (app-of-apps owner)'], 'declared', ["GitOps rekonciliace deklarovaného stavu. Dostupnost serveru není důkaz Synced/Healthy aplikací.", "GitOps reconciliation of desired state. Server reachability does not establish application Synced/Healthy status."]),
  node('arc', ['ARC (Actions Runner Controller)', 'ARC (Actions Runner Controller)'], 'declared', ["Řadič Kubernetes runnerů GitHub Actions.", "Kubernetes controller for GitHub Actions runners."]),
]

// --- Namespaces owned by ArgoCD app-of-apps (GitOps) ------------------------
type NS = { id: string; label: string; note?: Bilingual; nodes: Node[] }

const namespaceGroups = (view: PlatformView | null, inv: Inventory | null): NS[] => [
  {
    id: 'mesh', label: 'Gateway / ingress',
    nodes: [
      node('nginx', ['nginx ingress', 'nginx ingress'], inv?.gitops.apps.includes('ingress-nginx') ? 'declared' : 'planned', ['In-cluster ingress controller (GitOps app ingress-nginx).', 'In-cluster ingress controller (GitOps app ingress-nginx).']),
      ...(inv?.gitops.apps.includes('envoy-gateway') ? [node('envoy-gateway', ['Envoy Gateway', 'Envoy Gateway'], 'declared', ['Gateway API implementace (GitOps apps envoy-gateway, envoy-gateway-crds, envoy-gateway-config).', 'Gateway API implementation (GitOps apps envoy-gateway, envoy-gateway-crds, envoy-gateway-config).'])] : []),
      ...(inv?.gitops.istio ? [node('istio', ['Istio', 'Istio'], 'declared', ['Istio image/chart je přítomen v GitOps manifestech.', 'An Istio image/chart is present in the GitOps manifests.'])] : []),
      ...(inv?.gitops.cilium ? [node('cilium', ['Cilium', 'Cilium'], 'declared', ['Cilium image/chart je přítomen v GitOps manifestech.', 'A Cilium image/chart is present in the GitOps manifests.'])] : []),
    ],
  },
  {
    id: 'data', label: 'ns: cnpg-system / messaging / temporal / iam',
    note: ['OSS operátory pro data + identitu — in-cluster stavové jádro dle ADR-0027.', 'OSS data + identity operators — the in-cluster stateful core per ADR-0027.'],
    nodes: [
      node('cnpg', ['CloudNativePG (operátor)', 'CloudNativePG (operator)'], 'declared', ["Operátor databázových clusterů PostgreSQL; oddělený od sondy aplikační databáze.", "PostgreSQL cluster operator, separate from the application database probe."]),
      node('postgres', ['PostgreSQL (aplikační databáze)', 'PostgreSQL (application database)'], 'declared', ['Sonda databázového listeneru; nepotvrzuje zdraví operátoru.', 'Database listener probe; does not establish operator health.']),
      node('kafka', ['Strimzi / Kafka', 'Strimzi / Kafka'], 'declared', ["Událostní platforma Kafka spravovaná Strimzi. Sonda měří dostupnost broker listeneru.", "Kafka event platform managed by Strimzi. The probe measures broker listener reachability."]),
      node('temporal', ['Temporal (workflow engine)', 'Temporal (workflow engine)'], 'declared', ['Orchestrace platebních a závěrkových workflow (settlement, SEPA, statement EoM). Frontend :7233 v ns temporal + vlastní Postgres (CNPG); aplikační workery se připojují gRPC při startu (ADR-0057 app-plane).', 'Payment & close workflow orchestration (settlement, SEPA, statement EoM). Frontend :7233 in ns temporal + own Postgres (CNPG); app workers connect via gRPC at startup (ADR-0057 app-plane).']),
      node('apicurio', ['Apicurio (SQL storage)', 'Apicurio (SQL storage)'], 'declared', ["Registr schémat událostí. Sonda nepotvrzuje úplnost ani kompatibilitu schémat.", "Event schema registry. The probe does not establish schema completeness or compatibility."]),
      node('keycloak', ['Keycloak', 'Keycloak'], 'declared', ["Poskytovatel OIDC identity. Funkčnost přihlášení ověřuje samostatný průchod.", "OIDC identity provider. Login functionality requires a separate user journey check."]),
      node('valkey', ['Valkey / Redis', 'Valkey / Redis'], 'declared', ["Cache a zámky používané aplikacemi.", "Application cache and locks."]),
      node('vault', ['OpenBao + ESO (KMS unseal)', 'OpenBao + ESO (KMS unseal)'], 'declared', ["OpenBao spravuje tajemství; External Secrets Operator je synchronizuje do Kubernetes.", "OpenBao manages secrets; External Secrets Operator synchronizes them into Kubernetes."]),
      ...(inv?.gitops.clickhouse ? [node('clickhouse', ['ClickHouse', 'ClickHouse'], 'declared', ['ClickHouse server image je přítomen v GitOps manifestech (analytics, ai-platform).', 'A ClickHouse server image is present in the GitOps manifests (analytics, ai-platform).'])] : []),
    ],
  },
  {
    id: 'banking', label: 'ns: accounts / admin-ui (+ per-domain)',
    note: ['Doména = namespace (ADR-0037).', 'Domain = namespace (ADR-0037).'],
    nodes: [
      node('account-svc', ['account-service', 'account-service'], 'declared', ["Doménová služba účtů. Pro aktuální stav použijte System Health.", "Account domain service. See System Health for current status."]),
      node('admin-ui', ['admin-ui (tato aplikace)', 'admin-ui (this app)'], 'declared', ["Operační konzole. Verze této aplikace je uvedena v záhlaví.", "Operations console. The running application version is shown in the header."]),
      node('services', [`GitOps aplikace (${inv?.gitops.apps.length ?? 0})`, `GitOps applications (${inv?.gitops.apps.length ?? 0})`], 'declared', [`Aplikace deklarované v openbank-infra/gitops/apps: ${(inv?.gitops.apps ?? []).join(', ')}.`, `Applications declared in openbank-infra/gitops/apps: ${(inv?.gitops.apps ?? []).join(', ')}.`]),
      node('notification-svc', ['Notification Service (T2)', 'Notification Service (T2)'], 'declared', ["Zpracování notifikací; počet replik řídí deklarované škálování. Nula replik sama o sobě není porucha.", "Notification processing; declared scaling controls replicas. Zero replicas alone is not an outage."]),
      node('security-scanner-svc', ['Security Scanner', 'Security Scanner'], 'declared', ["Bezpečnostní kontroly flotily a evidence incidentů. Aktuálnost výsledků je uvedena ve Security Excellence.", "Fleet security checks and incident evidence. Security Excellence shows result freshness."]),
    ],
  },
  {
    id: 'observability', label: 'ns: observability',
    note: ['Observabilita deklarovaná v GitOps; dostupnost ukazují sondy.', 'Observability declared in GitOps; probes show reachability.'],
    nodes: [
      node('prometheus', ['Prometheus', 'Prometheus'], 'declared', ["Sběr a dotazování metrik. Dostupnost serveru nepotvrzuje pokrytí všech cílů.", "Metric collection and queries. Server reachability does not establish coverage of every target."]),
      node('grafana', ['Grafana', 'Grafana'], 'declared', ["Vizualizace metrik přes chráněný OIDC přístup.", "Metric visualization through protected OIDC access."]),
      node('loki', ['Loki', 'Loki'], 'declared', ["Agregace a dotazování logů.", "Log aggregation and queries."]),
      node('tempo', ['Tempo', 'Tempo'], 'declared', ["Distribuované trasování.", "Distributed tracing."]),
      node('otel', ['OpenTelemetry Collector', 'OpenTelemetry Collector'], 'declared', ["Příjem a směrování telemetrie.", "Telemetry ingestion and routing."]),
      node('alloy', ['Grafana Alloy', 'Grafana Alloy'], 'declared', [`DaemonSet sbírá logy z uzlů a posílá je do Loki (ns observability). ${dsText(view, 'observability/alloy', true)}`, `DaemonSet collects node logs and ships them to Loki (ns observability). ${dsText(view, 'observability/alloy', false)}`]),
      node('pyroscope', ['Pyroscope', 'Pyroscope'], 'declared', ["Průběžné profilování aplikací.", "Continuous application profiling."]),
      node('alertmanager', ['Alertmanager', 'Alertmanager'], 'declared', ["Směrování a seskupování alertů přes chráněný přístup.", "Alert routing and grouping through protected access."]),
      node('pyrra', ['Pyrra (SLO)', 'Pyrra (SLO)'], 'declared', ["Vyhodnocování SLO a error budgetů nad metrikami.", "SLO and error-budget evaluation over metrics."]),
      node('glitchtip', ['GlitchTip', 'GlitchTip'], 'declared', ["Evidence aplikačních chyb.", "Application error tracking."]),
      node('goalert', ['GoAlert', 'GoAlert'], 'declared', ["On-call směrování a eskalace.", "On-call routing and escalation."]),
      node('ntfy', ['ntfy', 'ntfy'], 'declared', ["Notifikační most pro alerty.", "Notification bridge for alerts."]),
    ],
  },
  {
    id: 'policy', label: 'admission / policy',
    nodes: [
      node('kyverno', ['Kyverno (admission policy)', 'Kyverno (admission policy)'], 'declared', ["Admission policy. Dosah a výsledky pravidel vyžadují policy reporty.", "Admission policy. Rule coverage and outcomes require policy reports."]),
    ],
  },
]

function StatusDot({ status }: { status: Status }) {
  const m = STATUS_META[status]
  return <m.Icon aria-hidden="true" size={13} style={{ color: m.color, flexShrink: 0 }} />
}

type CloudNodeBoxProps = { n: Node; selectedId: string | null; liveStatus: Record<string, InfraStatusResult> | null; t: (cs: string, en: string) => string; onSelect: (node: Node) => void }

function CloudNodeBox({ n, selectedId, liveStatus, t, onSelect }: CloudNodeBoxProps) {
  const active = selectedId === n.id
  const probeId = INFRA_PROBE_MAP[n.id]
  const candidate = probeId && liveStatus ? liveStatus[probeId] : null
  const probe = recentArchitectureProbe(candidate)
  const status: Status = probe?.status === 'UP' ? 'live' : probe?.status === 'DOWN' ? 'partial' : n.status
  const m = STATUS_META[status]
  const pm = probe ? PROBE_META[probe.status] : null
  return <button type="button" onClick={() => onSelect(n)} title={t(...n.desc)} aria-label={`${t(...n.name)}${pm ? ` ${pm.label}` : ''} — ${t(...m.label)}`} aria-pressed={active} aria-controls={active ? 'cloud-architecture-selection' : undefined} style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '6px 10px', borderRadius: '8px', cursor: 'pointer', background: m.bg, border: `1px solid ${active ? m.color : m.border}`, boxShadow: active ? `0 0 0 2px color-mix(in srgb, ${m.color} 35%, transparent)` : 'none', color: 'var(--text-primary)', fontSize: '12px', fontWeight: 600, fontFamily: 'inherit', textAlign: 'left' }}>
    <StatusDot status={status} />
    {t(...n.name)}
    {' '}{pm && <span style={{ display: 'inline-flex', alignItems: 'center', gap: '2px', marginLeft: '4px', padding: '1px 5px', borderRadius: '10px', background: pm.bg, border: `1px solid ${pm.border}`, fontSize: '10px', fontWeight: 700, color: pm.color }}><pm.Icon size={9} aria-hidden="true" />{pm.label}</span>}
  </button>
}

function ArchitectureZone({ title, subtitle, accent, children }: { title: string; subtitle?: string; accent: string; children: React.ReactNode }) {
  return <div style={{ border: `1.5px solid ${accent}`, borderRadius: '12px', padding: '14px 16px', background: `color-mix(in srgb, ${accent} 4%, var(--surface))` }}><div style={{ display: 'flex', alignItems: 'baseline', gap: '10px', marginBottom: '10px', flexWrap: 'wrap' }}><span style={{ fontSize: '11px', fontWeight: 800, letterSpacing: '0.06em', color: accent, textTransform: 'uppercase' }}>{title}</span>{subtitle && <span style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>{subtitle}</span>}</div>{children}</div>
}

function ArchitectureArrow({ label }: { label?: string }) {
  return <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', margin: '2px 0' }}><div aria-hidden="true" style={{ width: 0, height: 0, borderLeft: '6px solid transparent', borderRight: '6px solid transparent', borderTop: '8px solid var(--border)' }} />{label && <span style={{ fontSize: '10px', color: 'var(--text-secondary)', marginTop: '-2px' }}>{label}</span>}</div>
}

export default function CloudArchitecturePage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const view = usePlatformView()
  const inventory = view ? view.inventory : platformVersions?.inventory ?? null
  const bootstrap = useMemo(() => bootstrapNodes(view), [view])
  const namespaces = useMemo(() => namespaceGroups(view, inventory), [view, inventory])
  const substrate = useMemo(() => substrateNodes(view, inventory), [view, inventory])
  const k8sText = view ? versionText(view.items.kubernetes) : `${eksVersion()} (${LIVE_UNAVAILABLE_NOTE})`
  const [selected, setSelected] = useState<Node | null>(null)
  const [liveStatus, setLiveStatus] = useState<Record<string, InfraStatusResult> | null>(null)
  const [checkedAt, setCheckedAt] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)

  const fetchStatus = useCallback(async () => {
    setRefreshing(true)
    try {
      const res = await fetch('/api/infra/status', { cache: 'no-store', signal: AbortSignal.timeout(10_000) })
      if (!res.ok) throw new Error('Infra observation unavailable')
      if (res.ok) {
        const data: Record<string, InfraStatusResult> = await res.json()
        setLiveStatus(data)
        setCheckedAt(new Date().toLocaleTimeString(dateLocale))
      }
    } catch {
      setLiveStatus(null)
      setCheckedAt(null)
    } finally {
      setRefreshing(false)
    }
  }, [dateLocale])

  useEffect(() => {
    const initialLoad = window.setTimeout(() => { void fetchStatus() }, 0)
    const timer = window.setInterval(() => { if (document.visibilityState === 'visible') void fetchStatus() }, 30_000)
    const visible = () => { if (document.visibilityState === 'visible') void fetchStatus() }
    document.addEventListener('visibilitychange', visible)
    return () => { window.clearTimeout(initialLoad); window.clearInterval(timer); document.removeEventListener('visibilitychange', visible) }
  }, [fetchStatus])

  const wrap = { display: 'flex', flexWrap: 'wrap' as const, gap: '8px' }
  const selectNode = useCallback((n: Node) => setSelected(current => current?.id === n.id ? null : n), [])

  return (
    <div>
      <ContentSourceNote source={view?.declarationSource} />
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span>{t('Dokumentace', 'Docs')}</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Cloud architektura', 'Cloud Architecture')}</span>
          </>}
        title={t('Cloud architektura (AWS)', 'Cloud Architecture (AWS)')}
        subtitle={t('Cílový stav dle ADR-0027 se status overlay z živého clusteru openbank-sandbox · klikni na prvek pro detail', 'Target state per ADR-0027 with a status overlay from the live openbank-sandbox cluster · click an element for detail')}
        icon={<Cloud aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
      />

      {/* Legend + honesty note */}
      <div className="card" style={{ padding: '12px 16px', marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '20px', flexWrap: 'wrap' }}>
        {(Object.keys(STATUS_META) as Status[]).map(s => {
          const m = STATUS_META[s]
          return (
            <div key={s} style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <StatusDot status={s} />
              <span style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)' }}>{t(...m.label)}</span>
            </div>
          )
        })}
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginLeft: 'auto', fontSize: '11px', color: 'var(--text-secondary)', flexWrap: 'wrap' }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
            <Info aria-hidden="true" size={13} />
            {t('Diagram = strategie (ADR-0027); badge', 'Diagram = strategy (ADR-0027); badge')}
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: '2px', padding: '1px 5px', borderRadius: '10px', background: 'var(--success-bg)', border: '1px solid var(--success-border)', fontSize: '10px', fontWeight: 700, color: 'var(--success-text)' }}>
              <Wifi aria-hidden="true" size={9} />UP
            </span>
            {t('= živý probe z EKS openbank-sandbox.', '= live probe from EKS openbank-sandbox.')}
          </span>
          {checkedAt && <span>{t('Naposledy:', 'Last:')} {checkedAt}</span>}
          <button
            type="button"
            onClick={() => void fetchStatus()}
            disabled={refreshing}
            aria-busy={refreshing}
            aria-label={t('Obnovit stav infrastruktury', 'Refresh infrastructure status')}
            style={{ display: 'flex', alignItems: 'center', gap: '4px', background: 'none', border: '1px solid var(--border)', borderRadius: '6px', padding: '3px 8px', cursor: refreshing ? 'not-allowed' : 'pointer', color: 'var(--text-secondary)', fontSize: '11px', fontFamily: 'inherit', opacity: refreshing ? 0.6 : 1 }}
          >
            <RefreshCw aria-hidden="true" size={11} style={{ animation: refreshing ? 'spin 1s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: selected ? '1fr 320px' : '1fr', gap: '16px', alignItems: 'start' }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0' }}>
          <ArchitectureZone title={t('Internet / Edge', 'Internet / Edge')} subtitle={t('Deklarace odvozené z IaC', 'Declarations derived from IaC')} accent="var(--info-text)">
            <div style={wrap}>{edgeNodes(inventory).map(n => <CloudNodeBox key={n.id} n={n} selectedId={selected?.id ?? null} liveStatus={liveStatus} t={t} onSelect={selectNode} />)}</div>
          </ArchitectureZone>

          <ArchitectureArrow label={t('TLS (ACM)', 'TLS (ACM)')} />

          <ArchitectureZone title={t('AWS substrate', 'AWS substrate')} subtitle={t('sandbox účet · regionální nasazení · jediná AWS-managed vrstva', 'sandbox account · regional deployment · the only AWS-managed layer')} accent="var(--warning-text)">
            <div style={wrap}>{substrate.map(n => <CloudNodeBox key={n.id} n={n} selectedId={selected?.id ?? null} liveStatus={liveStatus} t={t} onSelect={selectNode} />)}</div>
          </ArchitectureZone>

          <ArchitectureArrow />

          <ArchitectureZone title={t('EKS cluster — openbank-sandbox', 'EKS cluster — openbank-sandbox')} subtitle={t(`k8s ${k8sText} · Karpenter Graviton/Spot autoscaling`, `k8s ${k8sText} · Karpenter Graviton/Spot autoscaling`)} accent="var(--accent-text)">
            <div style={{ marginBottom: '12px' }}>
              <div style={{ fontSize: '11px', fontWeight: 700, color: 'var(--text-secondary)', marginBottom: '6px' }}>
                {t('Platform bootstrap (sandbox-platform → seeduje GitOps)', 'Platform bootstrap (sandbox-platform → seeds GitOps)')}
              </div>
              <div style={wrap}>{bootstrap.map(n => <CloudNodeBox key={n.id} n={n} selectedId={selected?.id ?? null} liveStatus={liveStatus} t={t} onSelect={selectNode} />)}</div>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {namespaces.map(ns => (
                <div key={ns.id} style={{ border: '1px dashed var(--border)', borderRadius: '10px', padding: '10px 12px' }}>
                  <div style={{ display: 'flex', alignItems: 'baseline', gap: '8px', marginBottom: '8px', flexWrap: 'wrap' }}>
                    <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)', fontFamily: 'monospace' }}>{ns.label}</span>
                    {ns.note && <span style={{ fontSize: '10px', color: 'var(--text-secondary)' }}>{t(...ns.note)}</span>}
                  </div>
                  <div style={wrap}>{ns.nodes.map(n => <CloudNodeBox key={n.id} n={n} selectedId={selected?.id ?? null} liveStatus={liveStatus} t={t} onSelect={selectNode} />)}</div>
                </div>
              ))}
            </div>
          </ArchitectureZone>

          <ArchitectureArrow label={t('pull (read-only deploy key)', 'pull (read-only deploy key)')} />

          <ArchitectureZone title={t('Git (jediný zdroj pravdy)', 'Git (single source of truth)')} accent="var(--success-text)">
            <div style={wrap}>
              <CloudNodeBox n={node('git', ['Git repo — openbank monorepo', 'Git repo — openbank monorepo'], 'declared', ['IaC + k8s manifesty + app-of-apps. ArgoCD odsud pulluje desired state přes read-only SSH deploy key. Proti tomuto repu cluster rekonciluje.', 'IaC + k8s manifests + app-of-apps. ArgoCD pulls desired state from here via a read-only SSH deploy key. This repo is what the cluster reconciles against.'])} selectedId={selected?.id ?? null} liveStatus={liveStatus} t={t} onSelect={selectNode} />
            </div>
          </ArchitectureZone>
        </div>

        {selected && (
          <div id="cloud-architecture-selection" className="card" role="region" aria-label={t('Detail architektonického prvku', 'Architecture element details')} style={{ padding: '18px', position: 'sticky', top: '16px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '10px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <StatusDot status={selected.status} />
                <span style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)' }}>{t(...selected.name)}</span>
              </div>
              <button type="button" onClick={() => setSelected(null)} aria-label={t('Zavřít detail architektury', 'Close architecture details')} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', padding: 0 }}>
                <X aria-hidden="true" size={16} />
              </button>
            </div>
            <span style={{
              display: 'inline-block', fontSize: '11px', fontWeight: 600, padding: '2px 8px', borderRadius: '20px',
              background: STATUS_META[selected.status].bg, color: STATUS_META[selected.status].color,
              border: `1px solid ${STATUS_META[selected.status].border}`, marginBottom: '12px',
            }}>{t(...STATUS_META[selected.status].label)}</span>
            <p style={{ fontSize: '13px', color: 'var(--text-secondary)', lineHeight: 1.6, margin: 0 }}>{t(...selected.desc)}</p>
          </div>
        )}
      </div>
    </div>
  )
}
