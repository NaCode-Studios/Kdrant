# Kdrant brand assets

The logo is the whole name — **KDRANT** — cut by a single diagonal hyperplane. The lower half slides
along the cut, and the cut itself is filled with the signature gradient (`#A855F7 → #7F52FF → #2FB2FF`),
masked by the letters so it never runs past the last one. The K on its own is a tile, not the logo.

| File | Use |
| --- | --- |
| `kdrant-lockup-dark.svg` | the logo on a dark surface |
| `kdrant-lockup-light.svg` | the logo on a light surface |
| `kdrant-lockup-mono-dark.svg` / `-mono-light.svg` | one flat ink, no gradient |
| `kdrant-symbol.svg` / `kdrant-symbol-mono.svg` | the offset K on its own — internal use |
| `kdrant-favicon.svg` / `-light.svg` / `kdrant-favicon-512.png` | favicon, avatar, app icon |
| `kdrant-social-preview.png` | the GitHub social preview (2560×1280) |
| `kdrant-tokens.css` | colour, type, space, radius and elevation tokens |
| [`../kdrant-hero.png`](../kdrant-hero.png) | the README banner (2560×680) |

Every SVG here is self-contained — the wordmark is outlined, so it renders identically whether or not
Space Grotesk is installed.

**Construction** — Space Grotesk 700, uppercase, tracking `-0.055em`. The cut runs from 60% of the cap
height on the left to 44% on the right; the lower half offsets by 28% of the body across and 6.5% down;
the gradient band in the cut is about 6% of the cap height. Clear space is half the cap height. Minimum
size is 18px of body — below that, only the K in its tile.

**Type** — Space Grotesk 700 (headings and wordmark), Space Grotesk 400 (body), JetBrains Mono (code,
versions, labels).

**Colour** — surfaces Void `#05060B`, Surface `#0B0E17`, Raised `#121728`; borders `#1A2036`; text Ice
`#EEF1F8`. The accent is Kotlin Purple `#7F52FF` between Violet `#A855F7` and Vector Blue `#2FB2FF`;
Signal `#34D399`, Fault `#FB7185`. The signature gradient appears **once per surface** — the logo, the
primary action, or a single thread of accent. Never as a page background, never under text.

**Don't** — use the K alone as the logo · rotate, skew or stretch the wordmark · change the angle of the
cut · let the gradient leave the letters · add a shadow, an outline or a second gradient · swap the
typeface or alter the tracking · set the name in lowercase inside the lockup.
