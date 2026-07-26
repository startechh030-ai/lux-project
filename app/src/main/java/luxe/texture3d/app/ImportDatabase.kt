package luxe.texture3d.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="import_jobs")
data class ImportJob(
    @PrimaryKey val id:String,
    val batchId:String,
    val sourceUri:String,
    val displayName:String,
    val sourceSize:Long,
    val sourceFormat:String,
    val resourceUrisJson:String="[]",
    val state:String="WAITING",
    val progress:Int=0,
    val message:String="Waiting",
    val outputAssetIdsJson:String="[]",
    val error:String="",
    val createdAt:Long=System.currentTimeMillis(),
    val updatedAt:Long=System.currentTimeMillis()
)

@Dao
interface ImportJobDao {
    @Query("SELECT * FROM import_jobs ORDER BY CASE state WHEN 'RUNNING' THEN 0 WHEN 'WAITING' THEN 1 ELSE 2 END, sourceSize ASC, createdAt ASC")
    fun observeAll():Flow<List<ImportJob>>

    @Query("SELECT * FROM import_jobs WHERE id=:id LIMIT 1")
    suspend fun get(id:String):ImportJob?

    @Query("SELECT * FROM import_jobs WHERE state IN ('RUNNING','WAITING') ORDER BY CASE state WHEN 'RUNNING' THEN 0 ELSE 1 END, sourceSize ASC, createdAt ASC LIMIT 1")
    suspend fun nextPending():ImportJob?

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun put(job:ImportJob)

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun putAll(jobs:List<ImportJob>)

    @Query("UPDATE import_jobs SET state=:state, progress=:progress, message=:message, error=:error, outputAssetIdsJson=:assets, updatedAt=:now WHERE id=:id")
    suspend fun update(id:String,state:String,progress:Int,message:String,error:String="",assets:String="[]",now:Long=System.currentTimeMillis())

    @Query("DELETE FROM import_jobs WHERE state IN ('COMPLETED','PARTIAL','FAILED','CANCELLED')")
    suspend fun clearFinished()
}

@Database(entities=[ImportJob::class],version=1,exportSchema=false)
abstract class ImportDatabase:RoomDatabase(){
    abstract fun jobs():ImportJobDao
    companion object {
        @Volatile private var instance:ImportDatabase?=null
        fun get(context:Context)=instance?:synchronized(this){instance?:Room.databaseBuilder(context.applicationContext,ImportDatabase::class.java,"luxe-imports.db").fallbackToDestructiveMigration().build().also{instance=it}}
    }
}
