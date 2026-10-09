# ThePrisons – Release-Audit

Stand: 2026-10-09. Geprüft wurden `dev`, `origin/Release`, Tags, die GitHub-Releases (öffentliche API), CI-Läufe, die Website und der Discord-Workflow.
In dieser Session wurde **nichts** veröffentlicht, getaggt, nach `Release` gemergt oder nach Discord gepostet.

## 1. Was öffentlich ist

| Kanal | Stand | Enthält das (jetzt pausierte) Item-System? |
|---|---|---|
| GitHub Release „latest“ | **v1.2.1** (2026-10-07), Asset `ThePrisons-Nebula-v1.2.1-mc1.21.11.jar`, kein Prerelease | ja – eigene Item-Texturen, Tier-Rahmen und Comic-Filter (ältere Fassung, ohne die 35 HD-Texturen) |
| Weitere Releases | v1.2.0, v1.1.3, v1.1.2, v1.1.1, v1.1.0 (alle 2026-10-05 bis 10-07, alle aus `Release`) | ja |
| v1.3.0-beta.1 | **nicht veröffentlicht**: kein Tag, kein GitHub-Release | – |
| `origin/Release` | `877185c`. Enthält den kompletten v1.3.0-beta.1-Stand (`mod_version=1.3.0-beta.1`, 35 Texturen) über PR #20 sowie den direkten Commit `877185c Update discord.yml` | ja |
| Website `noxtryn.github.io/ThePrisons` | live (HTTP 200), aus `Release` deployt, Inhalt „Cosmic Evolution“ mit **„35 approved textures“**, Texturgalerie, „HD Item Textures“ als erstes Feature | ja, wird beworben |
| README (EN/DE) auf `Release` und `dev` | „PUBLIC BETA RELEASE CANDIDATE · v1.3.0-beta.1“, bewirbt die 35 Sci-Fi-Texturen und verweist für den Download auf Releases, wo es keine 1.3.0-beta.1 gibt | ja |
| Discord | Workflow „Discord“ am 2026-10-08 15:29 UTC manuell auf `Release` gestartet (Erfolg). Vorgabe: `action=release`, `version=1.3.0-beta.1`, **`dry_run=true`**. Ob mit `dry_run=false` gestartet wurde, ist ohne Log-Zugriff **nicht feststellbar** | möglicherweise angekündigt |

## 2. Abweichungen und Risiken

| # | Befund | Folge |
|---|---|---|
| A1 | `Release` und Website bewerben ein Feature, das auf `dev` seit `084f88c` pausiert ist | Ein Merge `dev → Release` würde den Pages-Build auslösen (Pfadfilter `CHANGELOG*.md`, `src/paused/…/theprisons_items_standard/**`). Die Website würde dann weiter „35 textures“ zeigen, obwohl der Mod sie nicht mehr ausliefert. |
| A2 | Der CHANGELOG-Abschnitt `[1.3.0-beta.1]` listet „35 approved sci-fi item textures“ unter *Added*. Auf `dev` steht im neuen `[Unreleased]`-Abschnitt die Pause. | Wird 1.3.0-beta.1 mit dem heutigen `dev` getaggt, widersprechen sich die Release-Notes (Quelle: `scripts/changelog-section.sh`). |
| A3 | README verlinkt einen 1.3.0-beta.1-Download, den es nicht gibt (sagt das in Zeile 65 aber selbst) | Verwirrung bei Nutzern |
| A4 | `origin/Release` hat einen Commit, der `dev` fehlt (`877185c`, `.github/workflows/discord.yml`: neuer Ablauf, Dry-Run-Default, neue Inputs) | Kein Merge-Konflikt zu erwarten, aber `dev` testet einen veralteten Discord-Workflow. Empfehlung: `origin/Release` nach `dev` zurückmergen (eigener, freigegebener Schritt). |
| A5 | Lokales `Release` steht 73 Commits hinter `origin/Release` | Wird nicht benutzt; nichts tun, nie davon pushen |
| A6 | Die Discord-Inhalte (`discord-bot/content/*.json`) enthalten Platzhalter für Version und Download, die aus dem neuesten GitHub-Release kommen | Ein Post bezieht sich automatisch auf v1.2.1, solange 1.3.0-beta.1 nicht existiert |
| A7 | `scripts/check-release.sh` prüft, dass genau 35 Vorschau-PNGs existieren und dass jede Website-Texturkarte eine Quelle hat. Der Pfad zeigt jetzt auf `src/paused/…` | Die Website-Prüfung bleibt grün, solange die Website die Texturen zeigt. Werden sie von der Website entfernt, muss das Skript mit angepasst werden. |
| A8 | Die 1.3.0-beta.1-Notes nennen als bekannte Probleme den Scale-3-Fehler und den fehlenden Live-Test. Beides ist weiterhin offen. | Ein Beta-Release wäre nach wie vor nicht live-verifiziert |

## 3. Release-Blocker für die nächste Veröffentlichung

Ein Release (ob 1.3.0-beta.1 oder eine neue Nummer) braucht vorher:

1. **Produktentscheidung (PO):** Wie wird die Pause öffentlich dargestellt? Optionen: (a) Website, README und Discord-Texte von den Texturen bereinigen; (b) 1.3.0-beta.1 verwerfen und als 1.3.0-beta.2 ohne Texturen neu schneiden; (c) Website unverändert lassen, bis Phase 2 fertig ist, dann **nicht** nach `Release` mergen. **Ohne Freigabe ändert diese Session keine öffentlichen Texte.**
2. CHANGELOG bereinigen: Der Abschnitt der zu taggenden Version darf keine nicht ausgelieferten Features nennen.
3. `origin/Release` → `dev` zurückmergen (A4).
4. `./gradlew clean test build` und `checkPausedCosmicItems` grün; Client-GameTests `-Pshowcase` und `-Pmarket` grün.
5. Live-Abnahme in Prism („Cosmic“, Fabric 0.19.5, Java 21) auf Cosmic Prisons: `/ah`, `/ee`, Item List, Tooltips, Original-Lore. **MANUAL TEST REQUIRED.**
6. Erst danach PR `dev → Release`, Tag `v<version>`; Release-Workflow und Pages laufen automatisch. Discord-Post nur manuell, erst als Dry-Run.

## 4. CI-Zustand (öffentliche GitHub-API)

| Workflow | Letzter Lauf | Ergebnis |
|---|---|---|
| CI auf `dev` (`e30829d`) | 2026-10-08 15:25 UTC | success |
| CI auf `Release` (`877185c`) | 2026-10-08 15:28 UTC | success |
| Website auf `Release` (PR #20) | 2026-10-08 15:25 UTC | success |
| Discord (`workflow_dispatch`, `Release`) | 2026-10-08 15:29 UTC | success (Inhalt unbekannt, s. o.) |

Lokal (Windows, JDK 21.0.12) in dieser Session: Baseline `clean test build` grün (737 Tests); nach der Pause grün (732 Tests); `checkPausedCosmicItems` grün (8 Tests); `runClientGameTest -Pmarket` grün. `check-release.sh` und die Bot-Tests konnten mangels Python lokal **nicht** laufen. Sie laufen beim nächsten Push in CI.

## 5. JAR-Vergleich (`ThePrisons-Nebula-v1.3.0-beta.1-mc1.21.11.jar`, lokal gebaut)

| | Vor der Pause (`e30829d`) | Nach der Pause (`084f88c`) |
|---|---|---|
| Größe | 6 639 787 B | 2 587 053 B |
| SHA-256 | `ef6acafb…3984149f` | `8ec12f16…4ef9c921` |
| Einträge | 2 549 | 881 |
| `resourcepacks/` | 42 Dateien | 0 |
| `assets/theprisons/items`, `models` | 465 + 465 | 0 |
| `assets/theprisons/textures/item`, `entity`, `gui/sprites/tooltip`, `tier_frame` | 509 + 16 + 24 + 1 | 0 |
| Mixins in `theprisons.mixins.json` | 28 | 22 |
| Klassen `ItemLookModule`, `ItemTexturePacks`, `VanillaBases`, `ComicTextures`, `ComicFilter` + 6 Mixins | enthalten | nicht enthalten; auch keine Referenz darauf im Bytecode |

Hinweis: Die SHA-Werte gelten nur für diesen lokalen Build. Der Release-Workflow baut auf Ubuntu neu.

## 6. Git-Regeln, eingehalten in dieser Session

- Gearbeitet nur auf `dev`. `Release` (lokal und remote) unverändert. Kein Force-Push, kein Tag, kein Release, kein Discord-Post.
- `stash@{0}` unverändert. Die sechs uncommitteten Mode-Diffs wurden weder committet noch zurückgesetzt. Bei `scripts/check-release.sh` wurde nur der Inhalt committet, der Index-Modus bleibt `100755`.
- Der Pausen-Commit `084f88c` ist **lokal**. Er wird erst nach Freigabe gepusht.
