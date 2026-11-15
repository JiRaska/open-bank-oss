// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Keys & Certificates catalogue (#16292): WHAT the platform holds and WHY, written for every
 * department. This file is descriptive only. It carries no key material, no Secret name that
 * could be used to fetch one, and no expiry date: expiry is always MEASURED at request time
 * (route.ts, from operator metrics), never asserted here, because a date written into source
 * is a claim with a shelf life.
 *
 * Every entry names the place in the repo that defines it, so the catalogue can be checked
 * against the tree (keys-catalog.test.ts asserts each `definedIn` path exists).
 */

export type KeyKind = 'root-ca' | 'issuing-ca' | 'tls-leaf' | 'signing-key' | 'encryption-key' | 'credential'

/** How the expiry of an entry can be known. */
export type ExpirySource =
  /** cert-manager Certificate(s), matched on the metric's labels. */
  | { type: 'cert-manager'; namespace?: string; name?: string; issuer?: string; excludeNames?: string[] }
  /** Strimzi cluster or clients CA. */
  | { type: 'strimzi'; ca: 'cluster' | 'clients' }
  /** Issued for minutes at a time on demand; nothing long-lived to expire. */
  | { type: 'short-lived'; ttl: string }
  /** A key with no expiry by design (KMS, transit, HMAC). Rotation, not expiry, is the control. */
  | { type: 'no-expiry'; rotation: string }
  /** Exists, but nothing the platform runs can observe its expiry today. */
  | { type: 'untracked'; reason: string }

/**
 * Exposure to a cryptographically relevant quantum computer.
 * - `key-exchange`: harvest-now-decrypt-later; traffic recorded today is readable later.
 * - `signature`: forgeable once a quantum computer exists, but past signatures stay valid
 *   evidence only if re-sealed; urgency depends on how long the signature must hold.
 * - `symmetric`: AES/HMAC with >=256-bit keys; Grover halves strength, still adequate.
 */
export type QuantumExposure = 'key-exchange' | 'signature' | 'symmetric'

/** How hard it is to replace the algorithm or the key. */
export type Agility = 'automatic' | 'routine' | 'ceremony'

export interface KeyEntry {
  id: string
  kind: KeyKind
  nameCs: string
  nameEn: string
  /** What it is for, in plain language. */
  purposeCs: string
  purposeEn: string
  /** What breaks for the bank and its customers if it expires or leaks. */
  impactCs: string
  impactEn: string
  /** Team accountable for rotation. */
  owner: string
  /** Public algorithm descriptor, as declared in the defining manifest. */
  algorithm: string
  quantum: QuantumExposure
  agility: Agility
  /** Parent in the trust tree (the CA that signs it), when there is one. */
  parent?: string
  expiry: ExpirySource
  definedIn: string
  runbook?: string
}

const CEREMONIES = 'docs/runbooks/key-ceremonies'

export const KEY_CATALOG: readonly KeyEntry[] = [
  {
    id: 'root-ca',
    kind: 'root-ca',
    nameCs: 'Kořenová certifikační autorita',
    nameEn: 'Root certificate authority',
    purposeCs: 'Kotva důvěry pro veškerou komunikaci mezi službami uvnitř banky. Kdo jí věří, věří všem certifikátům, které podepsala.',
    purposeEn: 'Trust anchor for all service-to-service traffic inside the bank. Whoever trusts it trusts every certificate it signed.',
    impactCs: 'Po expiraci přestanou platit všechny interní certifikáty naráz: služby spolu přestanou mluvit, platby, účty i přihlášení se zastaví.',
    impactEn: 'On expiry every internal certificate stops validating at once: services stop talking to each other, payments, accounts and sign-in halt.',
    owner: 'platform',
    algorithm: 'ECDSA P-256',
    quantum: 'signature',
    agility: 'ceremony',
    expiry: { type: 'cert-manager', namespace: 'cert-manager', name: 'openbank-sandbox-ca' },
    definedIn: 'openbank-infra/gitops/components/platform/clusterissuer.yaml',
    runbook: `${CEREMONIES}/cert-manager-pki-recovery.md`,
  },
  {
    id: 'internal-mtls',
    kind: 'tls-leaf',
    nameCs: 'Interní certifikáty služeb (mTLS)',
    nameEn: 'Internal service certificates (mTLS)',
    purposeCs: 'Každá služba jimi prokazuje svou identitu ostatním a šifruje provoz mezi nimi.',
    purposeEn: 'Each service proves its identity to the others with these and encrypts the traffic between them.',
    impactCs: 'Vypršení jednoho certifikátu odřízne danou službu od jejích volajících. Obnovují se samy 15 dní předem; alert znamená, že obnova selhává.',
    impactEn: 'One expired certificate cuts that service off from its callers. They renew themselves 15 days ahead; an alert means renewal is failing.',
    owner: 'owning service team',
    algorithm: 'ECDSA P-256',
    quantum: 'key-exchange',
    agility: 'automatic',
    parent: 'root-ca',
    expiry: { type: 'cert-manager', issuer: 'openbank-ca', excludeNames: ['openbao-tls'] },
    definedIn: 'openbank-infra/gitops/components/accounts/account-service-internal-tls.yaml',
    runbook: `${CEREMONIES}/certificate-expiry.md`,
  },
  {
    id: 'openbao-tls',
    kind: 'tls-leaf',
    nameCs: 'Certifikát trezoru tajemství (OpenBao)',
    nameEn: 'Secret vault certificate (OpenBao)',
    purposeCs: 'Chrání spojení k trezoru, ze kterého si služby berou hesla k databázím a další tajemství.',
    purposeEn: 'Protects connections to the vault services fetch database passwords and other secrets from.',
    impactCs: 'Trezor načítá certifikát jen při startu. Obnovený certifikát se projeví až po restartu, jinak vyprší starý a služby nedostanou svá tajemství.',
    impactEn: 'The vault loads its certificate only at start. A renewed certificate takes effect after a restart; otherwise the old one expires and services cannot get their secrets.',
    owner: 'platform',
    algorithm: 'ECDSA P-256',
    quantum: 'key-exchange',
    agility: 'routine',
    parent: 'root-ca',
    expiry: { type: 'cert-manager', name: 'openbao-tls' },
    definedIn: 'openbank-infra/gitops/components/openbao/openbao-tls.yaml',
    runbook: `${CEREMONIES}/certificate-expiry.md`,
  },
  {
    id: 'public-edge',
    kind: 'tls-leaf',
    nameCs: 'Veřejné webové certifikáty (Let\'s Encrypt)',
    nameEn: 'Public web certificates (Let\'s Encrypt)',
    purposeCs: 'Zelený zámeček v prohlížeči: dokazují klientům, že mluví s bankou, a šifrují jejich spojení.',
    purposeEn: 'The padlock in the browser: they prove to customers they are talking to the bank and encrypt their connection.',
    impactCs: 'Po expiraci prohlížeč i mobilní aplikace odmítnou web, API i administraci. Pro klienta to vypadá jako výpadek banky.',
    impactEn: 'On expiry browsers and the mobile app refuse the website, API and admin console. To a customer it looks like the bank is down.',
    owner: 'platform',
    algorithm: 'ECDSA P-256 / RSA 2048 (ACME)',
    quantum: 'key-exchange',
    agility: 'automatic',
    expiry: { type: 'cert-manager', issuer: 'letsencrypt-prod' },
    definedIn: 'openbank-infra/gitops/components/envoy-gateway/api-edge-tls.yaml',
    runbook: `${CEREMONIES}/certificate-expiry.md`,
  },
  {
    id: 'kafka-cluster-ca',
    kind: 'issuing-ca',
    nameCs: 'Certifikační autorita Kafky (brokery)',
    nameEn: 'Kafka cluster CA (brokers)',
    purposeCs: 'Podepisuje certifikáty serverů událostní sběrnice, přes kterou si služby předávají platby, změny účtů a notifikace.',
    purposeEn: 'Signs the certificates of the event bus servers that carry payments, account changes and notifications between services.',
    impactCs: 'Po expiraci se služby nepřipojí ke sběrnici a všechny události se zastaví.',
    impactEn: 'On expiry services cannot connect to the bus and every event flow stops.',
    owner: 'platform',
    algorithm: 'Strimzi-generated',
    quantum: 'signature',
    agility: 'automatic',
    expiry: { type: 'strimzi', ca: 'cluster' },
    definedIn: 'openbank-infra/gitops/components/kafka/kafka.yaml',
    runbook: `${CEREMONIES}/certificate-expiry.md`,
  },
  {
    id: 'kafka-clients-ca',
    kind: 'issuing-ca',
    nameCs: 'Certifikační autorita Kafky (klienti)',
    nameEn: 'Kafka clients CA',
    purposeCs: 'Podepisuje certifikát každé služby, která na sběrnici čte nebo zapisuje.',
    purposeEn: 'Signs the certificate of every service that reads from or writes to the bus.',
    impactCs: 'Po expiraci broker odmítne všechny služby naráz.',
    impactEn: 'On expiry the broker rejects every service at once.',
    owner: 'platform',
    algorithm: 'Strimzi-generated',
    quantum: 'signature',
    agility: 'automatic',
    expiry: { type: 'strimzi', ca: 'clients' },
    definedIn: 'openbank-infra/gitops/components/kafka/kafka.yaml',
    runbook: 'docs/runbooks/0008-kafka-mtls-scheme-accepted-cutover.md',
  },
  {
    id: 'pki-agent',
    kind: 'issuing-ca',
    nameCs: 'Identita AI agentů (OpenBao PKI)',
    nameEn: 'AI agent identity (OpenBao PKI)',
    purposeCs: 'Každý běh AI agenta dostane certifikát na 5 minut, kterým se prokazuje nástrojům. Ukradený certifikát je za chvíli k ničemu.',
    purposeEn: 'Every AI agent run gets a 5-minute certificate it uses to identify itself to tools. A stolen one is worthless within minutes.',
    impactCs: 'Selhání vydávání zastaví AI asistenty v administraci; bankovní provoz běží dál.',
    impactEn: 'If issuance fails, AI assistants in the console stop; banking keeps running.',
    owner: 'platform',
    algorithm: 'OpenBao PKI',
    quantum: 'signature',
    agility: 'automatic',
    expiry: { type: 'short-lived', ttl: '300s' },
    definedIn: 'openbank-infra/gitops/components/openbao/openbao-agent-identity.yaml',
    runbook: 'docs/runbooks/0007-openbao-agent-identity.md',
  },
  {
    id: 'pki-document-signing',
    kind: 'issuing-ca',
    nameCs: 'Podepisování dokumentů klientem (OpenBao PKI)',
    nameEn: 'Client document signing (OpenBao PKI)',
    purposeCs: 'Vydává krátkodobé certifikáty, kterými klient podepisuje smlouvy a dokumenty.',
    purposeEn: 'Issues short-lived certificates customers sign contracts and documents with.',
    impactCs: 'Selhání znemožní elektronický podpis dokumentů.',
    impactEn: 'Failure blocks electronic signing of documents.',
    owner: 'documents',
    algorithm: 'OpenBao PKI',
    quantum: 'signature',
    agility: 'automatic',
    expiry: { type: 'short-lived', ttl: '300s' },
    definedIn: 'openbank-infra/gitops/components/openbao/openbao-document-signing-pki.yaml',
    runbook: 'docs/runbooks/0008-openbao-document-signing-pki.md',
  },
  {
    id: 'pades-seal',
    kind: 'signing-key',
    nameCs: 'Elektronická pečeť banky (PAdES)',
    nameEn: 'Bank electronic seal (PAdES)',
    purposeCs: 'Pečetí PDF výpisy a smlouvy, aby šlo prokázat, že je vydala banka a nikdo je nezměnil.',
    purposeEn: 'Seals PDF statements and contracts so it can be proven the bank issued them and nobody altered them.',
    impactCs: 'Bez platné pečeti jsou dokumenty jako důkaz bezcenné. Expiraci dnes nic nesleduje.',
    impactEn: 'Without a valid seal documents are worthless as evidence. Nothing tracks its expiry today.',
    owner: 'documents',
    algorithm: 'PKCS#12 keystore',
    quantum: 'signature',
    agility: 'ceremony',
    expiry: { type: 'untracked', reason: 'keystore is read once at start; its notAfter is not exported' },
    definedIn: 'openbank-infra/gitops/components/document-service/es-document-service-signing-keystore.yaml',
    runbook: 'docs/runbooks/0008-openbao-document-signing-pki.md',
  },
  {
    id: 'keycloak-signing',
    kind: 'signing-key',
    nameCs: 'Podpis přihlašovacích tokenů (Keycloak)',
    nameEn: 'Sign-in token signing (Keycloak)',
    purposeCs: 'Podepisuje tokeny, kterými se klienti i zaměstnanci prokazují po přihlášení.',
    purposeEn: 'Signs the tokens customers and staff present after signing in.',
    impactCs: 'Únik klíče by umožnil vydávat se za kohokoli. Klíč nemá expiraci, chrání ho rotace.',
    impactEn: 'A leaked key would let anyone impersonate anyone. It has no expiry; rotation protects it.',
    owner: 'iam',
    algorithm: 'RS256 (Keycloak default)',
    quantum: 'signature',
    agility: 'routine',
    expiry: { type: 'no-expiry', rotation: 'manual renewal ceremony' },
    definedIn: 'openbank-infra/gitops/components/keycloak/realm-template.json',
    runbook: `${CEREMONIES}/keycloak-signing-key-renewal.md`,
  },
  {
    id: 'cosign-kms',
    kind: 'signing-key',
    nameCs: 'Podpis softwaru (cosign v AWS KMS)',
    nameEn: 'Software signing (cosign in AWS KMS)',
    purposeCs: 'Podepisuje každý kontejner, který nasadíme. Cluster pustí jen podepsaný software.',
    purposeEn: 'Signs every container we deploy. The cluster admits only signed software.',
    impactCs: 'Klíč nikdy neopustí KMS. Jeho ztráta zastaví nasazování, únik oprávnění by umožnil podvrhnout software.',
    impactEn: 'The key never leaves KMS. Losing it stops deployments; leaked permissions would allow forged software.',
    owner: 'platform',
    algorithm: 'AWS KMS asymmetric',
    quantum: 'signature',
    agility: 'ceremony',
    expiry: { type: 'no-expiry', rotation: 'KMS key rotation ceremony' },
    definedIn: '.github/scripts/check-fleet-attestations.sh',
    runbook: `${CEREMONIES}/cosign-kms-rotation.md`,
  },
  {
    id: 'card-pan-kek',
    kind: 'encryption-key',
    nameCs: 'Šifrování čísel karet (transit KEK)',
    nameEn: 'Card number encryption (transit KEK)',
    purposeCs: 'Chrání čísla platebních karet. Klíč zůstává v trezoru, služby jím jen nechávají šifrovat a dešifrovat.',
    purposeEn: 'Protects payment card numbers. The key stays in the vault; services only ask it to encrypt and decrypt.',
    impactCs: 'Bez klíče nejdou karty zpracovat; únik oprávnění by odhalil čísla karet (PCI).',
    impactEn: 'Without the key cards cannot be processed; leaked access would expose card numbers (PCI).',
    owner: 'cards',
    algorithm: 'OpenBao transit (symmetric)',
    quantum: 'symmetric',
    agility: 'routine',
    expiry: { type: 'no-expiry', rotation: 'transit key rotate' },
    definedIn: 'docs/threat-models/openbank-card-issuance-service.md',
  },
  {
    id: 'push-credentials',
    kind: 'credential',
    nameCs: 'Klíče pro push notifikace (APNs, FCM)',
    nameEn: 'Push notification keys (APNs, FCM)',
    purposeCs: 'Umožňují posílat upozornění do mobilních aplikací klientů.',
    purposeEn: 'Let the bank send alerts to customers\' mobile apps.',
    impactCs: 'Při zneplatnění klienti přestanou dostávat upozornění na platby a přihlášení.',
    impactEn: 'If revoked, customers stop receiving payment and sign-in alerts.',
    owner: 'notifications',
    algorithm: 'ES256 provider key',
    quantum: 'signature',
    agility: 'routine',
    expiry: { type: 'untracked', reason: 'static credential; tracked only by its rotation-deadline annotation' },
    definedIn: 'openbank-infra/gitops/components/notifications/apns-externalsecret.yaml',
  },
] as const

export function catalogEntry(id: string): KeyEntry | undefined {
  return KEY_CATALOG.find((e) => e.id === id)
}
