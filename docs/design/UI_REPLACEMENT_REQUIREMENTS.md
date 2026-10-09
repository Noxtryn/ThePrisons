# ThePrisons – UI Replacement Requirements (technischer Vertrag für Claude Design)

Stand: 2026-10-09 · Code-Basis: `dev` `084f88c` (Cosmic Item System pausiert) · Ersteller: Lead Developer (Claude Code)

Dieses Dokument beschreibt, **was** die neue Oberfläche können muss und **unter welchen technischen Bedingungen** sie läuft. Es beschreibt **nicht**, wie sie aussehen soll; das liefert Claude Design. Alles hier ist aus dem aktuellen Code abgeleitet. Datei- und Klassennamen beziehen sich auf `src/main/java/io/theprisons/`.

**Auftrag:** vollständiger Neubau. Die bisherige Config GUI (`DashboardScreen` + `ClickGuiScreen`) wird ersetzt, nicht überarbeitet. Nach der Migration bleibt keine alte Oberfläche als versteckte Alternative bestehen.

**Ausgeschlossen:** alles, was zum pausierten Cosmic Item System gehört: Item-Texturen, Texturquelle, Tier-Rahmen, Badges, Tier-Tooltip-Rahmen, Comic-Filter, eigener Tooltip-Rahmen, die 35 HD-Texturen (siehe `docs/development/COSMIC_ITEM_PAUSE_PLAN.md`). Dafür gibt es keine Komponenten und keine Platzhalter.

---

## 1. Plattform und harte Einschränkungen

### 1.1 Rendering

- Minecraft 1.21.11, Fabric, Java 21, **nur Client**. Gerendert wird ausschließlich über Minecrafts 2D-`DrawContext`: gefüllte Rechtecke, Farbverläufe, Linien, GUI-Sprites aus dem Atlas (mit 9-Slice per `.mcmeta`), Item-Icons, Text und Tooltips. Es gibt kein HTML/CSS, keine Vektorpfade, kein Anti-Aliasing für Formen und keine Blur-Shader. „Abgerundete Ecken“ entstehen nur pixelweise oder über Sprites.
- Koordinaten sind **skalierte GUI-Pixel**. Die GUI-Skala ist ganzzahlig (1–4 oder Auto) und wird vom Spieler gewählt. Daraus folgen die effektiven Arbeitsflächen:

  | Fenster | Skala 1 | Skala 2 | Skala 3 | Skala 4 |
  |---|---|---|---|---|
  | 1280×720 | 1280×720 | 640×360 | **427×240** | 320×180 |
  | 1920×1080 | 1920×1080 | 960×540 | 640×360 | **480×270** |
  | 2560×1440 | – | 1280×720 | 853×480 | 640×360 |

  **Pflichtziel:** voll bedienbar ab **427×240** skalierten Pixeln, ohne abgeschnittene oder überlappende Inhalte. Dort ist die heutige GUI gescheitert (P0 aus dem Handover). Bei 320×180 sind Scrollen und Zusammenklappen erlaubt; Abschneiden ist auch dort nicht erlaubt.
- Text: Die Zeilenhöhe der Minecraft-Schrift beträgt 9 px (Glyphenhöhe 7–8 px). Text lässt sich per Matrix skalieren; Werte unter 1,0 werden schnell unleserlich, Faktoren über 1,0 nur ganzzahlig empfehlen. Verfügbare Schriften: Minecraft-Standard, `theprisons:boxy` (Bitmap, Setting „Boxy font“) und `theprisons:nebula` (Noto Sans Medium TTF). Eine neue Schrift braucht eine Lizenz und wird als Bitmap oder TTF-Provider eingebunden.
- Farben sind ARGB-Integer. Alpha-Blending ist möglich, Hintergrund-Blur nicht. Der Vanilla-Hintergrund lässt sich abdunkeln (Setting `click_gui.dim_background`).
- Clipping nur mit rechteckigen Scissor-Bereichen, verschachtelbar.
- Animation: Pro Frame wird neu gerendert. Interpolationen (Ease, Approach, Pulse) existieren bereits in `gui/kit/Ui`. Jede Bewegung muss das Setting `design.animations = false` respektieren (dann ohne Übergänge).

### 1.2 Eingabe

- Maus: links, rechts, mittel; Doppelklick; Ziehen; vertikales und horizontales Scrollen; Hover. **Rechtsklick** wird heute als „auf Standard zurücksetzen“ genutzt (Settings, HUD-Elemente). Diese Funktion muss erhalten bleiben, darf aber anders ausgelöst werden.
- Tastatur: Tastencodes (GLFW) und Zeichen-Eingabe getrennt. Escape schließt Screens; Enter bestätigt; Backspace löscht. Eine Keybind-Aufnahme muss jede Taste annehmen können, auch Shift, Strg und Funktionstasten.
- Keine Touch-Bedienung. Tastaturnavigation (Tab/Pfeile) ist heute nicht vorhanden. Gewünscht ist sie mindestens für Suche, Listen und Dialoge.

### 1.3 Performance

- HUD-Elemente und Overlays werden **jedes Frame** gezeichnet. Pro Frame keine Allokationen, kein Parsing und keine Berechnung. Das Projekt trennt bereits sauber zwischen *vorbereiteter Datenansicht* (z. B. `hud/SessionView`, `items/market/AhSnapshot`, `items/energy/EnergyOverlayModel`) und einem *dummen Renderer*. Das neue Designsystem muss dieses Muster beibehalten.
- Layouts werden nur neu berechnet, wenn sich Daten, Breite, Skala oder Stil ändern (Beispiel: `SessionHudModule` cacht sein `DashboardLayout`).
- Der In-Game-Profiler misst HUD-Kosten (`legacy:hud-render`, Modul-Sektionen). Die neue HUD-Schicht muss darin sichtbar bleiben.

### 1.4 Sprache

- Englisch und Deutsch, live umschaltbar (`click_gui.language`, `core/i18n/I18n`, Übersetzungen in `assets/theprisons/i18n/de.json`). Alle Layouts müssen deutsche Texte vertragen; diese sind typischerweise 20–35 % länger. Keine Beschriftung darf in eine feste Breite gepresst werden. Gekürzt wird nur mit Ellipse und mit Tooltip für den vollen Text.
- Modul- und Setting-Namen kommen aus dem Code (englisch) und werden zur Laufzeit über `I18n.t()` übersetzt.

### 1.5 Screens innerhalb von Container-Menüs

- AH, EE, Shops, Tinker, Storage und das Spielerinventar sind `HandledScreen`s mit **echten Server-Slots**. Ein eigenes Layout darf Slots verschieben (wie `MarketScreen`, `TinkerScreen`), muss aber jeden Slot klickbar, sichtbar und unverdeckt halten. Slot-Raster: 18 px pro Slot, Icon 16 px.
- Das Spielerinventar ist in 1.21.11 ein `RecipeBookScreen`. Die Item List hängt sich per Mixin an (`ThePrisonsMarketInventoryMixin`) und nutzt den Platz rechts und unterhalb des Inventars. Bei kleinen Skalen ist dieser Platz knapp.
- Overlays auf Vanilla-Menüs dürfen keine Server-Inhalte überdecken und nichts anklicken. Sie sind rein informativ (Projektregel).

### 1.6 Darstellung von Items

- Item-Icons werden mit `drawItem` gezeichnet und sehen **genau so aus, wie Server, Vanilla oder die Resource-Packs des Spielers sie liefern**. ThePrisons verändert Item-Grafiken nicht (Pause). Rarity wird **neben** dem Icon kommuniziert (Kartenrahmen, Label, Farbbalken), nie durch Übermalen des Icons.
- Original-Tooltips (Name, Lore) bleiben unverändert. ThePrisons hängt Blöcke **an** (`MARKET DATA`, Item Insights), ohne Zeilen zu ersetzen.

---

## 2. Einstiegspunkte, die die neue GUI übernehmen muss

| Einstieg | Heute | Anforderung |
|---|---|---|
| Right Shift (Setting `click_gui.keybind`, umbelegbar) | `DashboardScreen` | öffnet die neue zentrale GUI |
| `I` (Vanilla-Keybinding `key.theprisons.open_config`, in den Minecraft-Steuerungsoptionen umbelegbar) | `DashboardScreen` | öffnet die neue GUI. Zwei Tasten für dieselbe Aktion (Right Shift als Modul-Keybind, `I` als Vanilla-Keybinding) sind historisch bedingt. Ob beide bleiben, entscheidet der PO. |
| `/prisons`, `/prisons gui`, `/theprisons` | `DashboardScreen` | öffnet die neue GUI |
| `/prisons open <modul> [setting]` | `core/setup/ModCommands` → `ClickGuiScreen.focus` + `ClickGuiScreen`. **Nur im DEV-Build registriert**, obwohl `SetupGate` (Ore/Bandit Macro) den Link „Set now“ auch im Nutzer-Build in den Chat schreibt → dort heute ein unbekannter Befehl (Bug, im Code nachgewiesen, nicht live getestet) | Deep Link der neuen GUI; Befehl in **allen** Builds verfügbar |
| ModMenu „Config“ | `ThePrisonsModMenuIntegration` → `ThePrisonsClient.dashboard(parent)` | neue GUI mit `parent` als Rückkehrziel |
| Deep Link | `ClickGuiScreen.focus(module, settingId)`: öffnet ein Modul und hebt ein Setting 3 s blinkend hervor. Aufrufer: Dashboard-Kacheln und `/prisons open` | Pflicht: „öffne Modul X, scrolle zu Setting Y, markiere es“. Gebraucht von den Chat-Hinweisen bei fehlenden Pflicht-Settings (`SetupGate`, `Setting.required`). |
| HUD-Editor | Dashboard-Seite HUD sowie Action-Setting `hud_layout.open` | eigener Vollbild-Modus der neuen GUI (siehe §5.2) |
| Welcome-/Setup-Flow | `core/setup` (Chat-Links, Sprache) | Sprachwahl und erstes Öffnen müssen aus der neuen GUI erreichbar sein |

---

## 3. Datenmodell: was die GUI bedient

### 3.1 Module (`core/module/Module`)

| Feld/Methode | Bedeutung für die UI |
|---|---|
| `id()`, `name()`, `description()` | Identität, Titel, Beschreibung (über `I18n.t` übersetzt) |
| `category()` | `Category`: MINING, METEOR_MINING, BANDIT, PVP, COMBAT, QOL, HUD, GENERAL. METEOR_MINING, PVP und COMBAT sind aktuell **leer** und dürfen nicht als leere Seiten erscheinen. |
| `group()` | Untergruppe innerhalb der Kategorie (z. B. „Widgets“, „Items“, „Players“) |
| `enabled()` / `toggleable()` | An/Aus. `toggleable()==false` (z. B. `click_gui`, `design`) bedeutet: kein Schalter, „immer aktiv“. |
| `ModuleManager.forced(module)` | `FeatureProfile`: `true`/`false` bedeutet vom Produkt erzwungen, also **gesperrter** Zustand mit Erklärung; `null` heißt frei schaltbar. Im DEV-Build ist alles frei. |
| `canEnable()` | Grund, warum ein Einschalten gerade nicht geht (z. B. Pflicht-Setting fehlt) – als Hinweis anzeigen |
| `status()` | `Status(level, text)` mit `StatusLevel` OFF, IDLE, ACTIVE, WARNING, ERROR. Live-Statuszeile (z. B. „Mining · 4.8 OP/s“). |
| `lastStopReason()` | Warum ein Makro zuletzt stoppte. Anzeigen, bis es neu startet. |
| `missing()` | Liste der fehlenden Pflicht-Settings → Fehlerzustand und Deep Link |
| `keybind()` | `KeybindSetting` des Moduls (jedes Modul hat genau eines; `NONE` = nicht belegt) |
| `settings()` | geordnete Settings; die Reihenfolge ist bedeutungstragend |
| `ModuleManager.search(query)` | vorhandene Modulsuche |

### 3.2 Setting-Typen → benötigte Controls (`core/setting/Settings`)

Jeder Setting-Typ braucht eine Komponente mit den Zuständen **normal, hover, fokussiert/aktiv, deaktiviert, geändert (≠ Default), fehlerhaft (required), unsichtbar (`visibleWhen`)**.

| Typ | Daten | Benötigtes Control | Pflichtverhalten |
|---|---|---|---|
| `BoolSetting` | boolean | Schalter | Klick schaltet um; Reset auf Default |
| `IntSetting` | int, `min`, `max`, `step`, optional `suffix` (Einheit) | Slider **plus** exakte Eingabe | Ziehen rastet auf `step`; Werte werden geklemmt; Einheit anzeigen. Spannweiten bis 0–4000 (Positionen) bzw. 0–600 (Minuten) – ein Slider allein reicht dort nicht |
| `DoubleSetting` | double, `min`, `max`, `step`, `suffix` | wie `IntSetting` | Anzeige mit sinnvoller Nachkommazahl (aus `step` ableiten) |
| `EnumSetting<E>` | feste Optionen mit Label | Segment-Control (≤ 4 kurze Optionen) oder Dropdown | Labels können lang sein („Cosmic Textures first“) |
| `ChoiceSetting` | **dynamische** Optionen `Option(id, label, section, color)` plus `offLabel` | Dropdown mit Abschnittsüberschriften, Farbpunkt je Option und „Aus“-Eintrag | Optionen ändern sich zur Laufzeit (gespeicherte Routen, Bilddateien) – leere Liste muss gut aussehen |
| `MultiChoiceSetting` | Menge von Option-IDs, Optionen mit `section` und `color` | Mehrfachauswahl (Chips oder Checkliste) mit Abschnitten | Beispiel `ore_macro.ore_packs`: Erze × {Erz, Deepslate}. Das Dashboard zeigt das heute als Zeile pro Erz mit zwei Schaltern. „Alle/Keine“ je Abschnitt ist gewünscht. |
| `TextSetting` | String, `maxLength` (40–128) | Textfeld (einzeilig) | Cursor, Einfügen, Länge sichtbar bei Annäherung an das Limit; viele Felder sind kommagetrennte Listen (z. B. `storage_overlay.commands`) |
| `ColorSetting` | ARGB | Farbwahl | heute: 12-Farben-Palette `Settings.ColorSetting.PALETTE`. Freie Farbwahl (Hex) ist optional; die Palette muss bleiben. |
| `KeybindSetting` | GLFW-Keycode | Tasten-Aufnahme | Klick → „Press a key…“ → nächste Taste übernehmen; Backspace = löschen; Esc = abbrechen; Konflikte mit anderen Modul-Keybinds anzeigen (neu) |
| `ActionSetting` | Button-Label, Runnable | Button | optional Bestätigungsdialog bei destruktiven Aktionen (`storage_overlay.clear`, `session_stats.reset`) |

Gemeinsame Setting-Eigenschaften:

- `description()`: Hilfetext, vom Nutzer ausblendbar (`click_gui.descriptions`).
- `group()`: Untergruppe eines Moduls. Gruppen sind die primäre Gliederung großer Module (Ore Macro: 40 Settings in 10 Gruppen; Spear Helper: 36 in 6; Bandit Macro: 27 in 7).
- `visibleWhen()`: dynamisch, ändert sich mit anderen Werten. Ein Ein- und Ausblenden darf das Layout nicht springen lassen (Animation oder ruhige Neuberechnung).
- `required()` / `problem()`: Pflichtwert mit englischer Fehlermeldung. Er blockiert den Makrostart. Die Meldung erscheint statt der Beschreibung, in Fehlerfarbe.
- `isDefault()` / `reset()` / `defaultValue()`: Markierung „geändert“ und Reset je Setting.
- `persistent()`: Legacy-Settings sind *gebunden* (Wert liegt in `config/theprisons.json`). In der UI sehen sie identisch aus.
- `onChange`: Manche Änderungen haben sofortige Nebenwirkungen (Sprache wechselt live). Die UI muss nach jeder Änderung neu lesen, statt Werte zwischenzuspeichern.

### 3.3 Speichern und globale Aktionen (`core/config/ConfigStore`)

- Jede Änderung markiert die Config als *dirty*. Nach 40 Ticks (~2 s) Ruhe wird automatisch gespeichert. Die UI zeigt den Zustand („Unsaved changes – auto-saves in 2 s“ / „All changes saved“) und eventuelle Schreibfehler (`lastError()`).
- Aktionen heute: **Save** (`saveNow`), **Load** (`load(true)`, verwirft ungespeicherte Änderungen), **Reset module** (alle Settings des gewählten Moduls auf Default, mit Toast). Beim Schließen speichert `ClickGuiScreen` sofort, `DashboardScreen` markiert nur dirty. Die neue GUI speichert beim Schließen sofort.
- Toasts/Benachrichtigungen: `core.setNotifier` → `ThePrisonsHudRenderer.pushNotification(title, body, color)` mit den Stufen SUCCESS, WARNING, ERROR, INFO. Das wird in §5 ins Designsystem überführt.

### 3.4 Suche

Heute durchsucht `ClickGuiScreen` Module und Settings und filtert zusätzlich per Gruppen-Chips. Anforderung: eine globale Suche über Modulnamen, Modulbeschreibungen, Setting-Labels, Setting-Beschreibungen und Gruppen, in **EN und DE** (Treffer auf den übersetzten Text). Ein Treffer springt per Deep Link zum Setting.

---

## 4. Informationsarchitektur: was abgedeckt sein muss

Die neue GUI ersetzt beide heutigen Oberflächen. Die Gliederung entscheidet das Design. Folgende Inhalte müssen erreichbar sein:

### 4.1 Heute im Dashboard (`gui/dashboard/DashboardScreen`)

| Seite | Inhalt | Besondere Interaktion |
|---|---|---|
| Overview | Kachel je Feature (`FeatureProfile.feature()`), Statuspunkt an/aus, Beschreibung (2 Zeilen); Klick → Moduleditor | Statuspunkt pulsiert |
| Mining | Unter-Tabs „Ores“ (Erz × Deepslate-Schalter + Auto-Sprint), „Movement & View“, „Safety“, „Loot & Items“ (Settings-Gruppen des Ore Macro) | Erz-Schalterraster |
| Bandits | Unter-Tabs „Aim & Recall“, „Crosshair & Sight“, „Effects“, „Advanced“ (Spear Helper) sowie „Macro“, „Combat & Recall“, „Safety & Debug“ (Bandit Macro) | **Fadenkreuz-Presets** Minimal, Shooter, Sniper, Spear tip setzen auf einen Klick 6 Settings (`preset`, `crosshair_size`, `crosshair_gap`, `crosshair_thick`, `crosshair_dot`, `crosshair_color`) |
| Tunnel | Unter-Tabs „Scene“, „Look“, „Show“ (Tunnel Vision) | Hinweis auf F5+V und den Bildordner |
| Design | Theme (6 Presets), Kartendunkelheit, Animationen, Boxy-Schrift; **Live-Vorschau** | Vorschau reagiert sofort |
| Controls | Liste der Keybinds: GUI öffnen, Spear-Aim-Assist, Bandit Macro, Storage Overlay | Tasten-Aufnahme |
| HUD | Button „HUD-Editor öffnen“, Liste der HUD-Elemente, Link „All module & market settings“ | – |

### 4.2 Heute im Komplett-Editor (`gui/click/ClickGuiScreen`, `gui/click/Tab`)

Tabs Overview, Mining, Bandit, Market, Item Tools, HUD, PvP & Safety, Settings. Dazu Modulkarten mit Schalter, Status und Keybind, Gruppen-Chips, Suche, alle Setting-Controls, Popups für Enum und Choice, Footer mit Save/Load/Reset sowie Toasts.
Die Zuordnung ist nicht nur modulweise: `Tab.of(module, group)` verteilt die Gruppen des Ore Macro auf mehrere Tabs („Item sorter“/„Auto use“ → Items, „Defence“/„Breaks“/„Recovery“ → Safety). **Anforderung:** Die neue IA darf große Module über Seiten verteilen. Das Modul muss aber an genau einer Stelle Name, Status, Schalter und Keybind haben.

### 4.3 Vollständigkeit

**Jedes** Setting aus Anhang A muss erreichbar sein – außer den dort als *entfällt* markierten. Für DEV-only-Module (`bandit_dodge_test`) und REMOVED-Module (`safety`, `performance`, `session_stats`) gilt: nur im DEV-Build sichtbar, aber mit denselben Komponenten.

### 4.4 Feature-Profil in der UI

- **ON**-Module sind für Nutzer immer an. Ihre Settings sind änderbar, ihr Schalter nicht. Dieser Zustand muss als „Teil des Produkts“ erkennbar sein, nicht als Fehler.
- **FREE**-Module (`ore_macro`, `waypoint_editor`, `spear_helper`, `bandit_macro`) haben echte Schalter. Makros (Ore, Bandit) brauchen einen deutlichen Start/Stopp-Zustand mit Live-Status und Stop-Grund.
- **SYSTEM**-Module (`click_gui`, `design`, `hud_layout`) sind reine Einstellungsseiten ohne Feature-Kachel.

---

## 5. HUDs und Overlays im selben Designsystem

Alle folgenden Oberflächen müssen nach der Migration dieselben Tokens und Komponenten nutzen. Der Inhalt (die Datenfelder) ist fix; die Form bestimmt das Design.

### 5.1 HUD-Elemente (`HudRenderCallback`, verschieb- und skalierbar)

| Element | Datenquelle | Inhalt | Varianten |
|---|---|---|---|
| Session HUD | `hud/SessionView` (vorbereitet) ← `SessionViewFactory` | `title`, `clock`, `stateLine`, KPIs (`value`/`label`/`sub`/`Tone`), Activity mit Inventarfüllung %, Statuszeilen, Detailzeilen, Booster mit Restzeit; `Kind` ORE oder BANDIT | Stil `session_hud.dashboard_style`: Minimal, Standard, Detailed. Tönungen `Tone`: NEUTRAL, PRIMARY, ENERGY, XP, TIME, GOOD, WARN, BAD, MUTED |
| Scoreboard | `ScoreboardModule`/`ScoreboardData` | feste Zeilen: Spieler, Ökonomie, Welt, Session | ersetzt das Vanilla-Scoreboard |
| Tab-Liste (Better Tab) | `modules/hud/tab/TabList` | Spieler nach Rang gruppiert, Köpfe, Rangfarben, Ping, Header/Footer des Servers | auch als klickbarer Screen (Shift+Tab, §5.3) |
| Player Card | `PlayerCard` | Kopf, Name, Ping, Level, Rang, Gang; in der Nähe zusätzlich Gesundheit, Distanz, Ausrüstung mit Item-Icons | Rechtsklick auf einen Spieler |
| Pets & Trinkets (v1) | `ThePrisonsFeatureManager`, `ThePrisonsHudRenderer` | Cooldowns, „bereit“-Status, Countdown | Farben `pet_hud.color`, `ready_color`; Skala |
| Command Cooldowns (v1) | dto. | /jet, /fix, /feed, /near … mit Restzeit | |
| Satchels (v1) | dto. | Füllstand je Satchel-Typ, Warnung ab x % | |
| Session XP / Energy (v1, REMOVED) | dto. | XP, Energy, Raten pro Stunde | nur DEV |
| Armor (v1) | dto. | Haltbarkeit je Rüstungsteil, kritische Warnung | Icongröße, Abstand, Skala |
| Benachrichtigungen/Toasts | `ThePrisonsHudRenderer.pushNotification` | Titel, Text, Stufe (SUCCESS/WARNING/ERROR/INFO) | Farben `ready_announcements.title_color`/`body_color` |
| Tunnel Action Bar | `TunnelActionBarModule` | XP, Energie, Boosts der Session während Tunnel Vision | |
| Spear-Fadenkreuz | `SpearHelperModule`, `SightStyle`, `CrosshairPreset` | Presets DOT, SHOOTER, SNIPER, SPEAR; Größe, Lücke, Dicke, Punkt, Farbe; Wurfeffekte | kein Panel, aber Teil der visuellen Sprache |

### 5.2 HUD-Editor (`gui/hud/HudEditorScreen`, Vertrag `gui/hud/HudElement`)

- Das Spiel bleibt sichtbar, es erscheint ein feines Raster, jedes Element bekommt einen Rahmen und einen Namens-Chip.
- **Ziehen** verschiebt das Element. Es rastet am Raster und an den Bildschirm-Mittellinien ein; beim Einrasten erscheinen Hilfslinien. **Scrollen** skaliert in Schritten von 0,05. **Rechtsklick** setzt ein einzelnes Element zurück.
- Werkzeugleiste: Snap an/aus, „Alle zurücksetzen“, „Fertig“.
- `HudElement`: `id`, `name`, `bounds(w,h)` (Breite 0 = ausgeblendet), `moveTo`, `scale`/`setScale`, `drawPreview` (mit Beispielwerten), `reset`. Elemente zeigen Beispieldaten, auch wenn gerade nichts läuft.
- Neu gewünscht: Skalierung auch per Tastatur oder Feld (für Trackpads). Die Elemente sollen an den Bildschirmrändern bleiben, wenn sich Auflösung oder GUI-Skala ändern (heute: Positionen in skalierten Pixeln, `-1` = Standardposition).

### 5.3 Screens und Container-Overlays

| Oberfläche | Datei | Datenquelle | Pflichtinhalt |
|---|---|---|---|
| Item List (im Spielerinventar) | `items/client/InventoryItemList` | `ItemsService`, `ItemListModel`, `ItemSearchIndex`, `VirtualGrid`, Marktstatistiken | Suchleiste über der Hotbar (Tippen startet die Suche, Doppelklick zeigt alles); Kategorie-Chips (All, Loot, Enchants, Progression, Mining, Combat, Pets, Masks, Powerups, Special, Misc); Tier-Chips **nur, wo Tiers existieren** (Simple … Executive); große Karten mit Icon und Name; virtualisiertes Raster mit Scrollleiste; Detailbereich (Name, Kategorie, Marktdaten oder „No market data yet“); Hover-Tooltip = Original-Tooltip plus Marktblock. 211 registrierte Items. Muss neben dem Inventar bei Skala 3 und 1280×720 passen. |
| Market (`/ah`, `/ee`) | `modules/qol/market/MarketScreen` | `MarketModule`, `PriceBook`, `MarketIndex`, `items/market/*` | echtes 6-Zeilen-Servermenü im eigenen Layout; Kategorie-Icons; Suche über alle AH-Seiten (Hintergrund-Scan mit Fortschritt); Preis je Slot; Hover mit Preis, Verkäufer, Ablauf, Beobachtung, Vergleich und Energiewert; Navigation (Listings, History, Bin, Refresh, Zurück/Weiter, Analytics, Energy, Filter, Guide). Ausschalter `market.redesign` (dann Vanilla-Menü plus `MarketOverlay`-Karte). |
| EE-Ansicht | `MarketScreen` (EE-Modus) + `items/client/EeOverlayRender` | `items/energy/EeAnalysis` | Verfügbare Menge, Anzahl Verkäufer, günstigster Preis/1k, 7-Tage-Schnitt; Liste der Angebote (Verkäufer, Menge, Preis, Preis/1k); Kosten für 100k … 1M; Ausreißer markiert und ignoriert |
| AH-Slot-Overlay | `items/client/AhOverlayRender` | `items/market/AhSnapshot` | dünner Rahmen und kurzer Prozentwert bei deutlich günstigeren oder teureren Angeboten (`ah_overlay.borders`, `badges`); Analyse im Hover; Werte mit Quelle, Fenster, Stichprobe, Alter, Konfidenz |
| Energy-Panel | `items/client/EnergyOverlayRender` | `items/energy/EnergyOverlayModel` | gespeicherte Energie, Kapazität, Balken nur bei bekannter Kapazität, Rate/Restzeit nur wenn ableitbar (`energy_overlay.mode`) |
| Vanilla-AH/EE-Infokarte | `modules/qol/market/MarketOverlay` | `MarketModule` | nur wenn `MarketScreen` nicht ersetzt; wird in Phase 2 voraussichtlich in die AH/EE-Komponenten integriert |
| Shops `/gz`, `/pb` | `modules/qol/market/ShopScreen` | `ShopView` | echte Slots im Raster, Punktestand, Reset-Uhr, Wert des Angebots unter der Maus |
| Tinkerer | `modules/qol/market/TinkerScreen` | Servermenü | Statuskarte (Ergebnis plus Annehmen), 9×4-Angebotsraster, eigenes Inventar |
| Storage Overlay (`/pv`) | `modules/qol/storage/StorageOverlayScreen` | `StorageOverlayModule` (Cache aller Vault-Seiten) | Karten je Vault (`columns` 1–6 pro Zeile), Öffnen, Sortieren, Verschieben zwischen Seiten, optional Spielerinventar, Rescan/Clear |
| Spielerliste (Shift+Tab) | `modules/qol/players/PlayerListScreen` | `TabList` | klickbar → `PlayerViewer` (Tabs Vaults, Skills, Session); Rang-Header ein- und ausklappbar; Tippen sucht; horizontales Scrollen bei vielen Spalten; Esc/Tab schließt |
| Routenname | `modules/mining/ore/route/RouteNameScreen` | `RouteRecorder` | Textfeld, Zusammenfassung, Enter/Abbrechen |
| Waypoint-Editor (in der Welt) | `WaypointEditorModule` | `RouteStore` | nummerierte Wegpunkte in 3D; Linksklick fügt hinzu, Rechtsklick löscht, Enter speichert, Backspace verwirft. Braucht eine Hinweisleiste im HUD-Stil. |
| Tooltipblöcke | `items/ItemMarketTooltip`, `ThePrisonsFeatureManager.appendTooltip` | Markt-Cache, v1-Insights | angehängte Blöcke (`MARKET DATA`, `[TP] …`) in einheitlicher Typografie und Farbe; Original-Zeilen bleiben unangetastet |
| Tunnel Vision | `modules/general/tunnel/*` | – | eine Szene, keine Formular-UI; Notifications im Designsystem |

---

## 6. Heutige Design-Quellen (werden ersetzt)

Die neue Theme-Definition ersetzt **alle** folgenden Quellen. Sie sind hier aufgeführt, damit bestehende Nutzerwahlen abgebildet werden können.

| Quelle | Inhalt |
|---|---|
| `gui/theme/Theme` (Nutzer-Setting `design.theme`) | 6 Presets Accent/Title: Cosmic `#FF6EC7`/`#8AD8FF`, Aqua `#4FE8E0`/`#B8F6FF`, Sunset `#FF9A2E`/`#FFE04A`, Emerald `#5DE86B`/`#B8FFCC`, Royal `#A66CFF`/`#FFC93C`, Crimson `#FF3D6E`/`#FFB0C0` |
| `design.darkness` | Kartendeckkraft 10–100 % (Default 60) |
| `ui/ThePrisonsColors` | v1-/ClickGui-Palette: BG_* (Panel, Side, Section, Card, Input), FG_* (Primary/Secondary/Muted/Disabled), Accents (Cyan, Blue, Purple, Violet, Lime, Amber, Red), HEADING_PINK, Toggle-Verlauf Violet→Cyan |
| `gui/kit/Ui` | LABEL `#9AA3B5`, MUTED `#6B7385`, VALUE `#F2F4F8`, GOOD `#7CF0A0`, WARN `#FFE066`, BAD `#FF5E6C`; Widgets: card, round, outline, line, flowLine, shimmer, icon, sprite; Animations-Helfer |
| `items/ItemRarity` | Tierfarben: Simple `#D8DEE8`, Uncommon `#5DE86B`, Unique `#4FE8E0`, Elite `#4FD8F0`, Ultimate `#FFE04A`, Legendary `#FF9A2E`, Godly `#FF8DEB`, Mystic `#A66CFF`, Heroic `#FF8FB8`, Executive `#C7CED8`; unbekannt `#9AA3B5` |
| `items/client/TierColors`, lokale Konstanten in `MarketScreen`, `TunnelScene` (69 Hex-Literale), `DashboardScreen` (34), `ClickGuiScreen` (27), `SpearHelperModule` (20), `InventoryItemList` (20) | Streuwerte |
| `Settings.ColorSetting.PALETTE` | 12 wählbare Nutzerfarben `#F2EEFF #A8E8FF #00E5FF #4D8DFF #7B3FFF #C084FC #FF3DBA #F84EA8 #FF5E6C #FFC14D #65F59B #9AA0B8` |

**Pflicht für das neue Token-Set:**

1. Ein Accent-Preset-System, auf das sich die 6 bestehenden `design.theme`-Werte abbilden lassen (Alternative: eine dokumentierte Migration).
2. Semantische Farben: Erfolg, Warnung, Fehler, Info; für Session-HUD-Töne zusätzlich Energie, XP, Zeit und Primary.
3. Rarity-Farben für alle 10 Tiers plus „unbekannt“, unterscheidbar auch bei Farbfehlsichtigkeit (zusätzlich Label oder Form).
4. Kontrast für Text auf halbtransparenten Karten über beliebigem Spielhintergrund (hell/Schnee bis dunkel/Höhle).

Vorhandene GUI-Sprites (`assets/theprisons/textures/gui/sprites/`), weiterverwendbar oder ersetzbar:
`icon/`: armor_hud, better_tab, category_{bandit,combat,general,hud,meteor_mining,mining,pvp,qol}, command_cooldowns, cooldown_cache, item_insights, item_look (wird von PlayerViewer „Skills“ genutzt), message_notifications, ore_macro, page_{bandits,controls,design,hud,ores,overview,tunnel}, peaceful_mining, pet_hud, player_cards, ready_announcements, satchel_hud, scoreboard, session_hud, sneak_trade, spear_helper, storage_overlay, tunnel_vision, update_checker, vitals_warnings, waypoint_editor.
`market/`: button, cat_{all,combat,cosmetics,mining,other,upgrades}, cell, cell_active, glow, nav_{analytics,back,bin,categories,energy,filter,guide,history,listings,next,prev,refresh,search}, panel_body, panel_frame, tab.
Für Module ohne Icon (Market, Item List, AH/EE/Energy Overlay, Friends, Bandit Macro, Tunnel Action Bar, Design, Click GUI) werden neue Icons gebraucht.

---

## 7. Benötigte Komponenten (Mindestumfang)

Fenster/Shell (mit Navigation, Header, Statusleiste) · Navigationsliste mit Kategorien und Zählern · Kachel (Feature mit Status) · Modulkopf (Name, Beschreibung, Status, Schalter, Keybind, Gesperrt-Zustand, Stop-Grund) · Gruppen-Abschnitt (einklappbar) · Setting-Zeile (Label, Beschreibung/Fehler, Geändert-Marker, Reset) · Schalter · Slider mit Wertfeld · Segment-Control · Dropdown (statisch, dynamisch mit Abschnitten und Farbpunkten) · Mehrfachauswahl/Chips · Textfeld · Farbpalette · Tasten-Aufnahme · Button (primär, sekundär, destruktiv) · Bestätigungsdialog · Suchfeld mit Ergebnisliste · Toast · Tooltip (UI-Hilfe) · Scrollcontainer mit Scrollleiste · Tabs und Unter-Tabs · Leerzustand · Ladezustand („Preparing the item list…“, Scan-Fortschritt) · Badge/Chip (Tier, Kategorie, Status) · Statuspunkt · Fortschrittsbalken · KPI-Block (großer Wert, Label, Unterzeile) · Datenzeile (Label/Wert/Ton) · Item-Karte (Icon, Name, Rarity, Preis) · Preis-/Marktblock (Wert, Quelle, Alter, Konfidenz, Vergleich) · HUD-Panel (verschiebbar) · Editor-Overlay (Raster, Hilfslinien, Element-Chips, Werkzeugleiste) · Live-Vorschau (Design-Seite).

Für jede Komponente erwartet: Maße in skalierten Pixeln für Skala 1/2/3 (oder Regeln), Zustände, Verhalten bei langem deutschem Text, Verhalten bei Platzmangel.

---

## 8. Entfällt / wird nicht übernommen

| Element | Grund |
|---|---|
| `item_look` (alle 6 Settings), `design.comic_textures` | Cosmic Item System pausiert |
| `hud_layout.snap`, `hud_layout.grid` | wirkungslos: nur vom toten `ThePrisonsHudLayoutScreen` gelesen. Der HUD-Editor hat einen eigenen Snap-Schalter. |
| `ThePrisonsHudLayoutScreen`, `MarketSearch`, `NebulaHudRenderer` | toter bzw. ersetzter Code |
| Dashboard und ClickGui als getrennte Oberflächen | werden durch **eine** GUI ersetzt |

---

## 9. Abnahmekriterien für das Design

1. Jedes Setting aus Anhang A (außer §8) hat einen Platz und eine Komponente.
2. Layouts sind für die skalierten Größen **320×180, 427×240, 480×270, 640×360, 960×540, 1280×720** spezifiziert. Bei 427×240 ist alles erreichbar, nichts abgeschnitten.
3. Deutsche Texte sind in den Layouts berücksichtigt (Beispiel: „Speicher für Energie ab (Millionen)“).
4. Alle Zustände aus §3.2 sind pro Control definiert.
5. HUD-Elemente aus §5.1 und Overlays aus §5.3 nutzen dieselben Tokens wie die Config GUI.
6. Es gibt eine Bewegungs-Spezifikation **und** einen Zustand ohne Animation.
7. Keine Komponente für Item-Texturen, Texturquellen oder Itemrahmen.
8. Lieferung als Token-Tabelle (ARGB-Hex), Komponentenmaße in GUI-Pixeln, Layoutraster je Breakpoint, Screen-Mockups für die Skalen 1/2/3 bei 1280×720 und 1920×1080 sowie benötigte neue Sprites (PNG, Größe, 9-Slice-Ränder).

## 10. Offene Fragen an PO und Design

1. Bleiben die 6 Theme-Presets für Nutzer wählbar, oder gibt es ein festes Markendesign mit nur einer Akzentwahl?
2. Bleiben zwei Öffnen-Tasten (`I` als Vanilla-Keybinding, Right Shift als Modul-Keybind), oder wird es eine?
3. Bleibt die Design-Live-Vorschau?
4. Sollen ON-Module für Nutzer sichtbar „gesperrt“ sein oder unauffällig „immer aktiv“?
5. Sollen die v1-Widgets (Pets, Cooldowns, Satchels, Armor) als eigene HUD-Elemente bestehen bleiben oder in das Session HUD integriert werden?
6. Brauchen Overlays auf Vanilla-Menüs (wenn `market.redesign` aus ist) ein eigenes Erscheinungsbild, oder entfällt der Ausschalter?

---

## Anhang A – Vollständiges Setting-Inventar

Automatisch aus dem Code extrahiert (`084f88c`). Pro Modul kommt zusätzlich das Setting `keybind` (Typ Keybind) hinzu; die Default-Tasten stehen in Anhang B. *Typen:* `bool`, `integer`/`decimal` (Default, Min, Max, Step), `choice` (Enum, Default), `dynamicChoice` (Off-Label, Optionsquelle), `multi` (Defaults, Optionsquelle), `text` (Default, Max-Länge), `color`, `action` (Button-Label, Aktion). *Flags:* `conditional` = `visibleWhen`, `required` = Pflichtwert, `onChange` = sofortige Nebenwirkung. Werte wie `BARREN_BLOCKS_DEFAULT` sind Konstanten im jeweiligen Modul.

### A.1 Core-Module (`config/theprisons/modules.json`)

#### `click_gui`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `language` | choice | Language | `I18n.Lang.EN, I18n.Lang::label` | Display |  |
| `dim_background` | bool | Dim background | `true` | Display |  |
| `descriptions` | bool | Show descriptions | `true` | Display |  |

#### `design`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `theme` | choice | Theme | `Theme.COSMIC, Theme::label` | Design |  |
| `darkness` | integer | Card darkness | `60, 10, 100, 5` | Design | Einheit `%`  |
| `animations` | bool | Animations | `true` | Design |  |
| `sleek_font` | bool | Boxy font | `true` | Design |  |

#### `performance`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `rows` | integer | Rows | `6, 2, 14, 1` | Display |  |
| `show_max` | bool | Show worst case | `true` | Display |  |
| `reset` | action | Reset measurements | `"Reset", profiler::reset` | Display |  |

#### `safety`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `stop_on_damage` | bool | Stop on damage | `true` | Health |  |
| `low_health` | integer | Stop at health | `8, 0, 20, 1` | Health | Einheit ` HP`  |
| `inventory_full` | bool | Stop when inventory is full | `false` | Inventory |  |
| `max_runtime` | integer | Run limit | `0, 0, 600, 5` |  | Einheit ` min`  |
| `teleport_distance` | integer | Teleport distance | `8, 0, 64, 1` |  | Einheit ` blocks`  |
| `manual_override` | bool | Stop on manual input | `true` | Limits |  |
| `resume_after_teleport` | bool | Continue after teleport | `true` | Continuous operation |  |
| `restart_after_error` | bool | Restart after errors | `true` | Continuous operation |  |

#### `tunnel_actionbar`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `x` | integer | X | `-1, -1, 4000, 1` | Position |  |
| `y` | integer | Y | `-1, -1, 4000, 1` | Position |  |
| `scale` | decimal | Scale | `1.0D, 0.5D, 2.0D, 0.05D` | Position |  |

#### `tunnel_vision`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `background` | dynamicChoice | Background | `"Default (map)", TunnelVisionModule::backgrounds)` | Background |  |
| `drift` | bool | Slow camera drift | `true` | Background |  |
| `dim` | integer | Dim the backdrop | `12, 0, 60, 2` | Background | Einheit ` %`  |
| `surface` | choice | Animation | `Surface.AUTO, Surface::label` | Scene |  |
| `auto_seconds` | integer | Switch every | `40, 15, 180, 5` | Scene | conditional Einheit ` s`  |
| `quality` | choice | Performance | `Quality.BALANCED, Quality::label` | Scene |  |
| `targets` | bool | Crystals & asteroids | `true` |  |  |
| `road` | bool | Rainbow road | `true` | Road |  |
| `road_glow` | bool | Road glow | `true` | Road |  |
| `road_width` | integer | Road width | `100, 60, 140, 5` | Road | Einheit ` %`  |
| `player` | bool | Show my player | `true` | Player |  |
| `player_size` | integer | Player size | `80, 60, 140, 5` | Player | Einheit ` %`  |
| `sway` | bool | Idle sway | `true` | Player |  |
| `sparks` | bool | Sparks | `true` | Show |  |
| `shooting` | bool | Shooting stars | `true` | Show |  |
| `notifications` | bool | Macro notifications | `true` | Show |  |
| `ticker` | bool | Stats ticker | `true` | Show |  |
| `toggle` | action | Tunnel Vision | `"Start / stop (F5 + V)", () -> set(phase == Phase.OFF // phase == P...` | Show |  |

#### `scoreboard`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `x` | integer | X | `-1, -1, 4000, 1` | Position |  |
| `y` | integer | Y | `-1, -1, 4000, 1` | Position |  |
| `scale` | decimal | Scale | `1.0D, 0.5D, 2.0D, 0.05D` | Position |  |

#### `session_hud`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `x` | integer | X | `6, 0, 4000, 1` | Position |  |
| `y` | integer | Y | `6, 0, 4000, 1` | Position |  |
| `scale` | decimal | Scale | `1.0D, 0.5D, 2.5D, 0.05D` | Position |  |
| `dashboard_style` | choice | Dashboard style | `io.theprisons.hud.DashboardStyle.STANDARD, io.theprisons.hud.Dashbo...` | Dashboard |  |
| `width` | integer | Width | `320, 200, 420, 5` | Dashboard | Einheit ` px`  |
| `show_state` | bool | Show activity | `true` |  |  |
| `show_eta` | bool | Show ETA level-up | `true` | Rows |  |
| `show_yield` | bool | Show enchant yield | `true` | Rows |  |
| `log_unparsed` | bool | Log unknown booster / energy / XP / tax lines | `true` | Debug |  |

#### `better_tab`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `hidden_ranks` | text | Hidden ranks | `"", 256` | General |  |

#### `ore_macro`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `route` | dynamicChoice | Route | `"Off (tunnel mode)", () -> routes.options(MinecraftClient.getInstan...` | Route |  |
| `ore_packs` | multi | Ores | `OreCatalog.DEFAULT_SELECTION, OreCatalog.options()` | Ores | required  |
| `reach` | decimal | Mining reach | `4.4D, 2.5D, 6.0D, 0.1D` | Standard | conditional  |
| `max_drop` | integer | Max drop | `3, 1, 4, 1` | Standard | conditional  |
| `sprint` | bool | Auto sprint | `true` | Movement |  |
| `classic_steering` | bool | Classic steering (30.09) | `true` | Movement |  |
| `centring` | bool | Tunnel centring | `true` | Movement |  |
| `stone_blocks` | integer | Stone blocks before a new way | `BARREN_BLOCKS_DEFAULT, 1, 10, 1` | Movement |  |
| `route_memory` | bool | Route memory | `true` | Route memory |  |
| `plan_routes` | bool | Plan routes (world memory) | `true` | Movement |  |
| `mined_percent` | integer | Go to ore when mined (%) | `65, 10, 100, 5` | Movement |  |
| `show_path` | bool | Show pathfinder line | `false` | Display |  |
| `show_waypoints` | bool | Show waypoints | `false` | Display |  |
| `use_pet` | bool | Use pet when ready | `true` | Auto use |  |
| `pet_name` | text | Pet name contains | `"anti xp tax pet", 64` | Auto use | conditional required  |
| `use_ability` | bool | Use item abilities when ready | `true` | Auto use |  |
| `ability_names` | text | Ability items contain | `"fireball", 128` | Auto use | conditional required  |
| `item_sorter` | bool | Item sorter | `true` | Item sorter |  |
| `inventory_share` | integer | Inventory limit (%) | `65, 50, 95, 5` | Item sorter | conditional  |
| `vault_shards` | text | Private Vault - Shards | `"7", 40` | Item sorter | conditional required  |
| `vault_other` | text | Private Vault - Fallback (optional) | `"", 40` | Item sorter | conditional required  |
| `vault_energy` | text | Private Vault - Energy | `"9", 40` |  |  |
| `energy_trip_millions` | integer | Energy to the vault from (millions) | `8, 1, 100, 1` | Item sorter | conditional  |
| `godly_shards_trip` | integer | Godly shards before a trip | `10, 1, 64, 1` | Item sorter | conditional  |
| `money_trip_millions` | integer | Redeem money from (millions) | `10, 1, 1000, 1` | Item sorter | conditional  |
| `open_contrabands` | bool | Open contrabands at spawn | `true` | Item sorter | conditional  |
| `open_shards` | bool | Open shards at spawn | `true` | Item sorter | conditional  |
| `breaks` | bool | Breaks at a warden | `true` | Breaks |  |
| `break_every_min` | integer | Break every (min, from) | `10, 1, 120, 1` | Breaks |  |
| `break_every_max` | integer | Break every (min, to) | `30, 1, 180, 1` | Breaks |  |
| `break_length_min` | integer | Break length (s, from) | `10, 1, 300, 1` | Breaks |  |
| `break_length_max` | integer | Break length (s, to) | `30, 1, 600, 1` | Breaks |  |
| `anti_stuck` | bool | Anti-stuck | `true` |  |  |
| `guard_look_ahead` | integer | Guard look-ahead (blocks) | `2, 2, 8, 1` | Recovery |  |
| `flee_to_guard` | bool | Combat failsafe: run to a guard | `true` | Defence |  |
| `guarded` | bool | Stay in the guarded area | `true` | Defence |  |
| `tax_from_energy` | bool | Guard tax from energy per ore (experimental) | `false` | Defence | conditional  |
| `outside_with_player` | integer | Unguarded blocks, a player near | `0, 0, 10, 1` | Defence | conditional  |
| `outside_no_player` | integer | Unguarded blocks, nobody near | `6, 0, 20, 1` | Defence | conditional  |
| `guard_radius` | integer | Guarded radius (blocks) | `15, 4, 32, 1` | Defence | conditional  |

#### `waypoint_editor`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `route` | dynamicChoice | Route | `"Pick a route", () -> routes.options(MinecraftClient.getInstance()))` | Route |  |
| `start` | action | Editor | `"Start editing", () -> { MinecraftClient.getInstance(` |  |  |

#### `bandit_macro`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `mode` | choice | Mode | `Mode.SPEAR, m -> "Spear"` | Macro |  |
| `any_bandit` | bool | Include bosses & special bandits | `false` | Macro |  |
| `target_range` | integer | Target range | `60, 10, 120, 1` | Targeting | Einheit ` blocks`  |
| `min_safe` | integer | Minimum safe distance | `6, 2, 20, 1` | Targeting | Einheit ` blocks`  |
| `max_combat` | integer | Maximum combat distance | `34, 10, 80, 1` | Targeting | Einheit ` blocks`  |
| `preferred_distance` | integer | Combat distance (0 = auto) | `0, 0, 60, 1` | Targeting | Einheit ` blocks`  |
| `max_threats` | integer | Max nearby bandits | `3, 1, 8, 1` | Targeting |  |
| `patrol_route` | dynamicChoice | Patrol route | `"Off (stay here)", () -> routes.options(MinecraftClient.getInstance...` | Targeting |  |
| `positioning` | bool | Walk into range | `true` | Targeting |  |
| `stickiness` | integer | Target stickiness | `25, 0, 80, 1` | Targeting |  |
| `aim_speed` | integer | Human aim speed | `5, 1, 10, 1` | Combat |  |
| `pierce` | bool | Aim through more bandits | `true` | Combat |  |
| `charge_ticks` | integer | Hold the throw | `12, 0, 40, 1` | Combat | Einheit ` ticks`  |
| `line_margin` | decimal | Corridor width (each side) | `0.45D, 0.1D, 1.5D, 0.05D` | Combat | Einheit ` b`  |
| `throw_timeout` | integer | Throw timeout | `3, 1, 10, 1` | Combat | Einheit ` s`  |
| `return_timeout` | integer | Return timeout | `9, 3, 30, 1` | Combat | Einheit ` s`  |
| `auto_recall` | bool | Auto recall | `true` | Recall |  |
| `recall_threshold` | integer | Recall threshold | `2, 1, 8, 1` | Recall | Einheit ` bandits`  |
| `recall_deadline` | decimal | Recall at the latest after | `3.0D, 0.8D, 6.0D, 0.1D` | Recall | Einheit ` s`  |
| `recall_lead` | integer | Lead time (ping + reaction) | `150, 0, 500, 10` | Recall | Einheit ` ms`  |
| `player_detection` | bool | Player detection | `true` | Safety |  |
| `safety_radius` | integer | Player safety radius | `30, 8, 80, 1` | Safety | Einheit ` blocks`  |
| `retreat_health` | integer | Retreat below health | `8, 3, 19, 1` | Safety | Einheit ` hp`  |
| `stop_for_friends` | bool | React to friends & gang too | `false` | Safety |  |
| `search_timeout` | integer | Search timeout | `120, 20, 600, 5` | Safety | Einheit ` s`  |
| `movement_timeout` | integer | Movement timeout | `14, 4, 60, 1` | Safety | Einheit ` s`  |
| `debug` | bool | Debug mode | `false` | Debug |  |

#### `spear_helper`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `crosshair` | bool | Spear crosshair | `true` | Crosshair |  |
| `hide_vanilla` | bool | Replace vanilla crosshair | `true` | Crosshair |  |
| `preset` | choice | Style | `CrosshairPreset.SHOOTER, CrosshairPreset::label` | Crosshair |  |
| `crosshair_color` | color | Colour | `0x00FFD0` | Crosshair |  |
| `crosshair_size` | integer | Size | `5, 2, 14, 1` | Crosshair |  |
| `crosshair_gap` | integer | Gap | `3, 0, 12, 1` | Crosshair |  |
| `crosshair_thick` | integer | Thickness | `1, 1, 4, 1` | Crosshair |  |
| `crosshair_outline` | bool | Outline | `true` | Crosshair |  |
| `crosshair_dot` | bool | Centre dot | `true` | Crosshair |  |
| `any_bandit` | bool | Include bosses & special bandits | `false` | Aim assist (L) |  |
| `aim_window` | integer | Search window | `90, 20, 180, 5` | Aim assist (L) | Einheit `°`  |
| `line_margin` | decimal | Corridor width (each side) | `0.45D, 0.1D, 1.5D, 0.05D` | Aim assist (L) | Einheit ` b`  |
| `line_bar` | bool | Show the line bar | `true` | Aim assist (L) |  |
| `range` | integer | Range | `40, 8, 120, 1` | Aim assist (L) | Einheit ` blocks`  |
| `recall_deadline` | decimal | Deadline | `3.0D, 0.8D, 6.0D, 0.1D` | Recall signal (F) | Einheit ` s`  |
| `recall_min` | integer | Early signal from | `2, 1, 8, 1` | Recall signal (F) | Einheit ` bandits`  |
| `recall_lead` | integer | Lead time (ping + reaction) | `150, 0, 500, 10` | Recall signal (F) | Einheit ` ms`  |
| `recall_hud` | bool | Show the recall timer | `true` | Recall signal (F) |  |
| `recall_sound` | bool | Signal sound | `true` | Recall signal (F) |  |
| `marker` | bool | Sight point on the nearest enemy player | `true` | Sight point |  |
| `sight_style` | choice | Sight style | `SightStyle.RETICLE, SightStyle::label` | Sight point |  |
| `marker_color` | color | Colour | `0xFF4D4D` | Sight point |  |
| `sight_line` | bool | Line from the crosshair | `false` | Sight point |  |
| `cone` | integer | Search cone | `45, 5, 120, 1` | Sight point | Einheit `°`  |
| `include_friends` | bool | Include friends & gang | `false` | Sight point |  |
| `fx` | bool | Throw effects | `true` | Throw effects |  |
| `trail` | bool | Bullet trail | `true` | Throw effects |  |
| `trail_style` | choice | Trail style | `SpearEffects.Trail.LIGHTNING, SpearEffects.Trail::label` | Throw effects |  |
| `return_bolt` | bool | Lightning on return | `true` | Throw effects |  |
| `throw_burst` | bool | Muzzle burst at the throw | `true` | Throw effects |  |
| `return_flash` | bool | Flash on return | `true` | Throw effects |  |
| `fx_sound` | bool | Effect sounds | `true` | Throw effects |  |
| `fx_volume` | decimal | Effect volume | `1.0D, 0.0D, 2.0D, 0.1D` | Throw effects |  |
| `speed` | decimal | Throw speed | `2.5D, 0.5D, 6.0D, 0.05D` | Ballistics | Einheit ` b/tick`  |
| `gravity` | decimal | Gravity | `0.05D, 0.0D, 0.2D, 0.005D` | Ballistics |  |
| `drag` | decimal | Air drag | `0.99D, 0.9D, 1.0D, 0.005D` | Ballistics |  |

#### `market`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `auto_scan` | bool | Scan automatically | `true` | Scan |  |
| `interval` | integer | Pause between scans (minutes) | `5, 1, 60, 1` | Scan | conditional  |
| `scan_while_macro` | bool | Scan while the Ore Macro runs | `false` | Scan | conditional  |
| `shop_scan` | bool | Check the /gz and /pb shops | `true` | Scan |  |
| `redesign` | bool | Own /ah and /ee menus | `true` | Menus |  |

#### `friends`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `tab` | bool | Frame in the tab list | `true` | General |  |
| `glow` | bool | Glow in the world | `true` | General |  |

#### `player_cards`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `x` | integer | X | `-1, -1, 4000, 1` | Position |  |
| `y` | integer | Y | `-1, -1, 4000, 1` | Position |  |
| `scale` | decimal | Scale | `1.0D, 0.5D, 2.0D, 0.05D` | Position |  |

#### `sneak_trade`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `command` | text | Command | `"trade", 32` | General |  |

#### `storage_overlay`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `commands` | text | Commands | `"pv,vault,vaults,playervault,playervaults", 96` | General |  |
| `columns` | integer | Cards per row | `3, 1, 6, 1` | Layout |  |
| `inventory` | bool | Show inventory | `true` | Layout |  |
| `vault_count` | integer | Vault count | `0, 0, 200, 1` | Vaults |  |
| `rescan` | action | Rescan vaults | `"Rescan", this::rescan` | Vaults |  |
| `clear` | action | Forget cached pages | `"Clear", this::clearCache` | Vaults |  |

#### `ah_overlay`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `borders` | bool | Slot borders | `true` | Display |  |
| `badges` | bool | Percentage badges | `true` | Display |  |

#### `ee_overlay`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `borders` | bool | Listing borders | `true` | Display |  |

#### `energy_overlay`

| Setting | Typ | Label | Default / Bereich / Optionen | Gruppe | Flags |
|---|---|---|---|---|---|
| `mode` | choice | Mode | `Mode.DETAILED, m -> m.label` | Display |  |


### A.2 Legacy-v1-Module (gebundene Settings, Werte in `config/theprisons.json`, Quelle `modules/legacy/LegacyModules.java`)

Jedes Legacy-Modul hat außerdem seinen An/Aus-Zustand (gebunden an ein v1-Flag). Im Nutzer-Build sind alle als ON erzwungen, außer `session_stats` (REMOVED) und `hud_layout` (SYSTEM).

| Modul | Setting | Typ | Label | Default / Bereich | Gruppe |
|---|---|---|---|---|---|
| `pet_hud` | `ready_status` | bool | Show ready status | v1-Default | – |
| `pet_hud` | `countdown` | bool | Show countdown | v1-Default | – |
| `pet_hud` | `color` | color | Text colour | v1-Default | Style |
| `pet_hud` | `ready_color` | color | Ready colour | v1-Default | Style |
| `pet_hud` | `scale` | decimal | Scale | 0.5–2.5, Schritt 0.05 | Style |
| `command_cooldowns` | – | – | (nur An/Aus) | – | – |
| `satchel_hud` | `warnings` | bool | Fill warnings | v1-Default | – |
| `satchel_hud` | `warning_percent` | integer | Warn at | 50–100, Schritt 5, Einheit `%` | – |
| `session_stats` (REMOVED) | `paused` | bool | Paused | v1-Default | – |
| `session_stats` (REMOVED) | `reset` | action | Session totals → „Reset“ | – | – |
| `session_stats` (REMOVED) | `xp_scale`, `energy_scale` | decimal | XP/Energy widget scale | 0.5–2.5, Schritt 0.05 | Style |
| `armor_hud` | `warnings` | bool | Durability warnings | v1-Default | – |
| `armor_hud` | `scale` | decimal | Scale | 0.75–1.35, Schritt 0.05 | Style |
| `armor_hud` | `icon_size` | integer | Icon size | 10–20 | Style |
| `armor_hud` | `gap` | integer | Gap | 0–8 | Style |
| `item_insights` | `clue_steps` | bool | Clue scroll steps | v1-Default | – |
| `hud_layout` (SYSTEM) | `open` | action | Layout editor → „Open“ | öffnet den HUD-Editor | – |
| `hud_layout` | `snap` | bool | Snap to grid | **entfällt** (wirkungslos, §8) | – |
| `hud_layout` | `grid` | integer | Grid size | **entfällt** (wirkungslos, §8) | – |
| `hud_layout` | `module_hud_scale` | decimal | Module widget scale | 0.5–2.5, Schritt 0.05 | – |
| `message_notifications` | `sound` | bool | Sound | v1-Default | – |
| `message_notifications` | `volume` | decimal | Volume | 0.0–2.0, Schritt 0.05 | – |
| `peaceful_mining` | – | – | (nur An/Aus) | – | – |
| `vitals_warnings` | – | – | (nur An/Aus) | – | – |
| `ready_announcements` | `powerball` | bool | Powerball ready alert | v1-Default | – |
| `ready_announcements` | `title_color`, `body_color` | color | Alert title/text colour | v1-Default | Style |
| `cooldown_cache` | – | – | (nur An/Aus) | – | – |
| `update_checker` | `interval` | integer | Check every | 1–168, Einheit ` h` | – |

Die v1-HUD-Positionen (x/y je Widget) liegen ebenfalls in `config/theprisons.json` und werden ausschließlich über den HUD-Editor geändert (`gui/hud/LegacyHudElements`).

### A.3 Entfallene Settings (nicht übernehmen)

`item_look.source`, `item_look.frames`, `item_look.badges`, `item_look.pickaxes`, `item_look.gear`, `item_look.plain_items`, `design.comic_textures` (pausiert) sowie `hud_layout.snap` und `hud_layout.grid` (wirkungslos).

---

## Anhang B – Tasten und Befehle

| Auslöser | Aktion | Quelle | Umbelegbar über |
|---|---|---|---|
| Right Shift | GUI öffnen | `click_gui.keybind` | ThePrisons-GUI |
| `I` | GUI öffnen | `key.theprisons.open_config` | Minecraft-Steuerung |
| `R` | Session zurücksetzen (v1) | `key.theprisons.reset_stats` | Minecraft-Steuerung |
| `B` | Session pausieren (v1) | `key.theprisons.pause_stats` | Minecraft-Steuerung |
| (frei) | Routenaufnahme Start/Stop | `key.theprisons.route_record_start/stop` | Minecraft-Steuerung |
| `K` | Ore Macro an/aus | `ore_macro.keybind` | ThePrisons-GUI |
| `J` | Bandit Macro an/aus | `bandit_macro.keybind` | ThePrisons-GUI |
| `L` | Spear-Aim-Assist | `spear_helper.keybind` | ThePrisons-GUI |
| F5 + V | Tunnel Vision | `TunnelVisionModule` (fest) | – |
| Shift + Tab | Spielerliste | `PlayerCardModule` (fest) | – |
| Rechtsklick auf Spieler | Player Card | `PlayerCardModule` | – |
| Sneak + Rechtsklick auf Spieler | `/trade <name>` | `SneakTradeModule` | – |
| `/prisons` · `/theprisons` [`gui`] | GUI öffnen | `ThePrisonsCore` | – |
| `/prisons stop` · `toggle <modul>` · `perf [reset]` · `stats` | Makro stoppen, Modul umschalten, Profiler, Laufstatistik | `ThePrisonsCore` | – |
| `/prisons open <modul> [setting]` · `lang <en/de>` · `capture` | Deep Link, Sprache, Capture | `core/setup/ModCommands` (**nur DEV**) | – |
| `/prisons price …` · `market scan` · `shops` | Preise, manueller Scan | `MarketModule` | – |
| `/prisons friend add/remove/list` | Freunde | `FriendsModule` | – |
| `/prisons routes` · `route delete` | Routen | `RouteRecorder` | – |
| `/prisons set border` · `remove border` · `clear borders` · `borders` | Abbaugrenzen | `BorderMarks` | – |
| `/prisons sprint on/off` | Ore-Macro-Sprint | `OreMacroModule` | – |

## Anhang C – Heutiger UI-Code (wird ersetzt bzw. migriert)

| Datei | Zeilen | Rolle | Schicksal in Phase 2 |
|---|---|---|---|
| `gui/dashboard/DashboardScreen.java` | 1 120 | Config-Dashboard | ersetzt, dann gelöscht |
| `gui/click/ClickGuiScreen.java` | 1 212 | Komplett-Editor | ersetzt, dann gelöscht |
| `gui/click/Tab.java` | 67 | Tab-Zuordnung | Logik (Gruppe → Seite) wandert in die neue IA |
| `gui/hud/HudEditorScreen.java` | 291 | HUD-Editor | neu im Designsystem |
| `gui/hud/HudElement.java`, `LegacyHudElements.java` | 25 / 140 | HUD-Vertrag, v1-Adapter | Vertrag bleibt (ggf. erweitert) |
| `gui/kit/Ui.java`, `TextFit.java`, `HidesHud.java` | 204 / 112 / 5 | Zeichenhelfer, Textanpassung | durch das Designsystem ersetzt; `TextFit` wird übernommen |
| `gui/theme/Theme.java` | 38 | Theme-Presets | durch Tokens ersetzt |
| `gui/ThePrisonsHudLayoutScreen.java` | 208 | **tot** | löschen |
| `ui/ThePrisonsColors.java`, `ui/ThePrisonsHudRenderer.java` | 59 / 621 | v1-Palette, v1-HUD und Toasts | migrieren, dann löschen |
| `hud/*` | ~560 | Session-Dashboard (View/Layout/Renderer) | Renderer auf Tokens umstellen; View und Layout bleiben |
| `modules/hud/NebulaHudRenderer.java` | 293 | Vorgänger-Renderer, nur von Tests genutzt | löschen (Tests auf `SessionViewFactory` umstellen) |
| `modules/qol/market/MarketSearch.java` | 744 | Vorgänger der Item List, tot bis auf `age()` | löschen, `age()` verschieben |
| `items/client/*Render.java`, `InventoryItemList.java`, `TierColors.java` | – | Overlays, Item List | auf Komponenten und Tokens umstellen |
| `modules/qol/market/{MarketScreen,ShopScreen,TinkerScreen,MarketOverlay}.java` | 923 / 255 / 274 / – | Container-Screens | auf Komponenten und Tokens umstellen |
| `modules/qol/storage/StorageOverlayScreen.java`, `modules/qol/players/{PlayerListScreen,PlayerCard,PlayerViewer}.java`, `modules/hud/tab/TabList.java`, `modules/hud/scoreboard/ScoreboardModule.java` | – | weitere Oberflächen | auf Komponenten und Tokens umstellen |
