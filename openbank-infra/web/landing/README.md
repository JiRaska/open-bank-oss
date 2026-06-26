# Open Bank Foundation — landing page

Statická marketingová landing page pro vizi **Open Bank Foundation** — sdílený open-source
framework pro banky v éře AI (microservices, multi-cloud, AI, governance-as-code).

## Spuštění lokálně

```bash
cd openbank-foundation-site
python3 -m http.server 8766
# otevři http://localhost:8766
```

Žádný build, žádné závislosti — čisté HTML/CSS/JS.

## Struktura

| Soubor | Účel |
|---|---|
| `index.html` | obsah a struktura stránky |
| `styles.css` | vzhled (dark fintech téma, gradient, glow, responzivní) |
| `main.js` | scroll-reveal animace + parallax loga |
| `assets/openbank-logo.png` | logo s **transparentním pozadím** |
| `make_logo.py` | skript, který z původního PNG (zapečený checkerboard) vytvořil průhledné logo |

## Logo

Zdrojový obrázek `image_1780247453018654.png` měl checkerboard zapečený do pixelů (RGB bez
alfa kanálu). `make_logo.py` ho převedl na skutečně transparentní PNG: flood-fill pozadí od
okrajů, potlačení stínu, ponechání jen největší souvislé komponenty a měkký antialias okraje.

## Obsah / sekce

1. **Hero** — claim „The open framework for banking in the AI era" + logo
2. **Manifesto** — banky se staly software housy; sdílejme společné jádro
3. **Framework** — 6 pilířů (hexagonal, multi-cloud, AI-native, event-driven, governance-as-code, security)
4. **Why open source** — proč je sdílení bezpečnější a levnější
5. **Join** — výzva k zapojení (e-mail / LinkedIn)

> Texty i odkazy (e-mail, LinkedIn) jsou placeholdery — uprav v `index.html`.
