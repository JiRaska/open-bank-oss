# 05 — Provoz

## Příjem XML camt.053

Treasury zpracovává nedůvěryhodné soubory camt.053 pomocí `SecureXml`. Jeho DOM builder odmítne deklaraci DOCTYPE a externí entity ještě před vytvořením dokumentu. Odmítnutý či chybný soubor opravte u zdroje; nevypínejte ochranu parseru kvůli jeho přijetí. Služba neprovádí síťové načítání entit uvedených v souboru.

## První kontrola zastaveného obchodu

Před opakováním akce přečtěte obchod a jeho poslední přechod přes autentizované treasury API. Čekající schválení stále vyžaduje jiného lidského schvalovatele; zamítnutý produktový mandát nelze odstranit seniorní výjimkou pro limit protistrany. U zaúčtovaného obchodu bez očekávaného finančního efektu porovnejte přechod v treasury, doručení outboxu a výsledek idempotentního žurnálu ledgeru. Slepé opakování stejného přechodu může skrýt chybu na hranici účtování nebo doručení události.

Při párování výpisů sledujte výsledek reconciliace a otevřené rozdíly. Úspěšný import XML sám nedokazuje vypořádání obchodu. Repoziční runbook `docs/runbooks/svc-treasury.md` popisuje zdraví služby a obnovu; konfigurace nasazení zůstává v GitOps.
