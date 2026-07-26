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
    blurb: 'Každá doména banky běží ve vlastním zapečeném namespace — jako oddělené patro budovy.' },
  { id: 'identity', label: 'Identita & tajemství', labelEn: 'Identity & secrets', color: '#8b5cf6', icon: 'lock',
    blurb: 'Kdo se přihlásí a kde leží klíče: Keycloak, OpenBao, secrety, certifikáty.' },
  { id: 'backbone', label: 'Páteř', labelEn: 'Backbone', color: '#0ea5e9', icon: 'network',
    blurb: 'Co spojuje vše dohromady: fronta zpráv, vstupní brána, observabilita, GitOps.' },
  { id: 'platform', label: 'Platforma & CI', labelEn: 'Platform & CI', color: '#10b981', icon: 'cpu',
    blurb: 'Provozní mozek a továrna: AI agent, runnery, autoscaling, admission policy, skenery.' },
]
const NS_MAP = {
  // domain
  accounts: ['domain', 'Účty zákazníků'], balances: ['domain', 'Zůstatky (projekce ledgeru)'],
  ledger: ['domain', 'Hlavní kniha (double-entry GL)'], payments: ['domain', 'Platby (SEPA/domácí/SCT Inst)'],
  fx: ['domain', 'Směnárna / FX kurzy'], interest: ['domain', 'Úroky a kapitalizace'],
  aml: ['domain', 'AML — praní špinavých peněz'], sanctions: ['domain', 'Sankční screening'],
  kyc: ['domain', 'KYC — poznej svého klienta'], dispute: ['domain', 'Spory a chargebacky'],
  consent: ['domain', 'Souhlasy (PSD2)'], onboarding: ['domain', 'Onboarding zákazníků'],
  party: ['domain', 'Strany / klientská identita'], statements: ['domain', 'Výpisy'],
  audit: ['domain', 'Auditní záznamy'], sca: ['domain', 'Silné ověření (SCA)'],
  notifications: ['domain', 'Notifikace'], 'customer-edge': ['domain', 'Edge pro mobilní app'],
  'open-banking': ['domain', 'Open Banking / TPP'],
  // identity & secrets
  iam: ['identity', 'Keycloak — IAM / OIDC'], identity: ['identity', 'Identita'],
  vault: ['identity', 'OpenBao — trezor tajemství'],
  'external-secrets': ['identity', 'External Secrets Operator (OpenBao→k8s)'],
  'cert-manager': ['identity', 'cert-manager — TLS certifikáty'],
  // backbone
  messaging: ['backbone', 'Apache Kafka — sběrnice událostí'],
  'ingress-nginx': ['backbone', 'Vstupní brána (ingress)'],
  observability: ['backbone', 'Prometheus / Grafana / Loki / Tempo'],
  'external-dns': ['backbone', 'External DNS'],
  // platform & CI
  platform: ['platform', 'AI agent + řídicí plocha'],
  kyverno: ['platform', 'Kyverno — admission policy & podpisy'],
  keda: ['platform', 'KEDA — autoscaling'],
  'arc-runners': ['platform', 'CI runnery (GitHub Actions)'],
  'arc-systems': ['platform', 'CI runner controller'],
  'security-scanner': ['platform', 'Bezpečnostní skener'],
  'gradle-build-cache': ['platform', 'Gradle build cache'],
  'registry-cache': ['platform', 'Cache image registru'],
  'cnpg-system': ['platform', 'CloudNativePG operátor'],
  'admin-ui': ['platform', 'Admin portál (tato aplikace)'],
}

function buildNamespaces() {
  const declared = declaredNamespaces()
  // include curated ns even if an app destination didn't capture them, but mark derived ones
  const names = new Set([...declared, ...Object.keys(NS_MAP)])
  return [...names].sort().map((name) => {
    const [group, role] = NS_MAP[name] || ['domain', name]
    return { name, group, role, declared: declared.includes(name) }
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
const npCoverage = `${counts.networkPolicies} / ${ns.length}`

const securityLayers = [
  { id: 'edge', label: 'Edge', icon: 'globe', status: 'partial',
    analogy: 'Ostraha a turniket u vchodu do banky.',
    summary: 'CloudFront + WAF + TLS na hranici, než provoz vůbec dorazí do clusteru.',
    controls: ['CloudFront/WAF', 'TLS (ACM/cert-manager)', 'ingress-nginx'], adr: ['0027'], detailRoute: '/docs/cloud-architecture' },
  { id: 'network', label: 'Síť (segmentace)', icon: 'network', status: 'planned',
    analogy: 'Zamčené dveře mezi patry — bez propustky se mezi odděleními neprojde.',
    summary: `Zero-trust ideál je deny-by-default mezi namespaci. Realita: jen ${counts.networkPolicies} NetworkPolicy deklarované → provoz mezi namespaci je z velké části otevřený.`,
    controls: [`NetworkPolicy (${npCoverage})`, 'per-namespace deny-by-default (cíl)'], adr: ['0081'] },
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
  multiStage: img.ok ? img.multiStage : true,
  buildBase: img.ok ? img.buildBase : 'eclipse-temurin:25-jdk-alpine',
  runtimeBase: img.ok ? img.runtimeBase : 'eclipse-temurin:25-jre-alpine',
  steps: [
    { id: 'build', label: 'Build stage (JDK)', status: 'live', adr: [],
      detail: `Plný JDK (${img.ok ? img.buildBase : 'temurin:25-jdk-alpine'}) zkompiluje a sestaví aplikaci. Tento stage se NEDISTRIBUUJE — zůstává v něm jen nářadí.` },
    { id: 'runtime', label: 'Runtime stage (JRE-only)', status: 'live', adr: [],
      detail: `Distribuuje se jen štíhlý JRE (${img.ok ? img.runtimeBase : 'temurin:25-jre-alpine'}) + aplikace. Menší image = menší útočná plocha, žádný kompilátor/shell nářadí navíc.` },
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
  { item: 'NetworkPolicy (east-west)', plan: 'deny-by-default v každém ns', reality: `${npCoverage} ns má NetworkPolicy → většina provozu otevřená`, status: 'planned' },
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
  counts,
  groups: GROUPS,
  namespaces: ns,
  securityLayers,
  imageAnatomy,
  planVsReality,
}

writeFileSync(OUT, JSON.stringify(out, null, 2))
console.log(`[generate-cluster-topology] ${ns.length} namespaces, ${counts.networkPolicies} NP, ${counts.externalSecrets} ESO, image=${img.ok ? 'parsed' : 'fallback'} → ${OUT}`)
