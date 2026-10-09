# Current state — 2026-10-09 (Claude Code, Phase 1)

## Repository

- Branch `dev`. Basis bei Sessionbeginn: `e30829d` (= `origin/dev`).
- Neuer lokaler Commit **`084f88c`** `feat(items): pause the Cosmic item look until re-approved`. Danach folgt der Doku-Commit mit diesem Dokument. **Beide sind noch nicht gepusht.**
- `origin/Release` = `877185c` (enthält `dev` bis `e30829d` und einen direkten Commit an `discord.yml`). `Release` wurde nicht angefasst. Es gibt keinen Tag `v1.3.0-beta.1` und keinen Release; öffentlich ist v1.2.1.
- `stash@{0}` (`WIP on dev: fa30f51 …`) unverändert. Die sechs Mode-Diffs (`gradlew`, `scripts/*.sh`, 755→644 durch `core.fileMode=true` unter Windows) wurden weder committet noch zurückgesetzt.

## Phase-1-Ergebnisse

| Dokument | Inhalt |
|---|---|
| [`docs/audit/PROJECT_AUDIT.md`](../../audit/PROJECT_AUDIT.md) | Bestand, Einstiegspunkte, Speicherorte, Tests, Risiken R1–R16 |
| [`docs/audit/RELEASE_AUDIT.md`](../../audit/RELEASE_AUDIT.md) | öffentlicher Stand, Abweichungen, Release-Blocker, JAR-Vergleich |
| [`docs/design/UI_REPLACEMENT_REQUIREMENTS.md`](../../design/UI_REPLACEMENT_REQUIREMENTS.md) | technischer Vertrag für Claude Design inklusive vollständigem Setting-Inventar |
| [`docs/development/COSMIC_ITEM_PAUSE_PLAN.md`](../COSMIC_ITEM_PAUSE_PLAN.md) | Abgrenzung, Umsetzung, Verifikation und Reaktivierung der Pause |
| [`docs/development/PHASE_2_IMPLEMENTATION_PLAN.md`](../PHASE_2_IMPLEMENTATION_PLAN.md) | Zielarchitektur und Migrationsreihenfolge |

## Verifiziert in dieser Session (Windows 11, JDK 21.0.12 aus `~/.jdks/ms-21.0.12.1`)

- Baseline `./gradlew clean test build`: grün, 737 Tests.
- Nach der Pause `./gradlew clean test build`: grün, 732 Tests (8 in den pausierten Baum verschoben, 3 neue Schutztests).
- `./gradlew checkPausedCosmicItems`: grün, 8 Tests.
- `./gradlew runClientGameTest -Pmarket`: grün. Item List, AH, EE, Tinker und eigene Angebote gerendert; keine Missing-Model-Meldungen; Resource-Reload ohne die pausierten Packs.
- JAR: 881 Einträge, 2,6 MB; keine pausierten Klassen, Packs oder Referenzen.

## Nicht verifiziert

- `scripts/check-release.sh` und die Discord-Bot-Tests (kein Python auf dieser Maschine) → laufen in CI beim nächsten Push.
- `runClientGameTest -Pshowcase` (Config-GUI-Screenshots) wurde in dieser Session nicht ausgeführt.
- Live-Test in Prism und auf Cosmic Prisons: **MANUAL TEST REQUIRED**.

## Nächster Schritt

Auf die freigegebene Designspezifikation von Claude Design warten. Bis dahin: keine neue Config GUI, kein Ausbessern der alten GUI (der frühere P0 „ClickGuiScreen bei 1280×720 / Skala 3 reparieren“ entfällt durch den Neubau). Entscheidungen, die vorher fallen müssen, stehen in `RELEASE_AUDIT.md` §3 und `UI_REPLACEMENT_REQUIREMENTS.md` §10.

## Frühere Stände

Der Codex-Stand vom 2026-10-08 (letzter Commit `fbb1457` bzw. `e30829d`) steht in `NEXT_SESSION_1834.md`, `TEST_RESULTS.md`, `VISUAL_ACCEPTANCE_2026-10-08.md` und `DEPLOYMENT_AUDIT_2026-10-08.md`. Die Aussagen dort über die 35 Texturen im JAR gelten seit `084f88c` nicht mehr.
