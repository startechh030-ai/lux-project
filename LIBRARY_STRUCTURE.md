# Final Asset / Library / Project Separation

```text
files/
├── assets/      # imported 3D families: glTF, BIN, family.json
├── projects/    # working projects and binary .ulx packages
└── library/
    ├── Texture/
    ├── Audio/
    ├── Video/
    ├── Environment/{HDRI,EXR,KTX2}/
    ├── Animation/
    ├── Script/
    └── Element/ # project-created .ulelement families only
```

Imported models never create Ulelements. Their reusable files are cloned/hard-linked into typed Library folders and indexed by `family.json`. Animations are lightweight `.anim` descriptors that continue referencing source glTF/BIN data. Rig files are deferred.

A `.ulelement` is a UTF-8 family manifest created from project data. It can reference many payload files and records SHA-256 for each physical file. Single properties such as `.lightmap` remain leaf text resources and can be included in a Ulelement family.
