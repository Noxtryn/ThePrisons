# Übergabe an Claude Design – ThePrisons UI-Neubau

Stand: 2026-10-10 · Code-Basis `dev` (`6b9df19` + diese Übergabe) · Absender: Lead Developer (Claude Code)

Dieses Blatt ist der Einstieg. Der vollständige technische Vertrag steht in [`UI_REPLACEMENT_REQUIREMENTS.md`](UI_REPLACEMENT_REQUIREMENTS.md).

## Verbindliche Vorgaben des Product Owners

1. **Kompletter Neubau** der Config GUI. Die heutigen Oberflächen (Dashboard + Komplett-Editor) werden nicht überarbeitet, sondern nach der Migration entfernt.
2. **Alle 204 bestehenden Einstellungen** werden funktional übernommen (Vertrag, Anhang A), dazu je Modul ein Keybind.
3. **Ein Designsystem für alles:** Config GUI, HUD-Editor, alle HUDs (Session HUD, Scoreboard, Tab-Liste, Player Card, Pets/Cooldowns/Satchels/Armor, Toasts, Tunnel Action Bar, Fadenkreuz) und alle produktiven Overlays und Screens, **einschließlich AH und EE** (Vertrag §5).
4. **Cosmic Item System pausiert:** keine Item-Texturen, Texturquellen, Tier-Rahmen auf Items, Badges, Comic-Filter oder Tooltip-Rahmen. Items erscheinen so, wie Server und Vanilla sie liefern; Rarity wird **neben** dem Icon gezeigt.
5. Implementiert wird erst, wenn der PO das **finale Designpaket** freigegeben hat.

## Lesereihenfolge

1. Vertrag §1 – Plattformgrenzen: nur 2D-`DrawContext`, ganzzahlige GUI-Skala, **Mindestfläche 427×240 skalierte Pixel**, 9-px-Schrift, kein Blur, EN/DE.
2. Vertrag §2–§4 – Einstiege, Datenmodell, Setting-Typen → Controls, Informationsarchitektur.
3. Vertrag §5 – jedes HUD, jedes Overlay und jeder Screen mit Pflichtinhalt.
4. Vertrag §6–§7 – heutige Farbquellen (werden ersetzt) und benötigte Komponenten.
5. Vertrag §9–§10 – Abnahmekriterien und offene Fragen.
6. Anhang A – alle 204 Settings mit Typ, Bereich, Gruppe und Flags.

## Erwartete Lieferung

- [ ] **Token-Tabelle**: Farben als ARGB-Hex (Flächen, Text, Rahmen, Akzent-Presets, Semantik Erfolg/Warnung/Fehler/Info, Session-Töne Energie/XP/Zeit, 10 Rarity-Farben + „unbekannt“), Abstände, Radien (pixelgenau), Typo-Stufen, Animationsdauern.
- [ ] **Abbildung der 6 heutigen Theme-Presets** (`design.theme`: Cosmic, Aqua, Sunset, Emerald, Royal, Crimson) auf das neue System oder eine Migrationsregel.
- [ ] **Komponenten-Spezifikation** (Vertrag §7) mit Maßen in GUI-Pixeln und allen Zuständen: normal, hover, fokussiert, aktiv, deaktiviert, geändert, Fehler, gesperrt (vom Produkt erzwungen).
- [ ] **Layoutraster je Breakpoint**: 320×180, 427×240, 480×270, 640×360, 960×540, 1280×720.
- [ ] **Mockups**: Config GUI (Übersicht, ein großes Modul wie Ore Macro mit 10 Gruppen, Suche, Deep-Link-Markierung, Speichern-Status), HUD-Editor, Session HUD in Minimal/Standard/Detailed, Item List im Inventar, AH-Screen, EE-Screen, AH-Slot-Overlay mit Hover, Energy-Panel, Storage Overlay, Spielerliste und Player Card, Toasts – jeweils mit EN- **und** DE-Text, mindestens bei Skala 2 und 3 auf 1280×720.
- [ ] **Bewegungs-Spezifikation** und der Zustand bei ausgeschalteten Animationen (`design.animations = false`).
- [ ] **Sprite-Liste**: neue Icons (Module ohne Icon: Market, Item List, AH/EE/Energy Overlay, Friends, Bandit Macro, Tunnel Action Bar, Design, Click GUI), Größe in px, 9-Slice-Ränder für Panels und Buttons.
- [ ] **Antworten auf die offenen Fragen** (Vertrag §10).

## Referenzmaterial im Repository

| Was | Wo | Hinweis |
|---|---|---|
| Ist-Zustand nach der Pause (Item List, AH, EE, Tinker, eigene Angebote) | `docs/design/reference/current-state-2026-10-10/` (9 PNG, echter Fabric-Client, synthetische Marktdaten) | zeigt Items so, wie sie jetzt aussehen: ohne eigene Texturen |
| Heutige Config GUI bei Skala 1/2/3, 1920×1080 und 1280×720 | `docs/development/handover/visual-acceptance-2026-10-08/config/` | Beispiel für die Abnahmematrix und für den Fehler bei 1280×720 / Skala 3. Einstellungen zum inzwischen entfernten Comic-Filter können dort noch zu sehen sein. |
| Ältere Markt-Screenshots | `docs/development/handover/visual-acceptance-2026-10-08/000*_market_*.png` | **noch mit pausiertem Item-Look**, nicht als Vorlage verwenden |
| Vorhandene GUI-Sprites | `src/main/resources/assets/theprisons/textures/gui/sprites/{icon,market}/` | dürfen ersetzt werden |
| Schriften | `src/main/resources/assets/theprisons/font/` (`boxy` Bitmap, `nebula` = Noto Sans Medium) | neue Schrift nur mit Lizenz |

## Technische Rahmenbedingungen in einem Satz pro Punkt

- Rendering ist **reines 2D** mit Rechtecken, Sprites (9-Slice), Text und Item-Icons; Rundungen und Schatten müssen pixelweise oder als Sprite machbar sein.
- Jede Ansicht muss bei **427×240** vollständig bedienbar sein, ohne abgeschnittene Inhalte.
- **Deutsch** ist 20–35 % länger; keine festen Textbreiten.
- HUDs werden jedes Frame gezeichnet und müssen ohne aufwendige Effekte auskommen.
- Containerscreens (AH, EE, Shops, Tinker, Storage) enthalten **echte Server-Slots** (18 px), die sichtbar und klickbar bleiben müssen.
- Original-Tooltips von Items bleiben unverändert; ThePrisons hängt nur Blöcke an.

## Ablauf nach der Lieferung

1. Prüfung der Lieferung gegen den Vertrag (Machbarkeit in `DrawContext`, Vollständigkeit der 204 Settings). Rückfragen gehen gesammelt zurück.
2. Freigabe des Designpakets durch den PO.
3. Umsetzung nach [`docs/development/PHASE_2_IMPLEMENTATION_PLAN.md`](../development/PHASE_2_IMPLEMENTATION_PLAN.md): zuerst Fundament und Controls (mit Komponenten-Galerie nur im Test-Client), dann die neue GUI, danach HUDs und Overlays. Die alte GUI wird im selben Schritt gelöscht, in dem die neue nachweislich vollständig ist.
