# Phase 6 — Edit Mode Mesh Engine

**Target version:** 0.38.0 · **Status:** kernel complete and verified · **UI wiring:** next step

---

## The gap this closes

Luxe had no route to Edit mode, and it was not a UI problem. Three concrete blockers, all
found in the existing code:

1. **Geometry only exists on the GPU.** `EditorSceneManager.begin()` calls
   `asset.releaseSourceData()` right after `AssetLoader.createAsset(...)`. After a load
   there is no CPU-side vertex data anywhere in the app — nothing an editor could change.
2. **`View.pick()` cannot resolve elements.** `EditorActivity.pickScene()` resolves a tap
   to a *renderable entity* (one glTF primitive). That is correct for selecting scene
   objects and cannot distinguish a face from its neighbour, let alone an edge or vertex.
   It is also asynchronous, so it cannot drive a hover highlight.
3. **Nothing in the app reads vertex bytes.** The import pipeline writes validated glTF and
   its metadata extractors read the JSON, but no code path ever read a POSITION accessor.
   Confirmed by grep: the only `ByteBuffer` uses are for Filament material payloads.

So Edit mode needed three new things: a host-side mesh, a way to get geometry into it, and
a way to get edited geometry back onto the screen.

## What was added

Nine files. Six are pure Kotlin with **zero Android imports**; three are the Android bridge.

| File | Role | Platform |
|---|---|---|
| `EditMesh.kt` | Host-side editable triangle mesh (flat arrays), `MeshMath` helpers, `MeshBuilder` | pure |
| `MeshTopology.kt` | Half-edge adjacency as CSR int arrays; non-manifold tolerant | pure |
| `MeshSelection.kt` | Vertex / edge / face selection with Blender-style mode conversion | pure |
| `MeshOperators.kt` | Subdivide (linear + Loop), extrude, inset, delete, weld, compact | pure |
| `MeshRaycast.kt` | Möller–Trumbore hit test, element picking, screen→world ray | pure |
| `MeshHistory.kt` | Undo / redo over `(mesh, selection)` snapshots | pure |
| `GltfMeshLoader.kt` | glTF/GLB → `EditMesh`: real accessor reading, node transforms | Android |
| `EditableMeshRenderer.kt` | `EditMesh` → Filament buffers + line overlay | Android |
| `EditModeController.kt` | Owns Edit mode for one scene instance | Android |

Design constraint worth stating: **operators never mutate their input.** Each returns a new
`EditMesh`. That makes undo a reference swap rather than a set of inverse operations, and
it means a cancelled or failed operator leaves the scene untouched.

## Verified

```
kotlinc app/src/main/java/luxe/texture3d/app/{EditMesh,MeshTopology,MeshSelection,
        MeshOperators,MeshRaycast,MeshHistory}.kt \
        app/src/test/java/luxe/texture3d/verification/MeshKernelTest.kt \
        -include-runtime -d /tmp/meshkernel.jar
java -jar /tmp/meshkernel.jar
```

**157 checks, all passing** on Kotlin 2.1.21 (the version the project builds with).

The suite is deliberately aimed at invariants that are easy to break and invisible until
they are expensive:

- **Euler characteristic** — `V - E + F` must be 2 for a closed cube, 1 for an open grid.
  Every operator is checked to preserve it, which catches silent topology corruption.
- **Winding consistency** — every directed edge must appear at most once. A wall flipped
  inwards looks fine until it is lit.
- **Analytic Loop positions** — corners land on `0.625 × (-0.5,-0.5,-0.5)` and edge
  vertices on `sqrt(0.015625 + 2×0.140625)`, verifying Warren's weights rather than just
  "it looks smoother".
- **Area preservation** — linear subdivision splits triangles, so total area must be
  identical to 1e-5.
- **Round-trip picking** — screen ray → local ray → face hit.
- **History isolation** — editing the live mesh must not mutate a stored snapshot.

Three failures surfaced during development; all three were wrong *expectations*, not kernel
bugs, and each is now documented in the test:

- A triangulated cube corner has **valence 6**, not 3 — three cube edges plus three face
  diagonals. This is also why Loop's β is 1/16 there, not 3/16.
- Extruding an **isolated** region has no seam, so it translates rather than building
  walls. That matches Blender; the prism case needs a region inside a larger surface.
- Edge→face conversion is **lossy** (three edges around a cube corner touch four faces),
  the same way Blender's is.

The three Android files were type-checked against structural stubs of `org.json`,
`android.opengl.Matrix`, `android.util.Base64` and the Filament/gltfio bindings. That
validates syntax and typing but **not** real API signatures — see the integration notes.

## The MeshLab question

MeshLab itself is not embeddable here. The current release is a C++/Qt desktop application
with no Android build; the old 0.9 Android APK was a limited viewer, effectively abandoned
([Wikipedia](https://en.wikipedia.org/wiki/MeshLab)). PyMeshLab is Python, also
desktop-only.

What *is* reusable is **VCGlib**, MeshLab's mesh kernel: C++, header-only, no external
dependencies, 100k+ lines ([repo](https://github.com/cnr-isti-vclab/vcglib)). But:

> **VCGlib is GPL. Linking it would force the entire Luxe app to be GPL.**

Given that, the operators were written from scratch rather than ported from VCGlib. This
keeps licensing unconstrained, keeps the kernel in Kotlin (one codebase across Android,
Windows and Linux), and avoids juggling a second NDK build alongside `luxe_assimp`.

**Recommendation:** keep the pure-Kotlin kernel. If specific MeshLab-quality batch
algorithms are needed later (quadric decimation, isotropic remeshing, Poisson
reconstruction), add them as an *optional* native module behind the same operator API, in
a separate library so the GPL boundary stays unambiguous — and only if a GPL app is
acceptable.

## Integration: wiring Edit mode into EditorActivity

Four steps. This is the next piece of work.

**1. Create the controller** after `sceneManager` and `selectionBounds` exist:

```kotlin
editMode = EditModeController(
    engine = viewer.engine,
    scene = viewer.scene,
    lineMaterialBuffer = readAsset("materials/luxe_lines.filamat"),
    surfaceMaterialProvider = { /* see note below */ },
    onSelectionChanged = { text -> status.text = text },
    onGeometryChanged = { projectSession?.markDirty(); saveButton.text = "Save •" }
)
```

**2. Hide the gltfio asset while its mesh is being edited.** Without this you see both the
original and the edited copy at once:

```kotlin
val record = sceneManager.selected() ?: return
sceneManager.setVisible(record.uid, visible = false)
editMode.enter(record, projectSession?.sessionDir)
// on exit: sceneManager.setVisible(uid, true); editMode.exit()
```

**3. Route taps.** Note the y-axis difference: `View.pick()` wants y measured from the
bottom, `MeshRaycast.rayFromScreen` wants top-left like Android touch events.

```kotlin
cameraInput.onTap = { x, y ->
    if (editMode.active) {
        val eye = DoubleArray(3); val target = DoubleArray(3); val up = DoubleArray(3)
        manipulator.getLookAt(eye, target, up)
        val ray = MeshRaycast.rayFromScreen(
            eye.toFloat3(), target.toFloat3(), up.toFloat3(),
            x, y, surface.width, surface.height, TAN_HALF_FOV   // ~0.4142 at 45°
        )
        editMode.pick(ray.origin(), ray.direction(), additive = false)
    } else pickScene(x, y)
}
```

**4. Bind the WIP chrome.** The second header row and left rail already have placeholders
for exactly these: element mode (1/2/3), Select All, Invert, Grow, Subdivide, Extrude,
Inset, Delete, Weld, Undo, Redo. Each maps to one call on `EditModeController`.

### Two API details to confirm against Filament 1.69.4

- **`surfaceMaterialProvider`** — the surface needs a `MaterialInstance`. Get one from
  gltfio's `UbershaderProvider` for PBR parity, or duplicate an instance from the loaded
  asset. Confirm the exact factory signature. If the chosen material samples a normal map,
  `EditableMeshRenderer` must also supply a TANGENTS buffer.
- **`AssetLoader.createAsset`** — `EditorSceneManager.addGlb` passes a `java.nio.Buffer`.
  The stubs accepted this; worth confirming it matches the real binding rather than relying
  on a Kotlin platform-type coercion.

## Known limitations

| Limitation | Consequence | Fix |
|---|---|---|
| Selection clears after structural edits | Blender keeps new faces selected post-extrude | Have operators return an id map |
| Brute-force picking | Fine for taps, not per-frame hover | BVH over faces; keep current code as the leaf intersector |
| Full-snapshot undo | ~8 MB/step at 200k tris | Store per-operator attribute deltas |
| Single instance in Edit mode at a time | No multi-object editing | Deliberate for v1 |
| Wireframe drawn for every edge | Skipped above 120k edges | Draw selected + boundary edges only |
| Subset subdivision leaves T-junctions | Shading artefacts | Pair with `weld`, or restrict to whole-mesh |

## Windows and Linux

The six pure-Kotlin files have no Android dependency, so the kernel runs unchanged on a
desktop JVM — that is exactly how the 157 tests execute. Expanding to desktop means porting
the shell (Activities, Views, touch), not the engine:

- **Filament** already targets desktop (Vulkan / Metal / D3D12 / OpenGL), so
  `EditableMeshRenderer` ports with only the buffer-upload details changing.
- **Kotlin Multiplatform / Compose Multiplatform** is the lowest-friction route: it keeps
  the existing Kotlin investment and covers Windows and Linux from one codebase. Android
  can keep its native View UI and share only the engine module if a full migration is too
  disruptive.
- The mesh kernel should be extracted into a shared Gradle module (e.g. `:mesh`) so the
  Android app and the desktop shell depend on the same source. Doing that now is cheap;
  doing it after the UI hardens is not.
- Assimp is already built via CMake for Android; the same build works on desktop, so the
  import pipeline carries over.

**Suggested order:** finish the Android Edit-mode UI first (the interaction model is the
hard part and it is touch-specific), then extract `:mesh`, then build the desktop shell
against the already-proven engine.
