# ThePrisons – Projekt-Audit (Phase 1)

Stand: 2026-10-09. Geprüft auf `dev`, Basis-Commit `e30829d` (= `origin/dev` bei Sessionbeginn). Darauf folgt der Pausen-Commit `084f88c` (Cosmic Item System, siehe `docs/development/COSMIC_ITEM_PAUSE_PLAN.md`).
Alle Angaben beruhen auf Code, Git und Build-Läufen dieser Session. Was nicht geprüft werden konnte, ist ausdrücklich markiert.

Verwandte Dokumente: [`RELEASE_AUDIT.md`](RELEASE_AUDIT.md) · [`../design/UI_REPLACEMENT_REQUIREMENTS.md`](../design/UI_REPLACEMENT_REQUIREMENTS.md) · [`../development/COSMIC_ITEM_PAUSE_PLAN.md`](../development/COSMIC_ITEM_PAUSE_PLAN.md) · [`../development/PHASE_2_IMPLEMENTATION_PLAN.md`](../development/PHASE_2_IMPLEMENTATION_PLAN.md)

---

## 1. Kurzfassung

| Bereich | Befund |
|---|---|
| Build | grün (`clean test build`, JDK 21): vor der Pause 737 Tests, danach 732, jeweils 0 Fehler und 1 übersprungen |
| Größe | 364 Java-Dateien in `src/main` (~61 000 Zeilen), 94 Test-Klassen, 5 Client-GameTests |
| Größte Risiken | (1) **Release-Stand und öffentliche Texte widersprechen der Pause**: `Release`, Website und README bewerben die 35 Texturen; (2) **zwei parallele Config-Oberflächen** plus Legacy-v1-HUD; (3) **sieben Farb-/Theme-Quellen**; (4) **drei überlappende AH/EE-Darstellungspfade**; (5) Monolithen (`OreMacroModule` 6 495 Zeilen); (6) keine Live-Server-Verifikation von AH/EE/Lore |
| Toter Code | `gui/ThePrisonsHudLayoutScreen` (kein Aufrufer), `modules/qol/market/MarketSearch` (nur `age()` und ein Test nutzen ihn), `modules/hud/NebulaHudRenderer` (nur Tests), Gradle-Task `legacyBenchmark` (Quellordner existiert nicht) |
| Cosmic Item System | in dieser Session pausiert und aus dem JAR entfernt (2549 → 881 Einträge) |

---

## 2. Repository-Struktur

| Pfad | Inhalt | Ausgeliefert? |
|---|---|---|
| `src/main/java/io/theprisons/` | Mod-Code | ja |
| `src/main/resources/` | `fabric.mod.json`, `theprisons.mixins.json`, `assets/theprisons/{cosmic,font,i18n,lang,textures/gui,textures/font,tunnel,icon.png}`, `assets/minecraft/*` (Logo-/Menü-Icon-Items) | ja |
| `src/paused/cosmic-items/` | pausiertes Item-Look-System (Code, 6 Mixins, ~1 520 Assets, Tests) | **nein** |
| `src/test/` | 94 JUnit-Klassen, Fixtures unter `src/test/resources/{cosmic,items,market,menus,realcave}` | nein |
| `src/gametest/` | 5 Fabric-Client-GameTests (eigener Source-Set, `modId theprisons-gametest`) | nein |
| `archive/` | stillgelegte Experimente (Rüstungs-Anhänge, Masken, HD V2/V4-Packs, Redesign-Quellen), nicht kompiliert | nein |
| `docs/` | Architektur, Core-Rewrite, Bandit, Items, Handover, Marketing, Medien | nein |
| `site/` | GitHub-Pages-Website (statisch, EN/DE) | via Pages |
| `discord-bot/` | Python-3.12-Bot (Stdlib; Listener braucht `discord.py`) | nein |
| `scripts/` | Release-Checks, Changelog-Extraktion, Medien/Trailer (Python/Bash) | nein |
| `tools/` | Texturgeneratoren (`tools/textures/*.py`, V4-Pipeline), `showcase.sh` | nein |
| `gallery/`, `docs/media/` | Bilder für README/Website | nein |

### Paket-Landkarte `src/main/java/io/theprisons`

| Paket | Rolle | Anmerkung |
|---|---|---|
| `ThePrisonsMain` | Main-Entrypoint: registriert die Items `minecraft:theprisons`, `ah`, `ee`, `tinker`, `skilltree` (Icons) | muss vor dem Einfrieren der Registries laufen |
| `ThePrisonsClient` | Client-Entrypoint: Config laden, `ThePrisonsCore.install`, Module registrieren, Legacy-Handler, HUD-Callback | erzeugt `DashboardScreen`/`ClickGuiScreen`/`HudEditorScreen` |
| `core/` | `ThePrisonsCore` (Event-Bus, `ModuleManager`, `ConfigStore`, Control, World, Nav, Profiler, Safety, HUD-Service, Befehle `/prisons`, `/theprisons`) | neue Architektur (Core-Rewrite) |
| `core/cosmic/` | Spielmodell, Sensoren, Parser, State-Store, Capture/Replay | nutzt `PrisonsItems` über `StackReader` |
| `modules/` | Module (Ore Macro, Bandit, Market, HUD, QoL, General) + `ModuleRegistry`, `FeatureProfile` | |
| `modules/legacy/` | Adapter: v1-Features erscheinen als Module mit gebundenen Settings | Werte liegen in `config/theprisons.json` |
| `items/` | Item-Domäne: Registry, Klassifikation, Identität, Suche, `ItemRarity`, Markt (`items/market`), Energie (`items/energy`), Client-Overlays (`items/client`) | |
| `gui/` | `click/ClickGuiScreen`, `dashboard/DashboardScreen`, `hud/HudEditorScreen`, `kit/Ui`, `kit/TextFit`, `theme/Theme`, **`ThePrisonsHudLayoutScreen` (tot)** | wird in Phase 2 ersetzt |
| `hud/` | neues Session-Dashboard: `SessionView`, `SessionViewFactory`, `DashboardLayout`, `DashboardRenderer`, `DashboardStyle` | Layout und Render sind getrennt |
| `feature/`, `ui/`, `bandit/`, `state/`, `config/`, `cache/`, `update/` | **Legacy v1**: `ThePrisonsFeatureManager` (1 053 Zeilen), `ThePrisonsHudRenderer` (621), `ThePrisonsBanditManager`, `ThePrisonsTracker`, `ThePrisonsConfigManager`, `ThePrisonsCache`, `ThePrisonsUpdateChecker` | über `registerLegacyHandlers` an den Core-Bus angeschlossen |
| `mixin/` | 22 aktive Mixins (vorher 28) | siehe §6 |
| `modmenu/` | `ThePrisonsModMenuIntegration` → `ThePrisonsClient.dashboard()` | |

---

## 3. Build-System und Kompatibilität

| Element | Wert | Quelle |
|---|---|---|
| Minecraft | 1.21.11 | `gradle.properties` |
| Yarn | 1.21.11+build.3 | |
| Fabric Loader | 0.17.3 (Build); `fabric.mod.json` verlangt `>=0.17.3`. In Prism läuft 0.19.5 | |
| Fabric API | 0.141.1+1.21.11 | |
| Loom | 1.15.5 | `build.gradle` |
| Gradle Wrapper | 9.3.0 – meldet „Deprecated Gradle features … incompatible with Gradle 10“ | Build-Log |
| Java | 21 (Toolchain) | |
| ModMenu | 17.0.0, nur `modCompileOnly`, in `fabric.mod.json` als `suggests` | |
| JAR-Name | `ThePrisons-<Codename>-v<Version>-mc<MC>.jar`, aktuell `ThePrisons-Nebula-v1.3.0-beta.1-mc1.21.11.jar` | |
| Lizenz | All-Rights-Reserved; `authors` in `fabric.mod.json` ist leer | |

Gradle-Tasks außerhalb von `build`:

- `installPrismMod -PtheprisonsInstallDir=…`: kopiert nur in ein existierendes `mods`-Verzeichnis und verweigert die Installation neben anderen ThePrisons-JARs.
- `runClientGameTest` mit den Profilen `-Pshowcase`, `-Ptunnel`, `-Pmarket`. Ohne Profil läuft der DEV-Build (`-Dtheprisons.dev=true`) mit den Ore-Cave-Tests.
- `checkPausedCosmicItems` (neu): kompiliert und testet den pausierten Baum.
- `legacyBenchmark`: **defekt.** Er verweist auf `archive/pre-core-rewrite/…`; das Verzeichnis wurde nie committet. Der Task kompiliert nichts.

Lokale Umgebung dieser Session: Windows 11, JDK `~/.jdks/ms-21.0.12.1` (nicht auf `PATH`), kein Python, kein `gh`. Git hat auf diesem Rechner keine Identität und `core.fileMode=true`. Daraus entstehen sechs reine Mode-Diffs (`gradlew`, `scripts/*.sh` 755→644), die **nicht committet werden dürfen**, weil CI die Skripte direkt ausführt.

---

## 4. Git-Zustand

| Ref | Stand |
|---|---|
| `dev` (lokal) | `084f88c` (Pause) auf `e30829d` |
| `origin/dev` | `e30829d` – der Pausen-Commit ist **noch nicht gepusht** |
| `origin/Release` | `877185c`. Enthält `dev` bis `e30829d` (PR #20) und **einen Commit direkt auf `Release`**: `877185c Update discord.yml` (Workflow-Umbau, via GitHub-UI). `dev` hat diesen Commit nicht. |
| lokales `Release` | `3d7c8e8`, 73 Commits hinter `origin/Release` (unbenutzt, unverändert gelassen) |
| `codex/v4-pipeline` | lokaler Branch auf `85b3734`, Worktree `/tmp/theprisons-codex-v4` (Linux-Pfad, hier nicht vorhanden) |
| Tags | `v0.0.1-test`, `v1.0.0` … `v1.2.1`, außerdem Alt-Tags `cosmic`, `Cosmic`, `CosmicPrisons`, `ThePrisons-v1.1`. **Kein `v1.3.0-beta.1`.** |
| `stash@{0}` | `WIP on dev: fa30f51 …` – unverändert. Laut Handover: veraltete HD-V2-Assets plus ein unvollständiges Navigations-Experiment; **nicht anwenden**. |

### Codex-Periode (2026-10-08, `0d3cb95` … `e30829d`, 18 Commits, 709 Dateien, +41 775/−1 394)

Import-Pipeline für HD-Texturen (`0d3cb95`), Archivierung von Experimenten (`a508620`), Session-Dashboard (`a6188a8`), V4-Overlay-Gerüst (`7e65092`), vereinheitlichte Rarity-/Markt-Tooltips (`f41b962`, `705f4cc`), Komplett-Editor aus dem Dashboard erreichbar (`3cf74cd`, `0edb03c`), Redesign von `ClickGuiScreen` (`0dbc67c`), AH/EE-Präsentation (`ad7fe8a`), Aktivierung der 35 Sci-Fi-Texturen (`a6df094`), visuelle Abnahme-Screenshots (`fbb1457`, 79 PNG ≈ 11 MB in `docs/development/handover/visual-acceptance-2026-10-08/`), Release-Vorbereitung 1.3.0-beta.1 (`c11ec1b`), Website-Redesign (`e30829d`).

Die Handover-Dokumente (`docs/development/handover/*.md`) sind sachlich und vollständig. Drei Punkte sind darin ausdrücklich offen: der GUI-Fehler bei 1280×720 / Scale 3 (P0), der Live-Test auf Cosmic Prisons (P1) und die Original-Lore/Tooltips (P2). **Diese Session bestätigt den Befund.** Die Abnahme-Screenshots sind Render-Schnappschüsse; Tabs und Dropdowns wurden dabei teilweise per Reflection gesetzt, nicht per echter Eingabe.

---

## 5. Module und Feature-Profil

`FeatureProfile` legt fest, was Nutzer bekommen. `ON` ist immer an, `FREE` darf der Nutzer schalten, `REMOVED` ist aus und unsichtbar, `SYSTEM` sind reine Einstellungsmodule. Mit `-Dtheprisons.dev=true` wird nichts erzwungen.

| Modul-ID | Name | Kategorie | Profil | Taste | Klasse |
|---|---|---|---|---|---|
| `ore_macro` | Ore Macro | MINING | FREE | K | `modules/mining/ore/OreMacroModule` |
| `waypoint_editor` | Waypoint Editor | MINING | FREE | – | `modules/mining/ore/route/WaypointEditorModule` |
| `session_hud` | Session HUD | HUD | ON | – | `modules/hud/SessionHudModule` |
| `scoreboard` | Scoreboard | HUD | ON | – | `modules/hud/scoreboard/ScoreboardModule` |
| `better_tab` | Better Tab | HUD | ON | – | `modules/hud/tab/BetterTabModule` |
| `market` | Market Prices | QOL | ON | – | `modules/qol/market/MarketModule` |
| `sneak_trade` | Sneak Trade | QOL | ON | – | `modules/qol/SneakTradeModule` |
| `spear_helper` | Spear Helper | BANDIT | FREE | L | `modules/qol/bandit/SpearHelperModule` |
| `bandit_macro` | Bandit Macro (~2 %) | BANDIT | FREE | J | `modules/qol/bandit/BanditMacroModule` |
| `bandit_dodge_test` | Bandit Dodge Test | BANDIT | nur DEV | – | `modules/qol/bandit/debug/BanditDodgeTestModule` |
| `tunnel_vision` | Tunnel Vision | GENERAL | ON | F5+V | `modules/general/tunnel/TunnelVisionModule` |
| `tunnel_actionbar` | Tunnel Action Bar | HUD | ON | – | `modules/general/tunnel/TunnelActionBarModule` |
| `friends` | Friends | QOL | ON | – | `modules/qol/players/FriendsModule` |
| `player_cards` | Player Cards | QOL | ON | – | `modules/qol/players/PlayerCardModule` |
| `storage_overlay` | Storage Overlay | QOL | ON | – | `modules/qol/storage/StorageOverlayModule` |
| ~~`item_look`~~ | ~~Item Textures~~ | | **pausiert** | | `src/paused/cosmic-items/…/ItemLookModule` |
| `item_list` | Item List | QOL | ON | – | `items/client/ItemListModule` |
| `ah_overlay` | Auction Overlay | QOL | ON | – | `items/client/AhOverlayModule` |
| `energy_overlay` | Energy Overlay | QOL | ON | – | `items/client/EnergyOverlayModule` |
| `ee_overlay` | Energy Market Overlay | QOL | ON | – | `items/client/EeOverlayModule` |
| `click_gui` | Click GUI | GENERAL | SYSTEM | Right Shift | `modules/general/ClickGuiModule` |
| `design` | Design | GENERAL | SYSTEM | – | `modules/general/DesignModule` |
| `safety` | Safety | GENERAL | REMOVED | – | `modules/general/SafetyModule` |
| `performance` | Performance Monitor | GENERAL | REMOVED | – | `modules/general/PerformanceModule` |
| `pet_hud`, `command_cooldowns`, `satchel_hud`, `armor_hud`, `item_insights`, `message_notifications`, `peaceful_mining`, `vitals_warnings`, `ready_announcements`, `cooldown_cache`, `update_checker` | Legacy v1 | div. | ON | – | `modules/legacy/LegacyModules` |
| `session_stats` | Session Stats (v1) | HUD | REMOVED | – | `modules/legacy/LegacyModules` |
| `hud_layout` | HUD Layout (v1) | HUD | SYSTEM | – | `modules/legacy/LegacyModules` |

Zusätzliche Vanilla-Tastenbelegungen (Kategorie `theprisons:main`): `I` öffnet das Dashboard, `R` setzt die Session zurück, `B` pausiert die Session (`feature/ThePrisonsFeatureManager.register`), Route-Aufnahme Start/Stop (`RouteRecorder`). Die Settings stehen vollständig in `docs/design/UI_REPLACEMENT_REQUIREMENTS.md`, Anhang A.

---

## 6. Tatsächlich aktive GUI-, HUD- und Overlay-Einstiegspunkte

### Screens

| Screen | Geöffnet durch | Datei |
|---|---|---|
| `DashboardScreen` | Right Shift (`ClickGuiModule.onKeybind`), `I` (`ThePrisonsFeatureManager`), `/prisons`, `/prisons gui`, `/theprisons`, ModMenu-Button | `gui/dashboard/DashboardScreen.java` |
| `ClickGuiScreen` | Klick auf eine Feature-Kachel oder „All module & market settings“ im Dashboard (`openClassic`); `/prisons open` (nur DEV, `core/setup/ModCommands`) | `gui/click/ClickGuiScreen.java` |
| `HudEditorScreen` | Dashboard-Seite HUD, Setting `hud_layout.open` | `gui/hud/HudEditorScreen.java` |
| `MarketScreen` | ersetzt `/ah` und `/ee` (`MarketModule.interceptOpen`, wenn `market.redesign` an ist) | `modules/qol/market/MarketScreen.java` |
| `ShopScreen` | ersetzt `/gz`- und `/pb`-Shops | `modules/qol/market/ShopScreen.java` |
| `TinkerScreen` | ersetzt `/tinker` | `modules/qol/market/TinkerScreen.java` |
| `StorageOverlayScreen` | `/pv`-Befehle aus `storage_overlay.commands` | `modules/qol/storage/StorageOverlayScreen.java` |
| `PlayerListScreen` | Shift+Tab | `modules/qol/players/PlayerListScreen.java` |
| `RouteNameScreen` | Ende einer Routenaufnahme | `modules/mining/ore/route/RouteNameScreen.java` |
| ~~`ThePrisonsHudLayoutScreen`~~ | **kein Aufrufer** (tot) | `gui/ThePrisonsHudLayoutScreen.java` |

### HUD (`HudRenderCallback`, alle bis auf eine Ausnahme durch `InventoryItemList.hudGuard` gekapselt)

| Element | Renderer | Registrierung |
|---|---|---|
| Session HUD | `hud/DashboardRenderer` + `DashboardLayout` (Styles Minimal/Standard/Detailed) | `ModuleRegistry` |
| Scoreboard | `ScoreboardModule.render` | `ModuleRegistry` |
| Spear-Helper-Fadenkreuz/Effekte | `SpearHelperModule.render`, `SightStyle`, `CrosshairPreset` | `ModuleRegistry` |
| Tunnel Action Bar | `TunnelActionBarModule.render` (**ohne** `hudGuard`) | `ModuleRegistry` |
| Player Card | `PlayerCardModule.render` / `PlayerCard` | `ModuleRegistry` |
| v1-Widgets: Pets & Trinkets, Command Cooldowns, Satchels, Session XP/Energy, Armor, Notifications/Toasts | `ui/ThePrisonsHudRenderer` (Legacy) | `ThePrisonsClient` |
| Better Tab | `ThePrisonsPlayerListHudMixin` → `TabList` | Mixin |
| Tunnel Vision | `ThePrisonsGameRendererMixin`, `TunnelScene`/`TunnelFx`/`TunnelCarpet` | Mixin + Modul |
| Ore-/Route-Overlays in der Welt | Ore Macro, `BorderMarks`, `WaypointEditorModule` | Event-Bus |

### Overlays auf Containern und Inventar

| Overlay | Hook | Renderer |
|---|---|---|
| Item List im Spielerinventar | `ThePrisonsMarketInventoryMixin` (Render/Key/Char/Click), `ThePrisonsMarketOverlayMixin.mouseScrolled` | `items/client/InventoryItemList` |
| AH-Slot-Rahmen, Badges, Hover-Text | `ThePrisonsItemsOverlayMixin.drawSlot` und `getTooltipFromItem` | `items/client/AhOverlayRender` |
| EE-Karte, Slot-Rahmen, Hover | `ThePrisonsItemsOverlayMixin` | `items/client/EeOverlayRender` |
| Energy-Panel (angesehenes Item/Extraktor) | `ThePrisonsItemsOverlayMixin.render` | `items/client/EnergyOverlayRender` |
| Info-Karte auf Vanilla-AH/EE-Menüs (wenn `MarketScreen` nicht ersetzt) | `ThePrisonsMarketOverlayMixin.render` | `modules/qol/market/MarketOverlay` |
| Item-Insights-Tooltipzeilen, Slot-Overlay (Satchel-Füllstand, Clue-Schritte), Markt-Block | `ItemTooltipCallback`, `DrawItemStackOverlayCallback` | `ThePrisonsFeatureManager.appendTooltip`/`drawItemOverlay`, `items/ItemMarketTooltip` |

Pausiert und **nicht mehr aktiv**: Tier-Rahmen, Badges und Modelltausch (`DrawContext`/`ItemModelManager`-Mixins), Tier-Tooltip-Rahmen (`HandledScreenTooltip`-Mixin), Comic-Filter (`SpriteContents`/`TextureContents`-Mixins), Resource-Pack-Injektion (`ResourcePackManager`-Mixin).

---

## 7. Speichermechanismen

| Datei (unter `.minecraft/config/`) | Schreiber | Inhalt |
|---|---|---|
| `theprisons/modules.json` | `core/config/ConfigStore` | `{schema:1, modules:{<id>:{enabled, settings:{…}}}}`. Nur Abweichungen vom Default; 40 Ticks Debounce, atomarer Schreibvorgang auf dem IO-Executor. Unlesbare Datei → `.corrupt-<time>`. Unbekannte Settings werden beim nächsten Speichern entfernt. |
| `theprisons.json` | `config/ThePrisonsConfigManager` (v1) | Legacy-Werte (`hud.*`, `gui.*`, `qol.*`, `general.*`), angebunden über `Setting.bind`. HUD-Positionen der v1-Widgets. Wird bei jedem Core-Save mitgeschrieben (`addSaveListener`). |
| `theprisons/…` (Daten) | Module | Routen, Border-Marks, Marktkatalog `market/catalog.json`, Preisbuch, Activity-Log, Tunnel-Hintergründe `tunnel/`, `menu_dumps/`, Capture-Archiv. Pausiert und nicht mehr geschrieben: `item_bases.json` |
| Cache | `cache/ThePrisonsCache` | Pet-/Trinket-Cooldowns |

---

## 8. Tests

| Art | Umfang | Lauf in dieser Session |
|---|---|---|
| JUnit (`src/test`) | 94 Klassen (95 Suites). Schwerpunkte: Ore Macro (21), Market (10), Cosmic-Core (8), Items (7), Bandit-Nav (5) | 732 grün, 1 übersprungen |
| Pausierter Baum | `ItemTexturePacksTest` (6), `CosmicItemLookTest` (2) | 8 grün (`checkPausedCosmicItems`) |
| Client-GameTests (`src/gametest`) | `ShowcaseClientGameTest` (Dashboard, Config-Varianten in 3 Skalen × 2 Auflösungen), `MarketClientGameTest` (Item List, AH, EE, Tinker, eigene Angebote), `TunnelClientGameTest`, `RedstoneCaveClientGameTest` (Ore Macro, DEV), `ItemLookClientGameTest` (nur Screenshots von Item-Look und Storage Overlay; ohne Assertions; prüft den pausierten Look nicht mehr) | `-Pmarket` grün (9 Screenshots, keine Missing-Model-Meldungen) |
| Discord-Bot | `discord-bot/tests/test_bot.py` (Stdlib) | nicht lokal (kein Python), läuft in CI |
| Release-/Website-Check | `scripts/check-release.sh` (Python inline) | nicht lokal (kein Python), läuft in CI |

**Fehlende Live-Tests (MANUAL TEST REQUIRED):** Prism-Instanz „Cosmic“ mit dem tatsächlich installierten JAR; `/ah`, `/ee`, `/gz`, `/pb`, `/tinker` mit echten Serverdaten; Original-Lore und Tooltips; Preise und Beobachtungen; GUI-Skala 3 bei 1280×720 mit echter Maus- und Tastatureingabe; Bandit Macro auf dem Server; Ore Macro auf dem Server nach dem Incident vom 2026-10-08 (`docs/incidents/2026-10-08-ore-macro-loop.md`).

---

## 9. Bekannte Bugs, Legacy-Code und technische Risiken

| # | Risiko | Ort | Schwere | Empfehlung |
|---|---|---|---|---|
| R1 | Öffentliche Texte und `Release` bewerben die 35 Texturen; die Pause entfernt sie | `README*.md`, `site/index.html`, `CHANGELOG*.md` 1.3.0-beta.1, Discord-Content, `origin/Release` | **hoch** (Außenwirkung) | Entscheidung des PO vor jedem Merge nach `Release`, siehe `RELEASE_AUDIT.md` |
| R2 | Zwei produktive Config-Oberflächen mit eigenen Controls (`DashboardScreen` 1 125 Z., `ClickGuiScreen` 1 212 Z.) und ein toter Dritter (`ThePrisonsHudLayoutScreen`) | `gui/` | hoch | vollständiger Ersatz in Phase 2 |
| R3 | P0-Layoutfehler bei 1280×720 / GUI-Skala 3 (Tabs werden abgeschnitten, Spalten zu schmal) | `ClickGuiScreen` (feste `SIDEBAR_W=164`, `CONTROL_W=148`) | mittel | **nicht mehr reparieren**: wird durch den Neubau ersetzt |
| R4 | Sieben Farbquellen: `gui/theme/Theme`, `ui/ThePrisonsColors`, Konstanten in `gui/kit/Ui`, `items/ItemRarity`, `items/client/TierColors`, `hud/DashboardStyle`, lokale Hex-Literale (z. B. 69 in `TunnelScene`, 34 in `DashboardScreen`, 27 in `ClickGuiScreen`) | div. | mittel | ein Token-Set (Phase 2) |
| R5 | Drei überlappende AH/EE-Darstellungen: `MarketScreen` (Ersatz-Screen), `MarketOverlay` (Karte auf Vanilla-Menüs), `Ah/EeOverlayRender` (Slot-Overlays auf jedem `HandledScreen`) | `modules/qol/market`, `items/client` | mittel | in Phase 2 auf eine Präsentationsschicht zurückführen |
| R6 | Toter Code: `MarketSearch` (744 Z., Duplikat von `InventoryItemList`), `NebulaHudRenderer` (293 Z., Vorgänger von `DashboardRenderer`), `ThePrisonsHudLayoutScreen` | s. o. | niedrig | in Phase 2 löschen, `age()`/Formatierer verschieben, betroffene Tests anpassen |
| R7 | Legacy-v1-Schicht mit eigener Config-Datei, eigenen Keybinds (`I`, `R`, `B`) und eigenem HUD-Renderer | `feature/`, `ui/`, `config/`, `bandit/`, `state/` | mittel | HUD-Teil in Phase 2 ins Designsystem überführen; Config-Migration ist ein eigener Schritt |
| R8 | Monolithen: `OreMacroModule` 6 495 Z., `TunnelSteer` 2 178, `BanditMacroModule` 1 242, `CombatBrain` 993 | `modules/mining/ore`, `modules/qol/bandit` | mittel | kein Phase-1/2-Thema; nicht anfassen ohne eigenen Auftrag |
| R9 | `legacyBenchmark`-Task verweist auf einen nie committeten Ordner | `build.gradle` | niedrig | Task entfernen oder Quellen nachliefern |
| R10 | Gradle meldet Deprecations für Gradle 10 | Build | niedrig | vor Wrapper-Upgrade prüfen |
| R11 | Texturgeneratoren schreiben nach `src/main/resources/assets/theprisons/textures` und würden pausierte Assets wieder ausliefern | `tools/textures/*.py` | niedrig | `CosmicItemPauseTest` lässt den Build dann fehlschlagen; Generatoren erst bei Reaktivierung umstellen |
| R12 | `ItemLookClientGameTest` fotografiert einen Look, der nicht mehr existiert | `src/gametest` | niedrig | auf Storage Overlay reduzieren oder pausieren (Phase 2) |
| R13 | Bandit Macro ist laut Changelog bei ~2 %; Navigations-Experiment liegt in `stash@{0}` | `modules/qol/bandit` | – | Handover: keine Bandit-Arbeit vor UI-Abnahme |
| R14 | Update-Checker fragt Modrinth und GitHub ab (`update/ThePrisonsUpdateChecker`), ein Modrinth-Projekt `theprisons` ist **nicht verifiziert** | `update/` | niedrig | prüfen, ob das Modrinth-Projekt existiert |
| R16 | **Bug:** `SetupGate` (aufgerufen von `AutomationModule`, also Ore und Bandit Macro im Nutzer-Build) schreibt einen Chat-Link „Set now“ → `/prisons open <modul> <setting>`. `ModCommands` wird aber nur bei `FeatureProfile.DEV` registriert (`ThePrisonsClient`). Im Nutzer-Build führt der Link daher zu einem unbekannten Befehl. Im Code nachgewiesen, nicht live getestet. | `core/setup/SetupGate.java:30`, `ThePrisonsClient.java:64`; Korrekturplan: `PHASE_2_IMPLEMENTATION_PLAN.md` §3b | mittel | in Phase 2 mit dem Deep Link der neuen GUI beheben (`open` in allen Builds, `capture` bleibt DEV) |
| R15 | Uncommittete Mode-Diffs (755→644) auf diesem Rechner | Arbeitsbaum | niedrig | nie mit `git add -A` auf Repo-Ebene committen; ggf. `git config core.fileMode false` (nur auf Wunsch) |

Secret-Scan über alle getrackten Dateien (GitHub-/Slack-/AWS-Token, Discord-Webhooks und Bot-Token-Muster): **keine Treffer**. Der Bot-Token liegt laut Workflow ausschließlich in GitHub Secrets (`DISCORD_BOT_TOKEN`). `site/config.json` enthält keine Schlüssel.

---

## 10. GitHub Pages, Website, Discord-Bot

- **Website** (`site/`): statisch, zweisprachig, Deploy **nur aus `Release`** über `.github/workflows/pages.yml` (Push auf `Release` mit Pfadfilter, veröffentlichter Release oder manueller Lauf). Live unter `https://noxtryn.github.io/ThePrisons/` (HTTP 200). Sie zeigt „Cosmic Evolution“ und „35 approved textures“. Der Build kopiert die 35 PNGs (jetzt aus dem pausierten Pfad) und vier Abnahme-Screenshots nach `site/media/`.
- **Discord-Bot** (`discord-bot/`): Python 3.12. Veröffentlicht offizielle Nachrichten und Release-Posts, registriert Slash-Commands. Der Listener ist laut README **nicht gehostet**. Veröffentlicht wird nur manuell über den Workflow „Discord“ (`workflow_dispatch`, `dry_run` standardmäßig `true`). Auf `Release` wurde der Workflow am 2026-10-08 15:29 UTC manuell gestartet (Erfolg); **ob dabei `dry_run=false` gewählt und tatsächlich gepostet wurde, ist ohne Log-Zugriff nicht feststellbar.**
- **CI** (`ci.yml`): bei Push auf `dev`/`Release` und bei PRs. Ablauf: `check-release.sh`, Bot-Tests und Content-Check, `test build`, JAR als Artefakt. Letzte Läufe auf `dev` und `Release` waren grün.
- **Release** (`release.yml`): Tag `v*` → Abgleich von Version und Tag, Tests und Build, Release-Notes aus dem CHANGELOG (EN + DE), GitHub-Release, Prerelease bei `-`-Suffix.
