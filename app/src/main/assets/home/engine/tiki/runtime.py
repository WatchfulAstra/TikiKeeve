"""Loads a world's world.py and drives it. Called from the Android app."""
import importlib.util
import os
import sys
import traceback

from . import core

last_error = ""
_mod = None
_world_dir = None


def start(world_dir, api):
    global _mod, _world_dir, last_error
    stop()
    last_error = ""
    try:
        _world_dir = world_dir
        core.init(api, world_dir)
        if world_dir not in sys.path:
            sys.path.insert(0, world_dir)
        spec = importlib.util.spec_from_file_location("tiki_world", os.path.join(world_dir, "world.py"))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        _mod = mod
        if not hasattr(mod, "setup"):
            raise RuntimeError("world.py must define setup(w)")
        mod.setup(core.world)
        core.world._flush()
        return True
    except Exception:
        last_error = traceback.format_exc()
        return False


def update(dt):
    global last_error
    w = core.world
    try:
        w.time += dt
        w.input._read(w._api)
        fn = getattr(_mod, "update", None)
        if fn:
            fn(w, dt)
        w.physics.step(dt)
        w._flush()
        return True
    except Exception:
        last_error = traceback.format_exc()
        return False


def stop():
    """Forget the world so the next one starts clean (including its helper modules)."""
    global _mod, _world_dir
    if _world_dir:
        for name, m in list(sys.modules.items()):
            f = getattr(m, "__file__", None)
            if f and f.startswith(_world_dir):
                del sys.modules[name]
        if _world_dir in sys.path:
            sys.path.remove(_world_dir)
    _mod = None
    _world_dir = None
