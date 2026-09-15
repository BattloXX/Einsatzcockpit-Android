package cloud.einsatzleiter.smsgatewayplugin.kontakte

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert

data class KontaktDetail(
    @androidx.room.Embedded val kontakt: KontaktEntity,
    @Relation(parentColumn = "id", entityColumn = "kontakt_id") val telefone: List<TelefonEntity>,
    @Relation(parentColumn = "id", entityColumn = "kontakt_id") val zuordnungen: List<ZuordnungEntity>,
)

@Dao
interface KontaktDao {
    @Query("SELECT * FROM kontakte ORDER BY anzeigename COLLATE NOCASE, id")
    suspend fun list(): List<KontaktEntity>

    @Query("""
        SELECT * FROM kontakte
        WHERE anzeigename LIKE '%' || :query || '%'
           OR vorname LIKE '%' || :query || '%'
           OR nachname LIKE '%' || :query || '%'
           OR organisation LIKE '%' || :query || '%'
        ORDER BY anzeigename COLLATE NOCASE, id
    """)
    suspend fun searchByName(query: String): List<KontaktEntity>

    @Query("""
        SELECT DISTINCT k.* FROM kontakte k
        INNER JOIN kontakt_telefone t ON t.kontakt_id = k.id
        WHERE t.nummer_normalisiert LIKE :normalizedQuery || '%'
        ORDER BY k.anzeigename COLLATE NOCASE, k.id
    """)
    suspend fun searchByNumber(normalizedQuery: String): List<KontaktEntity>

    @Transaction
    @Query("SELECT * FROM kontakte WHERE id = :kontaktId")
    suspend fun detail(kontaktId: Long): KontaktDetail?

    // REPLACE would delete the parent row and cascade-delete existing mappings.
    @Upsert
    suspend fun upsertKontakt(kontakt: KontaktEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKontakte(kontakte: List<KontaktEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelefone(telefone: List<TelefonEntity>)

    @Upsert
    suspend fun upsertZuordnung(zuordnung: ZuordnungEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertZuordnungen(zuordnungen: List<ZuordnungEntity>)

    @Query("DELETE FROM kontakt_telefone WHERE kontakt_id = :kontaktId")
    suspend fun deleteTelefoneForKontakt(kontaktId: Long)

    @Query("DELETE FROM kontakt_zuordnungen WHERE kontakt_id = :kontaktId")
    suspend fun deleteZuordnungenForKontakt(kontaktId: Long)

    @Query("DELETE FROM kontakte WHERE id = :kontaktId")
    suspend fun deleteKontakt(kontaktId: Long)

    @Query("DELETE FROM kontakt_zuordnungen WHERE id = :zuordnungId")
    suspend fun deleteZuordnung(zuordnungId: Long)

    @Query("DELETE FROM kontakt_telefone")
    suspend fun deleteAllTelefone()

    @Query("DELETE FROM kontakt_zuordnungen")
    suspend fun deleteAllZuordnungen()

    @Query("DELETE FROM kontakte")
    suspend fun deleteAllKontakte()
}

@Dao
interface KontaktSyncStatusDao {
    @Query("SELECT * FROM kontakt_sync_status WHERE id = 1")
    suspend fun get(): KontaktSyncStatusEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(status: KontaktSyncStatusEntity)

    @Query("DELETE FROM kontakt_sync_status")
    suspend fun deleteAll()
}
