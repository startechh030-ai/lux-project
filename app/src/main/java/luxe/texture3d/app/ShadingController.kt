package luxe.texture3d.app

import com.google.android.filament.Engine
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.FilamentAsset

/**
 * The Solid / Material shading switch — Blender's 'Z' menu, boiled down to the two modes
 * that matter while modelling.
 *
 * **Solid** is "hide the texture from Filament so it does not render it": every texture slot
 * on the asset's materials is emptied and the base colour is set to a neutral grey, so what
 * is left is pure form and silhouette. **Material** puts the asset back exactly as it was.
 *
 * ## Why it swaps instances instead of editing them
 *
 * Editing a material instance in place would mean knowing every parameter gltfio had set,
 * just to put it back afterwards. Swapping is both simpler and lossless: the original
 * instances are held here untouched and re-attached when Material is chosen.
 */
enum class ShadingMode { SOLID, MATERIAL }

class ShadingController(private val engine: Engine, private val scene: Scene) {

    var mode: ShadingMode = ShadingMode.SOLID
        private set

    /** The asset currently under control, and the instances it arrived with. */
    private var asset: FilamentAsset? = null
    private val original = mutableMapOf<Int, com.google.android.filament.MaterialInstance>()

    /** Instances created for Solid mode, so they can be destroyed rather than leaked. */
    private val solids = mutableListOf<com.google.android.filament.MaterialInstance>()

    /**
     * Points the controller at [next] and applies the current mode to it.
     *
     * Called whenever the selection changes; passing null simply lets go of the old asset.
     */
    fun bind(next: FilamentAsset?) {
        if (next === asset) return
        restore()
        asset = next
        original.clear()
        if (next != null) apply(next)
    }

    /** Switches mode on the bound asset. Returns the mode now showing. */
    fun toggle(): ShadingMode {
        mode = if (mode == ShadingMode.SOLID) ShadingMode.MATERIAL else ShadingMode.SOLID
        asset?.let { apply(it) }
        return mode
    }

    private fun apply(asset: FilamentAsset) {
        val renderables = engine.getRenderableManager()
        val entities = asset.entities
        for (entity in entities) {
            if (!renderables.hasComponent(entity)) continue
            val instance = renderables.getInstance(entity)
            val primitives = renderables.getPrimitiveCount(instance)
            for (p in 0 until primitives) {
                when (mode) {
                    ShadingMode.MATERIAL -> {
                        val back = original[entity * 8 + p]
                        if (back != null) renderables.setMaterialInstanceAt(instance, p, back)
                    }
                    ShadingMode.SOLID -> {
                        val current = renderables.getMaterialInstanceAt(instance, p)
                        original.putIfAbsent(entity * 8 + p, current)
                        renderables.setMaterialInstanceAt(instance, p, solidOf(current))
                    }
                }
            }
        }
    }

    /**
     * A texture-free twin of [source].
     *
     * A brand new instance of the same material starts with gltfio's defaults, which have no
     * textures bound — so "solid" is really just "this material, unwrapped". Setting a
     * neutral base colour on top gives the flat, untextured read that makes edges and
     * topology legible while editing.
     *
     * Each parameter is set on its own because gltfio only compiles the slots a given
     * material actually uses; a slot that is absent is skipped rather than fatal.
     */
    private fun solidOf(source: com.google.android.filament.MaterialInstance): com.google.android.filament.MaterialInstance {
        // NOTE: `material` is the one call here I cannot compile-check without an Android
        // SDK — it relies on MaterialInstance keeping a handle to the Material that made
        // it. If it does not resolve, build the twin from a Material you already hold
        // instead; nothing else in this file depends on it.
        val twin = source.getMaterial().createInstance()
        solids.add(twin)
        twin.setParameter("baseColor", 0.72f, 0.74f, 0.78f, 1f)
        twin.setParameter("metallic", 0f)
        twin.setParameter("roughness", 0.65f)
        twin.setParameter("reflectance", 0.5f)
        return twin
    }

    /** Puts every original instance back. Safe to call at any time. */
    private fun restore() {
        val current = asset ?: return
        val renderables = engine.getRenderableManager()
        for (entity in current.entities) {
            if (!renderables.hasComponent(entity)) continue
            val instance = renderables.getInstance(entity)
            for (p in 0 until renderables.getPrimitiveCount(instance)) {
                original[entity * 8 + p]?.let {
                    renderables.setMaterialInstanceAt(instance, p, it)
                }
            }
        }
        for (s in solids) engine.destroyMaterialInstance(s)
        solids.clear()
        original.clear()
    }

    /** Releases everything. Call from the activity's teardown. */
    fun destroy() {
        restore()
        asset = null
    }
}
