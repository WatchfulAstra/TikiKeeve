Drop your own files here, then reference them from world.py:

  w.model("tree", "assets/tree.glb", pos=(0, 0, 0))              # GLB
  w.model("rock", "assets/rock.fbx", scale=0.01)                 # FBX (binary, static geometry only)
  w.box("floor", size=(20, 0.2, 20), material="assets/floor.zip", uv=6)   # PBR texture ZIP

This sample ships with no models or textures on purpose; a missing file raises a clear error.
