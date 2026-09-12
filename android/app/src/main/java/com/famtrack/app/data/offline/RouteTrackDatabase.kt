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
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

/**
 * Cache persistente de endereços (ETAPA 11A). Uma linha por coordenada
 * arredondada (~11m): [fullText] é o endereço completo (tela de atividades),
 * [shortText] é o rótulo curto (cards/só títulos de percurso); [lastUsedAt]
 * alimenta o limite de linhas mantidas.
 */
@Entity(tableName = "address_cache")
internal data class AddressCacheEntity(
    @PrimaryKey val cacheKey: String,
    val fullText: String?,
    val shortText: String?,
    val lastUsedAt: Long
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

@Dao
internal interface AddressCacheDao {

    @Query("SELECT * FROM address_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun find(key: String): AddressCacheEntity?

    @Upsert
    suspend fun upsert(entity: AddressCacheEntity)

    /** Mantém no máximo [limit] linhas (mais recentes por [lastUsedAt]). */
    @Query(
        "DELETE FROM address_cache WHERE cacheKey NOT IN (" +
            "SELECT cacheKey FROM address_cache ORDER BY lastUsedAt DESC LIMIT :limit)"
    )
    suspend fun trimTo(limit: Int)
}

@Database(
    entities = [StoredPointEntity::class, AddressCacheEntity::class],
    version = 2,
    exportSchema = false
)
internal abstract class RouteTrackDatabase : RoomDatabase() {

    abstract fun routePointDao(): RoutePointDao

    abstract fun addressCacheDao(): AddressCacheDao

    companion object {

        /** ETAPA 11A — aditiva (apenas a nova tabela): a fila route_points sobrevive. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `address_cache` (" +
                        "`cacheKey` TEXT NOT NULL, " +
                        "`fullText` TEXT, " +
                        "`shortText` TEXT, " +
                        "`lastUsedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`cacheKey`))"
                )
            }
        }

        // Teto de linhas do cache de endereços (evita crescimento indefinido).
        private const val ADDRESS_CACHE_MAX_ROWS = 2000

        fun getInstance(context: Context): RouteTrackDatabase =
            Room.databaseBuilder(
                context,
                RouteTrackDatabase::class.java,
                "route_track.db"
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun addressCacheMaxRows(): Int = ADDRESS_CACHE_MAX_ROWS
    }
}