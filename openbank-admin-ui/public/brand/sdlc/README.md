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

`openbank-mark.svg` is a byte-for-byte copy of the current two-leaf Lípa mark in
`openbank-infra/web/landing/assets/lipa-mark.svg`, also used on https://open-bank.tech/.
Keep the original geometry and colours. The retired vault-medallion/BotB lockup is not
used in this scene. The mark is composed independently of generated train artwork.

## Heritage steam train

The current scene uses `train-atlas-v3.webp` (1536 × 1024; locomotive in the upper row)
and `heritage-coach.webp` (1448 × 1086). These are original built-in ImageGen assets,
encoded as WebP with their geometry and alpha preserved. The earlier electric atlas is
retained as the material reference. The active background is the Masaryk-inspired three-track station described below; the brandmark follows the current website identity. Passenger sprites remain separate so boarding, waving, worry and celebration
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

## Cabin composition

`TrainScene.tsx` measures the coach opening in its 300 × 225 display coordinates.
Cabin contents are clipped to x=59..242, y=522..600 and the original coach frame is
redrawn above them through an inverse mask. Seats, smaller passenger sprites and subtle
glazing establish depth behind the brass frame. The idea and PR-check stages use a
proposal and an inspected ticket in the same opening; passengers appear at boarding.
The masthead, coaches and tender all use the current two-leaf mark.

## Masaryk-inspired station and concurrent tracks

`masaryk-station-parallel.webp` is original artwork created with the built-in ImageGen tool.
It is a realistic illustration inspired by the historic Masaryk station hall, not a documentary
photograph. The final 1536 × 1024 image was encoded as WebP with cwebp, without resizing.
The intermediate single-track candidate is not shipped.

The three near running surfaces are at image y=634, 798 and 971. Every train uses a common
local wheel-contact baseline of y=680; the coach is shifted down 6.3px and locomotive 1.4px
before scaling. The entire train translates only horizontally, so playback cannot lift the
wheels off the rail. Contact shadows stay on that baseline.

Each lane has independent illustrative progress and a different playback cadence. Selecting
a lane changes the detail without resetting its progress. Pause and reduced motion apply to
all lanes. A failed-check example affects only the selected lane. This illustrates overlapping
CI; it does not simulate concurrent writes to main or claim three carriers are always admitted.
The bounded batch/strict overlap and reservation limits are documented in the CI guide and
implemented in `.github/scripts/merge-train.py` (the deferral window in `settle`).

Initial generation prompt:

> Use case: photorealistic-natural.
> Asset type: realistic empty railway-station photographic background for an interactive software-delivery train scene.
> Primary request: A genuinely plausible elegant Prague railway station inspired by the historic concourse and platform architecture of Praha Masarykovo nádraží. This replaces a fantasy riverside/castle station. Produce a wide landscape 1536 x 1024 frame. NO train or people: the application will place its own side-on train in the foreground.
> Scene/backdrop: authentic mid-19th-century Central European terminus, warm pale ochre plaster station walls with tall dark arched windows and doors, a historic iron-and-timber glazed canopy with slender dark green cast-iron columns, cream wooden trusses with restrained muted red details, warm spherical pendant lamps, convincing everyday railway details. Late blue hour, soft natural ambient light, warm station illumination, subtle realistic damp stone, sophisticated architectural photography.
> Composition/framing: Camera looks EXACTLY PERPENDICULAR across the rails, at train wheel/waist height, long-lens architectural elevation, straight horizontal rails parallel to the image bottom from edge to edge. NOT looking down the tracks. One foreground railway track. Its NEAR shiny steel running rail must be one continuous absolutely horizontal unbroken rail at approximately y=900 in the 1024px canvas; the other rail is behind it at y=840. Realistic rail head, web, sleepers and gravel ballast. Empty open space above these rails across the entire bottom third for a side-on train occupying y=670..900. Station platform edge behind the track, never in front of the composited train. Architecture is detailed mostly in the upper two-thirds; keep lower third uncluttered and near rail unobstructed. Avoid foreground columns, fences, signs or objects crossing the train area.
> Style/medium: photoreal architectural location photograph, physically plausible materials and scale, restrained colour grading, detailed but natural, believable Czech railway. Not a painting, videogame scene, toy set, fantasy palace or shiny theme park.
> Constraints: no trains, locomotives, coaches, people, animals, logos, text, letters, watermark, city panorama, castle, river, bridge, theatrical gold filigree, neon strips, cartoon rendering or tilted/converging track. This is a background plate inspired by the station, not a claim to documentary accuracy.

Three-track refinement prompt (the first result was the edit target):

> Use case: precise-object-edit.
> Asset type: realistic railway-station background plate for THREE simultaneous train lanes.
> Edit target: the attached generated Masaryk-inspired station photograph. Preserve its believable photographic architecture, warm pale ochre walls, dark green iron columns, cream/red timber-and-glass canopy, warm globe lamps, blue-hour ambient light and damp stone.
> Primary request: Pull the camera back and slightly raise it to show THREE empty parallel railway tracks across the foreground, separated by plausible narrow island platforms. Keep the camera EXACTLY SIDE-ON, perpendicular to the tracks. All rails must be perfectly horizontal from left edge to right edge, never converging towards a vanishing point.
> Layout: 1536 x 1024 pixels. Station architecture occupies the upper 42% (y=0..430). First far track has its NEAR wheel-running rail around y=600; second track around y=770; third near track around y=940. Each track has two rails, sleepers and gravel, and a low platform behind it. Narrow realistic stone platform strips separate the three track beds. Preserve a clear unobstructed train insertion zone 150px tall immediately above EACH of those near running rails, across x=290..1430. No foreground columns or objects in those insertion zones. Photo details can appear behind them. No trains, people, logos, text, signs, labels, castle, river, cartoon, toys, neon or fantasy. This is an original realistic illustration inspired by the historic station, not a documentary photo.
