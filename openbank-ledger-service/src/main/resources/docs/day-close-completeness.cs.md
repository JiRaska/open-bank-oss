<!-- SPDX-License-Identifier: Apache-2.0 -->
# Důkaz úplnosti denní uzávěrky

Vyrovnaná předvaha může postrádat celý vyrovnaný účetní zápis. Účast na uzávěrce proto vyžaduje
výslovný výsledek od interest, lending, billing a treasury. Chybějící nebo REFUSED účastník
blokuje přechod CUTOFF -> TIED_OUT v režimu enforce. V režimu shadow může den přesto pokračovat;
operátorský panel tento režim zobrazuje. Výjimku může zaznamenat jen člověk, s důvodem a ověřenou
identitou. Výjimka není úspěšné zpracování.

Ledger ukládá důkaz z okamžiku přechodu do TIED_OUT ve stejné transakci jako den a jeho outbox
událost. Pozdější RAN hlášení zpětně neopraví neúplný historický důkaz. Nová tabulka je aditivní,
bez historického dopočtu; návrat předchozí image její historii zachovává.

GET /api/v1/ledger/close-readiness standardně vyhodnotí předchozí celý kalendářní měsíc.
Volitelný parametr month ve formátu YYYY-MM omezí čtení na jeden dokončený měsíc; starší měsíce
jsou pro schválení zastaralé. Každý den musí mít uložený důkaz se stejnými deklaracemi, zapnutou
kontrolou a úspěšným neprázdným tie-outem po cutoffu i posledním RAN hlášení. V enforce režimu stejnou podmínku
musí splnit i ruční uzavření. Pozdní produktové zpracování vyvolá nový tie-out. Výjimky a odmítnutí připravenost blokují. Každý požadovaný
job potřebuje během měsíce úspěšný uložený výsledek se zpracovanými položkami nebo účetními zápisy.
Prázdný průchod to nedokládá a denní accrual nenahradí kapitalizaci. Endpoint nemění konfiguraci
ani sám neschvaluje enforcement nebo nasazení.

Pořadí dodání: nejdřív schéma a čtecí endpoint ledgeru, potom image účastníků s důkazy konkrétních
jobů. Režim shadow a existující deployment holds trvají do dokončení měsíčního důkazu a schválení.
Starší hlášení zůstávají přijímána, ale měsíční připravenost nepotvrdí. Historii nedopočítávejte,
nenulujte čítač kvůli schválení a nespouštějte finanční job jen pro zaplnění důkazu.

Knihy penzijní společnosti a majetek účastníků fondů mají odlišné vlastníky a hranice. Penzijní
moduly zatím neposkytují účetní mapování a GL posting potřebné k účasti na účetní uzávěrce.
Publikace NAV ani úspěšný plánovač je nenahradí. Před aktivací kontrol potřebují vlastní mapované
zdroje, tie-out autoritu a úplnou uzávěrku.
