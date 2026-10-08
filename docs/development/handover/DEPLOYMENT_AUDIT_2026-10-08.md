# Deployment- und Sichtbarkeits-Audit (2026-10-08)

## Ursache der fehlenden sichtbaren Änderungen

- Ein Gradle-Build kopierte bislang keine JAR in eine Prism-Instanz. Die ausgelieferte JAR in `Cosmic/minecraft/mods` war daher nur mit dem Stand ihres letzten manuellen Kopiervorgangs identisch.
- Es existieren zwei getrennte Prism-Instanzen: `Cosmic` enthält `ThePrisons-Nebula-v1.2.1-mc1.21.11.jar`; `Cosmic(1)` enthält weiterhin die alte, nicht angefasste `ThePrisons-v.1.0.jar`. Nur `Cosmic` ist das verifizierte Ziel.
- Die vorher gestartete JAR enthielt noch keine Standard-Texturen. Ihr Resource-Reload protokollierte nur `theprisons_look`, nicht die neue Item-Überlagerung.
- Der Nutzerprofil-Schalter sperrte `market` ausdrücklich aus. Dadurch waren die AH-/EE-Flows trotz vorhandener Renderer nicht aktiv. Das Profil aktiviert `market` nun wieder verbindlich zusammen mit den bereits aktivierten Item-/Overlay-Modulen.
- Ein veralteter Startup-Pfad versuchte bei jedem Start ein nicht eingebettetes PVP-Pack zu installieren. Er wurde entfernt; die neue eingebettete Standard-Überlagerung braucht keinen Schreibzugriff auf `resourcepacks`.

## Reproduzierbarer Ablauf

1. In Prism die Instanz **Cosmic** auswählen (Minecraft 1.21.11, Fabric Loader 0.19.5, Java 21).
2. Im Repository `./gradlew clean test build --no-daemon` ausführen.
3. Nur bei Erfolg installieren: `./gradlew installPrismMod -PtheprisonsInstallDir=/absolute/path/to/Cosmic/minecraft/mods --no-daemon`.
4. Der Task akzeptiert ausschließlich ein bestehendes Verzeichnis namens `mods`, verweigert zusätzliche ThePrisons-JARs und prüft, dass die Zieldatei entstand.
5. SHA-256 von `build/libs/ThePrisons-Nebula-v1.2.1-mc1.21.11.jar` und der Zieldatei vergleichen.

Der am Audit-Tag installierte SHA-256 ist `863bb966095ac186c6119fb242750cc07512bd6dcfaee95d8716fa1075cfe385`.

## Tatsächliche Render-Einstiegspunkte

| Sichtbares Feature | Einstiegspunkt | Teststatus |
|---|---|---|
| Config-Dashboard | `ClickGuiModule` (Right Shift) bzw. `ThePrisonsFeatureManager` (I) -> `ThePrisonsClient.dashboard()` -> `DashboardScreen` | Im Fabric-Client gerendert; Screenshot `0000_showcase_01_dashboard_overview.png` |
| Item List | `ThePrisonsMarketInventoryMixin` -> `InventoryItemList.render()` | Im Fabric-Client durch Doppelklick und Suche getestet; Screenshots `market_1a_showall`, `market_1d_inventory_search` |
| AH | `MarketModule.interceptOpen()` -> `MarketScreen` | Im Fabric-Client mit 27 Testangeboten; Screenshot `market_2_ah` |
| EE | `MarketModule.interceptOpen()` -> `MarketScreen` | Im Fabric-Client mit 8 Testangeboten und 7-Tage-Wert; Screenshot `market_3_ee` |
| Session HUD / Rarity-Tooltip / slotbasierte AH- und EE-Overlays | HUD-Callbacks bzw. `ThePrisonsItemsOverlayMixin` | **NICHT separat visuell verifiziert**: Markt-Screens nutzen den direkten `MarketScreen`-Pfad, nicht alle Live-Server-Slot- und Tooltipbedingungen. |
| Freigegebene Standardtexturen | `ThePrisonsResourcePackManagerMixin` -> `ItemTexturePacks.assemble()` -> bestehende Modellpfade | Im echten Fabric-Client als `theprisons_items_standard` im ResourceManager geladen; 35-PNG/Manifest-/Modelltests bestanden. Ein Live-Cosmic-Item mit serverseitigem `ITEM_MODEL` wurde noch nicht manuell fotografiert. |

## Config-GUI: produktiver Pfad, nicht Dashboard-Entwurf

Die sichtbare Konfigurationsarchitektur besteht aus zwei echten, miteinander verbundenen Screens:

1. Mod Menu, `I` und Right Shift erreichen über `ThePrisonsModMenuIntegration`, `ClickGuiModule` und `ThePrisonsClient.dashboard()` zunächst `DashboardScreen`.
2. Ein Modul im Dashboard öffnet über dessen `openClassic`-Callback **`ClickGuiScreen`**. Diese Klasse baut die Kategorien, Modul-Karten, Suche, alle Einstellungs-Controls, Dropdowns, Keybind-Aufnahme sowie Save/Load/Reset und speichert in `ConfigStore` nach `config/theprisons/modules.json`.

Der frühere Fehler war, dass Änderungen am `DashboardScreen` als Überarbeitung des Editors gezählt wurden. Der V4-Redesign-Commit verändert deshalb direkt `ClickGuiScreen.render`, `renderSidebar`, `renderContent`, `renderCard`, die tatsächliche Kategorisierung in `Tab` und die echte Settings-Übersicht. Kein zusätzlicher Konfigurations-Renderer wurde angelegt.

- Neue, reale Kategorien: Overview, Mining, Bandit, Market, Item Tools, HUD, PvP & Safety und Settings. `Overview` ist keine Attrappe: sie verwendet dieselben Module und die gespeicherten Einstellungen des Editors.
- `MARKET` fasst die vorhandenen AH-/EE-/Market-Module zusammen; `ITEMS` die Item List, Item Look und Storage Overlay. Nicht vorhandene Meteor-Module werden nicht als leere Kategorie vorgetäuscht.
- Bei kleiner GUI-Höhe (insbesondere Skala 3) komprimiert die linke Navigation ihre Zeilen und blendet nur den optionalen Laufstatus aus; keine Kategorie liegt mehr über dem Footer.

### Sichtprüfung

`./gradlew runClientGameTest -Pshowcase -PshowcaseSeconds=1 --no-daemon` war erfolgreich und erzeugte
`build/run/clientGameTest/screenshots/0004_showcase_05_config_command_center.png`. Es ist ein echter Fabric-Client-Render von `ClickGuiScreen` (Gunmetal-Fenster, Command-Center-Header, Sidebar, Overview, Modul-Karten und echte Einstellungen). Der erste Lauf legte die Skala-3-Überdeckung im Sidebar-Footer offen; die darauf folgende responsive Korrektur ist kompiliert und getestet, aber noch nicht als neuer Screenshot archiviert. Daher ist die **endgültige manuelle Sichtprüfung des letzten Layout-Tunings noch offen**.

## Client-Testnachweis

`./gradlew runClientGameTest -Pmarket --no-daemon` startete Minecraft 1.21.11/Fabric, aktivierte das Marktmodul und schrieb reale Screenshots nach `build/run/clientGameTest/screenshots/`.

- `0001_market_1a_showall.png`: Item List geöffnet
- `0007_market_2_ah.png`: AH-Marktansicht
- `0008_market_3_ee.png`: Energie-Marktansicht
- `0000_showcase_01_dashboard_overview.png`: Dashboard im echten Client

Die Testdaten für AH/EE sind absichtlich synthetisch und beweisen den Render-/Interaktionspfad, nicht die Aktualität von Live-Serverpreisen.

## Manueller Live-Test nach Installation

1. Prism **Cosmic** starten und im Mod-Menü kontrollieren, dass ThePrisons 1.2.1 geladen ist.
2. Right Shift drücken: das Dashboard muss die Kartenansicht mit `27 features active` öffnen.
3. Inventar öffnen, die Suchleiste doppelklicken und `shard` tippen: die Item List muss erscheinen.
4. Einen freigegebenen Shard, Key, Book, Revealed Book, Contraband oder Charge Orb mit serverseitigem Modell anzeigen: nur diese 35 Pfade müssen das neue Sci-Fi-Artwork verwenden; andere Cosmic-Items müssen unverändert bleiben.
5. `/ah` und `/ee` öffnen: Marktansicht, Preiszeile und Angebotsdaten müssen erscheinen. Mit einem echten Gegenstand hovern, um den serverseitigen Tooltip-/Overlaypfad zu prüfen.
6. Bei Abweichung `logs/latest.log` mitsenden und insbesondere nach `theprisons_items_standard`, `Core started` und Mixin-Fehlern suchen.
