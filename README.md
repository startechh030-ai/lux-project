# Luxe Texture3D

Package/application id:

```txt
luxe.texture3d.app
```

## Current step: static camera, centered model orbit

We simplified the viewport behavior:

```txt
Import GLB
→ model is centered with ModelViewer.transformToUnitCube()
→ camera stays static
→ tiny dot stays at screen center as pivot marker
→ 1 finger drag rotates the model around that center pivot
→ pinch zoom scales the model from the same pivot
→ no panning / no random movement
```

Main new files:

```txt
ModelOrbitController.kt
PivotDotView.kt
```

Current active gesture logic is Kotlin-side only for stability. The uploaded native camera files remain in the project, but this step does not depend on native camera movement.

Expected feel:

- model should stay centered
- model should rotate in-place
- tiny dot marks the orbit/pivot center
- gizmo updates with model orbit
