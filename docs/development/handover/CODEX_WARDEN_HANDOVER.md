# Übergabe an Codex: Warden-Abstand im Ore-Höhlen-Gametest

Status: **OPEN / UNRESOLVED** · Stand 2026-10-10 · Branch `dev` (Ende Phase 2: `960ed21`, davor `25f764b`)

## Aufgabe

Isoliert untersuchen, warum `RedstoneCaveClientGameTest` mit
`AssertionError: Came within 0.5 blocks of the warden` (Zeile 224) fehlschlägt, und ob das Macro die Sicherheitsabstände zum Warden einhält. Keine GUI-Arbeit, keine neuen Features.

## Befund

- Reproduktion: `./gradlew runClientGameTest` (ohne `-P`; DEV-Profil `-Dtheprisons.dev=true`, Ore Macro aktiv, generierte Höhle).
- Test: ein eiserner Golem „1000❤ Warden“ (`NoAI`) steht in der Höhlenmitte (`WARDEN_X`, `WARDEN_Z`). Das Ore Macro soll 15 Blöcke Abstand halten; der Test verlangt mindestens 13,5 (`m[9]`, Zeile 223).
- Gemessen, je ein Lauf: Stand `960ed21` 0,55 Blöcke; Stand `25f764b` (vor Phase 2) 0,36 Blöcke. Beide Läufe: Assertion, danach Exit `0xC0000409` des Test-Clients.
- Logs: `docs/development/handover/phase2/logs/default-dev-gametest-AFTER-phase2.log` und `...-BASELINE-25f764b.log` (`[cave]`-Zeilen, Profiler-Abschnitte, HUD „Wardens: 1 (nearest 8m)“).
- Ob es früher grün war, ist nicht belegt. Es gibt zwei Läufe, keine Wiederholungen. Die Streuung ist unbekannt.

## Wo anfangen

- `src/gametest/java/io/theprisons/gametest/RedstoneCaveClientGameTest.java`: Zeilen ~105-125 (Warden), ~205-230 (Auswertung `m[9]`).
- Guard-Logik: `modules/mining/ore/GuardArea.java`, `ClassicSteer.java`, `TunnelSteer.java`, `OreMacroModule.java` (Suche nach `warden` / Guard), `modules/general/SafetyModule.java`.
- Wie wird ein Golem mit Namen „…❤ Warden“ als Guard erkannt (Namensformat, Entity-Typ, Reichweite des Scans `world:scan`)? Erkennt das HUD ihn (ja: „Wardens: 1“), das Steering aber nicht?
- Hypothesen zum Prüfen, keine Befunde: Erkennung vs. Steering, Tick-Reihenfolge, Test-Spawnhöhe `floor(WARDEN_X, WARDEN_Z) + 1`, Pfad durch einen Engpass an der Warden-Position, Testdauer.

## Vorgehen (Vorschlag)

1. Test ohne Änderung drei Mal laufen lassen, `m[9]` je Lauf notieren (Streuung).
2. Minimal reproduzieren: Warden-Position und Macro-Start fix, Abstand pro Tick loggen.
3. Erst danach Code ändern. Den Schwellwert 13,5 nicht senken, den Test nicht deaktivieren.

## Randbedingungen

- Pro Test-Lauf genau eine Minecraft-Instanz (viel Speicher: der Rechner war während Phase 2 knapp).
- `Release` und `stash@{0}` nicht anfassen. `src/paused/cosmic-items` bleibt pausiert (`CosmicItemPauseTest`, `checkPausedCosmicItems`).
- Phase-2-Änderungen mit möglichem Bezug (nur zum Ausschließen, nicht als Verdacht): `Module.always`/`observe` (Event-Besitzer), `enabledByDefault` bei fünf Overlay-Modulen, `FeatureProfile.forced/kind`, Wegfall `DashboardScreen`. Ein Lauf mit `git stash`-freiem Worktree auf `25f764b` reicht für den Vergleich.
