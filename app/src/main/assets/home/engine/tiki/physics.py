"""Tiny physics: gravity + axis-aligned boxes vs static boxes + optional heightfield ground.

Spheres are treated as boxes of their size. Dynamic bodies do not collide with each other.
"""
import math


class Body:
    def __init__(self, obj, half, static, bounce):
        self.obj = obj
        self.half = half
        self.static = static
        self.bounce = bounce
        self.vel = [0.0, 0.0, 0.0]
        self.on_ground = False

    @property
    def pos(self):
        return self.obj.pos


class Physics:
    def __init__(self):
        self.gravity = -20.0
        self.ground_drag = 3.0
        self.bodies = []
        self._height = None

    def heightfield(self, fn):
        """fn(x, z) -> ground height. Dynamic bodies rest on it."""
        self._height = fn

    def add(self, obj, size=None, static=False, bounce=0.0):
        s = obj.scale if size is None else size
        if isinstance(s, (int, float)):
            s = (s, s, s)
        body = Body(obj, [abs(float(v)) / 2.0 for v in s], static, float(bounce))
        self.bodies.append(body)
        return body

    def remove(self, body):
        if body in self.bodies:
            self.bodies.remove(body)

    def step(self, dt):
        statics = [b for b in self.bodies if b.static]
        n = max(1, int(math.ceil(dt * 60.0)))
        h = dt / n
        for _ in range(n):
            for b in self.bodies:
                if not b.static:
                    self._step_body(b, h, statics)

    def _step_body(self, b, h, statics):
        b.vel[1] += self.gravity * h
        b.on_ground = False
        p = b.obj.pos
        for a in (0, 2, 1):
            p[a] += b.vel[a] * h
            for s in statics:
                sp = s.obj.pos
                if (abs(p[0] - sp[0]) < b.half[0] + s.half[0]
                        and abs(p[1] - sp[1]) < b.half[1] + s.half[1]
                        and abs(p[2] - sp[2]) < b.half[2] + s.half[2]):
                    v = b.vel[a]
                    if v > 0 or (v == 0 and p[a] < sp[a]):
                        p[a] = sp[a] - s.half[a] - b.half[a]
                    else:
                        p[a] = sp[a] + s.half[a] + b.half[a]
                        if a == 1:
                            b.on_ground = True
                    b.vel[a] = -v * b.bounce if abs(v) > 1.0 else 0.0
        if self._height is not None:
            g = self._height(p[0], p[2]) + b.half[1]
            if p[1] <= g:
                p[1] = g
                v = b.vel[1]
                if v < 0:
                    b.vel[1] = -v * b.bounce if abs(v) > 1.0 else 0.0
                b.on_ground = True
        if b.on_ground and self.ground_drag > 0:
            k = max(0.0, 1.0 - self.ground_drag * h)
            b.vel[0] *= k
            b.vel[2] *= k
