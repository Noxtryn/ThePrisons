# Phase 2 – Implementierungsplan: neue UI-Architektur

Stand: 2026-10-09 · Basis `dev` `084f88c` · Voraussetzung: **freigegebene Designspezifikation von Claude Design**. Vorher wird keine neue Config GUI implementiert.
Eingaben: [`docs/design/UI_REPLACEMENT_REQUIREMENTS.md`](../design/UI_REPLACEMENT_REQUIREMENTS.md) (Vertrag), [`docs/audit/PROJECT_AUDIT.md`](../audit/PROJECT_AUDIT.md) (Bestand, Risiken).

## 1. Ziele

1. **Eine** zentrale GUI statt `DashboardScreen` und `ClickGuiScreen`.
2. **Ein** Designsystem: zentrale Tokens, eine Theme-Definition, wiederverwendbare Controls.
3. Responsive Layout-Engine, die ab 427×240 skalierten Pixeln trägt (Skala 3 bei 1280×720).
4. Daten-, Layout- und Renderlogik getrennt (das Muster aus `hud/SessionView` und `AhSnapshot` wird zur Regel).
5. HUDs, HUD-Editor und alle Overlays laufen über dasselbe System.
6. Am Ende **keine** doppelten produktiven UI-Implementierungen, keine versteckte Alt-GUI, keine ungenutzten Renderer. Rückfallebene ist die Git-Historie.

## 2. Zielarchitektur

```
io.theprisons.uikit                    (neu; kein Modulwissen, keine Spiellogik)
  theme/   Tokens          ← aus der Design-Spezifikation (Farben ARGB, Abstände, Radien, Typo, Dauer)
           Theme           Accent-Presets; liest design.theme / darkness / animations / sleek_font
  layout/  Node, Insets, Size, Constraints, Stack/Row/Grid/Wrap/Scroll, Breakpoints
           TextMetrics     (Breite/Ellipse/Umbruch; übernimmt gui/kit/TextFit)
  render/  Painter         (einzige Stelle mit DrawContext-Aufrufen: rect, border, nineSlice, text, item, clip-Stack)
  widget/  Widget          measure → arrange → render → input; Zustände hover/focus/active/disabled/changed/error
           Toggle, Slider, NumberField, Segmented, Dropdown, MultiSelect, TextField, ColorPalette, KeyCapture,
           Button, Chip, Badge, StatusDot, ProgressBar, Tabs, ScrollView, Card, Toast, Tooltip, Dialog,
           KpiBlock, DataRow, ItemCard, PriceBlock, EmptyState, LoadingState
  input/   FocusManager (Tab/Pfeile), HitTest aus dem Layoutbaum (statt handgepflegter Hit-Listen)
  anim/    Animator (respektiert design.animations; ohne Animation = Endzustand sofort)

io.theprisons.gui.config               (neu; die eine Config GUI)
  ConfigScreen                         Shell, Navigation, Seiten, Suche, Footer/Statusleiste, Toasts
  model/   ConfigViewModel             liest ModuleManager/Module/Setting/FeatureProfile/ConfigStore; keine Render-Aufrufe
           PageModel, ModuleModel, SettingModel (Typ, Zustand, Sichtbarkeit, Problem, geändert)
           Navigation                  Seitenaufbau (ersetzt gui/click/Tab inkl. Gruppe→Seite)
           SearchIndex                 Module + Settings + Gruppen, EN und DE
           DeepLink                    open(moduleId, settingId) – eine API für Kacheln, /prisons open, SetupGate
  controls/SettingControls             Setting-Typ → Widget (genau ein Mapping für alle 10 Typen)
  presets/ CrosshairPresets            (bisher DashboardScreen.applyLook)

io.theprisons.gui.hud                  HudEditorScreen auf uikit; HudElement-Vertrag bleibt
io.theprisons.hud                      View/Layout bleiben; Renderer zeichnen nur noch über Painter + Tokens
```

Regeln:

- **Nur `uikit.render.Painter` ruft `DrawContext` auf.** Ausnahmen sind Welt-Rendering (Tunnel-Szene, Waypoints, Ore-Pfade) und Vanilla-Item- und Tooltip-Aufrufe, die der Painter kapselt.
- **Farben nur aus `Tokens`.** Ein Architekturtest scannt `src/main/java` außerhalb von `uikit/theme` (und der Weltszene) auf ARGB-Literale; Vorbild ist `CosmicItemPauseTest`.
- **ViewModels sind ohne Minecraft-Client testbar** (reine JUnit-Tests); Widgets und Layout ebenfalls, über `TextMetrics`-Stub.
- **Keine Allokation pro Frame** in HUD und Overlays: Layoutbaum und Texte werden nur bei Datenänderung neu aufgebaut.

## 3. Migrationsreihenfolge

Jeder Schritt ist ein eigener Commit (oder eine kleine Serie) auf `dev`. Pflicht vor jedem Commit: `./gradlew clean test build`, `./gradlew checkPausedCosmicItems`, die passenden Client-GameTests. Screenshots kommen nach `docs/development/handover/`.

| Schritt | Inhalt | Löscht | Abnahme |
|---|---|---|---|
| **P2.0** Vorbereitung | Designspezifikation freigegeben; offene Fragen aus dem Vertrag §10 entschieden; `origin/Release` → `dev` zurückmergen (Freigabe nötig); Release-Strategie für die Pause entschieden (`RELEASE_AUDIT.md` §3) | – | Freigabe PO |
| **P2.1** Fundament | `uikit.theme`, `layout`, `render`, `anim` und `TextMetrics` – rein, ohne sichtbare Änderung | – | Unit-Tests: Layout an allen Breakpoints, Ellipse/Umbruch DE/EN, Token-Vollständigkeit |
| **P2.2** Controls | alle Widgets aus §2 mit allen Zuständen; **Komponenten-Galerie nur in `src/gametest`** (kein produktiver Zweitpfad) | – | GameTest-Screenshots je Control × Zustand × Skala 1/2/3 |
| **P2.3** Datenmodell | `ConfigViewModel`, `Navigation`, `SearchIndex`, `DeepLink`, `SettingControls` | – | **Vollständigkeitstest:** Jedes Setting jedes registrierten Moduls (Nutzer- und DEV-Profil) wird genau einer Seite und einem Control zugeordnet; Ausnahmen nur aus der Liste „entfällt“ |
| **P2.4** Neue Config GUI | `ConfigScreen`; alle Einstiege umhängen: Right Shift, `I`, `/prisons`, `/prisons gui`, ModMenu, Dashboard-Kachel-Ersatz, `/prisons open` **in allen Builds** (behebt R16), `SetupGate`-Links | **im selben Schritt nach bestandener Abnahme:** `gui/dashboard/DashboardScreen`, `gui/click/ClickGuiScreen`, `gui/click/Tab`, `gui/ThePrisonsHudLayoutScreen`, `hud_layout.snap/grid`, Factory-Methoden in `ThePrisonsClient` | GameTest mit **echter** Maus- und Tastatureingabe (Navigation, Suche, jedes Control, Deep Link, Speichern/Laden/Reset) bei 1280×720 und 1920×1080, Skala 1–4; kein abgeschnittener Inhalt |
| **P2.5** HUD-Editor | `HudEditorScreen` auf uikit: Raster, Snap, Hilfslinien, Skalierung per Rad und Feld, Reset | alter Editor-Code | GameTest: Ziehen, Snap, Skalieren, Reset |
| **P2.6** HUDs | Session-HUD-Renderer, Scoreboard, Tab-Liste, Player Card, Toasts, v1-Widgets (Pets, Cooldowns, Satchels, Armor), Tunnel Action Bar, Spear-Fadenkreuz-Farben | `ui/ThePrisonsHudRenderer` (Zeichenteil), `ui/ThePrisonsColors`, `modules/hud/NebulaHudRenderer` (Tests auf `SessionViewFactory` umstellen), `gui/theme/Theme`, `gui/kit/Ui` (sobald unbenutzt) | Profiler: HUD-Kosten ≤ heute; Screenshots aller Elemente |
| **P2.7** Overlays und Screens | Item List, AH/EE (**eine** Präsentationsschicht statt `MarketScreen`-Eigenzeichnung + `MarketOverlay` + `Ah/EeOverlayRender`), Energy-Panel, Shops, Tinker, Storage Overlay, Spielerliste/PlayerViewer, Routenname, Waypoint-Hinweisleiste, Tooltipblöcke | `modules/qol/market/MarketSearch` (`age()` → Formatierer), `items/client/TierColors`, doppelte Overlay-Pfade | `runClientGameTest -Pmarket` mit allen Screens; Original-Tooltips unverändert |
| **P2.8** Abschluss | Architekturtests aktiv (keine Farbliterale, keine `DrawContext`-Aufrufe außerhalb des Painters, keine Referenz auf gelöschte Klassen); Doku und Handover; Live-Abnahme in Prism auf Cosmic Prisons | Restliche Altpfade | **MANUAL TEST REQUIRED**: Live-Server, alle Skalen |

Die alte GUI wird **erst** in P2.4 gelöscht, wenn die neue alle Settings, Aktionen und Einstiege nachweislich übernommen hat (Vollständigkeitstest aus P2.3 plus GameTest). Zwischen P2.3 und P2.4 gibt es keinen Zustand, in dem beide GUIs ausgeliefert werden.

## 3a. Verbindliche PO-Vorgaben (2026-10-10)

1. Die Config GUI wird vollständig neu entwickelt. Die alte wird nach erfolgreicher Migration **entfernt**, nicht als Alternative behalten.
2. **Alle 204 bestehenden Settings** (178 Core + 26 Legacy, Anhang A des Vertrags) werden funktional übernommen. Das gilt auch für `hud_layout.snap` und `hud_layout.grid`, die heute wirkungslos sind. Sie werden im neuen HUD-Editor wirksam (Snap-Standard, Rastergröße). Der Vollständigkeitstest aus P2.3 prüft gegen exakt diese 204 IDs plus ein `keybind` pro Modul.
3. **Sämtliche HUDs und produktiven Overlays**, einschließlich AH und EE, bekommen das neue Designsystem (P2.6, P2.7). Keines bleibt auf dem alten Look.
4. Implementierung erst nach Freigabe des finalen Designpakets durch den PO.
5. Das Cosmic Item System bleibt pausiert (siehe §3c).

## 3b. Korrektur `/prisons open` (Risiko R16) – Teil von P2.4

**Befund (Code-Analyse, nicht live reproduziert):**

- `core/module/AutomationModule.java:42` ruft vor jedem Makrostart `SetupGate.check(this)` auf. Das betrifft `ore_macro` und `bandit_macro`, beide im Nutzer-Build frei schaltbar.
- Bei fehlenden Pflicht-Settings schreibt `core/setup/SetupGate.java:30` je Lücke einen Chat-Link „Set now“ mit `ClickEvent.RunCommand("/prisons open <modul> <setting>")` (`ModChat.link`).
- Den Befehl `open` (und `lang`) registriert `core/setup/ModCommands.register`. Das geschieht nur in `ThePrisonsClient.java:64` hinter `if (FeatureProfile.DEV)`. Im Nutzer-Build ist der Link daher tot (unbekannter Befehl).
- Wann es auftritt: Der Nutzer leert ein Pflichtfeld des Ore Macro (z. B. `vault_shards`, `pet_name`, `ability_names`, `ore_packs`) und startet mit `K`. Mit den Defaults sind die Pflichtwerte gefüllt; deshalb ist der Fehler bisher nicht aufgefallen.
- `capture` liegt zwar in `ModCommands`, ist dort aber zusätzlich über `CaptureService.enabled()` abgesichert. Ein Registrieren in allen Builds macht also kein Entwicklerwerkzeug sichtbar.

**Korrektur in P2.4 (mit der neuen GUI, nicht vorher):**

1. `ModCommands.register(core)` in **allen** Builds aufrufen; die DEV-Bedingung in `ThePrisonsClient` entfällt. `capture` bleibt hinter `CaptureService.enabled()`.
2. `ModCommands.openAt` ruft die neue `gui.config.DeepLink.open(moduleId, settingId)` auf statt `ClickGuiScreen.focus` + `new ClickGuiScreen(...)`. Unbekannte Module oder Settings bekommen eine Chat-Rückmeldung statt eines stillen `return 0`.
3. Dieselbe `DeepLink`-API nutzen die Kacheln und die Suche der neuen GUI. Es gibt genau einen Weg, „Modul X, Setting Y“ zu öffnen.
4. Tests:
   - Unit: Jeder von `SetupGate` erzeugte Befehlstext ist im Befehlsbaum des **Nutzer-Profils** (`theprisons.dev=false`) auflösbar. Zusätzlich gilt für jedes Pflicht-Setting jedes Moduls: `DeepLink` findet Seite und Control.
   - Client-GameTest im Nutzer-Profil: Pflichtfeld leeren, Makro starten, den Chat-Link ausführen. Erwartet: die neue GUI ist offen, das Setting sichtbar und markiert.
5. Abnahme live in Prism: **MANUAL TEST REQUIRED**.

## 3c. Schutz der Pause während Phase 2

- `CosmicItemPauseTest` bleibt im Build und darf nicht abgeschwächt werden. Er prüft, dass keine pausierten Assets, Mixins, Module oder Settings im ausgelieferten Baum liegen.
- Bei jedem Phase-2-Commit gilt: `checkPausedCosmicItems` muss grün sein und der JAR-Scan auf `resourcepacks/`, `assets/theprisons/{items,models}`, `textures/item/prisons`, `ItemLookModule`, `ComicTextures` muss 0 Treffer ergeben.
- Das Designsystem zeichnet Rarity **neben** Items (Kartenrahmen, Label), nie auf oder hinter dem Item-Icon. Es gibt keinen `DrawContext.drawItem`-Hook und keine Item-Modell-, Textur- oder Tooltip-Rahmen-Mixins.
- `tools/textures/*.py` werden in Phase 2 nicht ausgeführt.

## 4. Was bewusst nicht Teil von Phase 2 ist

- Cosmic Item System (pausiert), Item-Texturen jeder Art.
- Bandit Macro und Navigation (Handover: keine Bandit-Arbeit vor UI-Abnahme), `stash@{0}`.
- Zerlegung von `OreMacroModule`/`TunnelSteer`.
- Migration der Legacy-v1-**Config** (`config/theprisons.json`) in `modules.json`. Die gebundenen Settings funktionieren in der neuen GUI unverändert; die Datei-Migration ist ein eigener, später freizugebender Schritt.
- Änderungen an öffentlichen Texten, Website oder Discord ohne Freigabe.

## 5. Risiken und Gegenmaßnahmen

| Risiko | Gegenmaßnahme |
|---|---|
| Design verlangt Effekte, die `DrawContext` nicht kann (Blur, glatte Kurven, Verläufe in beliebige Richtung) | früher Prototyp in P2.1/P2.2 mit Screenshots zurück an Claude Design; Sprites für Formen |
| Platzmangel bei Skala 3/4 | Breakpoints aus dem Vertrag; Layout-Unit-Tests an 320×180 und 427×240 |
| Settings gehen bei der Migration verloren | Vollständigkeitstest (P2.3) als Build-Gate |
| Gespeicherte Werte ändern ihre Bedeutung | Setting-IDs und Typen bleiben unverändert; die neue GUI liest und schreibt nur über die vorhandenen `Setting`-Objekte |
| Performance-Regression in HUDs | Profiler-Sektionen beibehalten; vorbereitete Views; Messung vor und nach P2.6 |
| Theme-Wahl der Nutzer bricht | Abbildung der 6 `design.theme`-Werte auf das neue Preset-System (Vertrag §6) |
| GameTests prüfen nur Render-Schnappschüsse | in P2.4 echte Eingabe-Simulation (`ClientGameTestContext`-Input) statt Reflection |

## 6. Definition of Done für Phase 2

- Eine GUI, alle Einstiege aus dem Vertrag §2 funktionieren, `/prisons open` in allen Builds.
- Alle Settings aus Anhang A erreichbar (Test), alle Controls mit allen Zuständen.
- 427×240 ohne Abschneiden, mit EN und DE.
- HUDs und Overlays nutzen Tokens und Painter; keine Farbliterale außerhalb von `uikit/theme`.
- `DashboardScreen`, `ClickGuiScreen`, `Tab`, `ThePrisonsHudLayoutScreen`, `ThePrisonsHudRenderer` (Zeichenteil), `ThePrisonsColors`, `NebulaHudRenderer`, `MarketSearch`, `gui/kit/Ui`, `gui/theme/Theme` sind gelöscht.
- `clean test build`, `checkPausedCosmicItems` und alle Client-GameTests grün; Live-Abnahme in Prism dokumentiert oder ausdrücklich als offen markiert.
