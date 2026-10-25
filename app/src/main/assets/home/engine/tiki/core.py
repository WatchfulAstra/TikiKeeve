"""World API exposed to world.py scripts as the `w` argument."""
from java import jarray, jdouble, jint

from .physics import Physics

world = None


def init(api, world_dir):
    global world
    world = World(api, world_dir)


def _lin(c, default=(1.0, 1.0, 1.0, 1.0)):
    """Color -> linear RGBA. Accepts '#rgb', '#rrggbb', '#rrggbbaa' or (r,g,b[,a]) in 0..1 (sRGB)."""
    if c is None:
        return default
    if isinstance(c, str):
        s = c.lstrip("#")
        if len(s) in (3, 4):
            s = "".join(ch * 2 for ch in s)
        v = [int(s[i:i + 2], 16) / 255.0 for i in range(0, len(s), 2)]
    else:
        v = [float(x) for x in c]
    if len(v) == 3:
        v.append(1.0)
    r, g, b, a = v[:4]
    return (r ** 2.2, g ** 2.2, b ** 2.2, a)


def _v3(v, default):
    if v is None:
        return list(default)
    if isinstance(v, (int, float)):
        return [float(v)] * 3
    return [float(x) for x in v]


def _flat(seq):
    out = []
    for v in seq:
        if isinstance(v, (int, float)):
            out.append(float(v))
        else:
            out.extend(float(x) for x in v)
    return out


class Input:
    def __init__(self):
        self.move_x = 0.0   # joystick, -1..1 (right +)
        self.move_y = 0.0   # joystick, -1..1 (forward +)
        self.look_dx = 0.0  # right-side drag since last frame, in dp
        self.look_dy = 0.0
        self.jump = False   # jump button held

    def _read(self, api):
        a = api.input()
        self.move_x, self.move_y, self.look_dx, self.look_dy = a[0], a[1], a[2], a[3]
        self.jump = a[4] > 0.5


class Obj:
    """A scene object. Change .pos / .rot (degrees) / .scale; changes are sent once per frame."""

    def __init__(self, id, pos, rot, scale):
        self.id = id
        self.pos = pos
        self.rot = rot
        self.scale = scale
        self._sent = None


class World:
    def __init__(self, api, world_dir):
        self._api = api
        self.dir = world_dir
        self.objects = {}
        self.input = Input()
        self.physics = Physics()
        self.time = 0.0

    # ---- scene ----
    def sky(self, color):
        r, g, b, _ = _lin(color)
        self._api.sky(r, g, b)

    def sun(self, direction=(-0.4, -1.0, -0.3), intensity=110000.0, color="#fff4dd"):
        d = _v3(direction, (0, -1, 0))
        r, g, b, _ = _lin(color)
        self._api.sun(d[0], d[1], d[2], float(intensity), r, g, b)

    def ambient(self, intensity=25000.0):
        self._api.ambient(float(intensity))

    def camera(self, pos, look=(0, 0, 0), fov=60.0):
        p = _v3(pos, (0, 0, 0))
        l = _v3(look, (0, 0, 0))
        self._api.camera(p[0], p[1], p[2], l[0], l[1], l[2], float(fov))

    def hud(self, text):
        self._api.hud(str(text))

    def log(self, *args):
        self._api.log(" ".join(str(a) for a in args))

    # ---- objects ----
    def box(self, id, pos=(0, 0, 0), size=(1, 1, 1), color="#ffffff", rot=(0, 0, 0), **kw):
        return self._prim("box", id, pos, size, rot, color, **kw)

    def sphere(self, id, pos=(0, 0, 0), size=1.0, color="#ffffff", rot=(0, 0, 0), **kw):
        return self._prim("sphere", id, pos, size, rot, color, **kw)

    def plane(self, id, pos=(0, 0, 0), size=(1, 1, 1), color="#ffffff", rot=(0, 0, 0), **kw):
        return self._prim("plane", id, pos, size, rot, color, **kw)

    def _prim(self, shape, id, pos, size, rot, color, material=None, uv=1.0, metallic=0.0, roughness=0.8):
        r, g, b, a = _lin(color)
        self._api.addPrimitive(id, shape, r, g, b, a, float(metallic), float(roughness), material, float(uv))
        return self._register(id, pos, rot, size)

    def mesh(self, id, vertices, indices, colors=None, uvs=None, normals=None, color="#ffffff",
             material=None, metallic=0.0, roughness=0.8, pos=(0, 0, 0), rot=(0, 0, 0), scale=(1, 1, 1)):
        """Custom triangle mesh. vertices: [(x,y,z),...]; indices: flat list or [(a,b,c),...];
        colors: optional per-vertex colors; uvs: optional [(u,v),...]; normals: optional (else smooth)."""
        v = _flat(vertices)
        idx = [int(i) for i in _flat(indices)]
        n = len(v) // 3
        if len(v) % 3 or n == 0:
            raise ValueError("vertices must be (x,y,z) triples")
        if len(idx) % 3 or not idx or max(idx) >= n or min(idx) < 0:
            raise ValueError("indices must be triangles referencing existing vertices")
        col = None
        if colors is not None:
            col = []
            for c in colors:
                col.extend(_lin(c))
            if len(col) != n * 4:
                raise ValueError("need one color per vertex")
        uv = _flat(uvs) if uvs is not None else None
        nr = _flat(normals) if normals is not None else None
        r, g, b, a = _lin(color)
        self._api.addMesh(
            id, jarray(jdouble)(v), jarray(jint)(idx),
            jarray(jdouble)(nr) if nr is not None else None,
            jarray(jdouble)(uv) if uv is not None else None,
            jarray(jdouble)(col) if col is not None else None,
            r, g, b, a, float(metallic), float(roughness), material)
        return self._register(id, pos, rot, scale)

    def model(self, id, path, pos=(0, 0, 0), rot=(0, 0, 0), scale=1.0, material=None):
        """Load a .glb or .fbx (path relative to the world folder). Raises if the file is missing."""
        self._api.addModel(id, path, material)
        return self._register(id, pos, rot, scale)

    def remove(self, id):
        self._api.remove(id)
        self.objects.pop(id, None)

    def get(self, id):
        return self.objects.get(id)

    def _register(self, id, pos, rot, scale):
        o = Obj(id, _v3(pos, (0, 0, 0)), _v3(rot, (0, 0, 0)), _v3(scale, (1, 1, 1)))
        self.objects[id] = o
        return o

    def _flush(self):
        api = self._api
        for o in self.objects.values():
            key = (tuple(o.pos), tuple(o.rot), tuple(o.scale))
            if key != o._sent:
                api.setTransform(o.id, o.pos[0], o.pos[1], o.pos[2], o.rot[0], o.rot[1], o.rot[2],
                                 o.scale[0], o.scale[1], o.scale[2])
                o._sent = key
