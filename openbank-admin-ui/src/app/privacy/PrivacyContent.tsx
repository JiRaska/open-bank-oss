// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

"use client"

import { ArrowLeft, Clock3, Cookie, FileCheck2, Languages, Mail, ShieldCheck, UserRoundCheck } from "lucide-react"
import { useLanguage } from "@/lib/i18n/LanguageContext"
import styles from "./privacy.module.css"

const COPY = {
  en: {
    switchLanguage: "Přepnout do češtiny", locale: "Čeština", eyebrow: "Privacy, explained",
    title: "Know what happens to your operator data.",
    intro: "OpenBank Admin uses only the identity, session and audit information needed to operate a governed banking workspace. There are no marketing or tracking cookies.",
    flowTitle: "The data journey",
    flow: [
      ["Identity", "Keycloak supplies your name, email and assigned roles so OpenBank can authenticate you and enforce role-based access."],
      ["Session", "A necessary NextAuth session cookie keeps you signed in until logout or token expiry."],
      ["Audit evidence", "Actions performed in the console create an audit trail for traceability and financial-sector obligations."],
    ],
    controllerTitle: "Who controls the data", controller: "OpenBank Foundation (in formation), operator of the demonstration banking platform on open-bank.tech.",
    purposeTitle: "Why the data is processed", purpose: "Identity and session data are required for authentication and access control. Audit records support obligations under the EBA ICT Risk Guidelines and PSD2, and the legitimate interest in traceable operations over banking data.",
    retentionTitle: "How long it is kept", retention: "The session cookie ends at logout or token expiry. Audit records are retained for the period required by financial-sector regulation.",
    rightsTitle: "Your rights", rights: "You can request access, correction or deletion where deletion does not conflict with regulatory retention duties. You may also lodge a complaint with the Czech Office for Personal Data Protection.",
    contactTitle: "Contacts", controllerContact: "Privacy and rights requests", securityContact: "Security vulnerability reports", securityFile: "Published security contact",
    back: "Back to secure sign-in", note: "This notice is available before sign-in because operators should understand the boundary before entering the workspace.",
  },
  cs: {
    switchLanguage: "Switch to English", locale: "English", eyebrow: "Soukromí srozumitelně",
    title: "Víte, co se děje s údaji operátora.",
    intro: "OpenBank Admin používá pouze údaje o identitě, relaci a auditu nezbytné pro řízené bankovní prostředí. Nepoužívá marketingové ani sledovací cookies.",
    flowTitle: "Cesta údajů",
    flow: [
      ["Identita", "Keycloak předá jméno, e-mail a přidělené role, aby vás OpenBank mohl ověřit a řídit přístup podle rolí."],
      ["Relace", "Nezbytná relační cookie NextAuth udržuje přihlášení do odhlášení nebo vypršení tokenu."],
      ["Auditní stopa", "Akce provedené v konzoli vytvářejí auditní stopu pro vysledovatelnost a povinnosti finančního sektoru."],
    ],
    controllerTitle: "Kdo je správcem", controller: "OpenBank Foundation (v přípravě), provozovatel demonstrační bankovní platformy na doméně open-bank.tech.",
    purposeTitle: "Proč údaje zpracováváme", purpose: "Identita a relační údaje jsou nutné pro autentizaci a řízení přístupu. Auditní záznamy podporují povinnosti podle EBA ICT Risk Guidelines a PSD2 a oprávněný zájem na vysledovatelnosti operací nad bankovními daty.",
    retentionTitle: "Jak dlouho údaje uchováváme", retention: "Relační cookie zaniká odhlášením nebo vypršením tokenu. Auditní záznamy uchováváme po dobu vyžadovanou předpisy finančního sektoru.",
    rightsTitle: "Vaše práva", rights: "Můžete požádat o přístup, opravu nebo výmaz tam, kde výmaz nekoliduje s regulatorní povinností uchování. Můžete také podat stížnost u Úřadu pro ochranu osobních údajů.",
    contactTitle: "Kontakty", controllerContact: "Žádosti k soukromí a právům", securityContact: "Hlášení bezpečnostních zranitelností", securityFile: "Publikovaný bezpečnostní kontakt",
    back: "Zpět k bezpečnému přihlášení", note: "Toto oznámení je dostupné před přihlášením, protože operátoři mají hranici zpracování znát ještě před vstupem do prostředí.",
  },
} as const

const FLOW_ICONS = [UserRoundCheck, Cookie, FileCheck2]

export function PrivacyContent() {
  const { language, setLanguage } = useLanguage()
  const copy = COPY[language]

  return (
    <main className={styles.page}>
      <a className={styles.skipLink} href="#privacy-content">{language === "en" ? "Skip to privacy details" : "Přeskočit k detailům soukromí"}</a>
      <header className={styles.header}>
        <a className={styles.brand} href="/auth/login">OpenBank <span>Admin</span></a>
        <button type="button" className={styles.languageButton} onClick={() => setLanguage(language === "en" ? "cs" : "en")} aria-label={copy.switchLanguage}>
          <Languages size={16} aria-hidden="true" />{copy.locale}
        </button>
      </header>

      <section id="privacy-content" className={styles.hero} aria-labelledby="privacy-title" tabIndex={-1}>
        <div className={styles.heroCopy}>
          <p className={styles.eyebrow}><ShieldCheck size={16} aria-hidden="true" />{copy.eyebrow}</p>
          <h1 id="privacy-title">{copy.title}</h1>
          <p className={styles.intro}>{copy.intro}</p>
        </div>
        <aside className={styles.note}><ShieldCheck size={20} aria-hidden="true" /><p>{copy.note}</p></aside>
      </section>

      <section className={styles.flow} aria-labelledby="data-flow-title">
        <h2 id="data-flow-title">{copy.flowTitle}</h2>
        <div className={styles.flowGrid}>
          {copy.flow.map(([title, body], index) => {
            const Icon = FLOW_ICONS[index]
            return <article key={title}><span className={styles.flowIcon}><Icon size={20} aria-hidden="true" /></span><span className={styles.step}>0{index + 1}</span><h3>{title}</h3><p>{body}</p></article>
          })}
        </div>
      </section>

      <section className={styles.details} aria-label={language === "en" ? "Privacy details" : "Podrobnosti o soukromí"}>
        <article><UserRoundCheck size={21} aria-hidden="true" /><div><h2>{copy.controllerTitle}</h2><p>{copy.controller}</p></div></article>
        <article><FileCheck2 size={21} aria-hidden="true" /><div><h2>{copy.purposeTitle}</h2><p>{copy.purpose}</p></div></article>
        <article><Clock3 size={21} aria-hidden="true" /><div><h2>{copy.retentionTitle}</h2><p>{copy.retention}</p></div></article>
        <article><ShieldCheck size={21} aria-hidden="true" /><div><h2>{copy.rightsTitle}</h2><p>{copy.rights}</p></div></article>
      </section>

      <section className={styles.contacts} aria-labelledby="contacts-title">
        <div><p className={styles.eyebrow}><Mail size={16} aria-hidden="true" />OpenBank</p><h2 id="contacts-title">{copy.contactTitle}</h2></div>
        <div className={styles.contactLinks}>
          <a href="mailto:hello@open-bank.tech"><span>{copy.controllerContact}</span><strong>hello@open-bank.tech</strong></a>
          <a href="mailto:security@open-bank.tech"><span>{copy.securityContact}</span><strong>security@open-bank.tech</strong></a>
          <a href="/.well-known/security.txt"><span>{copy.securityFile}</span><strong>security.txt</strong></a>
        </div>
      </section>

      <footer className={styles.footer}>
        <a href="/auth/login"><ArrowLeft size={17} aria-hidden="true" />{copy.back}</a>
        <span>admin.open-bank.tech</span>
      </footer>
    </main>
  )
}
