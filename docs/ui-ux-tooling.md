# Nexus UI/UX Tooling Decisions

> Status: **Decision record for the native JavaFX desktop clients**  
> Reviewed: 2026-11-14  
> Project baseline: JDK 27, JavaFX 27.0.1, FXML, JavaFX CSS, Gradle  
> Important: a tool being popular or highly starred is not evidence that it is compatible with JavaFX or production-ready for Nexus.

This document records the role, recommendation and adoption gate for each tool evaluated. It avoids overlapping libraries and separates **runtime dependencies** from **agent skills**, **design/prototyping tools** and **reference catalogs**.

## 1. Executive recommendation

### Target toolchain

| Purpose | Recommendation | Adoption status |
| --- | --- | --- |
| Native JavaFX theme and baseline controls | **AtlantaFX 3.0.0** | Preferred candidate; prove with an isolated JavaFX 27.0.1 spike before adding to production modules |
| Icons | **Ikonli 12.4.0 GA** + `ikonli-feather-pack` | Adopted in `desktop-shared`, `desktop-user` and `desktop-admin`; font verified inside both jpackage images |
| AI design research / UX heuristics | **UI UX Pro Max** | Preferred agent skill, but verify every JavaFX recipe against JavaFX 27 and this repository |
| Design critique / polish workflow | **Impeccable concepts** | Borrow workflow; do not assume its commands or checker support JavaFX |
| Product design and visual review | **Figma** | Optional design/prototyping workspace, not a runtime dependency |
| Collaborative AI prototyping | **OpenDesign** | Optional exploratory tool for concept prototypes and design-system discussion |
| Missing native controls | JavaFX built-ins first; **ControlsFX** selectively | Only add when a concrete component gap and JavaFX 27 compatibility are demonstrated |
| Complex dynamic business forms | **FormsFX** selectively | Only if a measured form-maintenance problem justifies the dependency |
| Reference libraries | AwesomeJavaFX, Awesome DESIGN.md, Awesome Design Skills | Catalogs only; inspect each candidate individually |

**Do not install all candidates.** More dependencies increase CSS conflicts, startup/package size, compatibility and licensing costs. Nexus needs a small, well-tested set and a strong in-repository design contract.

## 2. Evaluation criteria

A candidate must be assessed on:
1. Native JavaFX support, specifically JavaFX 27.0.1.
2. Java/JDK compatibility and supported release status.
3. Theme/CSS interoperability with the existing Nexus `ThemeManager` and semantic tokens.
4. Maintenance, release cadence, issue health and public documentation.
5. License compatibility with the way Nexus distributes desktop applications.
6. Runtime and packaging cost on supported desktop operating systems.
7. Whether it solves a concrete product problem without duplicating an existing capability.

GitHub stars are a discovery signal only. They are not a quality score, support commitment, compatibility guarantee or substitute for a proof of concept.

## 3. Candidate-by-candidate decisions

### 3.1 AtlantaFX — preferred JavaFX styling foundation

Repository: https://github.com/mkpaz/atlantafx  
License: MIT  
README baseline observed at review: release 3.0.0; Java 25+ and JavaFX 27+ are stated requirements.

**Why it fits**
- It is specifically a JavaFX CSS theme collection with additional native controls.
- CSS-first styling works with standard JavaFX controls and FXML.
- It provides light/dark theme families, customizable color systems and a sampler application.
- The stated JavaFX 27+ requirement aligns with Nexus JavaFX 27.0.1 and JDK 27.
- The MIT license is permissive, subject to normal notices.

**Decision: preferred foundation and strongest runtime candidate, but not an unconditional dependency.** The v3.0.0 API confirms themes including `PrimerLight`/`PrimerDark` and a built-in `ThemeManager`; the controls package includes `Card`, `ModalPane`, `Notification`, `Popover`, `SegmentedControl`, `Sidebar`, `Tile` and `ToggleSwitch` (among other controls). The exact JavaFX 27.0.1 integration still needs a focused Nexus proof of concept.

Use `PrimerLight`/`PrimerDark` as the first neutral baseline to evaluate—not as an irreversible aesthetic decision. Compare it with Cupertino and Nord in the Sampler, then test the best candidate with Nexus tokens and real screens. AtlantaFX uses looked-up CSS colors with a `-color-*` convention, which is a natural integration point for Nexus's existing `-color-*` vocabulary. The actual variable names are not identical, however: do not assume Nexus `-color-bg` maps automatically to any AtlantaFX background token. Keep Nexus tokens canonical and define/test an explicit mapping or aliases only where needed; do not introduce a second `-nx-*` namespace for the same concepts. See the [AtlantaFX 3.0.0 themes](https://mkpaz.github.io/atlantafx/atlantafx.base/atlantafx/base/theme/package-summary.html), [controls API](https://mkpaz.github.io/atlantafx/atlantafx.base/atlantafx/base/controls/package-summary.html), [theming guide](https://mkpaz.github.io/atlantafx/theming/) and [v3.0.0 changelog](https://github.com/mkpaz/atlantafx/blob/master/CHANGELOG.md).

**Integration rules**
- Start with the base theme artifact only; do not automatically import every AtlantaFX control, decoration, validation module or example.
- Preserve Nexus semantic tokens and its `ThemeManager` as the single application-level owner of SYSTEM/LIGHT/DARK preference.
- Decide whether AtlantaFX themes are the user-agent baseline and Nexus CSS is the application override layer. Document the exact stylesheet order.
- Do not run two theme managers that independently mutate the scene.
- Ensure dialogs, popup windows, menus, ComboBox and DatePicker popup skins inherit the intended theme.
- Use built-in controls only where their semantics fit. `ModalPane` is for an in-scene modal layer, `Popover` for anchored contextual information/actions, `Notification` for short non-blocking feedback, and `SegmentedControl` for a small mutually exclusive choice. `Card` and `Tile` are layout/presentation primitives, not a reason to wrap every piece of content in a card.
- Do not confuse AtlantaFX `SegmentedControl` with ControlsFX `SegmentedButton`; avoid installing both for the same use case.
- Do not use a notification/toast for every routine MLS/key-sync event. Persistent security or connection state should be represented persistently; transient notifications should be reserved for actionable events.
- Avoid using AtlantaFX-specific styling classes from every screen if a Nexus semantic class can express the same intent.
- Keep a product identity distinct from stock AtlantaFX sampler themes.

**Proof-of-concept exit criteria**
1. Compile and run with JDK 27 + JavaFX 27.0.1 on the project's target OS matrix.
2. Existing FXML views load without warnings or missing resources.
3. Existing SYSTEM/LIGHT/DARK behavior and persisted preference remain correct.
4. Rendered TextField, PasswordField, Button, ComboBox, DatePicker, ListView, TableView, ScrollPane, menus, dialogs and popups pass the existing visual/contrast tests.
5. Existing golden screenshot baselines are not unintentionally changed.
6. Native packaging, startup, memory and distribution do not regress materially.
7. The full FX test suite passes.

**Proof-of-concept status (2026-11-18, `./gradlew :ui-spike:spikeCheck` under Xvfb)**

Passing checks, each one an assertion that fails the build:
- Both bundled Nexus fonts load and keep the `Nexus Sans` family.
- `PrimerLight` and `PrimerDark` render the control sheet, and the real `ThemeManager` applies
  `theme.css` last: the field surface becomes the Nexus token (`0xffffff` LIGHT, `0x151c26` DARK)
  instead of Primer's, the theme class is on the root, and the bundled font survives.
- Measured contrast on the rendered controls: 14.99:1 body and 16.22:1 field text in LIGHT,
  15.72:1 and 14.57:1 in DARK (minimum asserted: 4.5:1).
- ComboBox and DatePicker popups open and are skinned; three Feather icons load from FXML with the
  pack's own font file (`FeatherIcons`); `admin.css` layers over the tokens and resolves
  `-color-surface`; `SegmentedControl`, `ToggleSwitch`, `Notification` and `ModalPane` build and
  skin; one `ThemeManager` round-trips SYSTEM/LIGHT/DARK through its preferences store.

The popup gap the spike originally printed (`combo popup stylesheets=[]`) is closed: `ThemeManager`
now styles an owner-bound popup's `Scene` with `theme.css` and the active theme class through
`PopupThemes`, and the spike asserts the popup scene stylesheet, the theme class and the Nexus
surface token (`0xffffff` LIGHT, `0x151c26` DARK) on a rendered ComboBox popup. The same contract is
covered for DatePicker, `MenuButton` context menus, tooltips and `ConfirmDialog` by
`PopupThemeFxTest`, including reopen, a live theme flip, and that closing an owner stops its popup
being tracked: the closed owner's popup keeps the theme it was shown under while a live owner's
popup in the same run follows the switch. `UserGoldenScreenshotFxTest` keeps four pixel baselines
for the same contract — `popup-combo-light/dark.png` and `popup-menu-light/dark.png` — captured from
the live popup window itself, since a popup is not part of the stage capture, with the theme changed
while it is open. They are regenerated like the screens, scoped to that one test:
`USER_UPDATE_GOLDENS=true ./gradlew :desktop-user:fxTest --tests '*UserGoldenScreenshotFxTest.openPopupsKeepTheirThemeAcrossAFlip'`
(and the same with `GOLDEN_BASELINE=ci` for `golden-ci`). Removing the popup class stamping fails
these baselines while every screen golden still matches.

A popup's content hangs off its scene root through JavaFX's internal `CSSBridge`, and a looked-up
colour on that content is resolved from the scene on the far side of the bridge, not from the popup
root. The class on the popup root alone therefore does not survive a flip, a reopen or a lost CSS
pass: `PopupThemes` repeats `theme-light`/`theme-dark` on every node of a popup's content and then
runs the CSS pass unconditionally, even when the class was already right. `ThemeManager` routes a
popup window to it and keeps only the scene-level work, so the popup rules live with the class that
discovers popups. Without
that stamping the ComboBox list and the tooltip label intermittently kept the palette they were
built under (roughly one run in three) while the popup root itself was correct. Application scenes
are deliberately not stamped: their tokens resolve from their own root, and stamping a scene's
descendants changes what the screens render, which the goldens caught.

**Decision (2026-11-18): retain AtlantaFX isolated in `ui-spike`; do not adopt it in the product
modules.** The evidence:
- Nexus `theme.css` already wins the cascade for every shipped control, in both themes, on real
  screens and in the rendered-control contrast checks. Primer contributes no token the product
  needs.
- The one gap that made adoption attractive was popup theming, and it is now solved in
  `desktop-shared` without a second stylesheet layer.
- Adopting Primer would add a second CSS layer plus third-party control skins to track across
  JavaFX upgrades, while the product keeps its own measured contrast, golden baselines and
  accessibility tests.
- The spike stays in the build as the standing proof that a new UI dependency has to clear the
  compatibility, licence and packaging gates before touching production modules.

The check runs in the `fx-test` CI job; its screenshots are uploaded as the `ui-spike` artifact.

Official references:
- https://github.com/mkpaz/atlantafx
- https://github.com/mkpaz/atlantafx/releases
- https://github.com/mkpaz/atlantafx/tree/master/base

### 3.2 Ikonli — preferred icon family integration

Repository: https://github.com/kordamp/ikonli  
License: Apache-2.0  
Stable version choice: **12.4.0 GA**. Do not use 12.5.0 Early Access under Nexus's GA-only dependency policy.

**Icon-pack correction:** the official Ikonli 12.4.0 pack catalogue lists Feather (`ikonli-feather-pack`) but does not list a Lucide pack. Do not invent or add `ikonli-lucide-pack` without a published artifact and official pack/provider evidence. For a coherent thin-line visual style, start the proof of concept with the official Feather pack. It is Lucide-adjacent in visual language but is not the Lucide icon set. If exact Lucide assets are a hard product requirement, evaluate licensed SVG resources separately rather than depending on an unverified Ikonli artifact.

**Why it fits**
- It provides icon packs for Java applications, including JavaFX integrations.
- Font-backed vector glyphs scale more predictably than arbitrary Unicode symbols or raster icons.
- It lets the project establish one coherent icon family and a repeatable icon sizing/accessibility convention.

**Decision: adopted for both desktop clients (2026-11-18).** `desktop-shared` exposes the coordinates
below as API dependencies and ships one icon factory, `Icons`; `desktop-user` and `desktop-admin`
declare them as well so their own runtime classpaths, and therefore their jpackage images, name the
font they ship. Every icon in the product is `Icons.of(...)` / `Icons.set(...)` / `Icons.themeToggle(...)`,
styled by the single `nx-icon` rule in `theme.css`; no glyph character is left in FXML or in a
resource bundle, and each icon-only control carries translated accessible text.

Initial proof-of-concept coordinates:

```groovy
implementation 'org.kordamp.ikonli:ikonli-javafx:12.4.0'
implementation 'org.kordamp.ikonli:ikonli-feather-pack:12.4.0'
```

The official Ikonli guide confirms JavaFX `FontIcon` can be used in FXML and exposes `-fx-icon-code`, `-fx-icon-size` and `-fx-icon-color` CSS properties. It also documents the JavaFX module names for modular applications. Verify the actual pack/font loading inside the current `jpackage` output; the current Nexus modules are not to be changed to JPMS solely by copying a documentation sample. References: [Ikonli JavaFX usage](https://kordamp.org/ikonli/#_javafx), [Feather pack](https://kordamp.org/ikonli/#_feather).

**Packaging and visual result (2026-11-18)**

- `./gradlew :desktop-user:packageClient :desktop-admin:packageAdmin -PpackageType=app-image`
  succeeds on this machine (JDK 27 `jpackage`, Linux).
- In both produced images the Feather font is inside the shipped application, not only on a compile
  classpath: `<image>/lib/app/ikonli-feather-pack-12.4.0.jar` contains
  `META-INF/resources/feather/4.28/fonts/feather.ttf`, alongside `ikonli-core` and `ikonli-javafx`.
  `lib/app` is the input set jpackage records on the image's classpath; `lib/runtime` is its private
  jlink runtime.
- Both packaged applications were launched from their images under Xvfb and screenshotted: the
  theme toggle renders a Feather sun in the dark theme (stored preference), so the icon font loads
  from the jar inside the image rather than from the Gradle test classpath. The loaded family is
  `FeatherIcons`, the name the pack's `feather.ttf` registers, in the spike and in the product.
- The FX tests assert the same wiring without a screenshot: the toggle's graphic is a `FontIcon`
  with `Feather.MOON` in a light view and `Feather.SUN` after a click, and a button without text
  must carry a `FontIcon` plus non-blank accessible text.

Rules:
- Select one coherent pack (for example, one Material Design or Bootstrap pack) as the primary family. Do not mix several packs for visual variety.
- Pin the GA version and the exact JavaFX integration/artifact required; inspect Maven coordinates before adding.
- Use icon glyphs as visuals, not as the only accessible name. Icon-only buttons must have accessible text, tooltip and keyboard focus.
- Use consistent sizes, baseline alignment and spacing. Verify bundled font resources in jpackage outputs on every target OS.
- Avoid icon font usage for complex illustrations or brand marks; use properly licensed SVG/raster assets where appropriate.
- Do not upgrade to an Early Access release simply because it is newer.

References:
- https://github.com/kordamp/ikonli
- https://github.com/kordamp/ikonli/releases

### 3.3 UI UX Pro Max — preferred agent research skill, with a JavaFX verification gate

Repository: https://github.com/nextlevelbuilder/ui-ux-pro-max-skill

The current repository advertises multiple design/UX rule collections and explicitly includes a JavaFX stack dataset. The JavaFX-specific material covers FXML, JavaFX Application lifecycle, FX-thread ownership, Task/Service, observable controls, CSS tokens and layout. However, the dataset inspected for this review references JavaFX 26 documentation and has an August 2026 verification date. Nexus is on JavaFX 27.0.1.

**Decision: use it as a design-research assistant, not as an unquestioned source of production implementation rules.**

Recommended usage:
- Query the JavaFX stack for native implementation guidance.
- Query UX/accessibility guidance for the specific interaction being designed.
- Keep queries narrow and validate the retrieved guidance against OpenJFX 27 documentation and current project code.
- Treat web-only guidance (ARIA, CSS breakpoints, GSAP, browser layout metrics, CLS, WebP/AVIF optimization) as inapplicable unless it has a direct, translated JavaFX equivalent.
- Do not copy web controls, React patterns, Tailwind classes or browser APIs into a JavaFX application.
- Do not let generated style recommendations override [Nexus UI/UX Design System](ui-ux-design-system.md).
- Pin the chosen skill version/commit. Review changes before upgrading it; do not let upstream skill updates silently rewrite product decisions.

Reference:
- https://github.com/nextlevelbuilder/ui-ux-pro-max-skill
- JavaFX stack dataset: https://github.com/nextlevelbuilder/ui-ux-pro-max-skill/blob/main/cli/assets/data/stacks/javafx.csv

### 3.4 Impeccable — borrow the critique process, not its platform assumptions

Repository: https://github.com/pbakaus/impeccable  
License: Apache-2.0

Impeccable offers a design vocabulary and structured workflows for product context, critique, audits, polish, motion, onboarding and documenting a design system. Its README and skill documentation are strongly centered on frontend interface workflows; this review did not find a JavaFX-specific implementation stack. The strongest contribution to Nexus is the repeatable critique/audit/polish discipline, not a browser-specific executor.

**Decision: do not install it as the primary JavaFX implementation skill.** Borrow these process ideas:
- A persistent product brief (`PRODUCT.md` or equivalent).
- A documented visual system (`DESIGN.md` or this project's `ui-ux-design-system.md`).
- A focused critique of one surface or interaction at a time.
- An explicit audit for accessibility, performance, themes, edge cases and interaction states.
- A final polish pass after functional correctness.
- Inspection of the actual rendered target, not just source code or design tokens.

Do not run a browser-based audit or assume its commands can inspect a native JavaFX Scene. Use JavaFX FX tests, screenshots and manual rendered review instead.

Reference: https://github.com/pbakaus/impeccable

### 3.5 Taste Skill — inspiration only; not appropriate as the JavaFX build skill

Repository: https://github.com/leonxlnx/taste-skill  
License: MIT

Taste Skill's current description and installation material target polished websites and frontend implementations with browser-based checking, real photography, page sections, hover behavior and web rendering.

**Decision: do not install or run it as the UI implementation workflow for Nexus.** Its art direction, anti-generic design thinking and insistence on inspecting rendered output can inspire the product. Its page-per-screen rules, marketing imagery, hover recipes, browser checker and web-specific motion constraints are not native JavaFX desktop conventions.

Potential use: read for visual-art-direction inspiration when developing the Nexus brand, without importing its scripts, assets or exact house rules.

Reference: https://github.com/leonxlnx/taste-skill

### 3.6 OpenDesign — optional concept/prototype workspace

Repository: https://github.com/nexu-io/open-design  
License: Apache-2.0

OpenDesign describes itself as a collaborative, local-first AI design-agent workspace, with prototypes, design systems, templates, media and agent integrations.

**Decision: optional for exploration, not a runtime dependency or source of truth.**

Use it when:
- Comparing early visual directions.
- Exploring a screen's composition or interaction sequence.
- Creating a prototype to review with people before implementing.
- Discussing design-system options with an AI agent.

Do not assume a generated web prototype is directly portable to JavaFX. Convert the approved design into JavaFX-specific layout constraints, CSS tokens, FXML components, keyboard behavior and FX tests. The approved repository documentation—not the external workspace—is the source of truth.

For production use, inspect the current release, data handling, local/cloud mode and model/API costs before connecting project material.

Reference: https://github.com/nexu-io/open-design

### 3.7 Awesome DESIGN.md — reference collection only

Repository: https://github.com/voltagent/awesome-design-md  
License: MIT

A curated collection of design-system documents analyzed from other brands. Useful for studying how organizations describe typography, color, spacing, layout and component rules. Treat `DESIGN.md` as a useful human/agent-readable Markdown convention, not as a formal 9-section specification that every project must follow.

**Decision: use as a reference catalog, not a dependency and not a design to copy.**

Rules:
- Study design patterns and documentation structure, not another company's brand identity.
- Check licensing and attribution of any copied asset or code.
- Do not paste a random brand's tokens into Nexus.
- Keep Nexus's own product-specific design contract in this repository.

Reference: https://github.com/voltagent/awesome-design-md

### 3.8 Awesome Design Skills — registry only

Repository: https://github.com/bergside/awesome-design-skills  
License: MIT

A registry/curation of design-oriented `SKILL.md` and `DESIGN.md` resources for agent tools.

**Decision: discovery catalog only.** Use it to discover a specialized skill when a concrete need appears. Inspect each skill's actual target platform, maintenance, license, dependencies, instructions and overlap before adopting it. Do not install dozens of skills or allow several skills to issue conflicting visual directions.

Reference: https://github.com/bergside/awesome-design-skills

### 3.9 AwesomeJavaFX — JavaFX discovery catalog

Repository: https://github.com/mhrimaz/AwesomeJavaFX

A curated directory of JavaFX frameworks, libraries, examples, tools, books and learning material.

**Decision: reference catalog only.** Useful when a concrete native control or development tool is missing. Check the activity, release compatibility, license and maintenance of each linked project independently. Its catalog is not a guarantee that every listed library is current or safe for JavaFX 27.

Reference: https://github.com/mhrimaz/AwesomeJavaFX

### 3.10 ControlsFX — selective controls, not a complete theme

Repository: https://github.com/controlsfx/controlsfx  
License: BSD-3-Clause

ControlsFX provides supplemental JavaFX controls and utilities.

**Decision: allow only for a concrete missing control.** Start with built-in JavaFX controls; next check AtlantaFX's included controls; then evaluate ControlsFX if the feature gap remains. ControlsFX exposes useful options such as `SearchableComboBox` and `SegmentedButton`; for Nexus, `SearchableComboBox` may be worth testing for genuinely large admin filter domains. AtlantaFX already has `SegmentedControl`, so do not add ControlsFX's `SegmentedButton` just to duplicate that behavior. Likewise, do not install ControlsFX `PopOver` when AtlantaFX `Popover` already covers the need. This avoids duplicate controls, competing CSS skins and unnecessary dependencies.

Before adoption:
- Verify the exact latest GA version, JavaFX 27.0.1 support and public API.
- Inspect transitive dependencies and native packaging.
- Avoid internal/implementation packages.
- Add a focused proof covering both themes, popup styling, accessibility, performance and target OS behavior.
- Prefer a small, isolated dependency surface.
- Do not add blanket `--add-exports` or `--add-opens` JVM flags based on a generic warning. Identify the exact control, package and reflective access requirement first; add only the narrowest justified flag, then test the packaged app on each target OS.

Reference: https://github.com/controlsfx/controlsfx

### 3.11 FormsFX — optional for complex business forms only

Repository: https://github.com/dlemmermann/FormsFX  
License: Apache-2.0

FormsFX provides a fluent JavaFX form abstraction with typed fields, grouping, validation and localization. The latest tagged GitHub release visible during this review is **11.6.0**, published 2023-01-23. That release age means JavaFX 27.0.1 compatibility and current maintenance must be independently demonstrated rather than assumed; it is safer to say the release history is stale than to claim the project is formally abandoned.

**Decision: not needed by default.** Consider it only if admin forms become sufficiently complex that the current FXML + focused controller + shared form-field conventions create measurable maintenance cost.

Before adoption:
- Verify release compatibility with JavaFX 27.0.1.
- Check whether its rendered controls integrate with Nexus tokens and AtlantaFX.
- Check keyboard behavior, accessible labels, localization, validation and error placement.
- Confirm the generated structure remains understandable and testable.
- Do not use it simply to replace a handful of straightforward forms.

Reference: https://github.com/dlemmermann/FormsFX

### 3.12 MaterialFX — do not combine with AtlantaFX

Repository: https://github.com/palexdev/MaterialFX  
License: LGPL-3.0

MaterialFX offers a set of Material Design-oriented JavaFX components and its own styling conventions. The latest tagged release visible in the GitHub releases during this review is **11.16.0** (2023); a claimed 11.17.0 release was not verified. Its license is materially different from the MIT/BSD/Apache alternatives, and its styling system has changed between major releases.

**Decision: not selected.** AtlantaFX already addresses the main theme/base-control problem and is the better-aligned choice for JavaFX 27. Combining both introduces overlapping control skins, CSS precedence complexity and greater maintenance. LGPL-3.0 also requires an explicit legal review for the planned desktop distribution model; do not treat it as interchangeable with MIT.

Reconsider only if the product deliberately chooses a Material Design visual system and the project accepts the compatibility and license work.

Reference: https://github.com/palexdev/MaterialFX

### 3.13 PixelDuke open source — alternative ecosystem, not a companion bundle

Website: https://www.pixelduke.com/open-source/

PixelDuke publishes JavaFX-related offerings including JMetro (Fluent-inspired theme), FXRibbon and other components.

**Decision: not selected for the current theme direction.**
- JMetro is an alternative visual theme direction, not an additional theme to stack with AtlantaFX.
- FXRibbon is relevant only if an actual command-ribbon interaction is needed; Nexus's messaging and admin workflows do not currently establish that need.
- Do not infer JavaFX 27 compatibility from a product page or an older sample; verify the exact PixelDuke component and release against JavaFX 27.0.1 before adoption.

Any future use requires an exact product/release, license review and JavaFX 27 proof of compatibility.

Reference: https://www.pixelduke.com/open-source/

## 4. Runtime dependency adoption protocol

No library is added to `desktop-shared`, `desktop-user` or `desktop-admin` merely because it appears in this document.

For each proposed dependency, the agent must:
1. Inspect its official repository, release notes, license and published artifact coordinates.
2. Verify the newest **GA** release and the target JavaFX/JDK compatibility. Nexus rejects alpha, beta, milestone, RC, early-access and snapshot dependencies.
3. Check the current Gradle dependency graph and any duplicate/transitive JavaFX artifacts.
4. Create a focused proof of concept before broad adoption.
5. Verify light/dark themes, FXML, controls/popups, keyboard and accessibility, i18n, golden screenshots and native packaging.
6. Record the chosen version, license, purpose, alternatives considered and why it is needed.
7. Add/update tests and this decision record in the same PR.
8. Pin the exact version; do not use dynamic versions or unverified prereleases.
9. Re-run dependency and license checks whenever upgrading.
10. Remove a dependency if the product no longer uses it.

### Existing Nexus dependency policy is mandatory

The root Gradle build runs a GA-only dependency gate. Do not weaken it to accommodate a tool's Early Access release. In particular, do not substitute Ikonli 12.5.0 Early Access for stable 12.4.0 without an explicit policy change reviewed separately.

## 5. Tools and libraries that are deliberately not selected

| Tool | Why it is not part of the default stack |
| --- | --- |
| Taste Skill | Website/HTML/browser-oriented workflow; its design thinking is inspiration, not JavaFX implementation guidance |
| Impeccable commands/checker | Useful product/critique workflow but no verified JavaFX-specific executor; use native FX screenshots/tests |
| OpenDesign | External design workspace/prototyping tool, not a JavaFX runtime framework |
| Awesome DESIGN.md / Awesome Design Skills / AwesomeJavaFX | Reference registries, not product dependencies |
| MaterialFX | Overlaps the chosen AtlantaFX theme/control layer; LGPL-3.0 requires additional legal review |
| JMetro | Alternative theme direction; never stack with AtlantaFX |
| `ikonli-lucide-pack` | Not listed in the official Ikonli 12.4.0 pack catalogue; use the documented Feather pack unless a published Lucide artifact is independently verified |
| FormsFX | More abstraction than needed unless complex admin forms justify it |
| ControlsFX | Add only for a demonstrated control gap after checking built-in and AtlantaFX controls |
| PixelDuke FXComponents | The JavaFX 27.0.1 compatibility of the exact component/release has not been established in this review |

## 6. Keeping the tool decisions current

Review this document when:
- A dependency is proposed, added, upgraded, replaced or removed.
- JDK or JavaFX is upgraded.
- A tool's project is archived, license changes, GA support changes or maintenance becomes uncertain.
- A previously deferred library is reconsidered.

For each new version, record:
- Exact version and release date.
- GA/prerelease status.
- JDK and JavaFX compatibility evidence.
- License.
- Tested OSes and packaging result.
- Tests run and their outcome.
- New risks, migration steps and rollback path.

Do not update a version number in this document based on a package badge alone. Inspect the official release page and the resolved Gradle artifact.

## 7. Primary links

- [Nexus UI/UX Design System](ui-ux-design-system.md)
- [Nexus Agent UI/UX skill](../.agents/skills/nexus-ui-ux/SKILL.md)
- [AtlantaFX](https://github.com/mkpaz/atlantafx)
- [Ikonli](https://github.com/kordamp/ikonli)
- [UI UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill)
- [Impeccable](https://github.com/pbakaus/impeccable)
- [Taste Skill](https://github.com/leonxlnx/taste-skill)
- [OpenDesign](https://github.com/nexu-io/open-design)
- [Awesome DESIGN.md](https://github.com/voltagent/awesome-design-md)
- [Awesome Design Skills](https://github.com/bergside/awesome-design-skills)
- [AwesomeJavaFX](https://github.com/mhrimaz/AwesomeJavaFX)
- [ControlsFX](https://github.com/controlsfx/controlsfx)
- [FormsFX](https://github.com/dlemmermann/FormsFX)
- [MaterialFX](https://github.com/palexdev/MaterialFX)
- [PixelDuke Open Source](https://www.pixelduke.com/open-source/)
