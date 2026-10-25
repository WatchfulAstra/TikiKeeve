import math
import random

SIZE = 96.0   # terrain width/depth in meters
N = 64        # grid cells per side

STOPS = [  # (height, sRGB color)
    (-4.0, (0.76, 0.70, 0.49)), (-1.0, (0.76, 0.70, 0.49)), (0.5, (0.31, 0.62, 0.23)),
    (3.0, (0.23, 0.48, 0.18)), (5.0, (0.62, 0.62, 0.62)),
]

S = {}


def height(x, z):
    return (3.2 * math.sin(x * 0.07) * math.cos(z * 0.06)
            + 1.6 * math.sin(x * 0.19 + 1.3) * math.sin(z * 0.17)
            + 0.6 * math.sin(x * 0.5) * math.cos(z * 0.43))


def shade(h):
    if h <= STOPS[0][0]:
        return STOPS[0][1]
    for (h0, c0), (h1, c1) in zip(STOPS, STOPS[1:]):
        if h <= h1:
            t = (h - h0) / (h1 - h0)
            return tuple(a + (b - a) * t for a, b in zip(c0, c1))
    return STOPS[-1][1]


def setup(w):
    w.hud("left: move   right: look   O: jump")
    w.sky("#8fc1ff")
    w.sun(direction=(-0.4, -1.0, -0.3))
    w.ambient(25000)

    # --- procedural terrain mesh ---
    verts, cols = [], []
    for i in range(N + 1):
        for j in range(N + 1):
            x = (i / N - 0.5) * SIZE
            z = (j / N - 0.5) * SIZE
            h = height(x, z)
            verts.append((x, h, z))
            cols.append(shade(h))
    idx = []
    for i in range(N):
        for j in range(N):
            a = i * (N + 1) + j
            b = a + 1
            c = a + N + 1
            d = c + 1
            idx += [a, b, c, b, d, c]
    w.mesh("terrain", verts, idx, colors=cols, roughness=0.95)
    w.physics.heightfield(height)

    # --- scattered pillars (static colliders) ---
    rng = random.Random(7)
    for k in range(14):
        x, z = rng.uniform(-40, 40), rng.uniform(-40, 40)
        ph = rng.uniform(2.0, 5.0)
        o = w.box("pillar%d" % k, pos=(x, height(x, z) + ph / 2 - 0.3, z), size=(1.5, ph, 1.5),
                  color=rng.choice(["#c0563a", "#d9a441", "#5a7bd0", "#9a6bd0"]), roughness=0.6)
        w.physics.add(o, static=True)

    # --- bouncing balls ---
    for k in range(6):
        x, z = rng.uniform(-8, 8), rng.uniform(-8, 8)
        o = w.sphere("ball%d" % k, pos=(x, height(x, z) + 6 + k, z), size=1.0, color="#f2f2f2", metallic=0.0, roughness=0.3)
        w.physics.add(o, size=(1, 1, 1), bounce=0.65)

    # --- player ---
    p = w.box("player", pos=(0, height(0, 0) + 3, 0), size=(0.8, 1.6, 0.8), color="#ff7a00")
    S["body"] = w.physics.add(p)
    S["yaw"] = 0.0
    S["pitch"] = 22.0


def update(w, dt):
    i = w.input
    S["yaw"] += i.look_dx * 0.25
    S["pitch"] = max(-5.0, min(70.0, S["pitch"] + i.look_dy * 0.25))

    y = math.radians(S["yaw"])
    fx, fz = math.sin(y), -math.cos(y)       # forward on the ground plane
    rx, rz = math.cos(y), math.sin(y)        # right
    b = S["body"]
    speed = 7.0
    b.vel[0] = (fx * i.move_y + rx * i.move_x) * speed
    b.vel[2] = (fz * i.move_y + rz * i.move_x) * speed
    if i.jump and b.on_ground:
        b.vel[1] = 9.0

    # third-person camera
    p = b.pos
    pr = math.radians(S["pitch"])
    d = 7.0
    cx = p[0] - fx * math.cos(pr) * d
    cz = p[2] - fz * math.cos(pr) * d
    cy = max(p[1] + 1.0 + math.sin(pr) * d, height(cx, cz) + 0.8)
    w.camera((cx, cy, cz), look=(p[0], p[1] + 0.8, p[2]), fov=60)
