# OpenBank — koncepční nápravný program pro otevřené issues (2026-09-03)

> Zpracováno analýzou všech **119 otevřených issues** v `JiRaska/open-bank-oss` k 2026-09-03.
> Cíl: **ne zavírat issues předčasně**, ale seskupit je podle kořenových příčin a definovat
> systémové protiopatření pro každý celek — tak, aby stejná třída problému nemohla vzniknout znovu.
> Dokument doplňuje [`audit-2026-09-03-top50.md`](audit-2026-09-03-top50.md); na rozdíl od něj mapuje
> *každé* otevřené issue na konkrétní klaster a opatření.

---

## 1. Executive summary

115 issues se rozpadá do **8 klastrů**, ale pouze **6 kořenových příčin**:

1. **Kontrola existuje na papíře, ne v enforcement bodu** (2-approval rule, mTLS, OPA enforce,
   DLQ, outbox-audit) — pravidlo je v `rules.yaml`/ADR, ale nic ho nevynucuje v tom místě, kde
   se rozhoduje (branch protection, Istio, channel config). → *Klaster A, D, G.*
2. **Důkazní infrastruktura se rozpadá dřív, než ji někdo všimne** (Test Intelligence, flaky
   evidence, perf baseline, VEX, attestation floors) — gates jsou zelené, protože měří špatnou
   věc nebo nic. → *Klaster B, C.*
3. **GitOps drift: repo říká A, cluster dělá B, gate kontroluje soubor, který nic nenasazuje**
   (Keycloak realm, deploy-drift, chybějící workloads, CNPG recovery). → *Klaster E.*
4. **Blokované práce bez unblock-path** (openbank-batch runner, DR cvičení, chaos drill) —
   11 issues visí na jediném chybějícím runneru. → *Klaster E/F.*
5. **Money-path bezpečnostní dluh s threat-modelem, který slibuje víc, než kód drží**
   (Temporal mTLS, fraud enforcement v workflow, delegation lifecycle, SCA binding bez producenta).
   → *Klaster A.*
6. **Legitimní produktový backlog** (Campaign Studio, QRlessPay, finrep, AI control plane) —
   to nejsou defekty; patří do roadmapy, ne do nápravného programu. → *Klaster H.*

**Nejvyšší páka:** tři systémové změny — (i) „control-at-the-point-of-decision" gate
(branch protection, Istio STRICT, DLQ wiring jako release blocker), (ii) jeden cluster-capable
runner (odblokuje 11 issues naráz), (iii) VEX store s fleet/base-layer scopem (nahradí 49–336
ručních statements) — pokryjí ~40 % všech otevřených issues.

**Pravidlo uzavírání:** issue se zavře až když je jeho *třída* pokrytá gate/automatizací, ne když
je opravená instance. Každý klaster má níže „exit kriterium třídy", ne seznam instancí.

---

## 2. Klastry, kořenové příčiny a systémová opatření

### Klaster A — Money-path security & integrity (21 issues)

**Issues:** #8199, #7867, #6035, #5913, #5900, #5728, #5679, #4942, #4754, #4403, #3765, #3679,
#3000, #2993, #2990, #2540, #2183, #1914, #1035, #8351, #6066.

**Kořenová příčina:** bezpečnostní kontroly jsou deklarované v ADR/threat modelech, ale
neenforceované v bodě rozhodnutí. Příklady tvaru: #2183 (2-approval rule: branch protection má
`required_approving_review_count 0`), #1914 (Istio STRICT mTLS: `istio.yaml` nerefereuje žádná
ArgoCD aplikace), #6066 (Temporal worker↔frontend bez transport auth, threat model mTLS kredituje),
#3679 (AUTHZ_ENFORCE=false default, drží ho jen manifest), #5900 (SCA binding: `ProposalToken` se
nikde nekonstruuje — kontrola 404 na každém zavolání).

**Systémové opatření (A1–A5):**

| # | Opatření | Co řeší | Exit kriterium třídy |
|---|---|---|---|
| A1 | **Branch-protection-as-code**: `rules.yaml` deklaruje money-path ochranu; CI gate porovnává živé GitHub branch protection settings s deklarací (dnes to dělá jen dokumentace) | #2183, #4828 | Gate červený při jakékoli odchylce; money-path všechny `required_approving_review_count: 2` |
| A2 | **Threat-model ⇄ runtime parity check**: pro každý „credited control" v threat modelu musí existovat spustitelný důkaz (config v manifestu + test), jinak CI fail | #6066, #1914, #3679, #2540, #3246 | Žádný threat-model kredit bez runtime důkazu; Istio STRICT mTLS nasazené přes ArgoCD |
| A3 | **Money-path integrity sweep**: DLQ (#8346→klaster D), outbox-audit (#6035), SCA producer (#5900), fraud enforcement v Temporal workflow (#4403), sweep authz scoping (#4754), null-array 400 (#7867), idempotency coverage gate (#8351) | 7 issues | Každá instance opravena + ratchet gate, který zabrání regresi třídy |
| A4 | **Delegation lifecycle completion** (ADR-0232): monotonic projections #8199, dual-run #2993, product projections #2990, M2M matrix-allows residual #3765, grant-guard agent #3000 | 5 issues | Delegation enforcement migrovaná, dual-run vyhodnocený, #3734 residual uzavřen |
| A5 | **Four-eyes & spend-reservation visibility**: approval inbox pokrývá všech 16 front (#5679), ADR-0249 D4 audit+notifikace (#5728), billing fee events v audit subscription (#6035) | 3 issues | Kontrola „každá four-eyes fronta má inbox řádek" jako gate |

**Prerekvizita A2:** Keycloak realm z gitu — #3246 a #2540 jsou stejný defekt (repo/template/vault/live
se rozcházejí a gate kontroluje soubor, který se nenasazuje). Řešení: realm template jako jediný
source of truth, CI porovnává *live* realm proti němu, Vault generovaný z template, ne ručně.

---

### Klaster B — Test Intelligence, CI evidence & flaky (24 issues)

**Issues:** #8333, #8263, #8222, #8175, #7998, #7984, #7980, #7978, #7976, #7376, #7284, #7246,
#7207, #7040, #6853, #6618, #6613, #6458, #5962, #5285, #4828, #4463, #3348, #8344, #8349.

**Kořenová příčina:** důkazní lanes (Test Intelligence, flaky evidence, Pact, coverage, mutation
testing) rostly organicky a měří samy sebe. Typické tvary: gate zelený, protože job nikdy neposlal
request (#8348→klaster B i když je tam číslován jako bug harnessu); CodeQL upload padá na rate
limitu a main security gate je z toho červená/zablokovaná (#7980); spot-kill auto-retry nezvládá
403 (#7976, #6853 — stejný mechanismus jako #6290); starý úspěšný perf run maskuje novější důkaz
(#8175); Pact broker selector vrací neparsovatelné odpovědi (#7376).

**Systémová opatření (B1–B5):**

| # | Opatření | Co řeší | Exit kriterium třídy |
|---|---|---|---|
| B1 | **CI self-healing úroveň 2**: rate-limit/403/spot-reclaim je *očekávaný* stav — centrální retry knihovna sebounded backoff + dead-letter fronta nerestartovatelných runů, místo per-workflow ad-hoc retry | #7976, #6853, #7980, #7998, #7978 | 30 dní bez manuálního re-runu z infrastrukturních důvodů; Dependency graph + Dependency Review gate aktivní |
| B2 | **Evidence validity windows**: každý důkaz (perf baseline, synthetic, Pact) má timestamp a TTL; novější důkaz vždy přebije starší; gate odmítá důkaz starší než N dní pro money-path | #8175, #7311, #8348, #8333 | Fuzz harness: každý služební job prokazuje ≥1 odeslaný request (datasource expressions + Temporal registrars vyřešeny v harnessu, viz #8348) |
| B3 | **Coverage & mutation ratchet**: statement coverage ≥70 % fleet-wide (#8344, OpenSSF Silver blocker), pitest na všech money-path + security-critical (#8349) | 2 issues | Ratchet v CI, baseline zamčený, žádné ruční výjimky bez ADR |
| B4 | **Pact/contract disciplína**: broker selector discovery fix (#7376), Testcontainers lifecycle evidence fleet-wide (#7246), per-test impact analysis shadow→enforce (#7207), Test Intelligence rollout (#6613, #7284, #8263, #8222, #7040, #4463) | 8 issues | Contract coverage gate pro money-path cross-service HTTP (#8345, klaster D) |
| B5 | **OpenAPI/enum drift sjednocení**: jeden comparator pro spec↔domain včetně 21 enumů ze shared libs, threshold 0.4 přepočítat (#7984, #5962, #8150, #8330) | 4 issues | info.version misclassification nemožná: gate porovnává oasdiff klasifikaci s info.version před merge |

---

### Klaster C — Supply chain: VEX, SBOM, image scan (7 issues)

**Issues:** #7987, #7597, #6988, #6720, #6717, #6378, #2365 (částečně).

**Kořenová příčina:** VEX statements se píšou ručně per-image. 49/56 overlayů tvrdí „blocked on
platform bump", který dávno landed (#7987); shared-artifact CVE stojí 42–336 ručních statements
(#6988). Dva released services nemají component key → žádný SBOM/provenance/VEX (#7597).

**Systémová opatření:**

| # | Opatření | Co řeší | Exit kriterium třídy |
|---|---|---|---|
| C1 | **VEX store s fleet/base-layer scopem**: jeden statement pro shared artifact se propaguje na všechny závislé images; auto-refresh při platform bump (#6988, #7987, #6720) | 3 issues | Žádný ruční per-image VEX pro shared layers; CVE-2026-59903 triage z jednoho místa |
| C2 | **Component-key gate**: release-please config validuje, že každý released component má key → SBOM/provenance/VEX povinné (#7597) | 1 issue | Gate červený při release bez component key |
| C3 | **Image rescan disposition SLA**: fixable CRITICAL/HIGH v shipped images musí mít disposition do X dní (#6717); CodeQL false-positive barrier: naučit CodeQL `sanitizeForLog()` jako sanitizer (custom query pack) místo 11 ručních dismissals (#6378) | 2 issues | 0 undispositioned fixable CRITICAL/HIGH; log-injection alerty řešené query pack, ne dismissal |

---

### Klaster D — Messaging: DLQ, schema registry, event disciplína (10 issues)

**Issues:** #8346, #8345, #5914→(H), #5752, #5745, #5902, #5698, #7539, #7194, #7190, #1916, #8351(→A).

**Kořenová příčina:** SmallRye default `failure-strategy: fail` + chybějící DLQ = rethrow zastaví
kanál, swallow ztratí data. Jen 4/44 kanálů má DLQ (#8346). Eventy jsou raw unversioned JSON,
žádné .avsc (#1916, ADR-0006 incomplete). `sourceService` konvence se rozbila mid-stream a
audit_entries má dva producenty (#5902).

**Systémová opatření:**

| # | Opatření | Co řeší | Exit kriterium třídy |
|---|---|---|---|
| D1 | **DLQ-as-release-blocker**: každý incoming channel musí mít `failure-strategy` + explicitní per-service DLQ topic + KafkaTopic CR + Write ACL; gate nad `application.yaml` (nested tvar, ne dotted) | #8346, #5752, #5745 | 44/44 kanálů s DLQ; account-service off default topic; check-event-handler-swallows.py vidí všechny tvary |
| D2 | **Schema registry + Avro (ADR-0006 dokončit)**: postupná migrace peněžních topics na .avsc; envelope `occurredAt/timestamp` konvence enforceovaná | #1916, #5902 | Schema registry nasazená; money-path eventy verzované backward-compat; sourceService lint gate |
| D3 | **Orphan & stale-outbox remediations dotáhnout**: #5698 (9 stranded parties — consumer fix + detection landed, zbyvá remediatace), #7539 (stale outbox reclaim flaky) | 2 issues | 0 stranded parties; reclaim IT stabilní 30 dní |
| D4 | **Referral/Campaign event backbone**: transactional outbox pro referral lifecycle (#7190, #7194) — prerekvizita pro klaster H | 2 issues | Lifecycle eventy přes outbox, publikované verze připnuté |

---

### Klaster E — GitOps, deploy drift, DR, kapacity (15 issues)

**Issues:** #8347, #8127, #7621, #6568, #6458, #6432, #5760, #5756, #4757, #4755, #3975, #3806,
#3348(→B), #3246(→A2), #2540(→A2), #2365, #6729.

**Kořenová příčina:** triádický drift — repo deklaruje, cluster dělá, gate kontroluje třetí věc.
A jediná chybějící kapacita (cluster-capable runner) blokuje DR/chaos/restores — 11 issues nese
label `blocked` právě kvůli tomu.

**Systémová opatření:**

| # | Opatření | Co řeší | Exit kriterium třídy |
|---|---|---|---|
| E1 | **Jeden cluster-capable runner** (openbank-batch pool nebo jeho náhrada): odblokuje #8347, #4757, #4755, #6458, #2365 a self-heal lanes. CI validace `ci_runners` proti skutečným poolům, aby „pool bez kapacity" nemohl vzniknout znovu (#6458) | 6+ issues | Runner existuje, DR-restore-verify běží na quarterly schedule, Scenario A chaos drill má změřené RTO |
| E2 | **Desired-state ⇄ live diff gate**: pro každý gitops workload gate porovnává verzi/manifest/backup-status *live* proti repo; deploy-drift label mizí (#8127, #7621, #6568, #5760 — tax-reporting: rozhodnout deploy-vs-stop-releasing) | 4 issues | 0 services s driftem > N dní; can-i-deploy neblokuje na counterpartech s 0 pacts (publikuje prázdný pact set) |
| E3 | **CNPG backup integrita**: `ContinuousArchiving=True` bez recovery pointu je gate-fail, ne green (#3975); backup-staleness alert opravit (collector lag #5756) | 2 issues | litellm-db volume remediation, copilot-db WAL archivace, žádný cluster bez recovery pointu |
| E4 | **Release evidence grace window**: verify-release-evidence počítá s právě vydaným release u Monday cronu (#6729); standing critical-alert digest: Alertmanager secrets vytvořit nebo job zrušit (#6432) | 2 issues | Žádná false-red regulator-facing kontrola; digest buď běží, nebo je odstraněn |
| E5 | **Canary capacity policy**: 21 one-replica Rollouts (15 money-path) nemůže rollnout na plném clusteru — buď kapacita headroom, nebo 2-replica minimum pro money-path, a gate #3545 přestane počítat „declared" za „rollable" (#3806) | 1 issue | Rollout simulace v CI; plný cluster ≠ rollout deadlock |

---

### Klaster F — Observability, SLO, performance evidence (9 issues)

**Issues:** #8350, #7311, #7483, #7451, #7371, #5769, #5869, #4348, #6618(→B).

**Kořenová příčina:** metriky existují, ale „co lze prokázat" ≠ „co metrika říká" (vzorec
accepted-vs-delivered z ADR-0252). Perf baseline evidence není executable (#7311), SLO targets
nejsou kalibrované z evidence (#8350), pentest attestation mintuje z bare green (#5769).

**Systémová opatření:**

| # | Opatření | Co řeší | Exit kriterium třídy |
|---|---|---|---|
| F1 | **Evidence-backed SLO program**: perf baseline executable (#7311) → k6 p95 gate (#3348, přesunout z triage) → kalibrace SLO z baseline (#8350) | 3 issues | Money-path p95 regression = červený CI; SLO targets odvozené, ne odhadované |
| F2 | **Synthetic & RUM evidence chain**: browser RUM v sandbox (#7483), Web Vitals scheduled (#7371), synthetic attest deployed build (#7451), ADR-0252 fáze 1–4 (#4348) | 4 issues | Journey-based production assurance běží, least-privilege identity (#7324→G) |
| F3 | **Attestation integrity floors**: pentest/restore attestation nese exercised-operation count s floorem (#5769); MTTR history + weekly RCA automation (#5869) | 2 issues | Žádná attestation z bare green; 69 TTL'd attestations (#2365) postupně doplněné |

---

### Klaster G — Governance & procesní enforcement (9 issues)

**Issues:** #7970, #6560, #6253, #6243, #6426, #5869(→F), #7324, #6618(→B), #3737, #5839.

**Kořenová příčina:** governance-as-code je silná, ale scope gateů se udržuje ručně (hand-kept
seznamy), takže nové služby/jevy gateům unikají. Money-path merge guard nevidí NOVOU službu
(#6560); gates.yaml má tail narrower-than-subject (#6253).

**Systémová opatření:**

| # | Opatření | Co řeší | Exit kriterium třídy |
|---|---|---|---|
| G1 | **Scope derivation, ne hand-kept lists**: money-path scope se derivuje z deklarace v service metadata (governance.yaml), ne ze seznamu adresářů v gate skriptu | #6560, #6253 | Nová money-path služba je guardována od prvního commitu; gate-scope audit = 0 narrower rows |
| G2 | **Independent review enforcement** (souvislost A1): required approvals 2 + codeowners pro money-path | #7970 | Branch protection-as-code live |
| G3 | **ADR-0270 sweep dokončit**: Party.status derived — klasifikace všech consumerů (#6243, blocked na sweep) | 1 issue | Klasifikační tabulka kompletní, migrace naplánovaná |
| G4 | **OPA/coordinator PEP shadow piloty s exit kriterii**: case.join/contribute (#6426), incident-triage swarm (#5839), swarm coordination (#3737), synthetic least-privilege identity (#7324) | 4 issues | Každý pilot má měřitelné exit kriterium a deadline; bez exit kriteria se pilot nespouští |

---

### Klaster H — Produktový backlog (ne defekty; 20 issues)

**Issues:** #7203, #7198, #7194, #7190 (Campaign Studio/Referral), #4712, #5074, #5914 (finrep
XBRL/ČNB), #668 (product configurability), #4883, #4866 (QRlessPay), #4459 (adverse media),
#4363 (push fallback policy), #7144 (AI control plane), #7068 (privacy notice), #5914, #3000(→A4),
#7654 (admin-ui design system), #7790, #7788, #8163, #8082, #1035(→A).

**Postoj programu:** toto jsou enhancementy/rozhodnutí, ne náprava. Tři doporučení:

1. **Rozdělit na „rozhodnutí" vs „implementace"**: #4866 (trademark), #4363 (fallback policy),
   #5760 (deploy vs release) jsou *decision* issues — potřebují ADR, ne kód. Každému nastavit
   decision deadline; po něm buď ADR, nebo zavřít jako „won't do".
2. **Admin-ui kontrakt sjednocení**: #8163, #7790, #7788, #8082, #7654 sdílejí příčinu — backend
   read-role model a admin-ui route bucket model se rozešly. Jedno opatření: RBAC matrix
   generovaná z backend `@Authorize` anotací jako source of truth, admin-ui routes derivované z ní.
3. **GDPR/compliance tail**: #7068 (legal content), #6646 (INSOLVENCY/ENFORCEMENT feed —
   external dependency, eskalovat na vendor decision), #5914 (finrep — regulovaná submission,
   držet jako roadmap milestone M-kategorie).

---

## 3. Sekvenční plán (4 vlny)

**Vlna 1 — „Páky" (2–3 týdny, odblokuje ~45 issues):**
1. E1: cluster-capable runner (odblokuje DR, chaos, batch, attestations).
2. A1+G2: branch-protection-as-code (2-approval money-path skutečně vynucené).
3. B1: centrální CI retry/rate-limit knihovna (CodeQL upload, spot-kill, dispatch).
4. C1: VEX fleet scope (49 overlayů se vyřeší jedním mechanismem).

**Vlna 2 — Money-path integrity (3–6 týdnů):**
A2 (threat-model⇄runtime parity), A3 sweep (DLQ→D1 paralelně, #7867, #5900, #4403, #4754),
E2/E3 (deploy-drift gate, CNPG recovery), E5 (canary capacity).

**Vlna 3 — Důkazní disciplína (4–8 týdnů):**
B2–B5, C2/C3, D2 (schema registry start), F1–F3, G1.

**Vlna 4 — Backlog hygiéna (průběžně):**
H: decision deadlines, RBAC matrix, delegation lifecycle A4, ADR-0270 sweep G3.

## 4. Pravidla, aby se issues neopakovaly

1. **Žádné issue se nezavírá instanční opravou bez ratchet/gate**, pokud je třída opakovatelná
   (vzorec z fleet gotchas: „prose is not a control").
2. **Každý nový gate dostane self-test** (#6618 ukázalo, že gate self-testy samy flaky jsou).
3. **Komentáře nesmí uvádět config hodnoty** — jen mechanismus (vzorec „shelf life komentáře"
   z #5752/#5745).
4. **Blocked label musí mít unblock-path a vlastníka** — 11 issues dnes visí na jednom runneru;
   to je kapacitní rozhodnutí, ne technický problém.
5. **Decision issues mají deadline** — po něm ADR nebo won't-do; věčné `triage`+`blocked` je
   způsob, jak backlog lže o své velikosti.

## 5. Příloha: mapa všech 115 issues → klaster

| Klaster | Issues |
|---|---|
| A Money-path security | 8199, 7867, 6035, 5913, 5900, 5728, 5679, 4942, 4754, 4403, 3765, 3679, 3000, 2993, 2990, 2540, 2183, 1914, 1035, 8351, 6066 |
| B CI/Test evidence | 8333, 8330, 8263, 8222, 8175, 8150, 8348, 8349, 8344, 7998, 7984, 7980, 7978, 7976, 7376, 7284, 7246, 7207, 7040, 6853, 6618, 6613, 6458, 5962, 5285, 4828, 4463, 3348 |
| C Supply chain/VEX | 7987, 7597, 6988, 6720, 6717, 6378, 2365, 8355 |
| D Messaging/DLQ | 8346, 8345, 5752, 5745, 5902, 5698, 7539, 7194, 7190, 1916, 8352 |
| E GitOps/DR/drift | 8347, 8127, 7621, 6568, 6432, 5760, 5756, 4757, 4755, 3975, 3806, 6729, 3246 |
| F Observability/SLO | 8350, 7311, 7483, 7451, 7371, 7324, 5769, 5869, 4348, 6901 |
| G Governance | 7970, 6560, 6253, 6243, 6426, 3737, 5839, 6480, 8354 |
| H Produktový backlog | 7654, 7790, 7788, 8163, 8082, 7203, 7198, 4712, 5074, 5914, 668, 4883, 4866, 4459, 4363, 7144, 7068, 6646 |

*(Nově přidaná za zpracování: #8352 — audit event-time disposice → klaster D spolu s #5902 (event
disciplína); #8353 — outbox-atomicity IT per money-path → součást opatření A3 (LendingOutboxWriteIT
jako vzorový pattern pro všechny money-path); #8354 — decision issue, patří do H-hygieny s deadline;
#8355 — reproducible builds → klaster C (supply-chain integrita, navazuje na SLSA). Každé číslo je
v tabulce právě jednou.)*

---

## 6. Stav provádění (2026-09-03, odpoledne)

### Vlna 1 — MERGED v PR #8371

Tři pákové změny nasazeny (CI zelené, 23 pass / 0 fail):

| Opatření | Artefakt | První živý nález |
|---|---|---|
| Branch-protection parity gate (#2183, #7970) | `check-branch-protection.py` + denní workflow | 1 writer, parity drží; revisit trigger je teď červené CI, ne komentář |
| Fleet-scoped VEX (#6988, #7987) | `_fleet.vex.json` + `check-vex-fleet-scope.py` | **32 promotion kandidátů** — 7 CVE udržovaných ve 53 identických kopiích |
| Centrální `gh-retry.sh` (#7976, #6853) | knihovna + dead-letter issue po vyčerpání | čeká na napojení `spot-kill-retry.sh` (blokováno cizí staged prací) |

### Ověření gate-pokrytí trackerů (vlna 2 se změnila v audit pokrytí)

Vlna 2 z programu předpokládala stavět nové gaty (A2, D1). Verifikace proti `main` ukázala, že
**obě už existují a jsou zelené** — backlog lže o své velikosti i tímto směrem:

| Třída | Gate | Stav 2026-09-03 |
|---|---|---|
| D1 DLQ pro každý incoming channel (#8346) | `check-incoming-dlq-wiring.py` (enforced) | 46/46 clean; zbytek = baseline s per-channel důvody, evidence přidána do #8346 |
| #5752 account-service implicit DLQ | totéž (KNOWN_UNWIRED) | živé, ale rename = live-topic migrace (stranded records + alert retargeting), ne config edit; evidence v issue |
| A2 threat-model ⇄ runtime | `check-threat-model-claims.py` (enforced) | existuje: PHANTOM / STUB / SELF-REF detekce claimů |
| #6480 quoted sequence names | `check-quoted-sequence-names.py` | zelené, ratcheted; evidence v issue |
| #5962 enum↔domain drift | `check-openapi-enum-vs-domain.py` | 16 driftů, všechny baselined, žádný nový |

**Korekce programu:** u trackerů, kde gate existuje, je skutečný zbytek práce *enumerovaný
baseline + migrační plán*, ne mechanismus. Priorita se přesouvá na: (i) fraud-service KafkaTopic
CR (jeden resource), (ii) DLQ rename migraci account/card-issuance, (iii) E1 cluster-capable
runner (kapacitní rozhodnutí, odblokuje 11 issues), (iv) integraci vlny 1 do `gates.yaml`/
`rules.yaml` po dotažení rozpracované staged práce.
