# Nexora Core V2

Discord-Bot für **Nexora • Mod Community**, Application ID `1557515544230371338`.
Unterstützt ThePrisons und Sky Supra unabhängig, mit persistenter Mod-Auswahl,
privaten Tickets und getrennten Release-Kanälen.

Die vollständige Betriebs- und Migrationsanleitung steht in [NEXORA.md](NEXORA.md).
Die bekannten, vom Owner bereitgestellten Kanal-IDs stehen in
[server-config.example.json](server-config.example.json). Server-ID und bekannte Kanäle sind dort dokumentiert.

## Lokal prüfen

```text
python -m pip install -r requirements.txt
python -m unittest discover -s tests -t .
python -m prisonsbot check
python -m prisonsbot register --dry-run
python -m prisonsbot panel --dry-run
```

Die Tests verbinden sich nicht mit Discord. Veröffentlichungen, REST-Client und
Content-Rendering nutzen die Standardbibliothek; der Gateway-Listener braucht discord.py.

## Befehle

`/mods`, `/download mod`, `/changelog mod`, `/support mod`, `/bug mod`, `/suggest mod`,
`/status`, `/setup preview`, `/setup apply approval`. Bestehende `/version` und `/roadmap` bleiben.

```text
python -m prisonsbot publish faq --dry-run
python -m prisonsbot release --mod theprisons --version 1.2.1 --dry-run
python -m prisonsbot release --mod sky-supra --version 1.0.0 --release-json release.json --dry-run
python -m prisonsbot setup snapshot
python -m prisonsbot setup preview --snapshot snapshot.json
python -m prisonsbot setup apply --plan plan.json --approval SHA256
python -m prisonsbot run
```

Das ursprüngliche `release --version ...` bleibt für lokale ThePrisons-Changelogs verfügbar.
Die V2-Workflows verwenden immer `--mod`, gemeinsame Concurrency und getrennte Zielkanäle.
Sky Supra hat noch kein Repository/Release; das Manifest beginnt ausdrücklich `unreleased`.

## Freigaben

Nur auf `dev` arbeiten. Keine Live-Änderungen durch Tests oder CI.
Migration braucht `NEXORA_MIGRATION_APPROVED=true` plus die genaue Prüfsumme eines frischen Plans.
Rollenwahl/Tickets brauchen `NEXORA_COMMUNITY_ENABLED=true`.
Live-Publishing/Command-Registrierung brauchen `NEXORA_PUBLISH_ENABLED=true`.
Diese Schalter erst nach ausdrücklicher Owner-Freigabe aktivieren.

Tokens ausschließlich über `DISCORD_BOT_TOKEN` im Prozess beziehungsweise als GitHub Secret.
Die übrigen IDs werden als GitHub Variables konfiguriert. Kein Webhook-Fallback,
keine Administrator-Berechtigung für den Bot, keine Löschung bestehender Kanäle oder Nachrichten.

Der Listener benötigt dauerhaftes Hosting und persistentes SQLite-State-Verzeichnis;
GitHub Actions hostet den Listener nicht. Konfiguration, Berechtigungsmatrix, minimale Bot-Rechte,
Onboarding und Verhalten bei unterbrochenen Operationen sind in NEXORA.md beschrieben.
