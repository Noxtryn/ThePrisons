<p align="center">
  <img src="docs/media/banner.png" alt="ThePrisons — Cosmic Evolution" width="100%">
</p>

<p align="center">
  <strong>THEPRISONS — COSMIC EVOLUTION</strong><br>
  <strong>ÖFFENTLICHER BETA-RELEASEKANDIDAT · v1.3.0-beta.1 · Minecraft 1.21.11 · Fabric · Java 21</strong>
</p>

<p align="center">
  <a href="https://noxtryn.github.io/ThePrisons/">Offizielle Website</a> ·
  <a href="https://github.com/Noxtryn/ThePrisons/releases">Download / Releases</a> ·
  <a href="CHANGELOG.de.md">Changelog</a> ·
  <a href="README.md">English</a> ·
  <a href="https://github.com/Noxtryn/ThePrisons/issues/new">Feedback & Fehler melden</a>
</p>

ThePrisons ist eine clientseitige Fabric-Mod für Cosmic Prisons. Cosmic Evolution verbindet die vorhandenen Ingame-Werkzeuge mit 35 freigegebenen Sci-Fi-Itemtexturen, einer überarbeiteten Config-GUI, einer Item-Übersicht im Inventar, Markt-Overlays und einem Live-Session-HUD.

> **Beta-Hinweis:** Dies ist ein öffentlicher Vorab-Release. AH-/EE-Verhalten und serverspezifische Itemdaten wurden auf Cosmic Prisons noch nicht vollständig geprüft. Marktwerte sind Beobachtungen, keine Handelsgarantie. Bei 1280×720 und GUI-Skalierung 3 können Inhalte der Config-GUI überlaufen oder gedrängt wirken.

## Funktionen im Beta-Release

- **Sci-Fi-Itemgrafiken:** 35 freigegebene integrierte Texturen für Shards, Contraband, Books, Revealed Books, Keys und Charge Orbs. Items ohne freigegebenen Ersatz behalten ihre bisherige Cosmic-Prisons-/Minecraft-Darstellung.
- **Config-GUI:** Die produktive Konfigurationsoberfläche bündelt Module und Einstellungen in durchsuchbaren Kategorien. `/prisons` öffnet das Dashboard; die Standardtaste steht in der Minecraft-Steuerung.
- **Inventory Item List:** Items im Inventar durchsuchen und filtern; Karten verwenden echte Item-Stacks und erhalten die originalen Spiel-Tooltips.
- **Auction-House-Analyse:** Das Overlay liest sichtbare Cosmic-Marktfenster und lokale Beobachtungen für Preisvergleiche. Das Verhalten wurde noch nicht vollständig auf einem Live-Server geprüft.
- **Energy-Exchange-Analyse:** `/ee`-Angebotsdetails zeigen beobachtete Preise je 1.000 Energy und Mengen-Kontext, sofern das Menü genug Daten enthält. Verfügbarkeit und Aktualität hängen von den gesehenen Angeboten ab.
- **Session-HUD:** Aktivität, Erze, Energy-/XP-Raten und weitere Session-Werte stammen aus den verfügbaren Clientdaten; manche Cosmic-spezifischen Angaben können fehlen oder nicht erkannt werden.
- **Mining-Werkzeuge:** Ore Macro, Wegpunkte und Routenaufzeichnung sind konfigurierbare Werkzeuge. Automatisierung kann gegen Serverregeln verstoßen; Nutzung auf eigenes Risiko.
- **Banditen-Werkzeuge:** Spear Helper und Bandit Macro sind enthalten. Das Bandit Macro ist jedoch ein frühes, größtenteils live-server-ungetestetes Experiment. Nicht unbeaufsichtigt laufen lassen.
- **Komfortfunktionen:** Private-Vault-Overlay, Spielerkarten, Freunde-/Gang-Farben, Benachrichtigungen, Abklingzeiten und weitere Client-Helfer.

## Screenshots und Texturen

Die Screenshots auf der [Website](https://noxtryn.github.io/ThePrisons/#screenshots) stammen aus echten Fabric-Client-Aufnahmen in automatisierten lokalen Testwelten. Die AH-/EE-Bilder verwenden synthetische Testdaten, keine Live-Angebote von Cosmic Prisons. Sie sind kein Nachweis eines Live-Server-Tests.

Die integrierte Standard-Texturquelle enthält den freigegebenen Batch mit 35 Motiven. Website-Vorschaubilder werden beim GitHub-Pages-Build aus denselben kanonischen PNG-Dateien kopiert und nicht separat nachgezeichnet.

## Installation mit Prism Launcher

1. Erstelle oder wähle in Prism eine Instanz für **Minecraft 1.21.11**.
2. Installiere **Fabric Loader 0.17.3 oder neuer** und verwende **Java 21**.
3. Füge Fabric API für Minecraft 1.21.11 hinzu. Mod Menu ist optional.
4. Lade die aktuelle JAR aus den [GitHub-Releases](https://github.com/Noxtryn/ThePrisons/releases). Öffne in Prism **Instanz bearbeiten → Mods → Hinzufügen** und wähle die `.jar` aus.
5. Starte die Instanz. `/prisons` öffnet im Spiel das Konfigurations-Dashboard. Die Release-Seite nennt die passende JAR und Version.

## Kompatibilität

| Komponente | Version |
| --- | --- |
| Minecraft | 1.21.11 |
| Fabric Loader | 0.17.3+ |
| Java | 21+ |
| Fabric API | Erforderlich, für Minecraft 1.21.11 |
| Mod Menu | Optional |
| Server | Für Cosmic Prisons entwickelt; allgemeine Client-Funktionen können anderswo nutzbar sein |

## Bekannte Einschränkungen

- Inhalte der Config-GUI können bei 1280×720 / GUI-Skalierung 3 gedrängt oder abgeschnitten sein.
- Cosmic-Prisons-Liveabnahme für `/ah` und `/ee`, Live-Marktbeobachtungen und serverspezifische Lore sind noch nicht vollständig erfolgt. Synthetische Client-Fixtures prüfen nur Darstellung und Berechnungen anhand von Testdaten.
- Markthistorie, Confidence und Aktualität hängen von den verfügbaren Client-Beobachtungen ab; fehlende Daten sind kein Angebotspreis.
- Das Bandit Macro ist experimentell. Automatisierung und Zielhilfe können Serverregeln verletzen oder zu Sanktionen führen.
- Der aktuelle Stand ist ein Release-Kandidat. Eine Beta-JAR ist erst nach Veröffentlichung im [GitHub-Releases-Bereich](https://github.com/Noxtryn/ThePrisons/releases) herunterladbar.

## Community und Projektlinks

- [Offizielle Website](https://noxtryn.github.io/ThePrisons/)
- [GitHub-Repository](https://github.com/Noxtryn/ThePrisons)
- [Releases](https://github.com/Noxtryn/ThePrisons/releases)
- [Fehler melden / Feedback geben](https://github.com/Noxtryn/ThePrisons/issues/new)
- Discord: Auf der Projektwebsite ist noch kein öffentlicher Einladungslink eingerichtet. Bis dahin bitte GitHub-Issues verwenden.

ThePrisons ist ein inoffizielles Fanprojekt und nicht mit Mojang, Microsoft oder Cosmic Prisons verbunden. Nutzung auf eigenes Risiko. Der Quellcode ist All Rights Reserved; siehe [LICENSE](LICENSE).
