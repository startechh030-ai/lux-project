package luxe.texture3d.app

import org.json.JSONObject
import java.io.File

object ProjectPackageManager {
    fun rebuild(projectDir:File):File{
        val projectFile=File(projectDir,"project.json");require(projectFile.isFile){"project.json is missing"};val json=JSONObject(projectFile.readText());val uid=json.optString("uid").ifBlank{error("Project UID is missing")};val name=json.optString("name",projectDir.name);val files=linkedMapOf<String,File>()
        projectDir.walkTopDown().filter{it.isFile&&it.extension.lowercase()!="ulx"&&!it.relativeTo(projectDir).invariantSeparatorsPath.startsWith("autosave/")}.forEach{files[it.relativeTo(projectDir).invariantSeparatorsPath]=it}
        val output=File(projectDir,"${projectDir.name}.ulx");UlxPackage.create(output,uid,name,files);UlxPackage.inspect(output);return output
    }
}
