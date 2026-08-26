// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

"use client"

import { ArrowLeft, Clock, Fingerprint, Cookie, ClipboardList, Scale, Mail } from "lucide-react"
import { useLanguage } from "@/lib/i18n/LanguageContext"

// One row of the "what we collect, and why" overview. Each maps 1:1 onto a
// section below — the overview is a scannable summary, not separate content.
function FlowStep({
  icon, title, detail,
}: { icon: React.ReactNode; title: string; detail: string }) {
  return (
    <div style={{
      display: "flex", gap: "12px", alignItems: "flex-start",
      padding: "14px", background: "var(--surface-2, #f8fafc)", borderRadius: "10px",
    }}>
      <div style={{
        width: "32px", height: "32px", flexShrink: 0, borderRadius: "8px",
        background: "var(--accent-light, #eef2ff)", color: "var(--accent, #6366f1)",
        display: "flex", alignItems: "center", justifyContent: "center",
      }}>
        {icon}
      </div>
      <div>
        <div style={{ fontSize: "13px", fontWeight: 600, color: "var(--text-primary, #0f172a)" }}>{title}</div>
        <div style={{ fontSize: "12px", color: "var(--text-secondary, #475569)", marginTop: "2px" }}>{detail}</div>
      </div>
    </div>
  )
}

function Section({
  id, icon, title, children,
}: { id: string; icon: React.ReactNode; title: string; children: React.ReactNode }) {
  return (
    <section aria-labelledby={id} style={{ marginTop: "32px" }}>
      <h2
        id={id}
        style={{
          fontSize: "16px", fontWeight: 700, marginBottom: "8px",
          display: "flex", alignItems: "center", gap: "8px", color: "var(--text-primary, #0f172a)",
        }}
      >
        <span aria-hidden="true" style={{ color: "var(--accent, #6366f1)", display: "flex" }}>{icon}</span>
        {title}
      </h2>
      {children}
    </section>
  )
}

export default function PrivacyContent() {
  const { language, setLanguage, t } = useLanguage()

  return (
    <div style={{ minHeight: "100vh", background: "var(--bg, #f8fafc)", fontFamily: "var(--font-sans, 'Inter', system-ui, sans-serif)" }}>
      <a
        href="#main"
        style={{
          position: "absolute", left: "-9999px", top: "0", zIndex: 100,
          padding: "10px 16px", background: "#4f46e5", color: "#fff",
          borderRadius: "0 0 8px 0", fontSize: "13px", fontWeight: 600,
        }}
        onFocus={e => { e.currentTarget.style.left = "0" }}
        onBlur={e => { e.currentTarget.style.left = "-9999px" }}
      >
        {t("Přeskočit na obsah", "Skip to content")}
      </a>

      <div style={{
        display: "flex", justifyContent: "flex-end", maxWidth: "720px", margin: "0 auto",
        padding: "16px 24px 0",
      }}>
        <button
          type="button"
          aria-label={t("Přepnout na angličtinu", "Switch to Czech")}
          title={t("Přepnout jazyk", "Switch language")}
          onClick={() => setLanguage(language === "en" ? "cs" : "en")}
          style={{
            padding: "6px 12px", borderRadius: "6px", border: "1px solid var(--border, #e2e8f0)",
            background: "var(--surface, #ffffff)", color: "var(--text-secondary, #475569)",
            fontSize: "12px", fontWeight: 600, cursor: "pointer",
          }}
        >
          {language.toUpperCase()}
        </button>
      </div>

      <main
        id="main"
        style={{
          maxWidth: "720px", margin: "0 auto", padding: "24px 24px 96px",
          color: "var(--text-primary, #0f172a)", lineHeight: 1.6,
        }}
      >
        <h1 style={{ fontSize: "24px", fontWeight: 700, marginBottom: "8px" }}>
          {t("Ochrana osobních údajů", "Privacy notice")}
        </h1>
        <p style={{ color: "var(--text-tertiary, #94a3b8)", fontSize: "13px", marginBottom: "28px" }}>
          {t("OpenBank Admin · Operations Portal · admin.open-bank.tech", "OpenBank Admin · Operations Portal · admin.open-bank.tech")}
        </p>
        <p style={{ fontSize: "14px", color: "var(--text-secondary, #475569)", marginBottom: "28px" }}>
          {t(
            "Tato stránka vysvětluje, jaké údaje o vás zpracováváme při přihlášení a používání operátorské konzole, a jaká máte práva. Je dostupná bez přihlášení, protože se týká i vás, kdo se právě chystáte přihlásit.",
            "This page explains what data we process about you when you sign in and use the operator console, and what rights you have. It is reachable without signing in, because it concerns you before you do.",
          )}
        </p>

        <h2 style={{ fontSize: "13px", fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.04em", color: "var(--text-tertiary, #94a3b8)", marginBottom: "12px" }}>
          {t("Přehled na první pohled", "At a glance")}
        </h2>
        <div style={{ display: "grid", gap: "10px" }}>
          <FlowStep
            icon={<Fingerprint size={16} aria-hidden="true" />}
            title={t("Identita z Keycloak", "Identity from Keycloak")}
            detail={t("Jméno, e-mail a přidělené role — pro přihlášení a řízení přístupu.", "Name, email and assigned roles — for sign-in and access control.")}
          />
          <FlowStep
            icon={<Cookie size={16} aria-hidden="true" />}
            title={t("Relační cookie", "Session cookie")}
            detail={t("Udržuje vás přihlášené. Žádné sledovací ani marketingové cookies.", "Keeps you signed in. No tracking or marketing cookies.")}
          />
          <FlowStep
            icon={<ClipboardList size={16} aria-hidden="true" />}
            title={t("Auditní záznam", "Audit log")}
            detail={t("Zaznamenává provedené akce nad bankovními daty pro dohled a regulaci.", "Records actions taken over banking data, for oversight and regulation.")}
          />
        </div>

        <Section id="controller" icon={<Scale size={16} aria-hidden="true" />} title={t("Kdo je správcem", "Who is the controller")}>
          <p style={{ fontSize: "14px" }}>
            {t(
              "OpenBank Foundation (v přípravě), provozovatel demonstrační bankovní platformy na doméně open-bank.tech. Kontakt: ",
              "OpenBank Foundation (in formation), operator of this demonstration banking platform on the open-bank.tech domain. Contact: ",
            )}
            <a href="mailto:hello@open-bank.tech">hello@open-bank.tech</a>.
          </p>
        </Section>

        <Section id="data" icon={<Fingerprint size={16} aria-hidden="true" />} title={t("Jaké údaje zpracováváme a proč", "What data we process, and why")}>
          <p style={{ fontSize: "14px" }}>
            {t(
              "OpenBank Admin je interní operátorská konzole — přístup mají pouze zaměstnanci a spolupracovníci přihlášení přes firemní Keycloak SSO účet. Při přihlášení a používání konzole zpracováváme:",
              "OpenBank Admin is an internal operator console — only staff and collaborators signed in through the corporate Keycloak SSO account can access it. When you sign in and use the console we process:",
            )}
          </p>
          <ul style={{ fontSize: "14px", paddingLeft: "20px" }}>
            <li>
              {t(
                "identitu z Keycloak (jméno, e-mail, přidělené role) — pro autentizaci a řízení přístupu (RBAC);",
                "identity from Keycloak (name, email, assigned roles) — for authentication and access control (RBAC);",
              )}
            </li>
            <li>
              {t(
                "relační cookie (NextAuth session) — nezbytnou pro udržení přihlášení, žádné sledovací ani marketingové cookies;",
                "a session cookie (NextAuth session) — necessary to keep you signed in, no tracking or marketing cookies;",
              )}
            </li>
            <li>
              {t(
                "záznam provedených akcí (audit log) — právním základem je plnění povinností dle EBA ICT Risk Guidelines a PSD2, a oprávněný zájem na vysledovatelnosti operací nad bankovními daty.",
                "a record of actions taken (audit log) — the legal basis is compliance with EBA ICT Risk Guidelines and PSD2 obligations, and the legitimate interest in the traceability of operations over banking data.",
              )}
            </li>
          </ul>
        </Section>

        <Section id="retention" icon={<Clock size={16} aria-hidden="true" />} title={t("Doba uchování", "Retention period")}>
          <p style={{ fontSize: "14px" }}>
            {t(
              "Relační cookie zaniká odhlášením nebo vypršením platnosti tokenu. Audit záznamy se uchovávají po dobu vyžadovanou regulatorními předpisy pro finanční sektor.",
              "The session cookie ends when you sign out or the token expires. Audit records are kept for the period required by financial-sector regulation.",
            )}
          </p>
        </Section>

        <Section id="rights" icon={<Scale size={16} aria-hidden="true" />} title={t("Vaše práva", "Your rights")}>
          <p style={{ fontSize: "14px" }}>
            {t(
              "Máte právo na přístup, opravu, výmaz (v rozsahu, který nekoliduje s výše uvedenou regulatorní povinností uchování) a na podání stížnosti u Úřadu pro ochranu osobních údajů. Žádosti směřujte na ",
              "You have the right to access, rectify, and erase your data (to the extent this does not conflict with the retention obligation above), and to lodge a complaint with the Office for Personal Data Protection. Send requests to ",
            )}
            <a href="mailto:hello@open-bank.tech">hello@open-bank.tech</a>.
          </p>
        </Section>

        <Section id="security" icon={<Mail size={16} aria-hidden="true" />} title={t("Bezpečnostní kontakt", "Security contact")}>
          <p style={{ fontSize: "14px" }}>
            {t("Nahlášení bezpečnostní zranitelnosti: ", "To report a security vulnerability: ")}
            <a href="mailto:security@open-bank.tech">security@open-bank.tech</a>{" "}
            {t("(viz ", "(see ")}
            <a href="/.well-known/security.txt">/.well-known/security.txt</a>).
          </p>
        </Section>

        <p style={{ marginTop: "40px" }}>
          <a
            href="/auth/login"
            style={{
              display: "inline-flex", alignItems: "center", gap: "6px",
              fontSize: "13px", fontWeight: 600, color: "var(--accent, #6366f1)",
            }}
          >
            <ArrowLeft size={14} aria-hidden="true" />
            {t("Zpět na přihlášení", "Back to sign-in")}
          </a>
        </p>
      </main>
    </div>
  )
}
