package luxe.texture3d.app

import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentAsset
import kotlin.math.max

/** Uniformly scales, horizontally centers, and rests an imported asset on y=0. */
object ModelPlacement {
    data class Result(val centerY: Float, val scale: Float)

    fun placeOnGround(engine: Engine, asset: FilamentAsset, centerZ: Float = -4f): Result {
        val bounds=asset.boundingBox
        val center=bounds.center
        val half=bounds.halfExtent
        val maxExtent=2f*max(half[0],max(half[1],half[2])).coerceAtLeast(1e-5f)
        val scale=2f/maxExtent
        val tx=-center[0]*scale
        val ty=-(center[1]-half[1])*scale
        val tz=centerZ-center[2]*scale
        val matrix=floatArrayOf(
            scale,0f,0f,0f,
            0f,scale,0f,0f,
            0f,0f,scale,0f,
            tx,ty,tz,1f
        )
        val tm=engine.transformManager
        tm.setTransform(tm.getInstance(asset.root),matrix)
        return Result(half[1]*scale,scale)
    }
}
