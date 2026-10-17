# 05 — Provoz

## Příjem XML camt.053

Treasury zpracovává nedůvěryhodné soubory camt.053 pomocí `SecureXml`. Jeho DOM builder odmítne deklaraci DOCTYPE a externí entity ještě před vytvořením dokumentu. Odmítnutý či chybný soubor opravte u zdroje; nevypínejte ochranu parseru kvůli jeho přijetí. Služba neprovádí síťové načítání entit uvedených v souboru.

## Sdílené výchozí konfigurace

Soubor `application.yaml` této služby nedeklaruje meze poolu reaktivního datasource. Pocházejí z výchozí konfigurace flotily v `openbank-libs-runtime/src/main/resources/application.yaml` (idle-timeout 5M, max-lifetime 30M, max-size 20), která je na classpath každé služby s nižší prioritou než vlastní soubor služby, a odpovídají tomu, co služba dříve deklarovala sama. Hodnota v `application.yaml` služby má přednost.
