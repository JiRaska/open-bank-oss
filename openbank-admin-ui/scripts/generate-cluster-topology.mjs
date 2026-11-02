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
import { sourceDate, preserveDerivedTimestamp } from './lib/source-date.mjs'

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
  '.github/workflows/Dockerfile.deploy',
]

// GitOps image pins change on ordinary deploys without changing any displayed fact.
// Bind freshness to the facts used by this dossier, not every input byte.
const inputFingerprint = (facts) =>
  `sha256:${createHash('sha256').update(JSON.stringify(facts)).digest('hex')}`

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
  // The ONE recipe every service image is built from. Per-service Dockerfiles were
  // declarations nothing built, and are being removed in favour of this file.
  const df = read(path.join(REPO, '.github', 'workflows', 'Dockerfile.deploy'))
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

// Every user-visible string is emitted as { cs, en } so the page can pick by language.
const L = (cs, en) => ({ cs, en })

// ── curated: per-namespace role + group (declared facts) ──────────────────────────────────────
const GROUPS = [
  { id: 'domain', label: 'Byznys domény', labelEn: 'Business domains', color: '#326CE5', icon: 'bank',
    blurb: L('Každá doména banky běží ve vlastním zapečeném namespace — jako oddělené patro budovy.',
      'Every banking domain runs in its own sealed namespace — like a separate floor of the building.') },
  { id: 'identity', label: 'Identita & tajemství', labelEn: 'Identity & secrets', color: '#8b5cf6', icon: 'lock',
    blurb: L('Kdo se přihlásí a kde leží klíče: Keycloak, OpenBao, secrety, certifikáty.',
      'Who signs in and where the keys live: Keycloak, OpenBao, secrets, certificates.') },
  { id: 'backbone', label: 'Páteř', labelEn: 'Backbone', color: '#0ea5e9', icon: 'network',
    blurb: L('Co spojuje vše dohromady: fronta zpráv, vstupní brána, observabilita, GitOps.',
      'What ties it all together: message bus, ingress gateway, observability, GitOps.') },
  { id: 'platform', label: 'Platforma & CI', labelEn: 'Platform & CI', color: '#10b981', icon: 'cpu',
    blurb: L('Provozní mozek a továrna: AI agent, runnery, autoscaling, admission policy, skenery.',
      'The operating brain and factory: AI agent, runners, autoscaling, admission policy, scanners.') },
]
const NS_MAP = {
  // domain
  accounts: ['domain', L('Účty zákazníků', 'Customer accounts')], balances: ['domain', L('Zůstatky (projekce ledgeru)', 'Balances (ledger projection)')],
  ledger: ['domain', L('Hlavní kniha (double-entry GL)', 'General ledger (double-entry GL)')], payments: ['domain', L('Platby (SEPA/domácí/SCT Inst)', 'Payments (SEPA/domestic/SCT Inst)')],
  fx: ['domain', L('Směnárna / FX kurzy', 'Exchange / FX rates')], interest: ['domain', L('Úroky a kapitalizace', 'Interest and capitalisation')],
  aml: ['domain', L('AML — praní špinavých peněz', 'AML — anti-money laundering')], sanctions: ['domain', L('Sankční screening', 'Sanctions screening')],
  kyc: ['domain', L('KYC — poznej svého klienta', 'KYC — know your customer')], dispute: ['domain', L('Spory a chargebacky', 'Disputes and chargebacks')],
  consent: ['domain', L('Souhlasy (PSD2)', 'Consents (PSD2)')], onboarding: ['domain', L('Onboarding zákazníků', 'Customer onboarding')],
  party: ['domain', L('Strany / klientská identita', 'Parties / customer identity')], statements: ['domain', L('Výpisy', 'Statements')],
  audit: ['domain', L('Auditní záznamy', 'Audit records')], sca: ['domain', L('Silné ověření (SCA)', 'Strong customer authentication (SCA)')],
  notifications: ['domain', L('Notifikace', 'Notifications')], 'customer-edge': ['domain', L('Edge pro mobilní app', 'Edge for the mobile app')],
  'open-banking': ['domain', L('Open Banking / TPP', 'Open Banking / TPP')],
  // identity & secrets
  iam: ['identity', L('Keycloak — IAM / OIDC', 'Keycloak — IAM / OIDC')], identity: ['identity', L('Identita', 'Identity')],
  vault: ['identity', L('OpenBao — trezor tajemství', 'OpenBao — secrets vault')],
  'external-secrets': ['identity', L('External Secrets Operator (OpenBao→k8s)', 'External Secrets Operator (OpenBao→k8s)')],
  'cert-manager': ['identity', L('cert-manager — TLS certifikáty', 'cert-manager — TLS certificates')],
  // backbone
  messaging: ['backbone', L('Apache Kafka — sběrnice událostí', 'Apache Kafka — event bus')],
  'ingress-nginx': ['backbone', L('Vstupní brána (ingress)', 'Ingress gateway')],
  observability: ['backbone', L('Prometheus / Grafana / Loki / Tempo', 'Prometheus / Grafana / Loki / Tempo')],
  'external-dns': ['backbone', L('External DNS', 'External DNS')],
  // platform & CI
  platform: ['platform', L('AI agent + řídicí plocha', 'AI agent + control plane')],
  kyverno: ['platform', L('Kyverno — admission policy & podpisy', 'Kyverno — admission policy & signatures')],
  keda: ['platform', L('KEDA — autoscaling', 'KEDA — autoscaling')],
  'arc-runners': ['platform', L('CI runnery (GitHub Actions)', 'CI runners (GitHub Actions)')],
  'arc-systems': ['platform', L('CI runner controller', 'CI runner controller')],
  'security-scanner': ['platform', L('Bezpečnostní skener', 'Security scanner')],
  'gradle-build-cache': ['platform', L('Gradle build cache', 'Gradle build cache')],
  'registry-cache': ['platform', L('Cache image registru', 'Image registry cache')],
  'cnpg-system': ['platform', L('CloudNativePG operátor', 'CloudNativePG operator')],
  'admin-ui': ['platform', L('Admin portál (tato aplikace)', 'Admin portal (this app)')],
}

function buildNamespaces() {
  const declared = declaredNamespaces()
  // include curated ns even if an app destination didn't capture them, but mark derived ones
  const names = new Set([...declared, ...Object.keys(NS_MAP)])
  return [...names].sort().map((name) => {
    const [group, role] = NS_MAP[name] || ['domain', L(name, name)]
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

const securityLayers = [
  { id: 'edge', label: L('Edge', 'Edge'), icon: 'globe', status: 'partial',
    analogy: L('Ostraha a turniket u vchodu do banky.', 'Security guard and turnstile at the bank entrance.'),
    summary: L('CloudFront + WAF + TLS na hranici, než provoz vůbec dorazí do clusteru.',
      'CloudFront + WAF + TLS at the perimeter, before traffic ever reaches the cluster.'),
    controls: ['CloudFront/WAF', 'TLS (ACM/cert-manager)', 'ingress-nginx'], adr: ['0027'], detailRoute: '/docs/cloud-architecture' },
  { id: 'network', label: L('Síť (segmentace)', 'Network (segmentation)'), icon: 'network', status: counts.networkPolicies > 0 ? 'partial' : 'planned',
    analogy: L('Zamčené dveře mezi patry — bez propustky se mezi odděleními neprojde.',
      'Locked doors between floors — without a pass you cannot move between departments.'),
    summary: L(`Zero-trust cíl je deny-by-default mezi namespaci. V GitOpsu je deklarováno ${counts.networkPolicies} NetworkPolicy; z tohoto počtu nelze určit pokrytí namespaců ani účinnou izolaci provozu.`,
      `The zero-trust goal is deny-by-default between namespaces. GitOps declares ${counts.networkPolicies} NetworkPolicies; this count cannot establish per-namespace coverage or effective traffic isolation.`),
    controls: [`NetworkPolicy manifests (${counts.networkPolicies})`, 'per-namespace deny-by-default (target)'], adr: ['0081'] },
  { id: 'identity', label: L('Identita & autorizace', 'Identity & authorization'), icon: 'lock', status: 'live',
    analogy: L('Občanka a oprávnění — každý ukáže, kdo je a co smí.', 'ID card and permissions — everyone shows who they are and what they may do.'),
    summary: L('Keycloak OIDC pro lidi i služby; OPA policy gate pro AI agenta a (cíl) REST.',
      'Keycloak OIDC for people and services; OPA policy gate for the AI agent and (target) REST.'),
    controls: ['Keycloak OIDC', 'OPA policy gate', 'role-based guards'], adr: ['0018', '0034', '0080'] },
  { id: 'pod', label: L('Pod hardening', 'Pod hardening'), icon: 'shield', status: pod.runAsNonRoot && pod.seccomp ? 'partial' : 'planned',
    analogy: L('Každý zaměstnanec pracuje v rukavicích a bez klíčů od trezoru.', 'Every employee works in gloves and without the vault keys.'),
    summary: L(`runAsNonRoot ${pod.runAsNonRoot ? '✓' : '✗'}, seccomp RuntimeDefault ${pod.seccomp ? '✓' : '✗'}, read-only FS ${pod.readOnlyRootFs ? '✓' : 'jen místy'}.`,
      `runAsNonRoot ${pod.runAsNonRoot ? '✓' : '✗'}, seccomp RuntimeDefault ${pod.seccomp ? '✓' : '✗'}, read-only FS ${pod.readOnlyRootFs ? '✓' : 'only in places'}.`),
    controls: ['runAsNonRoot', 'seccomp RuntimeDefault', 'drop ALL caps (cíl)', 'read-only rootfs (cíl)'], adr: ['0081'] },
  { id: 'image', label: L('Image (dodavatelský řetězec)', 'Image (supply chain)'), icon: 'box', status: 'partial',
    analogy: L('Zapečetěná, zarentgenovaná a podepsaná zásilka — víš, co je uvnitř a že s ní nikdo nehnul.',
      'A sealed, X-rayed and signed shipment — you know what is inside and that nobody tampered with it.'),
    summary: L('Multi-stage non-root image + CycloneDX SBOM + Cosign podpis (KMS). Kyverno ověřuje, zatím v režimu Audit (ne Enforce).',
      'Multi-stage non-root image + CycloneDX SBOM + Cosign signature (KMS). Kyverno verifies, for now in Audit mode (not Enforce).'),
    controls: ['multi-stage JRE-only', 'non-root', 'CycloneDX SBOM', 'Cosign signature', 'kyverno verify (Audit)'], adr: ['0029', '0030'], detailRoute: '#image' },
  { id: 'secrets', label: L('Tajemství', 'Secrets'), icon: 'key', status: 'live',
    analogy: L('Trezor s časovým zámkem — hesla nikdy neleží v kódu.', 'A time-locked vault — passwords never sit in the code.'),
    summary: L(`OpenBao drží tajemství; ${counts.externalSecrets} ExternalSecret je sype do k8s. Žádný secret v gitu.`,
      `OpenBao holds the secrets; ${counts.externalSecrets} ExternalSecrets feed them into k8s. No secret in git.`),
    controls: ['OpenBao', `External Secrets (${counts.externalSecrets})`, 'KMS unseal'], adr: ['0007', '0017'] },
]

const imageAnatomy = {
  multiStage: img.ok ? img.multiStage : null,
  buildBase: img.ok ? img.buildBase : null,
  runtimeBase: img.ok ? img.runtimeBase : null,
  steps: [
    { id: 'build', label: L('Build stage (JDK)', 'Build stage (JDK)'), status: img.ok ? 'live' : 'partial', adr: [],
      detail: L(`Plný JDK (${img.ok ? img.buildBase : 'nezjištěno / neparsováno'}) zkompiluje a sestaví aplikaci. Tento stage se NEDISTRIBUUJE — zůstává v něm jen nářadí.`,
        `The full JDK (${img.ok ? img.buildBase : 'not detected / not parsed'}) compiles and assembles the application. This stage is NOT DISTRIBUTED — only the tooling stays in it.`) },
    { id: 'runtime', label: L('Runtime stage (JRE-only)', 'Runtime stage (JRE-only)'), status: img.ok ? 'live' : 'partial', adr: [],
      detail: L(`Distribuuje se jen štíhlý JRE (${img.ok ? img.runtimeBase : 'nezjištěno / neparsováno'}) + aplikace. Menší image = menší útočná plocha, žádný kompilátor/shell nářadí navíc.`,
        `Only a slim JRE (${img.ok ? img.runtimeBase : 'not detected / not parsed'}) + the application are distributed. Smaller image = smaller attack surface, no extra compiler/shell tooling.`) },
    { id: 'nonroot', label: L('Non-root uživatel', 'Non-root user'), status: img.ok && img.nonRoot ? 'live' : 'partial', adr: ['0081'],
      detail: L('Proces běží jako `openbank` (ne root). I kdyby útočník unikl z aplikace, nemá v kontejneru práva roota.',
        'The process runs as `openbank` (not root). Even if an attacker escaped the application, they have no root rights in the container.') },
    { id: 'fastjar', label: L('Quarkus fast-jar vrstvy', 'Quarkus fast-jar layers'), status: img.ok && img.fastJar ? 'live' : 'partial', adr: [],
      detail: L('Závislosti (lib/) jsou ve vlastní vrstvě oddělené od kódu aplikace — rychlejší rebuild, lepší cache, čitelný obsah.',
        'Dependencies (lib/) sit in their own layer separate from application code — faster rebuilds, better caching, readable contents.') },
    { id: 'podsec', label: L('Pod securityContext', 'Pod securityContext'), status: pod.runAsNonRoot && pod.seccomp ? 'live' : 'partial', adr: ['0081'],
      detail: L(`Kubernetes navíc vynucuje: runAsNonRoot ${pod.runAsNonRoot ? '✓' : '✗'}, seccomp RuntimeDefault ${pod.seccomp ? '✓' : '✗'}, fsGroup ${pod.fsGroup ? '✓' : '✗'}. Read-only root FS a drop-ALL-caps jsou cíl.`,
        `Kubernetes additionally enforces: runAsNonRoot ${pod.runAsNonRoot ? '✓' : '✗'}, seccomp RuntimeDefault ${pod.seccomp ? '✓' : '✗'}, fsGroup ${pod.fsGroup ? '✓' : '✗'}. Read-only root FS and drop-ALL-caps are the target.`) },
    { id: 'sbom', label: L('CycloneDX SBOM', 'CycloneDX SBOM'), status: 'live', adr: ['0029'],
      detail: L('Ke každému image se generuje seznam materiálu (Software Bill of Materials) — přesně víme, jaké knihovny a verze jsou uvnitř, pro skenování zranitelností.',
        'Every image gets a Software Bill of Materials — we know exactly which libraries and versions are inside, for vulnerability scanning.') },
    { id: 'sign', label: L('Cosign podpis (KMS)', 'Cosign signature (KMS)'), status: 'live', adr: ['0029', '0030'],
      detail: L('Build pipeline podepíše každý image klíčem v AWS KMS. Ověřitelné: `cosign verify`. Tamper-evidence — podepsaný digest nelze podvrhnout.',
        'The build pipeline signs every image with a key in AWS KMS. Verifiable: `cosign verify`. Tamper evidence — a signed digest cannot be forged.') },
    { id: 'verify', label: L('Kyverno admission verify', 'Kyverno admission verify'), status: 'partial', adr: ['0030'],
      detail: L('Kyverno při nasazení ověřuje podpis proti veřejnému klíči. Zatím v režimu Audit (reportuje); flip na Enforce (blokovat nepodepsané) až po pokrytí celé flotily.',
        'At deploy time Kyverno verifies the signature against the public key. For now in Audit mode (reports); the flip to Enforce (block unsigned) comes once the whole fleet is covered.') },
  ],
}

const planVsReality = [
  { item: L('Namespace segmentace', 'Namespace segmentation'), plan: L('Doménová izolace, 1 ns / doména', 'Domain isolation, 1 ns / domain'), reality: L(`${counts.namespaces} namespaců`, `${counts.namespaces} namespaces`), status: 'live' },
  { item: L('NetworkPolicy (east-west)', 'NetworkPolicy (east-west)'), plan: L('deny-by-default v každém ns', 'deny-by-default in every ns'), reality: L(`${counts.networkPolicies} NetworkPolicy deklarovaných v GitOpsu; pokrytí namespaců a runtime účinnost neověřeny`, `${counts.networkPolicies} NetworkPolicies declared in GitOps; namespace coverage and runtime effectiveness unverified`), status: counts.networkPolicies > 0 ? 'partial' : 'planned' },
  { item: L('Podpis image', 'Image signing'), plan: L('Enforce — blokovat nepodepsané', 'Enforce — block unsigned'), reality: L('Cosign podpis zapojen, kyverno zatím Audit', 'Cosign signing wired, kyverno still Audit'), status: 'partial' },
  { item: L('Tajemství', 'Secrets'), plan: L('OpenBao + ESO, nic v gitu', 'OpenBao + ESO, nothing in git'), reality: L(`${counts.externalSecrets} ExternalSecret`, `${counts.externalSecrets} ExternalSecrets`), status: 'live' },
  { item: L('Pod hardening', 'Pod hardening'), plan: L('non-root + seccomp + read-only FS + drop caps', 'non-root + seccomp + read-only FS + drop caps'), reality: L(`non-root ${pod.runAsNonRoot ? '✓' : '✗'}, seccomp ${pod.seccomp ? '✓' : '✗'}, read-only/caps jen místy`, `non-root ${pod.runAsNonRoot ? '✓' : '✗'}, seccomp ${pod.seccomp ? '✓' : '✗'}, read-only/caps only in places`), status: 'partial' },
  { item: L('Admission policy', 'Admission policy'), plan: L('sada kyverno policies', 'a set of kyverno policies'), reality: L(`${counts.clusterPolicies} ClusterPolicy deklarovaných v GitOpsu; runtime stav neověřen`, `${counts.clusterPolicies} ClusterPolicies declared in GitOps; runtime state unverified`), status: counts.clusterPolicies > 0 ? 'partial' : 'planned' },
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

const stable = preserveDerivedTimestamp(OUT, out, 'generatedAt')
const rendered = JSON.stringify(stable, null, 2)
if (process.argv.includes('--check')) {
  const committed = read(OUT)
  let expected = rendered
  if (committed && !process.env.SOURCE_DATE_EPOCH) {
    try {
      const existing = JSON.parse(committed)
      if (existing.inputFingerprint === out.inputFingerprint) {
        expected = JSON.stringify({ ...out, generatedAt: existing.generatedAt }, null, 2)
      }
    } catch { /* Malformed JSON fails the exact comparison. */ }
  }
  if (committed !== expected) {
    console.error('[generate-cluster-topology] committed snapshot is missing or stale; regenerate from GitOps and Dockerfile inputs')
    process.exitCode = 1
  } else {
    console.log('[generate-cluster-topology] committed snapshot matches its declared inputs')
  }
} else {
  writeFileSync(OUT, rendered)
  console.log(`[generate-cluster-topology] ${ns.length} namespaces, ${counts.networkPolicies} NP, ${counts.externalSecrets} ESO, image=${img.ok ? 'parsed' : 'fallback'} → ${OUT}`)
}
