# Dauerbetrieb auf Windows

Der Listener läuft unabhängig von Codex. Eine eigene Python-Kopie und .venv liegen neben dem Bot und sind gitignored. Der Supervisor verhindert Doppelstarts, startet nach Prozessfehlern mit wachsender Pause neu, begrenzt Logs auf vier Dateien mit je 2 MB und schreibt Online-/Gateway-Status. Ein ungültiger Token oder fehlende Konfiguration stoppt den Start statt einer Endlosschleife.

## Lokale Einrichtung

1. `Install-Nexora.ps1 -Python <Pfad-zu-Python-3.12>` installiert die Umgebung. Bei dieser PC-Integration ist die Laufzeit bereits vorbereitet.
2. `Configure-Nexora.ps1` im normalen Windows-Terminal ausführen. Server-ID eingeben und danach den neuen Bot-Token verdeckt eingeben. Der Token wird mit Windows-DPAPI für diesen Benutzer verschlüsselt. Nicht in Chat, Git oder settings.json ablegen. Einen im Chat offengelegten Token vorher im Developer Portal zurücksetzen.
3. `Start-Nexora.ps1` starten. Der Launcher entschlüsselt den Token nur im Prozess und führt den Supervisor aus. Server-Schreibschalter sind zunächst alle false.
4. `Status-Nexora.ps1` zeigt Supervisor und Gateway-Zustand. Nur ein frischer `online`-Health-Eintrag bestätigt eine Verbindung zum konfigurierten Server. Ein Supervisor-Prozess allein beweist keine Discord-Verbindung.
5. `Enable-NexoraAutostart.ps1` legt einen versteckten Starter im persönlichen Autostartordner an. Der Bot startet beim nächsten Windows-Login. `Stop-Nexora.ps1` stoppt nur den eigenen Listener/Supervisor über eine Request-Datei. Ein manueller Neustart hebt diese Stop-Anforderung wieder auf.

Der Autostart gilt für diesen Windows-Benutzer nach dem Login. Bei Abmeldung, ausgeschaltetem PC, Standby oder unterbrochenem Internet besteht keine dauerhaft verfügbare Verbindung. Ein Betrieb vor dem Login oder echte 24/7-Verfügbarkeit benötigt einen Windows-Dienst mit geeignetem Dienstkonto oder einen ständig laufenden Server; diese Variante ist hier nicht eingerichtet. Energieeinstellungen werden nicht verändert.

## Migration bleibt getrennt

Installation/Autostart sind keine Freigabe zum Ändern des Discord-Servers. settings.json hält NEXORA_MIGRATION_APPROVED, NEXORA_COMMUNITY_ENABLED und NEXORA_PUBLISH_ENABLED auf false. Vor der Migration einen echten /setup preview durchführen und den exakten Plan freigeben. Rollen-ID-Konfiguration danach in state/settings.json ergänzen. Erst nach ausdrücklicher Freigabe die benötigten Schreibschalter aktivieren. Command-Registrierung und öffentliches Mod-Panel bleiben separate freigegebene Operationen.

Die Runtime wird nicht mit Git versioniert. Vor einem Umzug müssen Python/venv neu installiert und der Token auf dem Ziel-PC neu verschlüsselt werden. Bei Updates den Bot stoppen, Code aktualisieren, Tests prüfen und wieder starten. state/nexora.sqlite3 behalten.

Quellen: [Microsoft DPAPI/SecureString](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.security/convertto-securestring), [Windows-Autostart](https://support.microsoft.com/en-us/windows/configure-startup-applications-in-windows-115a420a-0bff-4a6f-90e0-1934c844e473).
