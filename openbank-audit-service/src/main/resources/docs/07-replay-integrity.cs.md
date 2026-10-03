# Integrita zápisu a opakování

Zápis do auditního řetězce získá transakční PostgreSQL advisory lock před kontrolou ID události, načtením hlavy řetězce a vložením dalšího článku. Tím se zápisy serializují i mezi pody. Opakované doručení se stejným ID je bez účinku pouze tehdy, když se se záznamem shoduje payload, typ události, typ a ID agregátu, ID aktéra i zdrojová služba. Konfliktní opakování selže místo tichého přijetí jiného důkazu a vyžaduje šetření u producenta. Auditní řetězec nenahrazuje původní artefakt události u producenta.
