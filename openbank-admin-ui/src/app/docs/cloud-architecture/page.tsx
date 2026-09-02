// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect, useCallback } from 'react'
import { Cloud, Info, CheckCircle2, CircleDashed, Circle, X, RefreshCw, Wifi, WifiOff, Minus } from 'lucide-react'
import type { InfraStatusResult } from '@/lib/infra/probes'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

// Bilingual string tuple: [Czech, English] — spread into t(cs, en) at render.
type Bilingual = [string, string]

// ---------------------------------------------------------------------------
// Source of truth for this diagram:
//   • Target architecture — ADR-0027 (cloud-agnostic substrate, everything
//     stateful/identity in-cluster OSS, OpenTofu + ArgoCD GitOps).
//   • Status overlay      — the REAL state of the openbank-sandbox EKS cluster
//     (account 265175468565, eu-north-1) as verified against the live cluster,
//     so this page documents the gap honestly instead of selling the target.
//
//   live    = running in the sandbox cluster / applied today
//   partial = deployed but incomplete or drifting (e.g. 1 of ~33 services)
//   planned = ADR-0027 target, not yet deployed
// ---------------------------------------------------------------------------

type Status = 'live' | 'partial' | 'planned'

const STATUS_META: Record<Status, { label: Bilingual; color: string; bg: string; border: string; Icon: React.ElementType }> = {
  live:    { label: ['Živé (běží dnes)', 'Live (running today)'],            color: '#059669', bg: '#ecfdf5', border: '#6ee7b7', Icon: CheckCircle2 },
  partial: { label: ['Částečné (nasazeno, neúplné)', 'Partial (deployed, incomplete)'], color: '#d97706', bg: '#fffbeb', border: '#fcd34d', Icon: CircleDashed },
  planned: { label: ['Plánováno (ADR-0027)', 'Planned (ADR-0027)'],          color: '#94a3b8', bg: '#f8fafc', border: '#cbd5e1', Icon: Circle },
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
  // 'postgres' probe (accounts-db-rw TCP) maps to the cnpg operator node
  cnpg:       'postgres',
}

const PROBE_META = {
  UP:      { color: '#059669', bg: '#ecfdf5', Icon: Wifi,    label: 'UP' },
  DOWN:    { color: '#dc2626', bg: '#fef2f2', Icon: WifiOff, label: 'DOWN' },
  UNKNOWN: { color: '#94a3b8', bg: '#f8fafc', Icon: Minus,   label: '?' },
}

type Node = { id: string; name: Bilingual; status: Status; desc: Bilingual }

const node = (id: string, name: Bilingual, status: Status, desc: Bilingual): Node => ({ id, name, status, desc })

// --- Edge / internet --------------------------------------------------------
const EDGE: Node[] = [
  node('route53', ['Route 53', 'Route 53'], 'planned', ['Autoritativní DNS pro veřejné bankovní + admin domény. Edge vrstva ADR-0027; zatím neprovisionováno.', 'Authoritative DNS for the public banking + admin domains. ADR-0027 edge tier; not provisioned yet.']),
  node('cloudfront', ['CloudFront + WAF + Shield', 'CloudFront + WAF + Shield'], 'planned', ['CDN, pravidla OWASP WAF a ochrana proti DDoS (Shield) na okraji sítě. Pouze ADR-0027 — neprovisionováno.', 'CDN, OWASP WAF rules and DDoS (Shield) at the edge. ADR-0027 only — not provisioned.']),
  node('alb', ['ALB (AWS LB Controller)', 'ALB (AWS LB Controller)'], 'planned', ['Veřejný L7 vstup k in-cluster ingressu. AWS Load Balancer Controller zatím není nainstalován; dnes se cluster dosahuje interně.', 'Public L7 entry to the in-cluster ingress. The AWS Load Balancer Controller is not installed yet; today the cluster is reached internally.']),
  node('acm', ['ACM (TLS certifikáty)', 'ACM (TLS certs)'], 'planned', ['Spravované TLS certifikáty pro edge / ALB. Pouze ADR-0027.', 'Managed TLS certificates for the edge / ALB. ADR-0027 only.']),
]

// --- AWS substrate (only AWS-managed layer per ADR-0027) --------------------
const SUBSTRATE: Node[] = [
  node('s3-state', ['S3 — tofu state', 'S3 — tofu state'], 'live', ['Vzdálený bucket se stavem OpenTofu (openbank-tofu-state-265175468565) + bootstrap. Aplikováno.', 'Remote OpenTofu state bucket (openbank-tofu-state-265175468565) + bootstrap. Applied.']),
  node('runner', ['EC2 / Mac mini CI runner', 'EC2 / Mac mini CI runner'], 'live', ['Self-hosted pool GitHub Actions runnerů (ADR-0040: Mac mini aktivní, EC2 studená záloha). Běží.', 'Self-hosted GitHub Actions runner pool (ADR-0040: Mac mini active, EC2 cold standby). Running.']),
  node('vpc', ['VPC — 3 AZ', 'VPC — 3 AZ'], 'live', ['modules/network: VPC 10.80.0.0/16, IGW, veřejné+privátní subnety napříč 3 AZ, jediný NAT (FinOps), S3 gateway + interface VPC endpointy. Aplikováno.', 'modules/network: VPC 10.80.0.0/16, IGW, public+private subnets across 3 AZ, single NAT (FinOps), S3 gateway + interface VPC endpoints. Applied.']),
  node('eks', ['EKS control plane (1.35)', 'EKS control plane (1.35)'], 'live', ['aws_eks_cluster v1.35, ACTIVE. OIDC/IRSA, EKS Pod Identity, authentication_mode=API (žádný aws-auth configmap), logy control-plane do CloudWatch. Addony: vpc-cni, kube-proxy, coredns, pod-identity.', 'aws_eks_cluster v1.35, ACTIVE. OIDC/IRSA, EKS Pod Identity, authentication_mode=API (no aws-auth configmap), control-plane logs to CloudWatch. Addons: vpc-cni, kube-proxy, coredns, pod-identity.']),
  node('nodegroup', ['Bootstrap node group (Graviton)', 'Bootstrap node group (Graviton)'], 'live', ['t4g.medium AL2023 spravovaná node group — 2 uzly Ready. Nese systémové pody, Karpenter controller a ArgoCD; zbytek provisionuje Karpenter.', 't4g.medium AL2023 managed node group — 2 nodes Ready. Carries system pods, Karpenter controller and ArgoCD; Karpenter provisions the rest.']),
  node('kms', ['KMS CMK', 'KMS CMK'], 'live', ['Zákaznicky spravovaný klíč pro envelope šifrování EKS secrets (aws_kms_key.secrets). Aplikováno.', 'Customer-managed key for EKS secrets envelope encryption (aws_kms_key.secrets). Applied.']),
  node('iam', ['IAM (cluster/node, OIDC, Karpenter)', 'IAM (cluster/node, OIDC, Karpenter)'], 'live', ['Role clusteru + uzlů, IRSA OIDC provider, IAM pro Karpenter controller/node přes EKS Pod Identity, SQS interruption queue. Aplikováno.', 'Cluster + node roles, IRSA OIDC provider, Karpenter controller/node IAM via EKS Pod Identity, SQS interruption queue. Applied.']),
  node('ecr', ['ECR', 'ECR'], 'live', ['Container registry pro všechny image openbank-*-service (265175468565.dkr.ecr.eu-north-1.amazonaws.com). Aktivně používáno — image pushovány přes build-push-service.sh. Zatím nespravováno přes IaC (ruční vytváření ECR repo); zapojení do IaC je follow-up.', 'Container registry for all openbank-*-service images (265175468565.dkr.ecr.eu-north-1.amazonaws.com). Actively used — images pushed via build-push-service.sh. Not yet managed via IaC (manual ECR repo creation); IaC wiring is a follow-up.']),
  node('cloudtrail', ['CloudTrail + Config', 'CloudTrail + Config'], 'planned', ['Neměnný audit na úrovni účtu + config drift (podmínka go-live DORA čl. 12). Zatím není v IaC.', 'Immutable account-level audit + config drift (DORA Art. 12 go-live condition). Not yet in IaC.']),
  node('worm', ['S3 Object Lock (WORM archiv)', 'S3 Object Lock (WORM archive)'], 'planned', ['Write-once compliance archiv. ADR-0027 — zatím není v IaC.', 'Write-once compliance archive. ADR-0027 — not yet in IaC.']),
]

// --- EKS platform bootstrap (sandbox-platform root, day-2) ------------------
const BOOTSTRAP: Node[] = [
  node('cert-manager', ['cert-manager', 'cert-manager'], 'live', ['Webhook/serving certifikáty pro ostatní operátory. 3 pody Running v ns cert-manager.', 'Webhook/serving certs for other operators. 3 pods Running in ns cert-manager.']),
  node('karpenter', ['Karpenter (Graviton / Spot)', 'Karpenter (Graviton / Spot)'], 'live', ['Autoscaler uzlů. EC2NodeClass + NodePool READY=True: pouze arm64, Spot-first, agresivní konsolidace. Auth přes EKS Pod Identity.', 'Node autoscaler. EC2NodeClass + NodePool READY=True: arm64 only, Spot-first, aggressive consolidation. Auth via EKS Pod Identity.']),
  node('argocd', ['ArgoCD (vlastník app-of-apps)', 'ArgoCD (app-of-apps owner)'], 'live', ['Plný ArgoCD (controller, applicationset, repo-server, redis, server) Running v ns argocd. Vlastní veškerý stav platformy/aplikací přes app-of-apps — to je GitOps engine.', 'Full ArgoCD (controller, applicationset, repo-server, redis, server) Running in ns argocd. Owns all platform/app state via app-of-apps — this is the GitOps engine.']),
  node('arc', ['ARC (Actions Runner Controller)', 'ARC (Actions Runner Controller)'], 'live', ['In-cluster GitHub Actions runnery. Controller Running v ns arc-systems; runner scale-set je podmíněn GitHub App secretem vytvořeným mimo systém.', 'In-cluster GitHub Actions runners. Controller Running in ns arc-systems; runner scale-set gated behind a GitHub App secret created out-of-band.']),
]

// --- Namespaces owned by ArgoCD app-of-apps (GitOps) ------------------------
type NS = { id: string; label: string; note?: Bilingual; nodes: Node[] }

const NAMESPACES: NS[] = [
  {
    id: 'mesh', label: 'ns: ingress-nginx',
    note: ['Mesh odložen: používá se VPC-CNI, zatím žádné Istio/Cilium (portabilita na prvním místě dle ADR-0037).', 'Mesh deferred: VPC-CNI in use, no Istio/Cilium yet (portability-first per ADR-0037).'],
    nodes: [
      node('nginx', ['nginx ingress', 'nginx ingress'], 'live', ['In-cluster ingress controller. ArgoCD app ingress-nginx Synced + Healthy. (Kong z ADR-0027 nebyl použit — gateway je nginx.)', 'In-cluster ingress controller. ArgoCD app ingress-nginx Synced + Healthy. (Kong from ADR-0027 was not used — nginx is the gateway.)']),
      node('istio', ['Istio (STRICT mTLS)', 'Istio (STRICT mTLS)'], 'planned', ['Service mesh, STRICT mTLS napříč clusterem. Base manifest existuje v k8s/base; nenasazeno.', 'Service mesh, cluster-wide STRICT mTLS. Base manifest exists in k8s/base; not deployed.']),
      node('cilium', ['Cilium (default-deny)', 'Cilium (default-deny)'], 'planned', ['CNI + default-deny NetworkPolicy. Cíl ADR-0027; dnes se používá VPC-CNI.', 'CNI + default-deny NetworkPolicy. ADR-0027 target; today VPC-CNI is used.']),
    ],
  },
  {
    id: 'data', label: 'ns: cnpg-system / messaging / temporal / iam',
    note: ['OSS operátory pro data + identitu — in-cluster stavové jádro dle ADR-0027.', 'OSS data + identity operators — the in-cluster stateful core per ADR-0027.'],
    nodes: [
      node('cnpg', ['CloudNativePG (operátor)', 'CloudNativePG (operator)'], 'live', ['Postgres operátor Running v ns cnpg-system. Spravuje PG clustery podle domén (accounts-db, keycloak-db, apicurio-db).', 'Postgres operator Running in ns cnpg-system. Manages the per-domain PG clusters (accounts-db, keycloak-db, apicurio-db).']),
      node('kafka', ['Strimzi / Kafka', 'Strimzi / Kafka'], 'live', ['Strimzi operátor + Kafka broker (openbank-cluster) Running v ns messaging. ArgoCD app „kafka" je OutOfSync (config drift), byť Healthy.', 'Strimzi operator + Kafka broker (openbank-cluster) Running in ns messaging. ArgoCD app "kafka" is OutOfSync (config drift) though Healthy.']),
      node('temporal', ['Temporal (workflow engine)', 'Temporal (workflow engine)'], 'live', ['Orchestrace platebních a závěrkových workflow (settlement, SEPA, statement EoM). Frontend :7233 v ns temporal + vlastní Postgres (CNPG); aplikační workery se připojují gRPC při startu (ADR-0057 app-plane).', 'Payment & close workflow orchestration (settlement, SEPA, statement EoM). Frontend :7233 in ns temporal + own Postgres (CNPG); app workers connect via gRPC at startup (ADR-0057 app-plane).']),
      node('apicurio', ['Apicurio (SQL storage)', 'Apicurio (SQL storage)'], 'live', ['Schema registry + vlastní Postgres (apicurio-db) Running v ns messaging — SQL storage, splňuje podmínku go-live ADR-0027 (ne in-memory).', 'Schema registry + its own Postgres (apicurio-db) Running in ns messaging — SQL storage, satisfying the ADR-0027 go-live condition (not in-memory).']),
      node('keycloak', ['Keycloak', 'Keycloak'], 'live', ['OIDC identity provider + keycloak-db Running v ns iam. ArgoCD app keycloak Synced + Healthy.', 'OIDC identity provider + keycloak-db Running in ns iam. ArgoCD app keycloak Synced + Healthy.']),
      node('valkey', ['Valkey / Redis', 'Valkey / Redis'], 'live', ['Cache/zámky (redis) Running v ns accounts vedle služby, která ji používá.', 'Cache/locks (redis) Running in ns accounts alongside the service that uses it.']),
      node('vault', ['OpenBao + ESO (KMS unseal)', 'OpenBao + ESO (KMS unseal)'], 'live', ['ArgoCD app openbao Synced + Healthy. Pod OpenBao (openbao-0) běží v ns vault; AWS KMS auto-unseal zapojen (stejný klíč, jaký používal Vault). Nasazen External Secrets Operator (ArgoCD app external-secrets Synced). ESO → OpenBao živé: vault-kv ClusterSecretStore čte KV přes eso k8s-auth roli; 16 ExternalSecrets SecretSynced. Migrováno z HashiCorp Vaultu na LF/MPL fork OpenBao (runbook 0005).', 'ArgoCD app openbao Synced + Healthy. OpenBao pod (openbao-0) running in ns vault; AWS KMS auto-unseal wired (same key Vault used). External Secrets Operator deployed (ArgoCD app external-secrets Synced). ESO → OpenBao live: the vault-kv ClusterSecretStore reads KV via the eso k8s-auth role; 16 ExternalSecrets SecretSynced. Migrated off HashiCorp Vault to the LF/MPL OpenBao fork (runbook 0005).']),
      node('clickhouse', ['ClickHouse', 'ClickHouse'], 'planned', ['Analytický sloupcový store. Cíl ADR-0027; nenasazeno.', 'Analytics columnar store. ADR-0027 target; not deployed.']),
    ],
  },
  {
    id: 'banking', label: 'ns: accounts / admin-ui (+ per-domain)',
    note: ['Doména = namespace (ADR-0037). Jeden vertikální řez je živý; zbytek flotily čeká na onboarding do GitOps.', 'Domain = namespace (ADR-0037). One vertical slice is live; the rest of the fleet is pending GitOps onboarding.'],
    nodes: [
      node('account-svc', ['account-service', 'account-service'], 'live', ['První Quarkus služba nasazená end-to-end: account-service + accounts-db (CNPG) + redis. ArgoCD app accounts Synced + Healthy.', 'First Quarkus service deployed end-to-end: account-service + accounts-db (CNPG) + redis. ArgoCD app accounts Synced + Healthy.']),
      node('admin-ui', ['admin-ui (tato aplikace)', 'admin-ui (this app)'], 'live', ['Operations konzole v Next.js, kterou právě používáte — nasazena in-cluster v ns admin-ui. ArgoCD app admin-ui Synced + Healthy.', 'The Next.js operations console you are using — deployed in-cluster in ns admin-ui. ArgoCD app admin-ui Synced + Healthy.']),
      node('services', ['Nasazeno 10+ doménových vertikál', '10+ domain verticals deployed'], 'partial', ['Živé GitOps aplikace: ledger, balances, payments (sepa/domestic/instant), sca, consent, agent (platform ns), notifications (T2 scale-to-zero), product-catalog, security-scanner. ~24 zbývajících služeb (anacredit, lending, sdd, swift, party, kyc, aml, sanctions, audit, dispute, card-issuance, clearing, interest, statement, fx, tpp-registry, psd2, pid, standing-order, transaction a další) čeká na onboarding do GitOps.', 'GitOps apps live: ledger, balances, payments (sepa/domestic/instant), sca, consent, agent (platform ns), notifications (T2 scale-to-zero), product-catalog, security-scanner. ~24 remaining services (anacredit, lending, sdd, swift, party, kyc, aml, sanctions, audit, dispute, card-issuance, clearing, interest, statement, fx, tpp-registry, psd2, pid, standing-order, transaction, and others) pending GitOps onboarding.']),
      node('notification-svc', ['Notification Service (T2)', 'Notification Service (T2)'], 'live', ['ArgoCD app notifications Synced. KEDA ScaledObject vlastní počet replik — ustálený stav je 0 podů (FinOps T2, ADR-0057). Probouzí se na lagu consumer-group openbank.notification.requests; vyprázdní se a vrátí na 0 po cooldownu.', 'ArgoCD app notifications Synced. KEDA ScaledObject owns replica count — steady state is 0 pods (FinOps T2, ADR-0057). Wakes on openbank.notification.requests consumer-group lag; drains and returns to 0 after cooldown.']),
      node('security-scanner-svc', ['Security Scanner', 'Security Scanner'], 'live', ['ArgoCD app security-scanner Synced. Každých 30 min sonduje všechny /q/health endpointy flotily; report se čte přes REST, DORA ICT incidenty jdou do openbank.security.ict.incident. Report je bez perzistence (v paměti), ale DORA ICT incidenty se od V5__create_ict_incidents.sql ukládají do vlastní Postgres (#4728) — dřív žily jen v ConcurrentHashMap a restart podu je tiše smazal.', 'ArgoCD app security-scanner Synced. Probes all fleet /q/health endpoints every 30 min; the report is served over REST and DORA ICT incidents go to openbank.security.ict.incident. The probe report is unpersisted (in-memory), but DORA ICT incidents have had their own Postgres table since V5__create_ict_incidents.sql (#4728) — before that they lived only in a ConcurrentHashMap and a pod restart silently emptied the register.']),
    ],
  },
  {
    id: 'observability', label: 'ns: observability',
    note: ['Plný stack nasazený přes ArgoCD GitOps (kube-prometheus-stack, Loki, Tempo, OTel collector).', 'Full stack deployed via ArgoCD GitOps (kube-prometheus-stack, Loki, Tempo, OTel collector).'],
    nodes: [
      node('prometheus', ['Prometheus', 'Prometheus'], 'live', ['ArgoCD app kube-prometheus-stack Synced + Healthy. Scrapuje všechny /q/metrics endpointy openbank-*-service. Běží v ns observability.', 'kube-prometheus-stack ArgoCD app Synced + Healthy. Scrapes all openbank-*-service /q/metrics endpoints. Runs in ns observability.']),
      node('grafana', ['Grafana', 'Grafana'], 'live', ['Součástí kube-prometheus-stacku. Dashboardy dostupné v clusteru. ArgoCD app Synced.', 'Bundled in kube-prometheus-stack. Dashboards available in the cluster. ArgoCD app Synced.']),
      node('loki', ['Loki', 'Loki'], 'live', ['ArgoCD app loki Synced + Healthy. Agregace logů ze všech podů. Chunky → in-cluster PVC (integrace s S3 je prod follow-up).', 'ArgoCD app loki Synced + Healthy. Log aggregation for all pods. Chunks → in-cluster PVC (S3 integration is a prod follow-up).']),
      node('tempo', ['Tempo', 'Tempo'], 'live', ['ArgoCD app tempo Synced + Healthy. Backend distribuovaného tracingu pro OTLP trasy z Quarkus služeb.', 'ArgoCD app tempo Synced + Healthy. Distributed tracing backend for OTLP traces from Quarkus services.']),
      node('otel', ['OpenTelemetry Collector', 'OpenTelemetry Collector'], 'live', ['ArgoCD app otel-collector Synced + Healthy. Pipeline: OTLP → Tempo (trasy) + Prometheus remote-write (metriky).', 'ArgoCD app otel-collector Synced + Healthy. Pipeline: OTLP → Tempo (traces) + Prometheus remote-write (metrics).']),
      node('alloy', ['Grafana Alloy', 'Grafana Alloy'], 'live', ['ArgoCD app alloy Synced + Healthy. DaemonSet (18 podů) sbírá logy z uzlů a posílá je do Loki; běží v ns observability.', 'ArgoCD app alloy Synced + Healthy. DaemonSet (18 pods) collects node logs and ships them to Loki; runs in ns observability.']),
      node('pyroscope', ['Pyroscope', 'Pyroscope'], 'live', ['ArgoCD app pyroscope Synced + Healthy. Continuous-profiling backend; StatefulSet Running v ns observability.', 'ArgoCD app pyroscope Synced + Healthy. Continuous-profiling backend; StatefulSet Running in ns observability.']),
      node('alertmanager', ['Alertmanager', 'Alertmanager'], 'live', ['Součástí kube-prometheus-stacku. StatefulSet Running v ns observability; směruje alerty z Prometheu.', 'Bundled in kube-prometheus-stack. StatefulSet Running in ns observability; routes alerts from Prometheus.']),
      node('pyrra', ['Pyrra (SLO)', 'Pyrra (SLO)'], 'live', ['ArgoCD app pyrra Synced + Healthy. SLO/error-budget engine nad Prometheem (pyrra-api + pyrra-kubernetes) Running v ns observability.', 'ArgoCD app pyrra Synced + Healthy. SLO/error-budget engine over Prometheus (pyrra-api + pyrra-kubernetes) Running in ns observability.']),
      node('glitchtip', ['GlitchTip', 'GlitchTip'], 'live', ['ArgoCD app glitchtip Synced + Healthy. Error/crash tracking (Sentry-kompatibilní), glitchtip-web + valkey Running v ns observability.', 'ArgoCD app glitchtip Synced + Healthy. Error/crash tracking (Sentry-compatible), glitchtip-web + valkey Running in ns observability.']),
      node('goalert', ['GoAlert', 'GoAlert'], 'live', ['On-call / eskalace alertů. Deployment Running v ns observability (ADR-0088).', 'On-call / alert escalation. Deployment Running in ns observability (ADR-0088).']),
      node('ntfy', ['ntfy', 'ntfy'], 'live', ['Push-notifikační most pro alerty. Deployment Running v ns observability (ADR-0088).', 'Push-notification bridge for alerts. Deployment Running in ns observability (ADR-0088).']),
    ],
  },
  {
    id: 'policy', label: 'admission / policy',
    nodes: [
      node('kyverno', ['Kyverno (admission policy)', 'Kyverno (admission policy)'], 'live', ['ArgoCD app kyverno Synced + Healthy. Vynucuje pod-security baseline, image pull policies a přítomnost resource-limitů. Zapojení CEL-based ValidatingAdmissionPolicy je follow-up (ADR-0037).', 'ArgoCD app kyverno Synced + Healthy. Enforces pod-security baseline, image pull policies, and resource-limit presence. CEL-based ValidatingAdmissionPolicy wiring is a follow-up (ADR-0037).']),
    ],
  },
]

function StatusDot({ status }: { status: Status }) {
  const m = STATUS_META[status]
  return <m.Icon aria-hidden="true" size={13} style={{ color: m.color, flexShrink: 0 }} />
}

type CloudNodeBoxProps = { n: Node; selectedId: string | null; liveStatus: Record<string, InfraStatusResult> | null; t: (cs: string, en: string) => string; onSelect: (node: Node) => void }

function CloudNodeBox({ n, selectedId, liveStatus, t, onSelect }: CloudNodeBoxProps) {
  const m = STATUS_META[n.status]
  const active = selectedId === n.id
  const probeId = INFRA_PROBE_MAP[n.id]
  const probe = probeId && liveStatus ? liveStatus[probeId] : null
  const pm = probe ? PROBE_META[probe.status] : null
  return <button type="button" onClick={() => onSelect(n)} title={t(...n.desc)} aria-label={`${t(...n.name)} — ${t(...m.label)}`} aria-pressed={active} aria-controls={active ? 'cloud-architecture-selection' : undefined} style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '6px 10px', borderRadius: '8px', cursor: 'pointer', background: m.bg, border: `1px solid ${active ? m.color : m.border}`, boxShadow: active ? `0 0 0 2px ${m.color}55` : 'none', color: 'var(--text-primary)', fontSize: '12px', fontWeight: 600, fontFamily: 'inherit', textAlign: 'left' }}>
    <StatusDot status={n.status} />
    {t(...n.name)}
    {pm && <span style={{ display: 'inline-flex', alignItems: 'center', gap: '2px', marginLeft: '4px', padding: '1px 5px', borderRadius: '10px', background: pm.bg, border: `1px solid ${pm.color}44`, fontSize: '10px', fontWeight: 700, color: pm.color }}><pm.Icon size={9} aria-hidden="true" />{pm.label}</span>}
  </button>
}

function ArchitectureZone({ title, subtitle, accent, children }: { title: string; subtitle?: string; accent: string; children: React.ReactNode }) {
  return <div style={{ border: `1.5px solid ${accent}`, borderRadius: '12px', padding: '14px 16px', background: `${accent}08` }}><div style={{ display: 'flex', alignItems: 'baseline', gap: '10px', marginBottom: '10px', flexWrap: 'wrap' }}><span style={{ fontSize: '11px', fontWeight: 800, letterSpacing: '0.06em', color: accent, textTransform: 'uppercase' }}>{title}</span>{subtitle && <span style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>{subtitle}</span>}</div>{children}</div>
}

function ArchitectureArrow({ label }: { label?: string }) {
  return <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', margin: '2px 0' }}><div aria-hidden="true" style={{ width: 0, height: 0, borderLeft: '6px solid transparent', borderRight: '6px solid transparent', borderTop: '8px solid var(--border)' }} />{label && <span style={{ fontSize: '10px', color: 'var(--text-secondary)', marginTop: '-2px' }}>{label}</span>}</div>
}

export default function CloudArchitecturePage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [selected, setSelected] = useState<Node | null>(null)
  const [liveStatus, setLiveStatus] = useState<Record<string, InfraStatusResult> | null>(null)
  const [checkedAt, setCheckedAt] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)

  const fetchStatus = useCallback(async () => {
    setRefreshing(true)
    try {
      const res = await fetch('/api/infra/status', { cache: 'no-store' })
      if (res.ok) {
        const data: Record<string, InfraStatusResult> = await res.json()
        setLiveStatus(data)
        setCheckedAt(new Date().toLocaleTimeString(dateLocale))
      }
    } catch {
      // graceful degradation — static statuses remain visible
    } finally {
      setRefreshing(false)
    }
  }, [dateLocale])

  useEffect(() => { void fetchStatus() }, [fetchStatus])

  const wrap = { display: 'flex', flexWrap: 'wrap' as const, gap: '8px' }
  const selectNode = useCallback((n: Node) => setSelected(current => current?.id === n.id ? null : n), [])

  return (
    <div>
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
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: '2px', padding: '1px 5px', borderRadius: '10px', background: '#ecfdf5', border: '1px solid #6ee7b744', fontSize: '10px', fontWeight: 700, color: '#059669' }}>
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
          <ArchitectureZone title={t('Internet / Edge', 'Internet / Edge')} subtitle={t('Edge vrstva ADR-0027 — zatím neprovisionováno', 'ADR-0027 edge tier — not provisioned yet')} accent="#0ea5e9">
            <div style={wrap}>{EDGE.map(n => <CloudNodeBox key={n.id} n={n} selectedId={selected?.id ?? null} liveStatus={liveStatus} t={t} onSelect={selectNode} />)}</div>
          </ArchitectureZone>

          <ArchitectureArrow label={t('TLS (ACM)', 'TLS (ACM)')} />

          <ArchitectureZone title={t('AWS substrate', 'AWS substrate')} subtitle={t('účet 265175468565 · eu-north-1 · jediná AWS-managed vrstva', 'account 265175468565 · eu-north-1 · the only AWS-managed layer')} accent="#f59e0b">
            <div style={wrap}>{SUBSTRATE.map(n => <CloudNodeBox key={n.id} n={n} selectedId={selected?.id ?? null} liveStatus={liveStatus} t={t} onSelect={selectNode} />)}</div>
          </ArchitectureZone>

          <ArchitectureArrow />

          <ArchitectureZone title={t('EKS cluster — openbank-sandbox', 'EKS cluster — openbank-sandbox')} subtitle={t('k8s 1.35 · Karpenter Graviton/Spot autoscaling', 'k8s 1.35 · Karpenter Graviton/Spot autoscaling')} accent="#7c3aed">
            <div style={{ marginBottom: '12px' }}>
              <div style={{ fontSize: '11px', fontWeight: 700, color: 'var(--text-secondary)', marginBottom: '6px' }}>
                {t('Platform bootstrap (sandbox-platform → seeduje GitOps)', 'Platform bootstrap (sandbox-platform → seeds GitOps)')}
              </div>
              <div style={wrap}>{BOOTSTRAP.map(n => <CloudNodeBox key={n.id} n={n} selectedId={selected?.id ?? null} liveStatus={liveStatus} t={t} onSelect={selectNode} />)}</div>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {NAMESPACES.map(ns => (
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

          <ArchitectureZone title={t('Git (jediný zdroj pravdy)', 'Git (single source of truth)')} accent="#059669">
            <div style={wrap}>
              <CloudNodeBox n={node('git', ['Git repo — openbank monorepo', 'Git repo — openbank monorepo'], 'live', ['IaC + k8s manifesty + app-of-apps. ArgoCD odsud pulluje desired state přes read-only SSH deploy key. Proti tomuto repu cluster rekonciluje.', 'IaC + k8s manifests + app-of-apps. ArgoCD pulls desired state from here via a read-only SSH deploy key. This repo is what the cluster reconciles against.'])} selectedId={selected?.id ?? null} liveStatus={liveStatus} t={t} onSelect={selectNode} />
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
