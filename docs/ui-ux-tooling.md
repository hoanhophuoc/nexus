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
| Icons | **Ikonli 12.4.0 GA** | Preferred candidate; add one icon pack after a packaging and visual proof |
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

**Decision: adopt as the preferred candidate, not an unconditional dependency.** First create a minimal proof in a temporary branch or isolated sample module.

**Integration rules**
- Start with the base theme artifact only; do not automatically import every AtlantaFX control, decoration, validation module or example.
- Preserve Nexus semantic tokens and its `ThemeManager` as the single application-level owner of SYSTEM/LIGHT/DARK preference.
- Decide whether AtlantaFX themes are the user-agent baseline and Nexus CSS is the application override layer. Document the exact stylesheet order.
- Do not run two theme managers that independently mutate the scene.
- Ensure dialogs, popup windows, menus, ComboBox and DatePicker popup skins inherit the intended theme.
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

Official references:
- https://github.com/mkpaz/atlantafx
- https://github.com/mkpaz/atlantafx/releases
- https://github.com/mkpaz/atlantafx/tree/master/base

### 3.2 Ikonli — preferred icon family integration

Repository: https://github.com/kordamp/ikonli  
License: Apache-2.0  
Stable version choice: **12.4.0 GA**. Do not use 12.5.0 Early Access under Nexus's GA-only dependency policy.

**Why it fits**
- It provides icon packs for Java applications, including JavaFX integrations.
- Font-backed vector glyphs scale more predictably than arbitrary Unicode symbols or raster icons.
- It lets the project establish one coherent icon family and a repeatable icon sizing/accessibility convention.

**Decision: preferred candidate after a small integration test.**

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

Impeccable offers a design vocabulary and structured workflows for product context, critique, audits, polish, motion, onboarding and documenting a design system. Its README and skill documentation are strongly centered on frontend interface workflows; this review did not find a JavaFX-specific implementation stack.

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

A curated collection of design-system documents analyzed from other brands. Useful for studying how organizations describe typography, color, spacing, layout and component rules.

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

**Decision: allow only for a concrete missing control.** Start with built-in JavaFX controls; next check AtlantaFX's included controls; then evaluate ControlsFX if the feature gap remains. This avoids duplicate controls, competing CSS skins and unnecessary dependencies.

Before adoption:
- Verify the exact latest GA version, JavaFX 27.0.1 support and public API.
- Inspect transitive dependencies and native packaging.
- Avoid internal/implementation packages.
- Add a focused proof covering both themes, popup styling, accessibility, performance and target OS behavior.
- Prefer a small, isolated dependency surface.

Reference: https://github.com/controlsfx/controlsfx

### 3.11 FormsFX — optional for complex business forms only

Repository: https://github.com/dlemmermann/FormsFX  
License: Apache-2.0

FormsFX provides a fluent JavaFX form abstraction with typed fields, grouping, validation and localization. The README sample still shows 11.3.2; the latest tagged GitHub release visible during this review is **11.6.0**, published 2023-01-23. That release age means JavaFX 27.0.1 compatibility and current maintenance must be independently demonstrated rather than assumed.

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

MaterialFX offers a set of Material Design-oriented JavaFX components and its own styling conventions. Its license is materially different from the MIT/BSD/Apache alternatives, and its styling system has changed between major releases.

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
| FormsFX | More abstraction than needed unless complex admin forms justify it |
| ControlsFX | Add only for a demonstrated control gap after checking built-in and AtlantaFX controls |
| PixelDuke FXComponents | The observed JavaFX 22 documentation does not establish JavaFX 27 compatibility |

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
