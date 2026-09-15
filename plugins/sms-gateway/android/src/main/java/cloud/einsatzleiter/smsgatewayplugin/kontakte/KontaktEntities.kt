package cloud.einsatzleiter.smsgatewayplugin.kontakte

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "kontakte")
data class KontaktEntity(
    @PrimaryKey val id: Long,
    val typ: String,
    val anzeigename: String,
    val vorname: String?,
    val nachname: String?,
    val funktion: String?,
    val organisation: String?,
    val email: String?,
    val erreichbarkeit: String?,
    val notizen: String?,
    val aktiv: Boolean,
    val archiviert: Boolean,
    val version: Long,
)

@Entity(
    tableName = "kontakt_telefone",
    foreignKeys = [ForeignKey(
        entity = KontaktEntity::class,
        parentColumns = ["id"],
        childColumns = ["kontakt_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["kontakt_id"]), Index(value = ["nummer_normalisiert"])],
)
data class TelefonEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "kontakt_id") val kontaktId: Long,
    val nummer: String,
    @ColumnInfo(name = "nummer_normalisiert") val nummerNormalisiert: String,
    val label: String?,
    val sort: Int,
    val bevorzugt: Boolean,
    @ColumnInfo(name = "sms_eignung") val smsEignung: Boolean,
)

@Entity(
    tableName = "kontakt_zuordnungen",
    foreignKeys = [ForeignKey(
        entity = KontaktEntity::class,
        parentColumns = ["id"],
        childColumns = ["kontakt_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["kontakt_id"]), Index(value = ["objekt_id"])],
)
data class ZuordnungEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "kontakt_id") val kontaktId: Long,
    @ColumnInfo(name = "objekt_id") val objektId: Long,
    val rolle: String,
    val sort: Int,
    val erreichbarkeit: String?,
)

/** A single row that scopes the locally cached contact generation. */
@Entity(tableName = "kontakt_sync_status")
data class KontaktSyncStatusEntity(
    @PrimaryKey val id: Int = STATUS_ROW_ID,
    @ColumnInfo(name = "cursor") val cursor: Long? = null,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int? = null,
    @ColumnInfo(name = "org_id") val orgId: Long? = null,
    @ColumnInfo(name = "base_url") val baseUrl: String? = null,
    @ColumnInfo(name = "last_success_at_ms") val lastSuccessAtMs: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
) {
    companion object {
        const val STATUS_ROW_ID = 1
    }
}

/** Deliberately small normalization for the indexed offline number search. */
fun normalizeTelefonnummer(value: String): String {
    val trimmed = value.trim()
    return buildString(trimmed.length) {
        trimmed.forEachIndexed { index, character ->
            if (character.isDigit() || (character == '+' && index == 0)) append(character)
        }
    }
}
