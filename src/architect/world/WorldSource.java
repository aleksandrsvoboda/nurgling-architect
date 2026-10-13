package architect.world;

import haven.Coord;
import haven.Coord2d;
import haven.Area;

/** Where terrain comes from. Grid coordinates are the engine's MCache grid coordinates. */
public interface WorldSource {
    /** Terrain for grid gc, or null when the world has nothing there (unexplored). May block; called off the UI thread. */
    GridData grid(Coord gc);

    /** World position (world units) the camera starts at. */
    Coord2d spawn();

    /** Grid-coordinate bounds of the plannable area, inclusive-exclusive; the camera is kept inside. */
    Area bounds();

    String describe();
}
