# Spec 003 — CSS Styles Inspired by Стройноватор

Status: Draft for review — specification only; styling not implemented.
Reference inspected: 2026-10-27.
Platform: Jmix 3.1.0, Java 21, Vaadin 25; existing `fin-assist` theme with `jmix-lumo` parent.

## 1. Purpose

Give Fin Assist a slightly customized, recognizably Стройноватор appearance based on [st-novator.ru](https://st-novator.ru/). Carry the website's crimson accents, neutral palette, Roboto typography, and softened rectangular forms into a practical financial application.

This is a modest theme extension. Keep the existing navigation, data density, component behavior, and business workflows. All adaptation choices below are author proposals for review, not individually approved user decisions.

## 2. Reference Evidence

The public homepage was inspected visually in Chrome, including its header, architectural-image carousel, three-column service cards, lower-page headings, contact band, and footer. Its downloaded HTML contains inline Elementor styles; the following values come from those declarations, not estimated screenshot colours.

| Reference element | Verified source evidence | Interpretation for Fin Assist |
| --- | --- | --- |
| Brand accent | `.elementor-kit-57`, `--e-global-color-47ac373: #D21242`; link and button rules use it | One crimson accent for primary actions, links, selection, and small brand details |
| Main dark colour | `--e-global-color-primary: #1A1A1A`; repeated custom dark token | Headings and primary text; the website also uses a dark footer |
| Secondary dark | `--e-global-color-secondary: #404040` | Supporting headings and strong secondary text |
| Body grey | `--e-global-color-text: #666666` | Help text and secondary descriptions |
| White | `--e-global-color-accent: #FFFFFF` | Main surfaces and text on crimson; the site's token name “accent” means white, not the brand crimson |
| Pale grey | `--e-global-color-6e11b78: #EBECED` | Muted surfaces and decorative separators |
| Main typography | Roboto: primary 600, text 400, accent 500 | Use Roboto consistently for the application |
| Secondary typography | Roboto Slab 400 is declared in the site's global kit and requested as a font | Source fact only; do not introduce a second family into dense application screens |
| Rounded shapes | Global button rules specify 16px corners; text inputs specify 6px; service thumbnails specify 16px | Preserve the softened geometry at smaller application-appropriate sizes |
| Section headings | Homepage headline rules specify Roboto 600 at 29px, with a 25px responsive override | Use a smaller, left-aligned application heading hierarchy |

Visual observations: a spacious white header with a black/crimson wordmark, dark navigation text, white content areas, rounded photographic panels, neutral body copy, crimson links and outline calls to action, short crimson heading accents, a pale-grey contact band, and a charcoal footer. The website's generous spacing and large imagery suit marketing content; they are not dimensions to copy into forms or grids.

Evidence boundary: desktop homepage appearance and delivered CSS declarations were inspected. No exhaustive mobile-site audit, computed-font audit, or inspection of every website route is claimed. Roboto Slab being declared does not establish that it is the dominant visible typeface. Source: [homepage and its inline style definitions](https://st-novator.ru/), notably `.elementor-kit-57` and `.elementor-2` selectors. Temporary inspection files are `/tmp/novator-reference.html` and `/tmp/novator-inline.css`; the extracted values above are the durable reference record.

## 3. Scope and Approach

Recommended: extend the existing shared Lumo theme with a small brand palette, typography, and reusable component styles. This captures the reference's identity while preserving familiar Jmix interaction and layout.

Alternatives considered:

1. Change only the primary colour: smallest change, but misses the site's typography and rounded visual language.
2. Recreate the website's layout: stronger literal resemblance, but oversized headers, photo carousels, and marketing spacing are inappropriate for financial data entry.

In scope: shared application shell, login, existing list/detail views, dialogs, buttons, fields, tabs, grids, menus, notifications, and future dashboard elements specified in Spec 001. Shared styling must apply to both route-based views and overlays.

Out of scope: new screens, logo redesign, copied photography, marketing content, carousels, animated headings, framework replacement, new theme switcher, report-template restyling, entity/schema changes, permissions, validation timing, action availability, or save/cancel semantics. This first version targets the light theme. Do not silently alter a separately enabled dark theme; if one is discovered during implementation, retain its existing appearance and record that boundary.

Relationship to existing specifications:

- [Spec 001](spec-001-mainview.md) owns dashboard structure and behavior. This document supplies its visual tokens; it does not create the dashboard or change the three-indicator/three-tile design.
- [Spec 002](spec-002-detail-views.md) owns editor widths, responsive arrangement, footer visibility, and usability fixes. Its structural requirements take precedence over generic spacing suggestions here.
- Theme styling must work on the current application independently of whether either draft has been implemented.

## 4. Colour System

Define a small set of application-owned custom properties with the `--fin-` prefix. Names below are proposed project tokens, not claimed framework APIs. Keep source colours exact; derived colours are explicitly application choices.

| Proposed token | Value | Origin and use |
| --- | --- | --- |
| `--fin-brand` | `#D21242` | Website; primary action fill, links, active indicators |
| `--fin-brand-hover` | `#B51039` | Derived; hover on primary actions |
| `--fin-brand-active` | `#970D30` | Derived; pressed primary actions |
| `--fin-brand-soft` | `#FCE7ED` | Derived; selected navigation/row surfaces, subtle accent backgrounds |
| `--fin-surface` | `#FFFFFF` | Website; header, drawer, cards, dialogs, fields |
| `--fin-canvas` | `#F7F7F8` | Derived; restrained background behind content surfaces |
| `--fin-surface-muted` | `#EBECED` | Website; secondary bands, grid headers, decorative separators |
| `--fin-text` | `#1A1A1A` | Website; normal data and primary headings |
| `--fin-text-secondary` | `#404040` | Website; labels and supporting headings |
| `--fin-text-muted` | `#666666` | Website; help text and metadata |
| `--fin-border` | `#D8DADD` | Derived; decorative card/grid dividers |
| `--fin-control-border` | `#858A91` | Derived; boundaries needed to identify input controls |

Use white text on solid crimson buttons. Use crimson text on white or the pale accent surface. Do not use crimson as body text or as the general colour of monetary values. Do not use pale decorative borders as the sole boundary of a control requiring visible identification.

Brand crimson is not the semantic error colour. Preserve the active framework's separate success/warning/error palettes and their existing meanings; verify their contrast after the theme change. Invalid fields require error text and a distinct invalid border/icon treatment, while keyboard focus uses an outer ring. Destructive actions retain explicit captions and existing confirmation behavior; they must not become indistinguishable from the primary Save action.

## 5. Typography

- Primary family: `Roboto`, followed by `Arial`, then `sans-serif`. Use Cyrillic and Latin glyph coverage, including Russian currency and punctuation.
- Self-host WOFF2 font assets with the appropriate redistribution licence included. Load only weights 400, 500, and 600, or an equivalent variable file. Use `font-display: swap`; rendering and login must work without external font requests.
- Do not hotlink fonts, CSS, scripts, images, or icons from the reference site. Obtain font assets from their official distribution during implementation.
- Body, field values, navigation, and buttons: 16px-equivalent base (`1rem` at the normal root size), weight 400; action labels and navigation emphasis 500.
- Dense table cells, table headers, field labels, and help text may use 14px-equivalent (`0.875rem`), with header/label weight 500. Do not shrink important data below 14px to solve overflow.
- Page heading: 24px-equivalent, weight 600, line-height about 1.25. Section heading: 18–20px-equivalent, weight 600. Dashboard numbers may use 28–32px-equivalent.
- Body line-height: approximately 1.5; compact controls retain their verified framework alignment and minimum heights.
- Use sentence case. Preserve Russian captions, currency/date formatting, and existing localization.
- Numeric financial columns use tabular numerals and existing right alignment. Do not alter stored values, number parsing, or precision for visual consistency.

## 6. Layout and Visual Elements

### Application shell

Keep the current header, drawer, home link, account controls, and logout placement. Use white shell surfaces, charcoal labels, and a subtle separator against the pale canvas. Apply a small crimson accent to the application-title area and active navigation; do not introduce the website's tall marketing header or dark full-height sidebar.

Selected navigation uses pale crimson fill, crimson text/icon, and a visible side marker. Hover uses a neutral tint and remains different from selection. Keep existing accessible current-item semantics. Icons remain the existing framework icon set, with consistent size and stroke/visual weight; colour is supplementary.

Retain the existing application name and icon assets in this scope. The website's custom wordmark is a reference for colour and emphasis, not a font to imitate or an asset to scrape into the app.

### Spacing, shape, and elevation

- Use a 4/8/12/16/24/32px-equivalent spacing scale. Target 24px content padding on desktop and 16px on narrow screens, except where Spec 002 or existing compact layouts require less.
- Fields and small controls: 6px corner radius. Buttons: 8px. Cards and dialogs: 12px. Larger dashboard tiles may use 16px.
- Keep ordinary surfaces flat with thin borders. Use restrained shadows for dialogs/popovers only; optional card hover shadow must not move or resize surrounding content.
- Use one padding layer around each content region. Do not add nested cards around every field group or increase grid row heights globally.
- A short static crimson rule may accompany the home-page heading. Avoid repeated decorative underlines on every form section, gradients, animated strokes, and background photography.

### Component requirements

| Component | Required appearance and states |
| --- | --- |
| Primary buttons | Solid crimson, white text, 500 weight; darker hover/pressed states; visible outer keyboard focus ring. Preserve the existing primary action hierarchy. |
| Secondary buttons | White/transparent surface, readable dark or crimson text, restrained border; pale hover. Cancel must remain visually subordinate to Save. |
| Tertiary/icon buttons | Minimal background, readable icon/caption, neutral hover, visible focus. Do not remove accessible labels or reduce hit targets. |
| Disabled controls | Clearly subdued using framework disabled styling; no interactive hover or pointer affordance. Do not blanket-fade a whole container containing readable content. |
| Text fields and pickers | White/light surface, 6px corners, readable boundary, dark values, secondary labels; distinguish focused, invalid, disabled, and read-only states. Preserve picker actions and required markers. |
| Checkboxes/radio buttons/switches | Crimson checked state where supported by the active theme; retain native dimensions, focus behavior, and disabled/invalid distinction. |
| Tabs | Neutral inactive labels, crimson active indicator and stronger active label; keyboard focus must remain distinguishable from the active tab. Preserve scrolling/wrapping behavior. |
| Grids | White body, light-grey headers, subtle row separators; pale neutral hover and pale crimson selection with dark text. Preserve sort/filter icons, selection markers, sticky areas, totals, and internal scrolling. No crimson striping. |
| Cards/dashboard tiles | White surface, thin border, 12–16px radius; dark title, muted description, restrained accent icon. Only actionable tiles receive interactive hover/focus styling. |
| Dialogs and overlays | White surfaces, 12px corners, restrained shadow; inherit font and tokens even when rendered outside the view DOM. Preserve title, close control, scroll bounds, and action footer. |
| Notifications and errors | Brand typography with existing semantic variants/icons. Do not turn success, warning, and error notifications into identical crimson messages. |
| Login | Same typeface, fields, primary button, neutral canvas, and restrained card treatment. Preserve existing authentication controls, messages, and any supported locale choice. |

Hover/colour transitions may last 120–160ms. No layout animation or motion-dependent interaction. Respect reduced-motion preferences.

## 7. Accessibility and Responsive Requirements

- Target at least 4.5:1 contrast for normal text and 3:1 for large text and essential control/state indicators. Check rendered pairs, including overlays, selected rows, hover, and validation states.
- Provide a keyboard focus outline at least 2px thick with a 2px separation where feasible. It must remain visible on white, muted, and crimson surfaces; use a contrasting two-layer ring when needed.
- Never encode current navigation, selection, errors, or financial meaning by colour alone. Retain text, markers, icons, and semantic state.
- Do not change framework hit targets or control density merely to match website proportions. Ensure at least 24×24 CSS-pixel pointer targets or sufficient spacing; prefer 44px targets for standalone touch actions where the existing layout permits.
- At 1440×900, 1024×768, 768×800, and 390×844, styling must not introduce clipped captions, overlapping controls, or page/dialog horizontal overflow. Wide grids may retain internal horizontal scrolling.
- At 200% zoom, core navigation and Save/Cancel remain reachable. Test long Russian labels and large monetary values; no truncation may make the action or value inaccessible.
- Font-loading failure must fall back cleanly without missing glyphs or unusable controls.

## 8. Implementation Boundaries

Verified repository locations:

- `src/main/java/ru/stnovator/finassist/FinAssistApplication.java` declares `@Theme(value = "fin-assist")` and imports the Lumo utility stylesheet.
- `frontend/themes/fin-assist/theme.json` declares `"parent": "jmix-lumo"`.
- `frontend/themes/fin-assist/styles.css` imports `fin-assist.css`, `view/main-view.css`, `view/main-view-top-menu.css`, and `view/login-view.css`.
- `frontend/themes/fin-assist/fin-assist.css` is currently a customization placeholder. The current shell CSS already uses Lumo typography, spacing, and contrast tokens.

Put shared brand tokens and font declarations in the existing theme, with component rules either in `fin-assist.css` or a small explicitly imported stylesheet. Keep shell/login rules in their existing files where appropriate. Add descriptor classes only when component scoping cannot be achieved through supported theme hooks. Avoid controller changes solely for static styling.

This specification deliberately does not prescribe unverified Jmix 2 / Vaadin 24 token mappings. Before implementation, verify each Lumo/Vaadin token, component CSS part, variant, and selector against the installed Jmix 3.1 / Vaadin 25 dependencies and official migration documentation. Map the project palette into verified active-theme properties; ensure overlay styles inherit the same values. Do not replace Lumo with Aura or another theme.

Do not copy Elementor selectors, WordPress styles, CSS resets, or website JavaScript into the application. Avoid blanket `button`, `input`, or `*` overrides that interfere with component internals. Prefer supported custom properties and parts, reusable semantic classes, and minimal specificity. Do not edit generated frontend output or add a CSS framework/dependency for this task.

Expected deliverables at implementation time: shared theme CSS/imports, locally packaged font files and licence, narrowly scoped shell/login rules, any necessary descriptor class additions, and before/after verification evidence. No database migration or business-service change is expected.

## 9. Acceptance and Verification

1. **Identity:** app uses exact source crimson and neutral colours in the specified roles, Roboto with Cyrillic coverage, restrained rounded geometry, and no remaining default-blue primary actions in the covered light-theme flows. Semantic information colours are exempt.
2. **Consistency:** login, shell, Customers/Contracts/Projects lists, detail editors, lookup dialogs, nested item dialogs, date pickers, dropdowns, tabs, filters, and notifications share the palette and typeface.
3. **State coverage:** check default, hover, pressed, selected, focused, disabled, read-only, invalid, and loading states wherever supported. A focused selected row/control remains identifiable.
4. **Density and layout:** compare before/after at all four target sizes and 200% zoom. Existing grid density, editor widths, footer reachability, and internal scroll behavior remain usable; styling does not regress Specs 001/002 when present.
5. **Accessibility:** verify contrast of rendered colour pairs, keyboard navigation/focus, long Russian captions, large numbers, reduced motion, and font fallback. Error messages and required markers remain legible and distinct from brand accents.
6. **Behavior:** smoke-test navigation, filtering, sorting, selection, dialog open/close, valid/invalid save, and cancel using isolated test data. No theme change alters permissions, validation rules, data values, or action availability.
7. **Assets:** fonts load locally; no browser request depends on st-novator.ru, Google Fonts, or a third-party styling CDN. Check network failures and browser console errors.
8. **Technical validation:** inspect every edited artifact with the applicable Jmix/static checks, verify imports and symbols, and run terminating `./gradlew --no-daemon clean test` using an isolated datasource. Never use a long-running server as the context-load test.
9. **Browser evidence:** inspect computed font, colour, radius, and focus styles on representative components and overlays, then capture before/after screenshots. A successful build alone does not establish that CSS tokens resolve or component variants render.

Inspect all existing list/detail screens once after global changes; exercise the full state matrix on login, a representative list, Contract/Project detail, a nested schedule-item dialog, and a validation/error flow. Record viewport, locale, view, state, and result. Use targeted regression tests only if implementation changes behavior; avoid brittle tests asserting every spacing value.

## 10. Review Status

This draft is ready for visual/design review. Main proposed choices are: light Lumo adaptation, crimson primary actions, white shell, Roboto only, compact control radii, and self-hosted fonts. The reference site's marketing imagery and typography scale are intentionally outside application scope. Implementation, application rendering verification, and final design approval have not occurred.
