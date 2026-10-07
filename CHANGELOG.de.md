# Changelog (Deutsch)

Die deutsche Fassung der Release-Notizen von ThePrisons. Der vollständige englische Verlauf steht in [CHANGELOG.md](CHANGELOG.md); die Formatregeln sind dort beschrieben.

## [1.2.1] - 2026-10-07

### Geändert

- **Item-Sortierer:** Items einer Art kommen jetzt in denselben privaten Tresor, egal welches Level, Prozent oder welche Zahl - „Charge Orb 6“ landet neben „Charge Orb 12“, „Aegis I“ neben „Aegis IV“ (Seltenheits-Wörter trennen weiterhin: ein Godly-Buch ist kein Simple-Buch)
- **Wormhole-Powerups:** Double Tap, Overdrive und BOGO haben je eine Textur pro Seltenheit, in der Farbe der Seltenheit, die das Enchant am Wormhole hat
- **„Random“-Items** (Random Enchant Book, Random Page, Random Prestige Token, Random Boss Egg, Random Trinket, Random Powerup, Random Satchel, ...) sind schwarz: die schwarze Version des Items, für das sie stehen, mit einem kleinen „?“

### Behoben

- **Item-Sortierer:** Erze mit Namen oder Lore (z. B. `diamond_ore`) kommen nicht mehr in private Tresore

## [1.2.0] - 2026-10-07

> **In Arbeit:** Das **Bandit-Makro** ist erst zu etwa 2 % fertig. Es ist in diesem Release, damit ihr seht, wohin die Reise geht – nicht, weil es fertig ist. Rechnet mit unsinnigem Verhalten und lasst es nicht unbeaufsichtigt laufen.

### Hinzugefügt

- **Bandit-Makro (in Arbeit, ~2 %)** - ein Zustandsautomat-Makro (Taste `J`), das Banditen mit dem Speer jagt: suchen, wie von Hand zielen, aufladen, werfen, zurückrufen, Abklingzeit. Dazu ein Gefahrencheck (Rückzug und Not-Stopp, wenn echte Spieler nahe kommen), eine Patrouillen-Route und eine HUD-Karte. Die Banditen-Seite des Dashboards hat dafür drei neue Unter-Tabs. Das meiste ist noch nicht auf dem Server getestet
- **Markt-Suche über das ganze Auktionshaus** - mit einem Suchtext liest der eigene Auktionshaus-Bildschirm im Hintergrund jede Server-Seite und zeigt alle Treffer in einer Liste; Hover zeigt Preis, Energiewert und Server-Seite, ein Klick bringt dich zum Item
- **Erz-Makro Verfolger** - wer dem Makro ständig folgt, bekommt zuerst eine höfliche Nachricht (`/msg`); ist er 30 Sekunden später noch da, geht das Makro weit weg (`/spawn` und `/warp` zurück). Wer dich getötet hat, wird gemerkt, und das Makro verschwindet, sobald er in die Nähe kommt
- **Erz-Makro Befehls-Abklingzeiten** - „This command is currently on cooldown for 22s“ nach `/spawn` oder `/home` wird abgewartet, danach wird der Befehl erneut gesendet
- **Erz-Makro Diamant-Mine** - der Weg vom Spawn zurück in die Diamant-Mine (zum Punkt laufen, nach unten schauen, springen)

### Geändert

- **Item-Sortierer:** Erze in jeder Form (Erze, Barren, Roherze, Erzblöcke, Kohle, Diamanten, Smaragde ...) zählen nicht mehr als „Inventar voll“ - abgebaute Diamanten schickten das Makro früher zum Spawn
- **Erz-Makro, bewachte Zone:** kein Weiterlaufen mehr auf Boden, den die Wächter-Kreise nicht abdecken (4 Blöcke Spielraum, nur seitwärts; Hinunterspringen ist erlaubt); mit Spieler in der Nähe läuft das Makro 0 ungeschützte Blöcke, allein 6
- **Erz-Makro, Spawn-Flucht:** `/spawn` wird erst nach 24 s erneut gesendet (der Server zählt 18 s herunter; ein neues `/spawn` startete den Countdown neu)
- **Freunde:** Banditen und Wächter werden nie als Freunde oder Gang eingefärbt; die Leuchtfarbe wird zwischengespeichert
- **Leistung:** HUD-Karten, das Session-HUD, die Schritt-Labels des Auktionshauses und das Spieler-Leuchten kosten pro Frame deutlich weniger (im Spiel gemessen)
- **Speer-Helfer** pausiert einige Sekunden, nachdem der Speer die Hand verlassen hat; das nimmt Lag bei Banditen-Events

### Behoben

- **Erz-Makro, /spawn-Countdown:** das Makro stoppt nicht mehr mit „no way“, solange der `/spawn`-Countdown noch läuft
- **Banditen-Zielen:** die Linie der Banditen wird über volle 180 Grad gewählt, der Durchschlagswinkel des Speers wird beachtet; Freunde in der Linie stoppen einen Wurf
