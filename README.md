# Nurgling Architect

An offline 3D base planner for Haven & Hearth, built on the nurgling client engine. It never connects to the game server or the internet. Object models and ground textures come from your local game cache, which the planner opens read-only.

You plan on a **generated world** (from a seed, in one of five styles) or a **flat sandbox**:
- place any object you have seen in game, from the build menu or the full object list;
- edit with layers, undo/redo, copy/paste, rows and arrays, and collision checks;
- plan nurgling areas and export them as an area preset;
- reshape and pave the ground; this is visual only.

## Build and run (Windows JDK 21+, Ant)

```
ant sync-engine   # copy the built client jars from ../nurgling2/bin into engine/ (pinned)
ant jar           # build/architect.jar
ant test          # plain-main tests
run.bat           # or: ant run
```

The engine needs nurgling's `feat/mapraster-owner-center` changes:
- terrain drawing without the in-game interface;
- `MapView.setview`;
- shadow lookups outside the shadow box count as lit.

Dev helpers:
- `tools/launch.ps1`: start detached, with logs in `build/out.log` and `build/err.log`.
- `tools/trace.ps1`: start with extra JVM flags and program arguments.
- `tools/stop.ps1`: stop the running planner.
- `tools/histo.ps1`: heap histogram.
- `tools/shot.ps1` and `tools/front.ps1`: take a screenshot; bring the window to the front.
- `java -cp build/architect.jar architect.DevTools index|gen`: catalogue statistics; generator previews as PNG.

## Where things live

- Planner settings and plans: `%APPDATA%\Nurgling Architect` (`plans\*.hplan` plus a `.hterrain` file per plan). Override with `-Darchitect.home=`.
- Game cache, read-only: `%APPDATA%\Haven and Hearth\data`. Override with `-Darchitect.gamedata=`.
- Java preferences node: `architect` (`-Dhaven.prefspec=architect`), kept apart from the game's.

## How it works

`architect.world.LocalWorldTransport` stands in for the network connection. It answers the engine's map requests from the plan's terrain store, and trims grids the viewer has left, the same as the game server does. The renderer, terrain streaming and object sprites are the engine's own, unmodified.
