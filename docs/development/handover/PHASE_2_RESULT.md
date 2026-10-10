# Phase 2 – Ergebnis: eine Config GUI, echte Modulverwaltung, ein HUD-/Overlay-System

Stand: 2026-10-10 · Branch `dev` · Umsetzung der PO-Entscheidung „Funktion vor Optik“ (kein externes Designpaket).
Dieses Dokument ersetzt für Phase 2 den früheren Plan `PHASE_2_IMPLEMENTATION_PLAN.md` (neue `uikit`-Architektur), der ausdrücklich zurückgestellt ist.

## 1. Arbeitspakete und Commits

| Paket | Inhalt | Commit |
|---|---|---|
| P2.1 | Eine Config GUI (`ConfigScreen`), neun Kategorien, `DashboardScreen` und `ThePrisonsHudLayoutScreen` entfernt, `/prisons open` in allen Builds | `8285ad1` |
| P2.2 | Kompaktes Layout für kleine Fenster, Inventar der 204 Settings, Vollständigkeitstests | `22b7734` |
| P2.3 | Echte Aktivierung (Lifecycle, Events, Persistenz), `FeatureProfile.Kind` | `1a98f25` |
| P2.4 | `HudLayout` + HUD-Editor (Snap/Grid aus `hud_layout`, Sichtbarkeit, Tastatur) | `d985ff6` |
| P2.5 | `Panel`-Kit für AH, EE, Energy, Item List, Markt-Karte | `010d3d1` |
| P2.6 | Totes `NebulaHudRenderer` entfernt, Config GUI folgt dem Design-Theme | `960ed21` |

## 2. Ein Einstieg

Alle Wege öffnen `ThePrisonsClient.configScreen(parent, core)` → `gui.config.ConfigScreen`:
Right Shift (Modul-Keybind `click_gui`), der Config-Keybind `I` (Minecraft-Steuerung), `/prisons` und `/prisons gui`, Mod Menu und die Chat-Links des `SetupGate` (`/prisons open <modul> [setting]`).
Es gibt keinen weiteren Config-Screen. `ConfigStructureTest.thereIsExactlyOneConfigScreen` prüft das im Quelltext (unter `gui/` erweitern nur `ConfigScreen` und `HudEditorScreen` die Klasse `Screen`; der HUD-Editor ist ein Werkzeug, keine zweite Config).

`/prisons open` (R16): `ModCommands.register` läuft jetzt in jedem Build. Unbekannte Module oder Settings werden im Chat gemeldet.

## 3. Kategorien

`ConfigCategory.home(module)` ordnet jedes Modul genau einer Heimat-Kategorie zu. Gruppen eines Moduls können in einer anderen Kategorie erscheinen (`ConfigCategory.of`): die Gruppen „Item sorter“ und „Auto use“ des Ore Macro stehen unter Utilities, „Defence“, „Breaks“, „Recovery“ unter PvP & Combat. Das Modul wird dabei nicht doppelt implementiert, nur doppelt verlinkt.

| Kategorie | Module |
|---|---|
| Overview | alle gelisteten Module |
| Mining | `ore_macro`, `waypoint_editor`, `tunnel_vision` |
| Bandit | `bandit_macro`, `spear_helper` (DEV: `bandit_dodge_test`) |
| Meteor Mining | keine (bewusst leer, nicht mit erfundenen Features gefüllt) |
| PvP & Combat | `peaceful_mining`, `vitals_warnings` (DEV: `safety`) |
| Market | `market`, `ah_overlay`, `ee_overlay`, `energy_overlay`, `item_list` |
| HUD & Overlays | `session_hud`, `scoreboard`, `better_tab`, `pet_hud`, `command_cooldowns`, `satchel_hud`, `armor_hud`, `hud_layout`, `tunnel_actionbar`, `player_cards`, `storage_overlay` |
| Utilities | `friends`, `sneak_trade`, `message_notifications`, `ready_announcements`, `cooldown_cache`, `update_checker`, `item_insights` |
| Settings | `click_gui` (Language, Anzeige), `design` |

Kleine Fenster (GUI-Skala 3 bei 1280×720 = 427×240): schmale Seitenleiste mit allen neun Kategorien; Liste und Einstellungen wechseln sich ab (Zurück-Chip oder Esc).

## 4. Aktivierung

`FeatureProfile.kind(module)`:

| Art | Bedeutung | GUI |
|---|---|---|
| `SWITCHABLE` | echter Schalter | Schalter |
| `CORE` (`cooldown_cache`, `update_checker`) | dauerhaft aktiv | Beschriftung „CORE“, kein Schalter, Settings bleiben änderbar |
| `SETTINGS_ONLY` | nur Einstellungen (`click_gui`, `design`, `hud_layout`) | kein Schalter |
| `NOT_IN_BUILD` (`safety`, `performance`, `session_stats`) | im Nutzer-Build entfernt | nicht gelistet (im DEV-Build schaltbar) |
| pausiert | Cosmic Item System | kein Modul, keine Setting, nicht in der JAR |

Die im Nutzer-Build ausgelieferten Features (`FeatureProfile.ON`) sind weiter standardmäßig an, kann der Spieler aber jetzt ausschalten. Das war vorher ein **Fake-Toggle**: `ModuleManager.toggle` ignorierte jeden Schalter eines erzwungenen Moduls.

Ein Fehler hinter dem Ausschalten wurde dabei behoben: Module, die ihre Event-Listener einmal beim Start mit sich selbst als Besitzer registrierten (Spear Helper, Scoreboard, Market, Storage Overlay, Player Cards, Tunnel Vision, Tunnel Action Bar), verloren sie beim ersten Ausschalten dauerhaft bis zum Neustart. Das betraf auch schon die frei schaltbaren Module Spear Helper und (im DEV-Build) alle anderen. `Module.always(type, handler)` registriert dauerhaft und führt den Handler nur aus, solange das Modul an ist; `Module.observe` läuft auch bei ausgeschaltetem Modul (Scoreboard: Beitrittszeit, Level-Cap). `onDisable` von Tunnel Vision und Spear Helper räumt auf, was ihr vorher stiller Tick zurückgesetzt hat. Das Storage Overlay fängt bei ausgeschaltetem Modul keine Tresore und Befehle mehr ab.

Persistenz: Core-Module in `modules.json`, v1-Module (Flags) in `theprisons.json`. Die Setting-IDs und gespeicherten Werte sind unverändert. Die Modul-ID `click_gui` blieb, nur die Anzeige heißt jetzt „Config GUI“.

## 5. Settings

`src/gametest/resources/settings-inventory.txt` enthält die 204 dokumentierten Settings aus Anhang A der Phase-1-Spezifikation (178 Core + 26 Legacy) als `modul.setting typ`.

- `ConfigStructureTest` (Unit): genau 204 eindeutige Einträge, jedes Modul hat eine explizite Kategorie, der Ore Macro verteilt seine Gruppen, nur ein Config-Screen.
- `ConfigGuiClientGameTest` (echter Client, `-Pconfig`): jedes dokumentierte Setting existiert in der Live-Registry und steht in der Kategorie seiner Gruppe; **kein undokumentiertes Setting** existiert (neue Settings müssen ins Inventar); jedes Modul hat ein `keybind`.

Slider-Grenzen, Dropdown-Optionen, Textfelder, Farben, Keybinds und Zurücksetzen (Rechtsklick pro Control, „Reset module“ pro Modul) kommen aus denselben `Setting`-Objekten wie vorher; die GUI schreibt nur darüber.

Nicht Teil der 204 und bewusst nicht übernommen: die Dashboard-Crosshair-Voreinstellungen (`applyLook`) – die zugrunde liegenden Settings des Spear Helper sind vollständig in der Config GUI.

## 6. HUD

`HudLayout` (`gui/hud`) ist die einzige Stelle für Layout-Regeln: Elementliste, Sichtbarkeit (schaltet die Module hinter dem Element), Snapping.

- `hud_layout.snap` und `hud_layout.grid` wirken jetzt: Rastergröße, Einrasten am Raster, an den Bildschirmrändern und an den Mittellinien. Der Editor schreibt beide Settings zurück (Schaltfläche „Snap“, „Grid n“).
- Editor: Ziehen, Mausrad oder `+`/`−` skalieren, Rechtsklick oder `R` setzt zurück, `H`/`Entf` blendet aus, Pfeiltasten verschieben (Shift = ein Rasterschritt), Liste aller Elemente mit Auge (auch ausgeblendete), „List“-Schalter, „Reset all“.
- Das ausgewählte Element hat bei Überlappung Vorrang.
- Module klemmen ihre Position bereits beim Zeichnen auf den Bildschirm; gespeicherte Positionen bleiben bei Auflösungs- oder Skalenwechsel sichtbar.

Das gemeinsame Design-Kit war bereits `gui.kit.Ui` + `Theme` (HUD-Widgets, Scoreboard, Editor, Overlays). Die Config GUI hatte eine eigene Palette; sie folgt jetzt dem Theme.

## 7. Overlays

`gui.kit.Panel`: Panel-Körper und -Rand, Trennlinie, Fortschrittsbalken, Slot-Rahmen, Platzierung neben dem Containermenü oder in einer Ecke (rein, mit `PanelTest`), Tooltip-Block. Genutzt von Energy, Energy Exchange (Karte, Slots, Tooltip), Auction House (Tooltip), Item List und der Markt-Karte für Vanilla-Menüs. Marktberechnungen und Datenquellen sind unverändert; es gibt weder gefälschte Preise noch Statistiken.

Storage Overlay und Spieler-Karten nutzten bereits `Ui`. Originale Minecraft-Menüs bleiben bedienbar (`-Pmarket` bleibt grün).

## 8. Tests und Abnahme

```
./gradlew clean build                     # alle Unit-Tests inkl. ConfigStructureTest, HudLayoutTest, PanelTest, CosmicItemPauseTest
./gradlew checkPausedCosmicItems          # pausiertes Cosmic Item System kompiliert, eigene Tests grün
./gradlew runClientGameTest -Pconfig      # Inventar, Einstieg, Aktivierung, Persistenz, Screenshots, HUD-Editor mit echter Mauseingabe
./gradlew runClientGameTest -Pmarket      # AH, EE, Shops, Tinker, Item List
./gradlew runClientGameTest               # DEV-Profil: Ore-Höhle, Item-Look-Test (aktuell ROT, siehe Abschnitt 9: Warden-Test)
```

Der Aktivierungstest schaltet jedes schaltbare Modul zweimal aus und an und vergleicht die Anzahl der Event-Listener (gleich bleibend), prüft, dass CORE-Module nicht ausschaltbar sind, dass Module außerhalb des Builds aus bleiben, und dass der Zustand nach Speichern und Laden erhalten bleibt (Core-Modul `scoreboard`, v1-Modul `armor_hud`). Macros bleiben unberührt (Einschalten startet sie).

JAR-Scan: `unzip -l build/libs/*.jar` enthält 0 Treffer für `resourcepacks/`, `assets/theprisons/items|models`, `textures/item/prisons`, `ItemLookModule`, `ComicTextures`, `ComicFilter`, `VanillaBases`, `ItemTexturePacks` (874 Einträge).

Screenshots (echter Fabric-Client): `docs/development/handover/phase2/` (Skala 1, 2, 3 bei 1280×720 und 1920×1080, alle Kategorien; HUD-Editor).

## 9. OPEN / UNRESOLVED: Warden-Test der Ore-Höhle

`./gradlew runClientGameTest` (DEV-Profil, ohne `-P`-Schalter) schlägt in `RedstoneCaveClientGameTest.java:224` fehl:

```
java.lang.AssertionError: Came within 0.5481967569594524 blocks of the warden
[cave] closest to the warden: 0.5 blocks      (Schwelle im Test: 13.5 Blöcke, Ziel des Macros: 15)
```

Status: **OPEN / UNRESOLVED**. Ursache nicht untersucht, nicht behoben, nicht eingegrenzt.

Gemessen (je ein Lauf, Logs in `phase2/logs/`):

| Lauf | Stand | Ergebnis |
|---|---|---|
| `default-dev-gametest-AFTER-phase2.log` | `960ed21` (Ende Phase 2) | fehlgeschlagen, 0,5 Blöcke |
| `default-dev-gametest-BASELINE-25f764b.log` | `25f764b` (Stand vor Phase 2, separater Worktree) | ebenfalls fehlgeschlagen, 0,36 Blöcke (`[cave]`-Zeile: 0,4) |

Aus je einem Lauf folgt weder, dass Phase 2 unschuldig ist, noch, dass der Fehler stabil reproduzierbar ist: der Test hängt an Zeit und Navigation, die beiden Werte unterscheiden sich, und beide Läufe endeten zusätzlich mit dem Prozess-Exit `0xC0000409` nach dem Assertion-Fehler (Folge des Absturzes des Test-Clients, vermutlich nicht Ursache). Phase 2 berührt weder Navigation noch Makro-Code, ändert aber Event-Registrierung (`Module.always`), Standard-Aktivierung und Schalter-Semantik einzelner Module. Eine isolierte Untersuchung steht aus: `CODEX_WARDEN_HANDOVER.md`.

Der Test ist **nicht** abgeschwächt oder deaktiviert worden. Die grünen Abnahmeläufe dieser Phase sind `-Pconfig` und `-Pmarket`; der Default-Lauf (Ore-Höhle, Item-Look) ist bis zur Klärung rot und damit kein Abnahmekriterium, das diese Phase erfüllt.

## 10. Bekannte Einschränkungen

- Warden-Test rot (Abschnitt 9).
- Live-Abnahme in Prism auf Cosmic Prisons steht aus (**MANUAL TEST REQUIRED**): alle GUI-Skalen, AH/EE gegen den echten Server, Ausschalten und Wiedereinschalten der Features im laufenden Spiel.
- Beim ersten Start nach dem Update sind die bisher erzwungenen Features an. Wer ein Feature ausschaltet, behält das über Neustarts.
- Die Standard-Positionen mehrerer HUD-Elemente überlappen sich im Titelbild des Gametests (Session HUD, Cooldowns, Pets liegen links oben). Ein sinnvolles Standard-Layout ist ein eigener Folgeschritt.
- Die Config GUI nutzt weiter die Standard-Schrift der Mod und eine eigene Layout-Logik (kein `uikit`); Farben folgen dem Design-Theme, die Optik wurde bewusst nicht neu entworfen.
- Dashboard-Crosshair-Voreinstellungen (`applyLook`) entfielen; die Einzel-Settings des Spear Helper sind vollständig vorhanden.
- Das Entfernen von `NebulaHudRenderer` nahm die Test-Assertions mit, die nur diesen toten Renderer prüften (Zeilentexte „REPAIR REQUIRED“, „Bot State“); diese Anzeigen gibt es im ausgelieferten Session-HUD nicht.
- Die v1-Config (`config/theprisons.json`) bleibt getrennt von `modules.json`; keine Migration in dieser Phase.
- Ein HUD-Editor-Klick auf die Elementliste hat Vorrang vor Elementen darunter (Schalter „List“ blendet sie aus).
- `src/paused/cosmic-items`, `stash@{0}` und `Release` unverändert.

## 11. Abnahme-Artefakte

- Screenshots (echter Fabric-Client): `phase2/config/` (alle neun Kategorien bei 1280×720 Skala 3, 1920×1080 Skala 1 und 2; HUD-Editor-Ablauf), `phase2/market/` (Item List, AH, EE, Tinker, Listings).
- Logs der Läufe: `phase2/logs/`.
- Letzter grüner Stand der Abnahmeläufe: `./gradlew clean build checkPausedCosmicItems`, `runClientGameTest -Pconfig`, `runClientGameTest -Pmarket` auf `960ed21`.
