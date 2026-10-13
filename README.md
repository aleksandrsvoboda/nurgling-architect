# Nurgling Architect

An offline 3D base planner for Haven & Hearth, built on the nurgling client engine. It runs without the game installed and without logging in. Object models and ground textures are downloaded from the game's public resource server the first time they're needed, then kept in the planner's own cache.

You plan on a **generated world** (from a seed, in one of five styles) or a **flat sandbox**:
- place any of about 1,850 game objects, from the build menu or the full object list;
- edit with layers, undo/redo, copy/paste, rows and arrays, and collision checks;
- plan nurgling areas and export them as an area preset;
- reshape and pave the ground; this is visual only.

## Run it

Clone and double-click **`run.bat`** (Windows). That's all:
- `runtime/`: a bundled Java 21, so nothing else needs installing.
- `engine/`: the nurgling client engine.
- `app/architect.jar`: the planner, with a catalogue of every placeable object (names only, no art).

You don't need the game installed. Game art comes from the game's resource server (`https://game.havenandhearth.com/res/`, the same place the game client gets it) the first time each object or ground type is used. It's stored in `%APPDATA%\Nurgling Architect\data`, and from then on it works offline. To have everything available offline at once, click **Download all game art** on the start screen (around 150 MB).

No game art is stored in this repository.

## Developing (JDK 21+, Ant)

```
ant jar           # app/architect.jar
ant test          # plain-main tests
ant sync-engine   # refresh engine/ from ../nurgling2/bin (pinned; only when you choose)
ant runtime       # rebuild the bundled Java in runtime/ with jlink
java -cp app/architect.jar architect.DevTools catalogue [gamedata]  # rebuild the shipped catalogue from a game cache,
                                                                    # probing the server for unseen build-menu objects
java -cp app/architect.jar architect.DevTools prefetch   # download everything into the planner's cache
```

The engine needs nurgling's `feat/mapraster-owner-center` changes:
- terrain drawing without the in-game interface;
- `MapView.setview`;
- shadow lookups outside the shadow box count as lit;
- an optional decoration radius (`Terrain.flavview`), so grass and flowers stay near the camera.

Dev helpers:
- `tools/launch.ps1`: start detached, with logs in `build/out.log` and `build/err.log`.
- `tools/trace.ps1`: start with extra JVM flags and program arguments.
- `tools/stop.ps1`: stop the running planner.
- `tools/histo.ps1`: heap histogram.
- `tools/shot.ps1` and `tools/front.ps1`: take a screenshot; bring the window to the front.
- `java -cp app/architect.jar architect.DevTools gen` and `probe <res>`: generator previews as PNG; inspect one server resource.

## Where things live

- Planner settings and plans: `%APPDATA%\Nurgling Architect` (`plans\*.hplan` plus a `.hterrain` file per plan). Override with `-Darchitect.home=`.
- Downloaded game art: `%APPDATA%\Nurgling Architect\data`. The game's own cache is never read. Override the server with `-Darchitect.resurl=`.
- Java preferences node: `architect` (`-Dhaven.prefspec=architect`), kept apart from the game's.

## How it works

`architect.world.LocalWorldTransport` stands in for the network connection. It answers the engine's map requests from the plan's terrain store, and trims grids the viewer has left, the same as the game server does. The renderer, terrain streaming and object sprites are the engine's own, unmodified.
