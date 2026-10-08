# Cosmic fixtures

One folder per topic: ores, pickaxes, bandits, spears, zones, items, masks, ah, energy-extractor, events.
Every `*.json` is a capture (schema 1) that `CosmicFixtureReplayTest` feeds through the current parsers and compares with the result recorded in the file.

Starter files marked `"synthetic": true` are hand-made from formats known from the game text; replace them with real captures as you collect them. Map: {energy-extractor=energy extractor menus (module off), ores=ore blocks around the player, ah=auction house screens (market module is off), masks=mask items}.
