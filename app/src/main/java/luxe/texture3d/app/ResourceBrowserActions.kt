package luxe.texture3d.app

/** Implemented by the editor in Phase 4. Phase 3C.3 only establishes the contract. */
interface ResourceBrowserActions {
    fun onOpenProject(projectPath:String) {}
    fun onSelectAsset(assetPath:String) {}
    fun onSelectLibraryResource(resourcePath:String) {}
    fun onSelectUlelement(elementPath:String) {}
    fun onAddToProject(resourcePath:String) {}
    fun onAddToScene(resourcePath:String) {}
}
