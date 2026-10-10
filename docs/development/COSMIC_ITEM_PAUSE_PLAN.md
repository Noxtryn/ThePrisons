# Cosmic Item System – Pausenplan

Status: **pausiert** (Product-Owner-Entscheidung, 2026-10-09). Keine Weiterentwicklung bis zur ausdrücklichen erneuten Freigabe.
Basis-Commit vor der Pause: `e30829d` (`dev`). Die Pause selbst ist ein eigener Commit auf `dev` (SHA siehe `docs/development/handover/CURRENT_STATE.md`).

## 1. Abgrenzung: was „Cosmic Item System“ im Code ist

Im Quellcode gibt es keinen Baustein mit diesem Namen. Die Abgrenzung wurde aus den tatsächlichen Abhängigkeiten abgeleitet und am 2026-10-09 mit dem Product Owner bestätigt (inklusive der beiden globalen Look-Schichten c und d):

| # | Teil | Was es zur Laufzeit tat | Einstieg |
|---|---|---|---|
| a | `ItemLookModule` („Item Textures“, Modul `item_look`) | ersetzte über einen Mixin das Item-Modell von ~460 erkannten Cosmic-Items, Spitzhacken, Schwertern, Speeren und Menü-Buttons durch eigene Modelle; zeichnete Tier-Rahmen hinter jedes GUI-Item, Badges (Orb-%, Level) in die Slot-Ecke und eigene Tier-Tooltip-Rahmen | `ModuleRegistry`, `FeatureProfile.ON` |
| b | `theprisons_items_standard` – die 35 HD-Standardtexturen | eingebautes Resource-Pack über den Classic-Texturen | `ThePrisonsResourcePackManagerMixin` |
| c | Comic-Texturfilter (`ComicTextures`/`ComicFilter`, Setting `design.comic_textures`, Standard **an**) | zeichnete beim Laden **jede** Vanilla-Textur (Items, Mobs, GUI) im Comic-Look um | `ThePrisonsSpriteContentsMixin`, `ThePrisonsTextureContentsMixin` |
| d | `theprisons_look`-Pack | ersetzte den Vanilla-Tooltip-Rahmen | `ThePrisonsResourcePackManagerMixin` |
| e | `VanillaBases` | lernte, welches Vanilla-Item eine Cosmic-Familie trägt, und schrieb `config/theprisons/item_bases.json` (nur für das Item-Look-Feature „Plain base items“) | Seiteneffekt in `PrisonsItems.info()` |

**Nicht Teil der Pause** (produktiv, unverändert erhalten):

- `PrisonsItems` – die Item-Erkennung (Familie, Tier, Badge). Sie wird von `core/cosmic/sense/StackReader` (Sensoren/Capture), `MarketScreen` (Tier) und dem AH-Overlay verwendet. Der Bezeichner `theprisons:prisons/<familie>/<variante>` in `Info.model()` ist dort ein **Klassifikationsschlüssel**; `StackReader` leitet daraus die Item-Klasse ab. Dafür wird kein Asset benötigt.
- `items/*` (Registry, Klassifikation, Suche, `ItemRarity`), Item List (`item_list`), AH-/EE-/Energy-Overlays, `MarketModule`, Storage Overlay.
- `assets/minecraft/{items,models,textures/item}` – Logo- und Menü-Icon-Items aus `ThePrisonsMain` (`minecraft:theprisons`, `ah`, `ee`, `tinker`, `skilltree`).
- GUI-Icon `textures/gui/sprites/icon/item_look.png` – wird auch von `PlayerViewer` (Tab „Skills“) genutzt.

## 2. Was umgesetzt wurde

Alles wurde mit `git mv` verschoben (Historie bleibt erhalten); nichts wurde gelöscht.

### Neuer, nicht ausgelieferter Quellbaum `src/paused/cosmic-items/`

```
src/paused/cosmic-items/
  README.md
  java/io/theprisons/modules/qol/items/   ItemLookModule, ItemTexturePacks, VanillaBases
  java/io/theprisons/modules/general/look/ ComicFilter, ComicTextures
  java/io/theprisons/mixin/                ThePrisons{ItemModelManager,DrawContext,HandledScreenTooltip,
                                           SpriteContents,TextureContents,ResourcePackManager}Mixin
  resources/theprisons-cosmic-items.mixins.json   (Mixin-Liste für die Reaktivierung; wird nirgends geladen)
  resources/assets/theprisons/items/prisons/**            465 Item-Definitionen
  resources/assets/theprisons/models/item/prisons/**      465 Modelle
  resources/assets/theprisons/textures/item/prisons/**    509 PNGs (inkl. ungenutzter armor/, mask_worn/)
  resources/assets/theprisons/textures/entity/**          16 PNGs (ungenutzte Rüstungs-Reste)
  resources/assets/theprisons/textures/gui/sprites/tooltip/**   24 Dateien (Tier-Tooltip-Rahmen)
  resources/assets/theprisons/textures/gui/sprites/tier_frame/** 1 Datei
  resources/resourcepacks/theprisons_items_standard/**    35 PNGs + manifest.json + pack.mcmeta
  resources/resourcepacks/theprisons_look/**              5 Dateien
  test/java/io/theprisons/modules/qol/items/  ItemTexturePacksTest (6 Tests), CosmicItemLookTest (2 Tests)
```

Gradle: Source-Sets `pausedCosmicItems` und `pausedCosmicItemsTest` in `build.gradle`. `processResources`/`jar` sehen sie nicht. Ein `build` kompiliert sie nicht. Mit `./gradlew checkPausedCosmicItems` werden sie gegen den aktuellen Hauptcode kompiliert und ihre 8 Tests ausgeführt.

### Entkopplung im produktiven Code

| Datei | Änderung |
|---|---|
| `src/main/resources/theprisons.mixins.json` | 6 Mixins entfernt |
| `modules/ModuleRegistry.java` | `ItemLookModule` wird nicht mehr registriert |
| `modules/FeatureProfile.java` | `item_look` aus `ON` entfernt |
| `gui/click/Tab.java` | `item_look`-Fall entfernt |
| `modules/general/DesignModule.java` | Setting `comic_textures` und Accessoren entfernt |
| `gui/dashboard/DashboardScreen.java` | Schalter „Comic textures“ auf der Design-Seite entfernt |
| `modules/qol/items/PrisonsItems.java` | `VanillaBases.observe(...)`-Seiteneffekt entfernt; Javadoc: `model` ist Klassifikationsschlüssel |
| `items/client/ItemStacks.java` | Item List erzwingt kein `ITEM_MODEL` mehr (sonst würden Masken als Missing-Model gezeichnet) |
| `modules/qol/market/MarketScreen.java` | `ItemLookModule.noFrame`-Klammer entfernt |
| `modules/qol/market/MarketSearch.java` | `noFrame`, `tooltipStyle` und Masken-`ITEM_MODEL` entfernt (Klasse ist ohnehin toter Code, siehe Phase-2-Plan) |
| `modules/qol/storage/StorageOverlayScreen.java` | Tooltip nutzt den eigenen `TOOLTIP_STYLE` des Stacks statt `ItemLookModule.tooltipStyle` |
| `paused/.../ComicTextures.java` | eigene `enabled`-Variable statt `DesignModule.comicTextures()` (damit der pausierte Baum eigenständig kompiliert) |
| `scripts/check-release.sh`, `.github/workflows/pages.yml` | Pfad der 35 Website-Vorschaubilder auf den pausierten Ort umgestellt |
| `src/test/.../PrisonsItemsTest.java` | 2 Item-Look-Tests in den pausierten Baum verschoben; Erkennungstests bleiben |
| `src/test/.../CosmicItemPauseTest.java` | **neu**: Regressionsschutz – keine pausierten Assets/Mixins/Module im ausgelieferten Baum |

## 3. Verifikation (2026-10-09, Windows, JDK 21.0.12)

| Prüfung | Ergebnis |
|---|---|
| Baseline vorher `./gradlew clean test build` | OK – 737 Tests, 0 Fehler, 1 übersprungen |
| Nachher `./gradlew clean test build` | OK – 732 Tests (−8 verschoben, +3 neu), 0 Fehler, 1 übersprungen |
| `./gradlew checkPausedCosmicItems` | OK – pausierter Code kompiliert gegen `main`, 8/8 Tests grün |
| JAR-Inhalt | 2549 → 881 Einträge, 6,6 MB → 2,6 MB; 1668 Einträge entfernt, 0 hinzugefügt |
| JAR-Scan auf `ItemLookModule`, `ItemTexturePacks`, `VanillaBases`, `ComicTextures`, `ComicFilter`, `theprisons_items_standard`, `theprisons_look`, `tier_frame`, `item_bases.json` in allen Dateien inkl. Bytecode | keine Treffer |
| `fabric.mod.json` im JAR | Entrypoints unverändert (`ThePrisonsMain`, `ThePrisonsClient`, ModMenu); kein Verweis auf die pausierte Mixin-Konfiguration |
| `theprisons.mixins.json` im JAR | 22 Einträge, keiner der 6 pausierten |
| Client-GameTest `./gradlew runClientGameTest -Pmarket` (echter Fabric-Client MC 1.21.11, synthetische Marktdaten) | OK – 9 Screenshots (Inventar, Item List alle/Shard/Hover/Maske, AH, EE, Tinker, eigene Angebote). Der Resource-Reload listet nur noch `theprisons` (kein `theprisons_items_standard`/`theprisons_look`). 0 Log-Meldungen zu fehlenden Modellen/Texturen. Sichtprüfung: Item-List-Karten zeigen Vanilla-Träger (Prismarin-Scherbe, Spielerkopf), keine Missing-Model-Würfel; AH zeigt Vanilla-/Server-Icons mit Standard-Tooltip und intaktem Block `MARKET OBSERVATION`; EE-Liste vollständig |
| `scripts/check-release.sh` | **lokal nicht ausführbar** (kein Python auf dieser Maschine); Pfade angepasst, Prüfung erfolgt in CI |
| Live-Test Cosmic Prisons / Prism | **MANUAL TEST REQUIRED** |

## 4. Auswirkungen für Nutzer

- Cosmic-Items werden wieder genau so dargestellt, wie Server und Vanilla (bzw. ein vom Spieler geladenes Resource-Pack oder die Mod „Cosmic Textures“) sie liefern. Es gibt keine Tier-Rahmen, keine Badges, keine eigenen Tooltip-Rahmen und keinen Comic-Filter mehr.
- Die Item List zeigt Karten mit dem Vanilla-Trägeritem (z. B. Papier, Spielerkopf bei Masken). Name, Kategorie, Tier und Marktdaten bleiben unverändert. Die Rarity-Farben der Karten stammen weiterhin aus `ItemRarity`.
- Gespeicherte Werte: Der Block `item_look` und `design.comic_textures` in `config/theprisons/modules.json` werden beim nächsten Speichern verworfen (`ConfigStore` prunt unbekannte Settings und schreibt nur registrierte Module). Eine Reaktivierung startet daher mit Standardwerten. `config/theprisons/item_bases.json` bleibt liegen und wird nicht mehr gelesen.
- Öffentliche Texte (README, Website, CHANGELOG 1.3.0-beta.1, Discord) bewerben weiterhin „35 approved sci-fi item textures“. Sie werden **nicht ohne Freigabe** geändert, siehe `docs/audit/RELEASE_AUDIT.md`.

## 5. Reaktivierung (nach Freigabe)

1. Dateien zurückverschieben (Historie bleibt erhalten):
   ```
   git mv src/paused/cosmic-items/java/io/theprisons/modules/qol/items/*.java   src/main/java/io/theprisons/modules/qol/items/
   git mv src/paused/cosmic-items/java/io/theprisons/modules/general/look        src/main/java/io/theprisons/modules/general/look
   git mv src/paused/cosmic-items/java/io/theprisons/mixin/*.java                src/main/java/io/theprisons/mixin/
   git mv src/paused/cosmic-items/resources/assets/theprisons/{items,models}     src/main/resources/assets/theprisons/
   git mv src/paused/cosmic-items/resources/assets/theprisons/textures/{item,entity} src/main/resources/assets/theprisons/textures/
   git mv src/paused/cosmic-items/resources/assets/theprisons/textures/gui/sprites/{tooltip,tier_frame} src/main/resources/assets/theprisons/textures/gui/sprites/
   git mv src/paused/cosmic-items/resources/resourcepacks                        src/main/resources/resourcepacks
   git mv src/paused/cosmic-items/test/java/io/theprisons/modules/qol/items/*.java src/test/java/io/theprisons/modules/qol/items/
   ```
   Die verwaisten Reste (`textures/item/prisons/armor`, `mask_worn`, `textures/entity`) **nicht** mit zurückholen, sondern nach `archive/` legen.
2. Die 6 Mixin-Namen aus `resources/theprisons-cosmic-items.mixins.json` wieder in `src/main/resources/theprisons.mixins.json` eintragen und die pausierte Datei löschen.
3. In `ModuleRegistry.registerAll` wieder `modules.register(new io.theprisons.modules.qol.items.ItemLookModule());` (nach `StorageOverlayModule`) und in `FeatureProfile.ON` wieder `"item_look"` aufnehmen.
4. Den Comic-Schalter wieder anbinden: Entweder in der neuen Config GUI als Setting, das `ComicTextures.setEnabled(on)` aufruft und danach `MinecraftClient.reloadResources()` auslöst, oder die alte `DesignModule`-Variante aus `git show e30829d:src/main/java/io/theprisons/modules/general/DesignModule.java` übernehmen. Dabei vorher klären, ob der Filter weiter standardmäßig an sein soll.
5. Optional wiederherstellen: `VanillaBases.observe(...)` in `PrisonsItems.info()`, die `ITEM_MODEL`-Zeile in `items/client/ItemStacks`, `noFrame` in `MarketScreen`, `tooltipStyle` in `StorageOverlayScreen`. Referenz: `git show e30829d -- <pfad>`.
6. Pfade in `scripts/check-release.sh` und `.github/workflows/pages.yml` zurückstellen. Die zwei Tests in `CosmicItemLookTest` zurück nach `PrisonsItemsTest` holen. `CosmicItemPauseTest`, die Source-Sets und den Task `checkPausedCosmicItems` aus `build.gradle` entfernen.
7. Verifikation: `./gradlew clean test build`, Client-GameTests, JAR-Inhalt prüfen (Erwartung: `resourcepacks/theprisons_items_standard` mit 35 PNGs vorhanden), dann ein Live-Test in Prism.

Solange der Pausenzustand gilt, gehört **keine** Item-Look- oder Textur-Einstellung in die neue Config GUI.
