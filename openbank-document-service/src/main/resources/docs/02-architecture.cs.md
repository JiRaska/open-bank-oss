# Architektura

## Hexagonální vrstvy (ADR-0002)

```
domain/            čistý Kotlin, NULA frameworkových importů
  model/           DocumentTemplate, Document, SignatureCeremony (+ Signer), enumy
  event/           DocumentTemplatePublished, DocumentGenerated, DocumentSigned, SignatureCeremonyCompleted
application/
  port/in/         DocumentTemplateUseCase, DocumentRenderUseCase, DocumentQueryUseCase, SignatureCeremonyUseCase
  port/out/        TemplateRepositoryPort, DocumentRepositoryPort, CeremonyRepositoryPort,
                   ObjectStorePort, TemplateRenderPort, PdfRenderPort, SignatureSealPort, DocumentOutboxRepository
  usecase/         DocumentTemplateService, DocumentRenderService, DocumentQueryService, SignatureCeremonyService
infrastructure/
  rest/            DocumentResource, SignatureCeremonyResource (+ dto)
  persistence/     entity, Panache reactive repozitáře, mappery
  render/          HandlebarsTemplateRenderer, HttpPdfRenderAdapter, PdfBoxPadesSealAdapter
  client/          ScaChallengeClient, ScaVerificationAdapter (integrace se sca-service, ADR-0021/0162 D4)
  kafka/ outbox/   KafkaDocumentOutboxEventPublisher, DocumentOutboxDispatcher, DocumentOutboxBacklogGauge
  authz/           AuthzProducer (OPA PDP)
```

Doménová vrstva importuje pouze JDK (`java.time`, `java.util`, `java.security.MessageDigest`) — žádný
Quarkus, Jakarta ani Panache. Napojení na framework žije výhradně v `infrastructure/`.

## Skutečné adaptéry za stabilními porty

Každý port má skutečný, neplacholderový produkční adaptér — nic zde není no-op:

| Port | Adaptér | Poznámka |
|---|---|---|
| `TemplateRenderPort` | `HandlebarsTemplateRenderer` — Handlebars.java, bez vlastních helperů, výchozí HTML escapování | bezlogické podle smlouvy (ADR-0162 D2): žádné spouštění libovolného kódu |
| `PdfRenderPort` | `HttpPdfRenderAdapter` — HTTP volání na `openbank-document-renderer` (WeasyPrint, výchozí) nebo Gotenberg (opt-in, `openbank.render.profile`) | ohraničené connect/request timeouty (ADR-0162 D3) |
| `ObjectStorePort` | Sdílený adaptér z `openbank-libs-runtime` (ADR-0161): `PostgresBlobStore` (vývoj/výchozí) nebo `S3ObjectStore` (produkce, Object Lock WORM) | volí se přes `openbank.objectstore.backend` |
| `SignatureSealPort` | `PdfBoxPadesSealAdapter` — skutečná PAdES-B pečeť (Apache PDFBox + BouncyCastle CMS), ve vývoji efemérní self-signed certifikát (hlasitý `WARN`), v produkci nakonfigurovaný PKCS12 keystore | fáze 2 = EU DSS PAdES-LTA s QSeal/HSM klíčem (ADR-0007/0162 D4) |

## Tok generování

1. `DocumentRenderService.render` najde **publikovanou** šablonu (`findPublished`).
2. `TemplateRenderPort.renderHtml` vloží datovou mapu do těla (HTML-escapováno).
3. `PdfRenderPort.htmlToPdf` vytvoří bajty; `ObjectStorePort.put` je uloží pod `documents/<id>`.
4. `Document` (adresovaný obsahem přes `Document.sha256`) je uložen **spolu s** outbox řádkem
   `DocumentGenerated` v jedné transakci (transakční outbox, ADR-0050).
5. `DocumentOutboxDispatcher` odesílá outbox řádky do Kafky na časovači, s resilience stackem
   (circuit breaker / retry / bulkhead / timeout). `DocumentOutboxBacklogGauge` publikuje metriku
   backlogu (`openbank.outbox.backlog`, tag `service="document"`).

## Tok podpisu

`SignatureCeremonyService.openCeremony` sestaví seřazený seznam podepisujících a otevře ceremonii
(`DRAFT → PENDING`). Rozhodnutí `SIGNED` musí nejdřív projít SCA ověřením (`ScaVerificationAdapter` →
`openbank-sca-service`: výzva musí být `COMPLETED` a patřit danému podepisujícímu, poté je spotřebována
přes `consume`, aby stejný důkaz nešlo znovu přehrát) a musí přijít od dalšího podepisujícího v pořadí
(rozhodnutí mimo pořadí je odmítnuto). `recordDecision` rozhodnutí aplikuje; jakmile podepíší všichni,
ceremonie dosáhne `COMPLETED` **pouze pokud** se nejdřív úspěšně zapečetí uložené bajty přes
`PdfBoxPadesSealAdapter` — selhání zapečetění selže celé volání, takže nezapečetěný dokument nikdy nemůže
být uložen jako `COMPLETED`. Teprve poté se vydá outbox událost `SignatureCeremonyCompleted`.
`SignatureCeremonyEntity` nese sloupec `@Version` (optimistické zamykání), takže dvě souběžná rozhodnutí
nad stejnou ceremonií nahlas kolidují (422) místo tichého přepsání.

## Perzistence

Vlastní databáze `openbank_documents`, `generation: none`, Flyway V1..V3. Pouze outbox entita rozšiřuje
`PanacheOutboxEntity` (Hibernate sekvence `document_outbox_seq`, vytvořená ve V3 — hlídá
`HibernateSequenceGuardTest`); ostatní entity používají aplikačně přidělená UUID/String id.
