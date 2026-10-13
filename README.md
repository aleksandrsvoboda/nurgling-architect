# Nurgling Architect

An offline 3D base planner for Haven & Hearth, built on the nurgling client engine. It never connects to the game server or the internet, and it runs without the game installed. Object models and ground textures ship with it in `pack/`.

You plan on a **generated world** (from a seed, in one of five styles) or a **flat sandbox**:
- place any of about 1,850 game objects, from the build menu or the full object list;
- edit with layers, undo/redo, copy/paste, rows and arrays, and collision checks;
- plan nurgling areas and export them as an area preset;
- reshape and pave the ground; this is visual only.

## Run it

Clone and double-click **`run.bat`** (Windows). That's all:
- `runtime/`: a bundled Java 21, so nothing else needs installing.
- `engine/`: the nurgling client engine.
- `pack/`: the game art (objects, ground, build menu).
- `app/architect.jar`: the planner.

You don't need the game installed. If a Haven & Hearth game cache exists on the PC, the planner also reads it, read-only, for anything missing from the pack.

The pack holds Haven & Hearth's own game assets. Keep the repository private unless you have the right to redistribute them.

## Developing (JDK 21+, Ant)

```
ant jar           # app/architect.jar
ant test          # plain-main tests
ant sync-engine   # refresh engine/ from ../nurgling2/bin (pinned; only when you choose)
ant runtime       # rebuild the bundled Java in runtime/ with jlink
java -cp app/architect.jar architect.DevTools pack     # rebuild pack/ from this PC's game cache
java -cp app/architect.jar architect.DevTools verify   # check every object/tile loads from the pack
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
- `java -cp app/architect.jar architect.DevTools index|gen`: catalogue statistics; generator previews as PNG.

## Where things live

- Planner settings and plans: `%APPDATA%\Nurgling Architect` (`plans\*.hplan` plus a `.hterrain` file per plan). Override with `-Darchitect.home=`.
- Game art: `pack/` in the clone. If a game cache exists at `%APPDATA%\Haven and Hearth\data` (override with `-Darchitect.gamedata=`), it's read too, read-only.
- Java preferences node: `architect` (`-Dhaven.prefspec=architect`), kept apart from the game's.

## How it works

`architect.world.LocalWorldTransport` stands in for the network connection. It answers the engine's map requests from the plan's terrain store, and trims grids the viewer has left, the same as the game server does. The renderer, terrain streaming and object sprites are the engine's own, unmodified.
