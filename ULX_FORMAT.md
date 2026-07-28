# ULX Container and Element Formats

## `.ulx`

`.ulx` is an unencrypted binary ZIP container, not a text file. It must contain `ulx/manifest.json`. Project payload entries live below `payload/` and may include glTF JSON, BIN data, project-local textures, thumbnails, and other project-local files. Encryption is reserved for a later format version.

Normal editor operation may use an extracted working directory, but the `.ulx` package is the canonical portable project representation.

## Text-based Element manifests

Element descriptors remain UTF-8 JSON text:

- `.ulelement`
- `.ulshader`
- `.ullight`
- `.ullightmap`
- `.ulenv`
- `.ulmat`
- `.ulrig`
- `.ulanim`
- `.ultexset`

These manifests reference content-addressed blobs. Binary payloads such as PNG, HDR, MP4, WEBM, glTF BIN, and compiled runtime caches remain binary blob files.

## Identity

- Element UID: stable reusable identity
- Revision UID: immutable version identity
- Blob SHA-256: physical content identity and deduplication key
