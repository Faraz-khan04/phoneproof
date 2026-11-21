# PhoneProof design system — "Signal blocks"

Material 3 structure, PhoneProof look: white cards on a soft cool-grey canvas, one teal-blue
interaction family (seed `#0B6E8A`), one big colour block per screen for the verdict.
Inspired by How We Feel for colour-as-meaning, big rounded type, calm spacing and soft shapes.

```
styles.css    tokens (:root) + every component class — the single source of truth
index.html    living style guide: foundations, all M3 components, Dev Mode, Copy as prompt
concept.html  two concept screens: Home and the seller's Report
icons.html    the icon sprite (also embedded in index.html and concept.html)
```

Open `index.html` in a browser (file:// works). Copy as prompt defaults to Jetpack Compose.

## Rules that keep it from looking generic
- One focal point per screen: the large filled button (Home) or the verdict block (Report).
- Colour carries meaning only: success / warning / error / untested, always with an icon and a word.
- Cards are white on the grey canvas with no shadow. Only floating things get a shadow. Never border + shadow.
- Spacing roles: related 8 < section 24 < major 40; gutter 20; touch targets 48.
- One family (Nunito Sans): 800 for display/headline only, 400 for body, tabular figures for numbers.
- No emoji icons, no gradients, no decorative animation.

## Extending
- **New token:** add it to the right group in `:root`; reference it by name, never a raw value.
  A new fill needs its `--on-*` partner, checked to ≥ 4.5:1 (math, not eyeballing).
- **New component:** a class block in `styles.css` using tokens only, a `<section id="key">` in
  `index.html` with `data-insp="key"` on each instance, and a `SPECS.key` entry. The Copy-as-prompt
  button appears automatically.
- **Switching theme:** change only the `:root` token values; components and the concept follow.
- **Dark theme (later):** add a `:root[data-theme="dark"]` override block; no component changes.

## Mapping to Compose
- Colour roles → `lightColorScheme(...)` plus a `PPColors` object for success / warning / untested.
- Type → `Typography` with all 15 M3 styles (Nunito Sans from `res/font`).
- Shape → `Shapes(extraSmall = 8.dp, small = 12.dp, medium = 20.dp, large = 32.dp, extraLarge = 40.dp)`.
- Spacing and elevation → small `object`s of `Dp` values; components read them by name.
