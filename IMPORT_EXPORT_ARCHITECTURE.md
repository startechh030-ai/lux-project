# Luxe Import / Export Architecture

## App-specific storage

Resolved through `getExternalFilesDir(null)`; never hardcode `/storage/emulated/0`.

```text
Android/data/luxe.texture3d.app/files/
├── imports/<asset-id>/
│   ├── model.gltf
│   ├── model.bin
│   ├── textures/
│   ├── thumbnail.png
│   └── import.json
├── projects/<project-id>/
│   ├── project.json
│   ├── scene/
│   ├── textures/
│   ├── thumbnail.png
│   └── autosave/
├── staging/<job-id>/
├── runtime-cache/
└── thumbnails/
```

`cacheDir` / `externalCacheDir` are used for disposable conversion scratch files. Staging is always deleted after success, failure, or cancellation.

## User-selected export directory

The Storage Access Framework folder selected by the user is export-only. Exports survive app uninstall and may be shared with other apps.

## Import / Export page

Sub-tabs:

- Queue
- Recent Imports
- Recent Conversions
- Recent Exports
- Failed

Multiple picker results are sorted by reported source size and converted serially. Only one native Assimp scene exists at a time.

## Supported first-pass conversions

- GLB / glTF package
- FBX
- DAE
- OBJ package
- STL
- PLY
- 3DS
- DXF
- ZIP package

USD / USDZ remain experimental and must never be advertised as lossless.

## Conversion output

Assimp exports glTF 2.0 JSON (`gltf2`) with BIN and texture resources. Filament loads this output directly through gltfio. `.filamesh` and `.filamat` are not part of this pipeline.

## Memory safety

- URI copied to one active staging job only when native seekable access is required.
- Queue runs one job at a time, smallest first.
- Input and expanded ZIP limits depend on Android memory class and allocatable storage.
- Native scene and importer are released after every job.
- Conversion can lower post-process quality and pause between phases after memory-pressure callbacks.
- Staging is cleaned in `finally` and by startup recovery.

## Project actions

Long press on a Hub project will eventually show:

- Details
- Transfer
- Export
- Clean Cache
- Duplicate
- Rename
- Delete

Export opens a larger modal with format and packaging options plus an optional 3D preview. Editor baking produces project-ready textures/geometry; Export only packages those baked results.

## Back behavior

Editor Back opens a Luxe custom discard modal when project state is dirty. It never uses a default Android AlertDialog.
