package com.famtrack.app.data.offline

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Linha da fila offline de pontos de rota (ETAPA 5). O [id] é o UUID gerado no
 * aparelho (chave também no servidor); [position] preserva a ordem de coleta
 * (o `created_at` original não é garantido — melhor ordenar pelo momento local).
 */
@Entity(tableName = "route_points")
internal data class StoredPointEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val userId: String,
    val latitude: Double,
    val longitude: Double,
    val recordedAt: String?,
    val accuracy: Float?,
    val speed: Float?,
    val bearing: Float?,
    val batteryLevel: Int?,
    val provider: String?,
    val position: Long
)

@Dao
internal interface RoutePointDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(points: List<StoredPointEntity>)

    @Query("SELECT * FROM route_points ORDER BY position ASC")
    fun all(): List<StoredPointEntity>

    @Query("SELECT COUNT(*) FROM route_points")
    fun count(): Int

    @Query("DELETE FROM route_points WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM route_points WHERE id NOT IN (:keep)")
    fun deleteNotIn(keep: List<String>)

    @Query("DELETE FROM route_points")
    fun clear()
}

@Database(entities = [StoredPointEntity::class], version = 1, exportSchema = false)
internal abstract class RouteTrackDatabase : RoomDatabase() {

    abstract fun routePointDao(): RoutePointDao

    companion object {
        fun getInstance(context: Context): RouteTrackDatabase =
            Room.databaseBuilder(
                context,
                RouteTrackDatabase::class.java,
                "route_track.db"
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}