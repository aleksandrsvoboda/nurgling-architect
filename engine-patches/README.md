# Engine patches

`engine/hafen.jar` is nurgling's client jar, built with `nurgling-engine.patch` applied. The patch is not part
of nurgling; it lives here so the planner's engine can be rebuilt.

What it changes:

- `haven/MapView.java`: terrain, overlays and grid lines render without a GameUI (the planner has none);
  `setview(int)` changes the view distance live; `Terrain.flavview` draws grass and flowers only near the
  camera (holds 144 FPS).
- `haven/ShadowMap.java`, `nurgling/render/DirectionalShadows.java`: ground beyond the shadow box counts as
  lit instead of dark (a long view distance showed a dark half-map).

Before `ant sync-engine`, apply it to the nurgling checkout and build nurgling:

```
cd C:\Users\imbecil\nurgling2
git apply C:\Users\imbecil\nurgling-architect\engine-patches\nurgling-engine.patch
ant
```

Then revert it in nurgling (`git apply -R ...`) if nurgling itself should stay unpatched.
