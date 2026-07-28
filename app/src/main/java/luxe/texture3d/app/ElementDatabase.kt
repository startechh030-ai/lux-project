package luxe.texture3d.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="elements",indices=[Index("type"),Index("scope"),Index("ownerProjectUid")])
data class ElementEntity(@PrimaryKey val uid:String,val name:String,val type:String,val scope:String,val ownerProjectUid:String?,val currentRevisionUid:String,val thumbnailPath:String?,val status:String,val createdAt:Long,val modifiedAt:Long)

@Entity(tableName="element_revisions",indices=[Index("elementUid"),Index(value=["elementUid","contentHash"],unique=true)])
data class ElementRevisionEntity(@PrimaryKey val uid:String,val elementUid:String,val versionLabel:String,val manifestPath:String,val contentHash:String,val createdAt:Long)

@Entity(tableName="element_dependencies",indices=[Index("parentRevisionUid"),Index("childElementUid")])
data class ElementDependencyEntity(@PrimaryKey val uid:String,val parentRevisionUid:String,val childElementUid:String,val childRevisionUid:String,val role:String,val required:Boolean)

@Entity(tableName="blobs")
data class BlobEntity(@PrimaryKey val sha256:String,val relativePath:String,val size:Long,val mime:String,val referenceCount:Long,val createdAt:Long)

@Entity(tableName="element_blob_refs",primaryKeys=["revisionUid","blobHash","logicalPath"],indices=[Index("blobHash")])
data class ElementBlobRefEntity(val revisionUid:String,val blobHash:String,val logicalPath:String,val role:String)

@Entity(tableName="ulx_projects")
data class UlxProjectEntity(@PrimaryKey val uid:String,val name:String,val ulxPath:String,val thumbnailPath:String?,val createdAt:Long,val modifiedAt:Long,val status:String)

@Entity(tableName="project_element_refs",indices=[Index("projectUid"),Index("elementUid")])
data class ProjectElementRefEntity(@PrimaryKey val instanceUid:String,val projectUid:String,val elementUid:String,val revisionUid:String,val role:String)

@Dao
interface ElementDao {
    @Query("SELECT * FROM elements WHERE status!='trashed' ORDER BY modifiedAt DESC") fun observeElements():Flow<List<ElementEntity>>
    @Query("SELECT * FROM elements WHERE uid=:uid LIMIT 1") suspend fun getElement(uid:String):ElementEntity?
    @Query("SELECT * FROM element_revisions WHERE elementUid=:elementUid ORDER BY createdAt DESC") suspend fun revisions(elementUid:String):List<ElementRevisionEntity>
    @Query("SELECT * FROM element_revisions WHERE elementUid=:elementUid AND contentHash=:hash LIMIT 1") suspend fun revisionByHash(elementUid:String,hash:String):ElementRevisionEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putElement(value:ElementEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putRevision(value:ElementRevisionEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putDependencies(values:List<ElementDependencyEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putBlobRefs(values:List<ElementBlobRefEntity>)
    @Query("SELECT COUNT(*) FROM project_element_refs WHERE elementUid=:uid") suspend fun projectUsageCount(uid:String):Int
}

@Dao
interface BlobDao {
    @Query("SELECT * FROM blobs WHERE sha256=:hash LIMIT 1") suspend fun get(hash:String):BlobEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(value:BlobEntity)
    @Query("UPDATE blobs SET referenceCount=referenceCount+:delta WHERE sha256=:hash") suspend fun increment(hash:String,delta:Long)
}

@Dao
interface UlxProjectDao {
    @Query("SELECT * FROM ulx_projects ORDER BY modifiedAt DESC") fun observeProjects():Flow<List<UlxProjectEntity>>
    @Query("SELECT * FROM ulx_projects WHERE uid=:uid LIMIT 1") suspend fun get(uid:String):UlxProjectEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(project:UlxProjectEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putRef(ref:ProjectElementRefEntity)
    @Query("SELECT * FROM project_element_refs WHERE projectUid=:projectUid") suspend fun refs(projectUid:String):List<ProjectElementRefEntity>
}

@Database(entities=[ElementEntity::class,ElementRevisionEntity::class,ElementDependencyEntity::class,BlobEntity::class,ElementBlobRefEntity::class,UlxProjectEntity::class,ProjectElementRefEntity::class],version=1,exportSchema=false)
abstract class ElementDatabase:RoomDatabase(){
    abstract fun elements():ElementDao
    abstract fun blobs():BlobDao
    abstract fun projects():UlxProjectDao
    companion object{@Volatile private var instance:ElementDatabase?=null;fun get(context:Context)=instance?:synchronized(this){instance?:Room.databaseBuilder(context.applicationContext,ElementDatabase::class.java,"luxe-elements.db").build().also{instance=it}}}
}
