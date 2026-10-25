# Tiki Keeve

Native Android app (Android 11+) that runs procedural 3D worlds scripted in Python.
Renderer: Google Filament (native PBR, not a webview). Python: Chaquopy (CPython 3.11).

## Get the APK
1. Push this folder to a GitHub repo (branch `main`).
2. Actions tab -> **Build APK** -> run (or it runs on push).
3. Download the `TikiKeeve-apk` artifact, unzip, install `app-release.apk` (signed with the debug key; allow "install unknown apps").

Local build: Java 17, Android SDK 34, Python 3.11 on PATH, then `gradle assembleRelease` (Gradle 8.5).

## Where things live (all editable, no rebuild)
First launch copies defaults to a folder, never overwriting your edits:

- With "all files access" granted (button on the project screen): `/sdcard/TikiKeeve/`
- Otherwise: `Android/data/com.tikikeeve.app/files/` (hard to reach on Android 11+; grant all-files access)

```
TikiKeeve/
  config.json            memory_budget_mb (700), max_texture_px (1024)
  splash/splash.json     background, duration_ms, image, scale
  splash/splash.png      YOUR splash image (not included; text fallback shown until you add it)
  engine/tiki/*.py       the Python engine itself; edit freely, delete engine/ to restore the bundled one
  projects/<name>/
     project.json        {"name": "...", "description": "..."}
     world.py            setup(w) required, update(w, dt) optional
     assets/             your .glb / .fbx / PBR .zip files
```
To bake a splash into the APK instead, add `app/src/main/assets/home/splash/splash.png` before building.

## world.py
```python
def setup(w): ...          # build the world once
def update(w, dt): ...     # every frame (dt in seconds, capped at 0.05)
```
Python API (`w`):

| | |
|---|---|
| `w.sky(color)`, `w.sun(direction, intensity, color)`, `w.ambient(intensity)` | lighting |
| `w.camera(pos, look, fov=60)` | call whenever you want to move it |
| `w.box/sphere/plane(id, pos, size, color, rot, material=None, uv=1, metallic=0, roughness=0.8)` | primitives; return an object |
| `w.mesh(id, vertices, indices, colors=, uvs=, normals=, ...)` | your own procedural mesh |
| `w.model(id, "assets/x.glb" or ".fbx", pos, rot, scale, material=None)` | load a model |
| `w.remove(id)`, `w.get(id)`, `w.hud(text)`, `w.log(...)`, `w.time` | |
| object: `.pos`, `.rot` (degrees), `.scale` | assign/mutate, sent to the renderer each frame |
| `w.input.move_x/move_y/look_dx/look_dy/jump` | joystick (left), look-drag (right), jump button |
| `w.physics.add(obj, size=None, static=False, bounce=0)` -> body with `.vel`, `.on_ground`; `w.physics.heightfield(fn(x,z))`; `.gravity` | simple physics |

Colors: `"#rrggbb"`, `"#rrggbbaa"` or `(r,g,b[,a])` in 0..1, treated as sRGB. Units are meters, +Y is up.
You may split code into more `.py` files in the world folder and `import` them.

## Adding assets (nothing is faked; missing files raise a clear error on screen)
- **GLB**: copy to `projects/<name>/assets/`, then `w.model("tree", "assets/tree.glb")`. Embedded textures/materials are used as authored. External-file `.gltf` is not supported.
- **FBX**: binary FBX only. Static geometry (positions, normals, UVs) is imported; node transforms, materials, embedded textures, skinning and animation are ignored. FBX is often in centimeters: use `scale=0.01`. For anything richer, convert to GLB.
- **PBR texture ZIP**: copy the zip to `assets/`, pass `material="assets/name.zip"`. Matched by file name: `basecolor|albedo|diffuse`, `normal` (OpenGL; names containing `dx` are skipped), `roughness`, `metallic`, `ambientocclusion`, or a packed `_orm`/`_arm`. Height maps are ignored. Use `uv=` to tile. Works on primitives, FBX (with UVs) and `w.mesh(..., uvs=...)`. Images are downscaled to `max_texture_px`.

## Memory budget
Process memory (PSS: Java + native + GPU-mapped) is checked every second and after every heavy load. Over `memory_budget_mb` the world is stopped, its objects freed, and an "Out of memory budget" screen appears. Top-right shows fps and MB used.

## Known limits (prototype)
- Python runs on the UI thread each frame; heavy per-frame Python lowers fps.
- No shadows, no reflections/IBL (uniform ambient only), so metallic surfaces look dark.
- Every object is its own mesh; hundreds of objects will cost draw calls. Prefer one `w.mesh` for big procedural geometry.
- Physics: boxes vs static boxes + heightfield only; spheres use box bounds; dynamic bodies don't collide with each other.
- Not tested on a device by the author of this scaffold; first CI run may need small fixes.
