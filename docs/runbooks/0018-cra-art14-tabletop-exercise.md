# Runbook 0018: CRA Art. 14 tabletop cvičení — „Log4Shell na money-path"

> Povinné před 2026-09-11 (runbook 0017, ADR-0278). Délka: 90 minut.
> Cíl: dokázat, že tým umí Art. 14 proceduru **na čas** — ne že umí runbook přečíst.
> Cvičení se nikdy nedotýká produkce ani skutečné ENISA platformy — vše se děje
> v testovacím záznamu a na papíře/mock formuláři.

## Role

| Role | Kdo | Odpovědnost |
|---|---|---|
| Facilitátor | security lead (ne on-call) | čte injekce, hlídá hodiny, nedává nápovědy |
| On-call responder | security on-call | vykonává runbook 0017 |
| Scribe | libovolný člen | zapisuje čas každé akce (důkaz pro hodnocení) |
| Observer | platform lead / CISO | hodnotí, nezasahuje |

## Scénář — počáteční injekce (T=0)

> *V 09:14 dorazil na security.txt kanál externí report: „Ve vaší bance běží
> `sepa-payment-service` se zranitelnou verzí serializační knihovny, ke které
> dnes ráno vyšel veřejný exploit (RCE přes deserializaci). Na CISA KEV je od
> včera." Současně Trivy nightly scan ukazuje CRITICAL CVE-2026-XXXX v
> `openbank-sepa-payment-service` image — nasazené na sandboxu i produkci.*

Facilitátor předá pouze tento text. Nic víc.

## Injekce v čase (facilitátor čte dle reálného postupu týmu)

- **+20 min** (pokud tým neověřil exploit aktivitu): „Threat-intel feed hlásí
  pozorované pokusy o zneužití v EU bankovním sektoru. U vás v logu WAF
  (simulováno) 3 requesty s exploit payloadem na `/api/v1/sepa/payments`."
- **+45 min** (pokud není založen incident záznam): „Reportér se ptá, zda má
  zranitelnost zveřejnit. Chce odpověď do 24 hodin."
- **+75 min** (pokud není rozhodnuto o hlášení): „NÚKIB poslal hromadný dotaz
  bankám, zda jsou zasaženy."
- **+90 min** (uzávěrka): „CEO se ptá: ‚Museli jsme něco hlásit? A hlásili
  jsme?'"

## Hodnoticí tabulka (pass/fail proti runbooku 0017)

| # | Kontrolní bod | Hodiny | Pass kriterium |
|---|---|---|---|
| 1 | Záznam v incident registru založen | do 1 h od T=0 | záznam existuje s časem, kategorií, závažností, dotčenými službami |
| 2 | Triage rozhodnuto **a zdůvodněno** | do 4 h | „hlásit / nehlásit + proč" zapsáno v záznamu |
| 3 | Rozpoznána Art. 14 povinnost | během triage | tým pojmenuje „aktivně zneužívaná zranitelnost" (KEV + pozorované pokusy) bez nápovědy facilitátora |
| 4 | Early warning **nachystán** | do 24 h (cvičení: draft do 60 min) | mock formulář obsahuje předmět + předpokládaný dopad; NEČEKÁ se na root cause |
| 5 | Adresáti správně | součást draftu | ENISA single platform + NÚKIB; ne jen interní kanály |
| 6 | Paralelní režimy pojmenovány | během cvičení | DORA (stejný záznam, vlastní lhůty) a ověření GDPR relevance (platby = osobní data? rozhodnout a zapsat) |
| 7 | Odpověď reportérovi | do 24 h (cvičení: do konce) | acknowledgement dle disclosure policy; žádné „neveřejňujte, prosím" |
| 8 | Mitigace navržena | do konce cvičení | např. WAF pravidlo / stažení verze / expedited release; SBOM dotčených verzí vyhledán v evidenci |
| 9 | Eskalace | do 24 h | vedení informováno, rozhodnutí o hlášení má vlastníka |
| 10 | Bus factor | průběžně | alespoň 2 lidé vědí, kde je ENISA přístup |

**Pass = 9/10 a body 2, 4 povinně.** Cokoli méně → akční položky s vlastníkem
a termínem do issue #8488, opakovat cvičení do 30 dnů.

## Závěr a důkaz

Scribe zapíše skutečné časy akcí do hodnoticí tabulky, facilitátor doplní
závěr (pass/fail + zjištění) a výstup se přiloží k issue #8488 jako komentář.
Tým, který cvičení prošel, má nárok tvrdit „Art. 14 pipeline je vyzkoušená";
tým, který ho jen četl, ne.

## Varianty pro příště

- Závažný **incident** místo zranitelnosti (ransomware na infra) — jiná část triage.
- Zranitelnost hlášená **v pátek 23:40** — testuje on-call a bus factor.
- False positive (KEV záznam se ukáže jako jiná verze knihovny) — testuje
  „nehlásit, ale zdůvodnit písemně".
