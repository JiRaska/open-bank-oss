// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Derives cluster-topology.json for the /docs/cluster dossier (ADR-0081). Pure GitOps + Dockerfile
// repo-walk — no cluster creds. Same provenance pattern as generate-governance.mjs /
// generate-infra-lifecycle.mjs: derive what's declared, curate the rest, tag every claim honestly.
//
// Derived (from the repo, cannot lie): the namespace set (ArgoCD app destinations), the
// NetworkPolicy / ExternalSecret / ClusterPolicy counts (manifests), the image base stages +
// non-root user (a representative Dockerfile), the pod securityContext (a Deployment manifest).
// Curated (declared facts, like governance.yaml): the per-namespace role/group, the six
// defense-in-depth layers, the image-anatomy narrative, and the plan-vs-reality rows — with the
// DERIVED counts injected so the reality column is real.

import { readFileSync, readdirSync, writeFileSync, existsSync } from 'fs'
import { createHash } from 'crypto'
import path from 'path'
import { fileURLToPath } from 'url'
import { sourceDate } from './lib/source-date.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const arg = (flag, fallback) => {
  const i = process.argv.indexOf(flag)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}
const REPO = path.resolve(arg('--repo', path.resolve(__dirname, '..', '..')))
const OUT = path.resolve(arg('--out', path.resolve(__dirname, '..', 'cluster-topology.json')))
const GITOPS = path.join(REPO, 'openbank-infra', 'gitops')
// Every repo path this generator reads. The provenance stamp is the commit time of the
// newest of them (issue #2621) — NOT the wall clock, which made every regeneration a
// guaranteed merge conflict. Keep this list in step with what the walkers below open.
// The generator script itself is deliberately NOT an input: including it makes the
// artifact depend on the very commit that regenerates it, so the stamp could never be
// settled before committing. A logic change shows up in the derived content instead.
const INPUTS = [
  'openbank-infra/gitops',
  'openbank-ledger-service/Dockerfile',
  'openbank-account-service/Dockerfile',
  'openbank-party-service/Dockerfile',
]

// Fingerprint the derived facts, not every byte under GitOps. Normal deploys rotate
// image pins in the same manifests we scan for kinds; those pins do not change any
// claim in this dossier and must not make its committed fallback appear stale.
function inputFingerprint(facts) {
  return `sha256:${createHash('sha256').update(JSON.stringify(facts)).digest('hex')}`
}

function isShallowCheckout() {
  try {
    return execFileSync('git', ['-C', REPO, 'rev-parse', '--is-shallow-repository'],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim() === 'true'
  } catch {
    return false
  }
}

const read = (p) => { try { return readFileSync(p, 'utf8') } catch { return null } }

// ── derived: namespace set (ArgoCD app destinations) ──────────────────────────────────────────
function declaredNamespaces() {
  const dir = path.join(GITOPS, 'apps')
  const set = new Set()
  if (existsSync(dir)) {
    for (const f of readdirSync(dir).filter((n) => n.endsWith('.yaml'))) {
      const txt = read(path.join(dir, f)) || ''
      for (const m of txt.matchAll(/namespace:\s*([a-z][a-z0-9-]+)/g)) set.add(m[1])
    }
  }
  // exclude managed-platform namespaces we don't present as OpenBank domains
  for (const n of ['argocd', 'kube-system', 'kube-public', 'kube-node-lease']) set.delete(n)
  return [...set].sort()
}

// ── derived: count manifests of a kind across gitops ──────────────────────────────────────────
function countKind(kind) {
  let n = 0
  const walk = (d) => {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name)
      if (e.isDirectory()) walk(p)
      else if (e.name.endsWith('.yaml') || e.name.endsWith('.yml')) {
        const txt = read(p) || ''
        n += (txt.match(new RegExp(`kind:\\s*${kind}\\b`, 'g')) || []).length
      }
    }
  }
  if (existsSync(GITOPS)) walk(GITOPS)
  return n
}

// ── derived: image anatomy from a representative Dockerfile ────────────────────────────────────
function imageFacts() {
  const candidates = ['openbank-ledger-service', 'openbank-account-service', 'openbank-party-service']
  let df = null
  for (const c of candidates) { df = read(path.join(REPO, c, 'Dockerfile')); if (df) break }
  if (!df) return { ok: false }
  const froms = [...df.matchAll(/FROM\s+([^\s]+)(?:\s+AS\s+(\w+))?/g)].map((m) => ({ image: m[1], stage: m[2] || null }))
  const buildBase = (froms.find((f) => f.stage === 'build') || froms[0] || {}).image
  const runtimeBase = (froms[froms.length - 1] || {}).image
  const nonRoot = /^\s*USER\s+(?!root)\w+/m.test(df) || /adduser/.test(df)
  const fastJar = /quarkus-app/.test(df)
  const zgc = /UseZGC/.test(df)
  return { ok: true, multiStage: froms.length > 1, buildBase, runtimeBase, nonRoot, fastJar, zgc }
}

// ── derived: pod securityContext from a Deployment manifest ────────────────────────────────────
function podSecurity() {
  const f = path.join(GITOPS, 'components', 'agent', 'agent-service.yaml')
  const txt = read(f) || ''
  return {
    runAsNonRoot: /runAsNonRoot:\s*true/.test(txt),
    seccomp: /type:\s*RuntimeDefault/.test(txt),
    fsGroup: /fsGroup:/.test(txt),
    readOnlyRootFs: /readOnlyRootFilesystem:\s*true/.test(txt),
  }
}

// ── curated: per-namespace role + group (declared facts) ──────────────────────────────────────
const GROUPS = [
  { id: 'domain', label: 'Byznys domény', labelEn: 'Business domains', color: '#326CE5', icon: 'bank',
    blurb: 'Mapa přiřazuje bankovním doménám namespace pro správu prostředků — ne automaticky izolovanou síť.',
    blurbEn: 'The map assigns each banking domain a namespace for organizing resources, like a separate floor of a building; network isolation must be verified separately.' },
  { id: 'identity', label: 'Identita & tajemství', labelEn: 'Identity & secrets', color: '#8b5cf6', icon: 'lock',
    blurb: 'Kdo se přihlásí a kde leží klíče: Keycloak, OpenBao, secrety, certifikáty.',
    blurbEn: 'Who signs in and where keys live: Keycloak, OpenBao, secrets and certificates.' },
  { id: 'backbone', label: 'Páteř', labelEn: 'Backbone', color: '#0ea5e9', icon: 'network',
    blurb: 'Co spojuje vše dohromady: fronta zpráv, vstupní brána, observabilita, GitOps.',
    blurbEn: 'The shared backbone: event messaging, ingress, observability and GitOps.' },
  { id: 'platform', label: 'Platforma & CI', labelEn: 'Platform & CI', color: '#10b981', icon: 'cpu',
    blurb: 'Provozní mozek a továrna: AI agent, runnery, autoscaling, admission policy, skenery.',
    blurbEn: 'Operations and delivery: AI agent, CI runners, autoscaling, admission policies and scanners.' },
]
const NS_MAP = {
  // domain
  accounts: ['domain', 'Účty zákazníků', 'Customer accounts'],
  balances: ['domain', 'Zůstatky (projekce ledgeru)', 'Balances (ledger projection)'],
  ledger: ['domain', 'Hlavní kniha (double-entry GL)', 'General ledger (double-entry)'],
  payments: ['domain', 'Platby (SEPA/domácí/SCT Inst)', 'Payments (SEPA, domestic and instant)'],
  fx: ['domain', 'Směnárna / FX kurzy', 'Foreign exchange rates'],
  interest: ['domain', 'Úroky a kapitalizace', 'Interest and capitalization'],
  aml: ['domain', 'AML — praní špinavých peněz', 'Anti-money-laundering checks'],
  sanctions: ['domain', 'Sankční screening', 'Sanctions screening'],
  kyc: ['domain', 'KYC — poznej svého klienta', 'Customer identity verification (KYC)'],
  dispute: ['domain', 'Spory a chargebacky', 'Disputes and chargebacks'],
  consent: ['domain', 'Souhlasy (PSD2)', 'PSD2 consents'],
  onboarding: ['domain', 'Onboarding zákazníků', 'Customer onboarding'],
  party: ['domain', 'Strany / klientská identita', 'Parties and customer identity'],
  statements: ['domain', 'Výpisy', 'Account statements'],
  audit: ['domain', 'Auditní záznamy', 'Audit records'],
  sca: ['domain', 'Silné ověření (SCA)', 'Strong customer authentication (SCA)'],
  notifications: ['domain', 'Notifikace', 'Notifications'],
  'customer-edge': ['domain', 'Edge pro mobilní app', 'Mobile app gateway'],
  'open-banking': ['domain', 'Open Banking / TPP', 'Open Banking / TPP'],
  communication: ['domain', 'Komunikační studio a šablony', 'Communication Studio and templates'],
  context: ['domain', 'Bankovní souvislosti pro vyšetřování', 'Banking context for investigations'],
  engagement: ['domain', 'Interakce uživatelů v aplikaci', 'In-app engagement events'],
  incentive: ['domain', 'Motivační pobídky', 'Incentive offers'],
  kyb: ['domain', 'Ověřování firemních klientů (KYB)', 'Business customer verification (KYB)'],
  referral: ['domain', 'Doporučení klientů (MGM)', 'Customer referrals (MGM)'],
  wealth: ['domain', 'Deklarovaný majetek klientů', 'Declared customer assets'],
  // identity & secrets
  iam: ['identity', 'Keycloak — IAM / OIDC', 'Keycloak — IAM / OIDC'],
  identity: ['identity', 'Identita', 'Identity'],
  vault: ['identity', 'OpenBao — trezor tajemství', 'OpenBao secrets vault'],
  'external-secrets': ['identity', 'External Secrets Operator (OpenBao→k8s)', 'External Secrets Operator (OpenBao to Kubernetes)'],
  'cert-manager': ['identity', 'cert-manager — TLS certifikáty', 'cert-manager — TLS certificates'],
  // backbone
  messaging: ['backbone', 'Apache Kafka — sběrnice událostí', 'Apache Kafka event bus'],
  'ingress-nginx': ['backbone', 'Vstupní brána (ingress)', 'Ingress gateway'],
  observability: ['backbone', 'Prometheus / Grafana / Loki / Tempo', 'Prometheus / Grafana / Loki / Tempo'],
  'external-dns': ['backbone', 'External DNS', 'External DNS'],
  // platform & CI
  platform: ['platform', 'AI agent + řídicí plocha', 'AI agent and control plane'],
  kyverno: ['platform', 'Kyverno — admission policy & podpisy', 'Kyverno admission policies and signatures'],
  keda: ['platform', 'KEDA — autoscaling', 'KEDA autoscaling'],
  'arc-runners': ['platform', 'CI runnery (GitHub Actions)', 'CI runners (GitHub Actions)'],
  'arc-systems': ['platform', 'CI runner controller', 'CI runner controller'],
  'security-scanner': ['platform', 'Bezpečnostní skener', 'Security scanner'],
  'gradle-build-cache': ['platform', 'Gradle build cache', 'Gradle build cache'],
  'registry-cache': ['platform', 'Cache image registru', 'Container registry cache'],
  'cnpg-system': ['platform', 'CloudNativePG operátor', 'CloudNativePG operator'],
  'admin-ui': ['platform', 'Admin portál (tato aplikace)', 'Admin portal (this application)'],
}

function buildNamespaces() {
  const declared = declaredNamespaces()
  // include curated ns even if an app destination didn't capture them, but mark derived ones
  const names = new Set([...declared, ...Object.keys(NS_MAP)])
  return [...names].sort().map((name) => {
    const [group, role, roleEn] = NS_MAP[name] || ['domain', name, name]
    return { name, group, role, roleEn, declared: declared.includes(name) }
  })
}

// ── assemble ──────────────────────────────────────────────────────────────────────────────────
const ns = buildNamespaces()
const counts = {
  namespaces: ns.length,
  networkPolicies: countKind('NetworkPolicy'),
  externalSecrets: countKind('ExternalSecret'),
  clusterPolicies: countKind('ClusterPolicy'),
}
const img = imageFacts()
const pod = podSecurity()

const securityLayers = [
  { id: 'edge', label: 'Edge', icon: 'globe', status: 'partial',
    analogy: 'Ostraha a turniket u vchodu do banky.',
    summary: 'CloudFront + WAF + TLS na hranici, než provoz vůbec dorazí do clusteru.',
    controls: ['CloudFront/WAF', 'TLS (ACM/cert-manager)', 'ingress-nginx'], adr: ['0027'], detailRoute: '/docs/cloud-architecture' },
  { id: 'network', label: 'Síť (segmentace)', icon: 'network', status: counts.networkPolicies > 0 ? 'partial' : 'planned',
    analogy: 'Zamčené dveře mezi patry — bez propustky se mezi odděleními neprojde.',
    summary: `Zero-trust cíl je deny-by-default mezi namespaci. V GitOpsu je deklarováno ${counts.networkPolicies} NetworkPolicy; z tohoto počtu nelze určit pokrytí namespaců ani účinnou izolaci provozu.`,
    controls: [`NetworkPolicy manifesty (${counts.networkPolicies})`, 'per-namespace deny-by-default (cíl)'], adr: ['0081'] },
  { id: 'identity', label: 'Identita & autorizace', icon: 'lock', status: 'live',
    analogy: 'Občanka a oprávnění — každý ukáže, kdo je a co smí.',
    summary: 'Keycloak OIDC pro lidi i služby; OPA policy gate pro AI agenta a (cíl) REST.',
    controls: ['Keycloak OIDC', 'OPA policy gate', 'role-based guards'], adr: ['0018', '0034', '0080'] },
  { id: 'pod', label: 'Pod hardening', icon: 'shield', status: pod.runAsNonRoot && pod.seccomp ? 'partial' : 'planned',
    analogy: 'Každý zaměstnanec pracuje v rukavicích a bez klíčů od trezoru.',
    summary: `runAsNonRoot ${pod.runAsNonRoot ? '✓' : '✗'}, seccomp RuntimeDefault ${pod.seccomp ? '✓' : '✗'}, read-only FS ${pod.readOnlyRootFs ? '✓' : 'jen místy'}.`,
    controls: ['runAsNonRoot', 'seccomp RuntimeDefault', 'drop ALL caps (cíl)', 'read-only rootfs (cíl)'], adr: ['0081'] },
  { id: 'image', label: 'Image (dodavatelský řetězec)', icon: 'box', status: 'partial',
    analogy: 'Zapečetěná, zarentgenovaná a podepsaná zásilka — víš, co je uvnitř a že s ní nikdo nehnul.',
    summary: 'Multi-stage non-root image + CycloneDX SBOM + Cosign podpis (KMS). Kyverno ověřuje, zatím v režimu Audit (ne Enforce).',
    controls: ['multi-stage JRE-only', 'non-root', 'CycloneDX SBOM', 'Cosign podpis', 'kyverno verify (Audit)'], adr: ['0029', '0030'], detailRoute: '#image' },
  { id: 'secrets', label: 'Tajemství', icon: 'key', status: 'live',
    analogy: 'Trezor s časovým zámkem — hesla nikdy neleží v kódu.',
    summary: `OpenBao drží tajemství; ${counts.externalSecrets} ExternalSecret je sype do k8s. Žádný secret v gitu.`,
    controls: ['OpenBao', `External Secrets (${counts.externalSecrets})`, 'KMS unseal'], adr: ['0007', '0017'] },
]

const imageAnatomy = {
  multiStage: img.ok ? img.multiStage : null,
  buildBase: img.ok ? img.buildBase : null,
  runtimeBase: img.ok ? img.runtimeBase : null,
  steps: [
    { id: 'build', label: 'Build stage (JDK)', status: img.ok ? 'live' : 'partial', adr: [],
      detail: `Plný JDK (${img.ok ? img.buildBase : 'nezjištěno / neparsováno'}) zkompiluje a sestaví aplikaci. Tento stage se NEDISTRIBUUJE — zůstává v něm jen nářadí.` },
    { id: 'runtime', label: 'Runtime stage (JRE-only)', status: img.ok ? 'live' : 'partial', adr: [],
      detail: `Distribuuje se jen štíhlý JRE (${img.ok ? img.runtimeBase : 'nezjištěno / neparsováno'}) + aplikace. Menší image = menší útočná plocha, žádný kompilátor/shell nářadí navíc.` },
    { id: 'nonroot', label: 'Non-root uživatel', status: img.ok && img.nonRoot ? 'live' : 'partial', adr: ['0081'],
      detail: 'Proces běží jako `openbank` (ne root). I kdyby útočník unikl z aplikace, nemá v kontejneru práva roota.' },
    { id: 'fastjar', label: 'Quarkus fast-jar vrstvy', status: img.ok && img.fastJar ? 'live' : 'partial', adr: [],
      detail: 'Závislosti (lib/) jsou ve vlastní vrstvě oddělené od kódu aplikace — rychlejší rebuild, lepší cache, čitelný obsah.' },
    { id: 'podsec', label: 'Pod securityContext', status: pod.runAsNonRoot && pod.seccomp ? 'live' : 'partial', adr: ['0081'],
      detail: `Kubernetes navíc vynucuje: runAsNonRoot ${pod.runAsNonRoot ? '✓' : '✗'}, seccomp RuntimeDefault ${pod.seccomp ? '✓' : '✗'}, fsGroup ${pod.fsGroup ? '✓' : '✗'}. Read-only root FS a drop-ALL-caps jsou cíl.` },
    { id: 'sbom', label: 'CycloneDX SBOM', status: 'live', adr: ['0029'],
      detail: 'Ke každému image se generuje seznam materiálu (Software Bill of Materials) — přesně víme, jaké knihovny a verze jsou uvnitř, pro skenování zranitelností.' },
    { id: 'sign', label: 'Cosign podpis (KMS)', status: 'live', adr: ['0029', '0030'],
      detail: 'Build pipeline podepíše každý image klíčem v AWS KMS. Ověřitelné: `cosign verify`. Tamper-evidence — podepsaný digest nelze podvrhnout.' },
    { id: 'verify', label: 'Kyverno admission verify', status: 'partial', adr: ['0030'],
      detail: 'Kyverno při nasazení ověřuje podpis proti veřejnému klíči. Zatím v režimu Audit (reportuje); flip na Enforce (blokovat nepodepsané) až po pokrytí celé flotily.' },
  ],
}

const planVsReality = [
  { item: 'Namespace segmentace', plan: 'Doménová izolace, 1 ns / doména', reality: `${counts.namespaces} namespaců`, status: 'live' },
  { item: 'NetworkPolicy (east-west)', plan: 'deny-by-default v každém ns', reality: `${counts.networkPolicies} NetworkPolicy deklarovaných v GitOpsu; pokrytí namespaců a runtime účinnost neověřeny`, status: counts.networkPolicies > 0 ? 'partial' : 'planned' },
  { item: 'Podpis image', plan: 'Enforce — blokovat nepodepsané', reality: 'Cosign podpis zapojen, kyverno zatím Audit', status: 'partial' },
  { item: 'Tajemství', plan: 'OpenBao + ESO, nic v gitu', reality: `${counts.externalSecrets} ExternalSecret`, status: 'live' },
  { item: 'Pod hardening', plan: 'non-root + seccomp + read-only FS + drop caps', reality: `non-root ${pod.runAsNonRoot ? '✓' : '✗'}, seccomp ${pod.seccomp ? '✓' : '✗'}, read-only/caps jen místy`, status: 'partial' },
  { item: 'Admission policy', plan: 'sada kyverno policies', reality: `${counts.clusterPolicies} ClusterPolicy (image-verify)`, status: 'partial' },
]

const out = {
  schema: 'openbank.cluster-topology/v1',
  source: 'derived (GitOps apps + manifests + a representative Dockerfile + Deployment securityContext) — ADR-0081',
  // Commit time of the newest input, not the clock — see scripts/lib/source-date.mjs (#2621).
  generatedAt: sourceDate(REPO, INPUTS),
  inputFingerprint: inputFingerprint({ ns, counts, img, pod }),
  counts,
  groups: GROUPS,
  namespaces: ns,
  securityLayers,
  imageAnatomy,
  planVsReality,
}

const rendered = JSON.stringify(out, null, 2)
if (process.argv.includes('--check')) {
  const committed = read(OUT)
  let expected = rendered
  if (committed && !process.env.SOURCE_DATE_EPOCH) {
    try {
      const existing = JSON.parse(committed)
      // A full-history build may see a newer image-pin commit than the snapshot.
      // Preserve the committed source date only when the derived facts still agree.
      if (existing.inputFingerprint === out.inputFingerprint || isShallowCheckout()) {
        expected = JSON.stringify({ ...out, generatedAt: existing.generatedAt }, null, 2)
      }
    } catch { /* malformed committed JSON still fails the exact comparison below */ }
  }
  if (committed !== expected) {
    console.error(`[generate-cluster-topology] ${OUT} is missing or stale; regenerate it from the declared GitOps and Dockerfile inputs`)
    process.exitCode = 1
  } else {
    console.log(`[generate-cluster-topology] ${OUT} matches its declared inputs`)
  }
} else {
  writeFileSync(OUT, rendered)
  console.log(`[generate-cluster-topology] ${ns.length} namespaces, ${counts.networkPolicies} NP, ${counts.externalSecrets} ESO, image=${img.ok ? 'parsed' : 'fallback'} → ${OUT}`)
}
