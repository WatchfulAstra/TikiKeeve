package com.tikikeeve.app

import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import kotlin.math.sqrt

/** Thin wrapper around Google Filament (native PBR renderer). Everything is loaded as GLB via gltfio. */
class FilamentScene(private val surfaceView: SurfaceView) {
    companion object { init { Utils.init() } }

    val engine: Engine = Engine.create()
    private val renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val view = engine.createView()
    private val camera = engine.createCamera(EntityManager.get().create())
    private var swapChain: SwapChain? = null
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(surfaceView.context)

    private val materials = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materials, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)

    private val sunEntity = EntityManager.get().create()
    private var ibl: IndirectLight? = null
    private var skybox: Skybox? = null
    private var aspect = 1.0
    private var fov = 60.0
    private var destroyed = false

    init {
        view.camera = camera
        view.scene = scene
        // Keep frame time steady on modest phones.
        view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply { enabled = true }

        LightManager.Builder(LightManager.Type.SUN)
            .color(1f, 0.96f, 0.88f).intensity(110000f)
            .direction(-0.4f, -1f, -0.3f).castShadows(false)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)
        setAmbient(25000f)
        setSky(0.1f, 0.12f, 0.2f)
        camera.lookAt(0.0, 5.0, 10.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
                displayHelper.attach(renderer, surfaceView.display)
            }
            override fun onDetachedFromSurface() {
                displayHelper.detach()
                swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait(); swapChain = null }
            }
            override fun onResized(width: Int, height: Int) {
                view.viewport = Viewport(0, 0, width, height)
                aspect = width.toDouble() / height.toDouble()
                updateProjection()
            }
        }
        uiHelper.attachTo(surfaceView)
    }

    private fun updateProjection() =
        camera.setProjection(fov, aspect, 0.1, 1000.0, Camera.Fov.VERTICAL)

    fun render(frameTimeNanos: Long) {
        if (destroyed || !uiHelper.isReadyToRender) return
        val sc = swapChain ?: return
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    fun setCamera(ex: Double, ey: Double, ez: Double, cx: Double, cy: Double, cz: Double, fovDeg: Double) {
        camera.lookAt(ex, ey, ez, cx, cy, cz, 0.0, 1.0, 0.0)
        if (fovDeg != fov) { fov = fovDeg; updateProjection() }
    }

    fun setSun(dx: Float, dy: Float, dz: Float, intensity: Float, r: Float, g: Float, b: Float) {
        val len = sqrt(dx * dx + dy * dy + dz * dz).takeIf { it > 1e-6f } ?: 1f
        val lm = engine.lightManager
        val i = lm.getInstance(sunEntity)
        lm.setDirection(i, dx / len, dy / len, dz / len)
        lm.setColor(i, r, g, b)
        lm.setIntensity(i, intensity)
    }

    /** Uniform ambient light (no reflections: there is no environment map). */
    fun setAmbient(intensity: Float) {
        val old = ibl
        val n = IndirectLight.Builder().irradiance(1, floatArrayOf(1f, 1f, 1f)).intensity(intensity).build(engine)
        scene.indirectLight = n
        ibl = n
        old?.let { engine.destroyIndirectLight(it) }
    }

    fun setSky(r: Float, g: Float, b: Float) {
        val old = skybox
        val n = Skybox.Builder().color(r, g, b, 1f).build(engine)
        scene.skybox = n
        skybox = n
        old?.let { engine.destroySkybox(it) }
    }

    fun addGlb(bytes: ByteArray): FilamentAsset {
        val asset = assetLoader.createAsset(ByteBuffer.wrap(bytes))
            ?: throw IllegalArgumentException("Filament could not parse this glTF/GLB data")
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        scene.addEntities(asset.entities)
        return asset
    }

    fun remove(asset: FilamentAsset) {
        scene.removeEntities(asset.entities)
        assetLoader.destroyAsset(asset)
    }

    fun setTransform(asset: FilamentAsset, m: FloatArray) {
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), m)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        uiHelper.detach()
        assetLoader.destroy()
        resourceLoader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        skybox?.let { engine.destroySkybox(it) }
        ibl?.let { engine.destroyIndirectLight(it) }
        engine.destroyEntity(sunEntity)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        engine.destroy()
    }
}
