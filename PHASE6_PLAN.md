# Phase 6 — Edit Mode Mesh Engine

**Target version:** 0.39.0 · **Status:** kernel + modelling tools complete and verified (283 checks) · **UI wiring:** the remaining step

> The modelling tools (loop cut, knife, bevel, mesh relab, move/rotate/scale, mirror) are
> implemented and verified in the kernel and are reachable from `EditModeHost`. What remains is
> forwarding touch events from `EditorActivity` and placing the transform panel — see
> "Wiring the gestures" below.

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

Eleven files. Seven are pure Kotlin with **zero Android imports**; four are the Android bridge.

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
| `MeshGltfWriter.kt` | `EditMesh` → GLB export, so edits survive leaving Edit mode | pure |
| `EditModeController.kt` | Owns Edit mode for one scene instance | Android |
| `EditModeUi.kt` | `EditModeToolbarView` (button panel) + `EditModeHost` (integration glue) | Android |

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

**177 checks, all passing** on Kotlin 2.1.21 (the version the project builds with).

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

`EditModeHost` already owns the controller, the toolbar, asset hiding, tap routing and
persistence, so `EditorActivity` needs five small changes. Nothing existing is removed.

**1. Add the field** near the other editor fields:

```kotlin
private lateinit var editHost: EditModeHost
```

**2. Create it** after `sceneManager` and `selectionBounds` are constructed, and add its
toolbar to `root`:

```kotlin
editHost = EditModeHost(
    context = this,
    engine = viewer.engine,
    scene = viewer.scene,
    lineMaterialBuffer = readAsset("materials/luxe_lines.filamat"),
    surfaceMaterialProvider = { /* see the API note below */ },
    sceneManager = sceneManager,
    onDirty = { projectSession?.markDirty(); if (::saveButton.isInitialized) saveButton.text = "Save •" },
    onStatus = { text -> status.text = text }
)
editHost.sessionDir = projectSession?.sessionDir
editHost.cameraProvider = {
    val eye = DoubleArray(3); val target = DoubleArray(3); val up = DoubleArray(3)
    manipulator.getLookAt(eye, target, up)
    EditModeHost.CameraFrame(
        eye = eye.map { it.toFloat() }.toFloatArray(),
        target = target.map { it.toFloat() }.toFloatArray(),
        up = up.map { it.toFloat() }.toFloatArray(),
        width = surface.width,
        height = surface.height,
        tanHalfFovY = TAN_HALF_FOV
    )
}
root.addView(
    editHost.toolbar,
    FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.END or Gravity.CENTER_VERTICAL
    ).apply { rightMargin = dp(8) }
)
```

with `private const val TAN_HALF_FOV = 0.4142f` in the companion object (45° vertical FOV).

Note the y-axis difference: `View.pick()` measures y from the bottom, while
`MeshRaycast.rayFromScreen` takes top-left like Android touch events. `EditModeHost`
handles that, so pass `x` and `y` straight through.

**3. Route taps.** Object mode keeps working unchanged when Edit mode is inactive:

```kotlin
cameraInput.onTap = { x, y -> if (!editHost.handleTap(x, y)) pickScene(x, y) }
```

**4. Add an entry point.** The natural place is each row of `showSceneList()`, next to the
existing Rename and Delete buttons:

```kotlin
row.addView(sceneControl("Edit", {
    dialog.dismiss()
    editHost.enter(record.uid, projectSession?.sessionDir)
}), LinearLayout.LayoutParams(dp(56), dp(32)).apply { leftMargin = dp(4) })
```

**5. Clean up** in `onDestroy()`, alongside the existing `sceneManager.destroy()`:

```kotlin
if (::editHost.isInitialized) runCatching { editHost.destroy() }
```

### What you get on screen

A right-side panel with element mode (Vert / Edge / Face), Subdivide, Extrude, Inset,
Delete, Weld, All, Invert, Grow, None, Undo, Redo, Exit. Buttons disable themselves when
they would be a no-op. Leaving Edit mode writes the edited geometry to
`<sessionDir>/local/<uid>-edited.glb`.

### Binding the WIP chrome later

The second header row and left rail already have placeholders for these same operations.
Pointing them at `editHost.controller` is a one-line change each; the toolbar is a working
reference implementation of the interaction model, not the final home for these controls.

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
| Edited mesh saved as a side GLB | The scene instance still points at the original asset until Phase 6C | Repoint `Record.source` at the exported GLB |
| Brute-force picking | Fine for taps, not per-frame hover | BVH over faces; keep current code as the leaf intersector |
| Full-snapshot undo | ~8 MB/step at 200k tris | Store per-operator attribute deltas |
| Single instance in Edit mode at a time | No multi-object editing | Deliberate for v1 |
| Wireframe drawn for every edge | Skipped above 120k edges | Draw selected + boundary edges only |
| Subset subdivision leaves T-junctions | Shading artefacts | Pair with `weld`, or restrict to whole-mesh |
| Export carries geometry only | No materials, textures, vertex colours or tangents | Extend `MeshGltfWriter` alongside a material graph |
| No hover highlight | Selection only on tap, not on drag-over | BVH, then hit test per move event |

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

---

## Wiring the gestures

`EditModeHost` exposes the gesture API; `EditorActivity` only has to forward events to it while
Edit mode is active, and place `transformPanel` on the side opposite `toolbar`.

```kotlin
// 1. Place the panel opposite the toolbar. In the existing frame layout that hosts
//    editHost.toolbar, add editHost.transformPanel with Gravity.START (the toolbar is END).
container.addView(editHost.transformPanel, FrameLayout.LayoutParams(
    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
    Gravity.START or Gravity.CENTER_VERTICAL
))

// 2. In the viewport's onTouch, while editHost.active, before the gesture detector sees it:
editHost.handleLongPress(x, y)               // commit the element under the finger
editHost.handleDrag(x, y, dx, dy)            // one-finger drag (x, y are absolute)
editHost.handleDragEnd()                     // commit knife / loop cut / bevel
editHost.handleTwoFingerScroll(distanceY)    // add segments, loops or rings

// 3. Call editHost.beginGesture() on ACTION_DOWN so the whole drag is one undo step.
```

`handleLongPress` returns false when nothing was hit, so the Activity can fall back to its
normal long-press behaviour.

## Design notes worth keeping

- **Gestures belong in the host, not the view.** `EditModeToolbarView` renders buttons and
  reports presses; everything that touches meshes, cameras or history lives in `EditModeHost`.
  That split is what keeps the Activity patch to a dozen lines.
- **One undo step per gesture.** `beginGesture` / `preview` / `endGesture` exist because a drag
  has to show its result continuously; recording history per frame would bury the edit.
- **Never trust an unrun compile.** Every tool here had bugs that compiled cleanly: the bevel
  collapsed to zero width on coplanar diagonals, the loop cut left a seam on closed rings, the
  bevel fillets faced inward on some edges. All were caught by asserting Euler characteristic,
  boundary count, unit normals and consistent winding — not by reading the code.
