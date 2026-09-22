#!/usr/bin/env bash
# Type-checks Luxe's mesh engine and Edit-mode UI against structural stubs, and runs the
# kernel test suite on the JVM. Neither step needs the Android SDK.
#
#   ./devkit-check.sh
#
# kotlinc is downloaded to /tmp on first run (it is not persisted with the workspace).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HOME/LuxeTexture3D" && pwd)"
STUBS="$HERE/stubs"
OUT=/tmp/luxe-build

if [ ! -x /tmp/kotlinc/bin/kotlinc ]; then
  echo "== fetching Kotlin 2.1.21 (matches the project's Kotlin version)"
  curl -sSL -o /tmp/kotlin.zip \
    https://github.com/JetBrains/kotlin/releases/download/v2.1.21/kotlin-compiler-2.1.21.zip
  unzip -q -o /tmp/kotlin.zip -d /tmp
fi
KOTLINC=/tmp/kotlinc/bin/kotlinc

echo "== 1/3  type-checking engine + Android bridge against stubs"
rm -rf "$OUT"; mkdir -p "$OUT/classes"
# shellcheck disable=SC2046
$KOTLINC -nowarn \
  $(find "$STUBS" -name '*.kt' | sort) \
  "$ROOT/app/src/main/java/luxe/texture3d/app/EditMesh.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/MeshTopology.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/MeshSelection.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/MeshOperators.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/MeshRaycast.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/MeshHistory.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/MeshGltfWriter.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/ModelPlacement.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/EditorSceneManager.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/GltfMeshLoader.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/EditableMeshRenderer.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/EditModeController.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/EditModeUi.kt" \
  -d "$OUT/classes"
echo "    ok — 13 sources + stubs compiled"

echo "== 2/3  building the kernel test harness"
$KOTLINC -nowarn \
  "$ROOT/app/src/main/java/luxe/texture3d/app/EditMesh.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/MeshTopology.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/MeshSelection.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/MeshOperators.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/MeshRaycast.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/MeshHistory.kt" \
  "$ROOT/app/src/main/java/luxe/texture3d/app/MeshGltfWriter.kt" \
  "$ROOT/app/src/test/java/luxe/texture3d/verification/MeshKernelTest.kt" \
  -include-runtime -d "$OUT/meshkernel.jar"

echo "== 3/3  running the suite"
java -jar "$OUT/meshkernel.jar"
