# Omezené čtení karet podle osoby

Customer 360 čte nejnovější karty jedné přesné osoby omezenou cestou repository (1–100 řádků). Čtení grafu má samostatný limit (1–200 řádků). Obě cesty řadí podle `created_at DESC, id DESC`, aby při shodném čase bylo pořadí stabilní, a odmítají hodnoty mimo limit místo neomezeného prohledávání. Flyway V14 přidává index pro dotaz na nejnovější karty osoby. Karta nalezená podle osoby je referencí důkazu; její případné ID účtu samo neprokazuje vlastnictví účtu bez kontroly zdroje.
