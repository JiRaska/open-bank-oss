# SDLC train passengers

`train-passengers.png` is an original generated 3 × 2 transparent atlas, 1536 × 1024 pixels.
The first row is the OpenBank lion, the second the lioness; columns are waving, worried,
and cheering. Each cell is 512 × 512 pixels. The atlas is used by the CI guide's SVG scene.

Created with the built-in ImageGen tool using the existing Lion Quest lion and lioness as
identity references. Those reference assets and their source attribution remain unchanged
in `public/lion-quest/NOTICE.txt`. Copyright OpenBank contributors.

Generation prompt:

> Use case: illustration-story. Asset type: one transparent sprite atlas for an OpenBank
> educational merge-train animation. Input images are identity/style references, not targets
> to overwrite. Preserve the recognisable white lion with navy/blue mane and white lioness
> with blonde hair and blue glasses, navy OpenBank jackets and small cyan infinity emblem,
> friendly polished 3D game artwork. Create one perfectly regular 3-column by 2-row atlas,
> landscape aspect 3:2, six equal square cells, transparent background, no grid or text.
> Center waist-up characters at the same scale and baseline, with transparent margins.
> Top row: male lion waving, worried/thoughtful, exuberantly cheering. Bottom row: lioness
> in the corresponding poses. Face the viewer at a slight three-quarter angle. These are
> passengers shown in train windows. No train, backgrounds, labels, extra subjects or clipping.

## Cinematic train scene

`train-atlas-v2.webp` is a transparent two-row 1536 × 1024 atlas: power car in the
top 1536 × 512 cell, passenger coach in the lower cell. `prague-station-v2.webp`
is the matching Prague blue-hour environment. Both were created with built-in ImageGen,
then encoded as WebP without changing their geometry. The scene assembles them in code;
passenger count, boarding and expressions still follow the guide state.

Train prompt:

> Production modular train atlas, two equal rows on a transparent 1536 × 1024 canvas.
> A sophisticated navy/graphite electric express power car facing right in the top row
> and a matching panoramic-window coach in the bottom row, both in true side elevation.
> Realistic metallic materials, cyan light trim, warm cabin lighting, detailed bogies,
> concealed steel wheels, cinematic OpenBank Explorer material and lighting reference.
> Equal geometry and baselines for assembly in code; no track, people, lettering,
> steam engine, cartoon blocks or toy proportions.

Correction prompt:

> Remove only the two generated cyan loop logos; restore matching navy metal and the
> continuous trim. Preserve exact geometry, vehicle positions, materials and alpha.
> Leave the vehicles unbranded; the application inserts the real vector brandmark.

Environment prompt:

> Premium Prague railway platform at blue hour, illuminated castle and river skyline,
> midnight navy and cobalt with amber architectural lighting, a steel-and-glass canopy,
> wet stone reflections and subtle cyan platform lighting. Straight horizontal tracks,
> uncluttered foreground for a side-on train. Cinematic detailed 3D environment matching
> the OpenBank Explorer reference; no train, people, characters, logos or lettering.

## Canonical brandmark

`openbank-mark.svg` uses the original vault-medallion and BotB vector geometry from
[Brandmark.jsx](https://github.com/JiRaska/openbank-app/blob/d81735d112c7ae005246de45e6d5d056e9ab1a0a/design-system/components/brand/Brandmark.jsx).
It is exported as a static lockup with the source's supported accent recolouring to cyan.
The mark is composed independently of the generated train artwork, never inferred by ImageGen.

## Heritage steam train

The current scene uses `train-atlas-v3.webp` (1536 × 1024; locomotive in the upper row)
and `heritage-coach.webp` (1448 × 1086). These are original built-in ImageGen assets,
encoded as WebP with their geometry and alpha preserved. The earlier electric atlas is
retained as the material reference. The Prague environment and canonical vector mark
are unchanged. Passenger sprites remain separate so boarding, waving, worry and celebration
still follow the selected journey state. Tall open windows make faces and paws visible.

Locomotive prompt:

> Modular transparent train atlas, 1536 × 1024, two equal rows. Historic Central European
> steam express facing right in strict side elevation, short coal tender, large drive wheels,
> detailed connecting rods, boiler and chimney, fine restrained steam. Premium cinematic 3D,
> midnight navy painted metal, warm brass, restrained cyan pinstripe, amber cabin light.
> Preserve the established material and lighting style. No passengers, logos, lettering,
> track or environment; actual transparent alpha. Matching heritage coach in the lower row.

Coach prompt:

> One very short historic Central European observation coach in strict side elevation,
> width-to-height ratio about 1.4:1, on a transparent canvas. Midnight navy body, warm brass,
> cyan pinstripe, visible steel wheels, luxurious realistic materials matching the locomotive.
> One single very large open window with a dark warm-lit empty cabin, no glass reflections,
> no bars across the opening. No people, logos, text, track or background.

Window refinement prompt:

> Preserve the 1448 × 1086 canvas, silhouette, materials, wheels and alpha. Enlarge only
> the central opening: x=240..1200, top y=270, bottom y=720. Lower the brass sill and cyan
> line to y=745, leaving a narrow lower panel. The opening must fit a mascot's head and
> waving paw. Keep the cabin empty; no logos, text or other changes.
