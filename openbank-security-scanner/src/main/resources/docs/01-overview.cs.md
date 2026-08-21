# Přehled

## Co služba dělá

`openbank-security-scanner` je **automatizovaný monitor bezpečnostní pozice** pro platformu OpenBank. Zajišťuje:

- **Prověřování všech 27 fleet služeb** každých 30 minut přes HTTP — kontrola dosažitelnosti přes `/q/health`, security headerů na API portu a expozice aktuátorů.
- **Spouštění 6 kontrol OWASP Top 10** na každé službě: security headery (A05), citlivá data v health endpointu (A02), expozice OpenAPI (A05 info-disclosure), neautentizované aktuátorové endpointy (A01), CORS wildcard (A05) a dosažitelnost služby (A05).
- **Skórování každé služby** 0–100 a přiřazení písmenkového grade (A+ → F), výpočet `PlatformSecurityReport` pokrývajícího všechny služby.
- **Správu ICT incidentů** — compliance důstojníci mohou hlásit, sledovat a aktualizovat životní cyklus DORA-grade ICT incidentů přes API `IctIncidentResource`. Incidenty jsou trvale uloženy v registru `ict_incidents` a zůstávají dostupné po restartu podu.
- **Vysílání eventů ICT incidentů** přímo do Kafka topicu `openbank.security.ict.incident`. Žádný outbox a žádná transakční záruka neexistuje; výsledky skenů se jako eventy nepublikují vůbec.

## Co služba NEDĚLÁ

- Neautentizuje ani neautorizuje koncové uživatele — je to interní platformový nástroj s vypnutým OIDC.
- Neprovádí DAST (Dynamic Application Security Testing) ani fuzzing — pouze HTTP-level black-box sondy.
- Neskenuje infrastrukturu ani síťovou vrstvu — pouze kontroly na aplikační vrstvě HTTP.
- Nevynucuje nápravu — reportuje zjištění; lidé a procesy vlastní odezvu.
- Neukládá kompletní těla HTTP odpovědí — pouze metadata, zjištění, mapu přítomnosti headerů a skóre.
- Nic nepersistuje. Výsledky skenů i ICT incidenty jsou pouze in-memory a zanikají s restartem podu (viz [04 — Data](./04-data.md)).

## Pozice v doméně

```
                      ┌─── každých 30 min ─────────────────────────────┐
                      ▼                                                 │
   ┌──────────────────────────────┐    výsledky skenů   ┌─────────────────┐
   │     security-scanner         │ ──────────────────► │   admin-ui      │
   │     (tato služba)            │                     │ (security page) │
   └──────────────┬───────────────┘                     └─────────────────┘
                  │ přímý Kafka emitter (pouze ICT incidenty)
                  ▼
         openbank.security.ict.incident
                  │
         ┌────────▼────────┐
         │  audit-service  │
         └─────────────────┘

   Sondy ──► všech 27 fleet služeb (přes /q/health + API port HTTP)
```

## Klíčové use-case

| Use case | API | Event |
|---|---|---|
| Spustit on-demand úplný sken | `POST /api/v1/security/scan` | — (žádný event; report jen přes REST) |
| Získat nejnovější platformový report | `GET /api/v1/security/report` | — |
| Získat výsledky pro konkrétní službu | `GET /api/v1/security/services/{name}` | — |
| Nahlásit nový ICT incident | `POST /api/v1/ict-incidents` | `IctIncidentReported` |
| Aktualizovat stav incidentu | `PATCH /api/v1/ict-incidents/{id}/status` | `IctIncidentUpdated` |
| Označit incident jako nahlášený regulátorovi | `POST /api/v1/ict-incidents/{id}/regulatory-report` | `IctIncidentRegulatoryReported` |

## Kontroly skenu na službu

| Kontrola | Kategorie OWASP | Závažnost při selhání |
|---|---|---|
| Služba nedosažitelná | A05 — Security Misconfiguration | CRITICAL |
| Chybí `X-Content-Type-Options` | A05 | MEDIUM |
| Chybí `X-Frame-Options` | A05 | MEDIUM |
| Chybí `Strict-Transport-Security` | A05 | MEDIUM |
| Chybí `Content-Security-Policy` | A05 | MEDIUM |
| Chybí `X-XSS-Protection` | A05 | MEDIUM |
| Chybí `Referrer-Policy` | A05 | MEDIUM |
| Chybí `Permissions-Policy` | A05 | MEDIUM |
| Citlivá data v health endpointu | A02 — Cryptographic Failures | HIGH |
| OpenAPI spec expozice bez autentizace | A05 (info-disclosure) | INFO |
| Neautentizovaný `/q/metrics` na API portu | A01 — Broken Access Control | MEDIUM |
| Neautentizovaný `/q/info` na API portu | A01 | MEDIUM |
| Neautentizovaný `/q/dev` na API portu | A01 | MEDIUM |
| CORS wildcard (`*`) origin | A05 | HIGH |

## Vzorec skórování

```
score = max(0, 100 - criticals × 30 - highs × 15 - mediums × 5)
```

Grady:
- A+ ≥ 95 | A ≥ 90 | B ≥ 80 | C ≥ 70 | D ≥ 60 | F < 60

`platformScore` = průměr skóre všech dosažitelných služeb.

## Závažnostní úrovně ICT incidentů (DORA čl. 17)

| Úroveň | Popis | Povinnost reportování |
|---|---|---|
| `P1_CRITICAL` | Výpadek celé platformy, únik dat, ransomware | Nahlásit ČNB do 4 hodin od detekce |
| `P2_HIGH` | Postiženo více služeb, porušení SLA | Nahlásit ČNB do 24 hodin |
| `P3_MEDIUM` | Degradace jedné služby | Pouze interní sledování |
| `P4_LOW` | Menší zjištění, žádný dopad na zákazníky | Pouze interní sledování |

## Závislosti

- **PostgreSQL** (`openbank-postgres`, schema `openbank_security`) — pouze Flyway historie schématu, žádné byznysové tabulky
- **Kafka** (`openbank-kafka`, topic `openbank.security.ict.incident`)
- **Všech 27 fleet služeb** — sondováno přes HTTP (read-only, `/q/health` nevyžaduje auth)
- **openbank-libs** ≥ 0.1.0 — BuildInfo, DocsResource
