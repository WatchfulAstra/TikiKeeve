import math

# A world needs setup(w). update(w, dt) is optional and runs every frame.
# w is the World API: see README.md ("Python API").

def setup(w):
    w.sky("#101820")
    w.sun()
    w.ambient(25000)
    w.box("ground", pos=(0, -0.5, 0), size=(30, 1, 30), color="#3a6b45", roughness=0.9)
    w.sphere("ball", pos=(0, 1.5, 0), size=2.0, color="#ff7a00", roughness=0.35)
    w.hud("New world: edit world.py")


def update(w, dt):
    a = w.time * 0.4
    w.camera((math.sin(a) * 9, 4, math.cos(a) * 9), look=(0, 1, 0))
