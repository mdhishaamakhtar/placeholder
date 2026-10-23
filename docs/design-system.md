# Design system

The look is monochrome and typographic: black or white, one grey, and color only where it carries meaning (syntax highlighting). Every value comes from tokens at the top of [`styles.css`](../web/src/jsMain/resources/public/styles.css):

- **Type:** Hanken Grotesk for interface and prose, Chivo Mono for code: a neo-grotesque monospace with the same construction, so code and interface read as one family. Both self-hosted. Six roles about 1.125x apart (14px labels and actions, 15px code, 16px body, 18px reading text, 20px page introductions) plus two display sizes for the landing headline and a new short code. All in `rem`, so browser zoom and font settings are respected.
- **Space:** one 4px-based scale (4, 8, 12, 16, 24, 32, 48, 64). Tight steps group related things; wide steps separate groups.
- **Motion:** only where it confirms something or prevents a jarring change, short (120 to 260ms), on a strong ease-out curve, with lighter versions under `prefers-reduced-motion`. The one flourish is a new short code rising in letter by letter.
- **Controls:** no browser-native widgets where they can't match the theme; menus are a themed combobox that works fully from the keyboard. Text actions get 44px touch targets on touch screens.
- **Themes:** dark by default, light on request, chosen server-side from a cookie so the first paint is already right.
