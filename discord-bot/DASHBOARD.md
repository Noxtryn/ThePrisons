# Nexora Control — lokale Dashboard-Grundlage

Diese Version implementiert Phase 1 und Entwurf-Editoren für fünf Kernmodule.
Sie führt keine Discord-Schreiboperationen aus. Autorollen-Regeln, Ticket- und
Release-Einstellungen werden noch nicht vom laufenden Bot übernommen.

## Änderungen prüfen

Speichere den Entwurf und wähle **Änderungen prüfen**. Im Demo-Modus entsteht
eine ausdrücklich markierte Simulation. Beim echten Login wird ein frischer
Serverzustand gelesen. Rollen, Kanaltypen, Ticketzugänge und modbezogene
Release-Kanäle werden geprüft. Der Struktur-Editor berücksichtigt jetzt seine
Kategorienamen und Kanalvorlage bei der Änderungsvorschau und behält vorhandene
IDs bei. Revision, Dokument- und Snapshot-Prüfsumme binden die Vorschau an ihren
Ausgangszustand. Vorschauen werden separat gespeichert und können als JSON
heruntergeladen werden. Bei geändertem Entwurf wird die Prüfung abgebrochen.

Die Vorschau ist ein Dashboard-Prüfbericht, kein vom CLI anwendbarer
Migrationsplan. Es gibt weiterhin keinen Apply-Endpunkt. Autorollen-Worker und
die Übernahme neuer Ticket-/Release-Einstellungen sind noch nicht aktiviert.

## Start

Im discord-bot-Verzeichnis:

```powershell
.venv\Scripts\python.exe -m pip install -r requirements-dashboard.txt
.venv\Scripts\python.exe -m prisonsbot.dashboard --demo
```

Öffne http://127.0.0.1:8765. Die lokale Demo besitzt eine eigene Datenbank
state/dashboard-demo.sqlite3. Sie funktioniert nur bei explizitem --demo und
ist ausschließlich an die Loopback-Adresse gebunden. Kein öffentlicher Betrieb.

## Echter Discord-Login

In der vorhandenen Application die Redirect-URI
http://127.0.0.1:8765/auth/callback registrieren. Den OAuth Client Secret lokal
als DISCORD_OAUTH_CLIENT_SECRET setzen, niemals in Git/Chat. Ohne --demo starten.
Dies ist der OAuth Client Secret, nicht der Bot-Token.

Login fordert identify und guilds an. Die Mitgliedschaft wird vor Zugriff
geprüft. Bearbeiten erfordert Owner, Administrator oder Manage Guild. Rechte
werden für jede geschützte Anfrage erneut über Discord geprüft. Moderator-
und Developer-Zuweisungen mit begrenzten Mod-Rechten folgen später.

Sessions laufen nach einer Stunde ab und werden beim Backend-Neustart ungültig.
OAuth-state ist an ein HttpOnly-Cookie gebunden, einmalig und fünf Minuten gültig.
Entwurf-Schreibzugriffe benötigen passenden Origin und CSRF-Token. Discord-
Tokens bleiben im Arbeitsspeicher des Backends. HTTP-Cookies sind für diesen
Loopback-Entwicklungsbetrieb vorgesehen. HTTPS, Secure-Cookies, Reverse Proxy,
Login-Rate-Limits und dauerhafte Sessions sind vor einer externen Bereitstellung
gesondert zu implementieren. Diese Version verweigert andere Hostnamen.

## Funktionen

- Community / ThePrisons / Sky Supra getrennte persistente Entwürfe.
- Rollen/Onboarding, Embed-Nachrichten, Tickets, Releases und Serverstruktur.
- Textvorschau und revisionsbasierte Konflikterkennung.
- Protokoll der gespeicherten Änderungen ohne Inhalts-/Tokenlogging.
- Bot-Health mit Kennzeichnung veralteter Signale nach 90 Sekunden.
- Lesende Serveransicht mit vorhandenen Rollen/Kanälen und der bestehenden
  Migrationsvorschau. Ein eigener Adapter verweigert alle Schreibmethoden.
  Vorschaupläne verwenden noch die Bot-Konfiguration, nicht Dashboard-Entwürfe.
  Die Daten werden 30 Sekunden zwischengespeichert. Demo liest keinen Server.
- Auswahlhilfen für Rollen und Kanäle nach Laden der echten Serverdaten.
- --health-file kann auf den echten Bot-State zeigen; sonst wird der Status
  als unbekannt angezeigt. Keine erfundenen Mitglieder-/Ticketzahlen.
- Mobile Layouts und barrierearm beschriftete Formulare.

## Nächste Anbindung

Gemeinsame Services für Rollen, Ticketzugriff und Releases aus dem vorhandenen
Bot herauslösen; Discord-Rollen/Kanäle lesend für Auswahlfelder laden. Danach
Snapshot, Änderungsvorschau, Berechtigungsmatrix und ausdrücklich freigegebene
Jobs ergänzen. Ein Entwurf darf niemals unmittelbar als Live-Konfiguration gelten.

Tests: python -m unittest discover -s tests -t .
