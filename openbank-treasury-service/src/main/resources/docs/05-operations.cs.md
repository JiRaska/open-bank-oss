# 05 — Provoz

## Příjem XML camt.053

Treasury zpracovává nedůvěryhodné soubory camt.053 pomocí `SecureXml`. Jeho DOM builder odmítne deklaraci DOCTYPE a externí entity ještě před vytvořením dokumentu. Odmítnutý či chybný soubor opravte u zdroje; nevypínejte ochranu parseru kvůli jeho přijetí. Služba neprovádí síťové načítání entit uvedených v souboru.
