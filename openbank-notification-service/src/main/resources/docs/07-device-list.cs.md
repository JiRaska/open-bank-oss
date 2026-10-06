# Rozsah seznamu zařízení

Cesta pro přístup subjektu může vrátit úplný seznam tokenů zařízení jedné osoby. Customer 360 používá oddělený databázově omezený výběr nejnovějších zařízení, řazený podle času vytvoření a ID, místo načtení celého seznamu a zkrácení v paměti. Flyway V18 přidává index pro čtení nejnovějších zařízení osoby. Token zařízení je citlivý přihlašovací materiál a nesmí být kopírován do projekce Contextu ani jejího seznamu sdělených důkazů.
