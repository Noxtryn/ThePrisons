# Capture mode and replay fixtures

Capture mode records one Cosmic Prisons moment (what the client displays) into a local JSON file, so a situation can be turned into a
test without describing it by hand.

## Turn it on (hidden by default)

Any one of: a developer build (`-Dtheprisons.dev=true`), `-Dtheprisons.capture=true`, or an empty file
`config/theprisons/capture.enabled`. Without one of them the command does not exist.

## Take a capture

In game: `/prisons capture` or `/prisons capture <short note>`. The file lands in `config/theprisons/captures/capture-<time>.json`.
Hold the interesting item / open the interesting menu first; a capture reads the held item, armour, the whole inventory, the open
container's slots (with lore), entities within 48 blocks, a block cube (radius 6), sidebar, boss bars, action bar, the zone / event the
model remembers and the last system lines.

## What is NOT in a capture

Access / refresh tokens, Microsoft / Minecraft session data, launcher files, IP addresses, e-mails, Discord tokens, player chat, private
messages. The capture is built only from what the normal client already shows; it never reads account or launcher files and never touches
the network. As a second line, every text goes through `PrivacyFilter` (tokens, addresses, e-mails, long secrets are replaced by
`<redacted>`; private-message lines are dropped). Your own name becomes `Self`, other real players `Player_1`, `Player_2` ... (the same
name always gets the same number). Bandit names (`bandit_ae_821e4c`) stay: they are the mechanic. Names that only appear inside free text
you typed in the note are not detected: do not put names into notes. Look through a file before you share it.

## Make it a test

Put the file into `src/test/resources/cosmic/<topic>/` (`ores`, `pickaxes`, `bandits`, `spears`, `zones`, `items`, `masks`, `ah`,
`energy-extractor`, `events`). `CosmicFixtureReplayTest` feeds every file through the current parsers and compares with the result recorded
in the file (`expected`). A changed parser that changes an answer fails that test with the exact keys that differ. Files with
`"synthetic": true` are hand-made starters in formats known from game text, not recordings; replace them with real ones.

A file of an older schema is refused with "stale capture ... record it again"; a newer one with "update ThePrisons".
Regenerate the synthetic starters: `./gradlew test --tests '*FixtureGenerator' -Dcosmic.fixtures.write=true`.
