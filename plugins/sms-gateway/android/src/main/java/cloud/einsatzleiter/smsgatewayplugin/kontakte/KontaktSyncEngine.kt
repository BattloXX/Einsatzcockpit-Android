package cloud.einsatzleiter.smsgatewayplugin.kontakte

import android.content.Context
import androidx.room.withTransaction
import cloud.einsatzleiter.smsgatewayplugin.EinsatzLivePoller
import cloud.einsatzleiter.smsgatewayplugin.OfflineCacheStatusStore
import cloud.einsatzleiter.smsgatewayplugin.WebViewCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches and persists the contact feed for either a WebView session or a paired device. This class intentionally does not schedule itself;
 * Phase C owns choosing when it is called.
 */
class KontaktSyncEngine(
    private val databaseProvider: (Context) -> KontaktDatabase = { KontaktDatabase.get(it) },
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun syncOnce(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
        val token = prefs.getString("el_device_token", null)?.takeIf { it.isNotBlank() }
        val db = databaseProvider(appContext)

        if (baseUrl.isNullOrBlank()) return@withContext fail(db, "Server-URL fehlt")
        try {
            val status = db.syncStatusDao().get()
            if (status?.cursor == null || status?.orgId == null || status?.schemaVersion == null ||
                status?.baseUrl != baseUrl) {
                syncSnapshot(db, baseUrl, token, status)
            } else {
                syncDelta(db, baseUrl, token, requireNotNull(status))
            }
            val contactCount = db.kontaktDao().count()
            OfflineCacheStatusStore.logActivity(
                appContext,
                "Kontakte aktualisiert: $contactCount/$contactCount Kontakte offline verfügbar",
            )
            true
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            OfflineCacheStatusStore.logActivity(appContext, "Kontakt-Sync fehlgeschlagen: $message")
            fail(db, message)
        }
    }

    /** Removes cached contacts and all sync metadata. Later phases call this on logout/re-pairing. */
    suspend fun wipeLocalData(context: Context) = withContext(Dispatchers.IO) {
        val db = databaseProvider(context.applicationContext)
        db.withTransaction {
            db.kontaktDao().deleteAllTelefone()
            db.kontaktDao().deleteAllZuordnungen()
            db.kontaktDao().deleteAllKontakte()
            db.syncStatusDao().deleteAll()
        }
    }

    private suspend fun syncSnapshot(
        db: KontaktDatabase,
        baseUrl: String,
        token: String?,
        previousStatus: KontaktSyncStatusEntity?,
    ) {
        val contacts = mutableListOf<KontaktPayload>()
        val mappings = mutableListOf<ZuordnungEntity>()
        var pageAfter = 0L
        var expectedOrgId: Long? = null
        var expectedCursor: Long? = null
        var expectedSchema: Int? = null
        var firstPage = true

        while (true) {
            val root = fetch(baseUrl, token, pageAfter = pageAfter)
            val envelope = parseEnvelope(root, "snapshot")
            assertSameScope(envelope, expectedOrgId, expectedCursor, expectedSchema)
            if (expectedOrgId == null) {
                expectedOrgId = envelope.orgId
                expectedCursor = envelope.cursor
                expectedSchema = envelope.schemaVersion
                // A different tenant must never remain readable once it is identified.
                if (previousStatus?.orgId != null && previousStatus.orgId != envelope.orgId) {
                    wipeDatabase(db)
                }
            }

            contacts += parseKontakte(root.getJSONArray("contacts"))
            if (firstPage && root.has("mappings") && !root.isNull("mappings")) {
                mappings += parseZuordnungen(root.getJSONArray("mappings"))
            }
            firstPage = false

            if (root.isNull("next_page")) break
            pageAfter = root.getLong("next_page")
            if (pageAfter < 0) throw SyncException("Ungültiger next_page-Wert")
        }

        val orgId = checkNotNull(expectedOrgId)
        val cursor = checkNotNull(expectedCursor)
        val schema = checkNotNull(expectedSchema)
        db.withTransaction {
            // No active table changes happen before every snapshot page was fetched and parsed.
            db.kontaktDao().deleteAllTelefone()
            db.kontaktDao().deleteAllZuordnungen()
            db.kontaktDao().deleteAllKontakte()
            db.kontaktDao().insertKontakte(contacts.map { it.kontakt })
            db.kontaktDao().insertTelefone(contacts.flatMap { it.telefone })
            db.kontaktDao().insertZuordnungen(mappings)
            db.syncStatusDao().put(successStatus(cursor, schema, orgId, baseUrl))
        }
    }

    private suspend fun syncDelta(
        db: KontaktDatabase,
        baseUrl: String,
        token: String?,
        initialStatus: KontaktSyncStatusEntity,
    ) {
        var requestCursor = checkNotNull(initialStatus.cursor)
        while (true) {
            val root = fetch(baseUrl, token, cursor = requestCursor)
            val envelope = parseEnvelope(root, "delta")
            if (envelope.orgId != initialStatus.orgId || envelope.schemaVersion != initialStatus.schemaVersion) {
                // A cursor is tenant/schema scoped. Do not apply this response to the old generation.
                wipeDatabase(db)
                syncSnapshot(db, baseUrl, token, null)
                return
            }
            val changes = parseChanges(root.getJSONArray("changes"))
            db.withTransaction {
                changes.forEach { change -> applyChange(db.kontaktDao(), change) }
                db.syncStatusDao().put(successStatus(
                    cursor = envelope.cursor,
                    schemaVersion = envelope.schemaVersion,
                    orgId = envelope.orgId,
                    baseUrl = baseUrl,
                ))
            }
            if (!root.optBoolean("has_more", false)) return
            if (envelope.cursor <= requestCursor) throw SyncException("Delta-Cursor macht keinen Fortschritt")
            requestCursor = envelope.cursor
        }
    }

    private suspend fun applyChange(dao: KontaktDao, change: Change) {
        when (change) {
            is Change.KontaktUpsert -> {
                dao.upsertKontakt(change.payload.kontakt)
                dao.deleteTelefoneForKontakt(change.payload.kontakt.id)
                dao.insertTelefone(change.payload.telefone)
            }
            is Change.ZuordnungUpsert -> dao.upsertZuordnung(change.payload)
            is Change.KontaktTombstone -> {
                dao.deleteTelefoneForKontakt(change.id)
                dao.deleteZuordnungenForKontakt(change.id)
                dao.deleteKontakt(change.id)
            }
            is Change.ZuordnungTombstone -> dao.deleteZuordnung(change.id)
        }
    }

    private fun fetch(baseUrl: String, token: String?, cursor: Long? = null, pageAfter: Long? = null): JSONObject {
        val url = buildString {
            append(baseUrl).append("/api/v1/device/kontakte/sync")
            when {
                cursor != null -> append("?cursor=").append(cursor)
                pageAfter != null -> append("?page_after=").append(pageAfter)
            }
        }
        val request = try {
            Request.Builder().url(url).get().apply {
                if (token != null) header("Authorization", "Bearer $token")
            }.build()
        } catch (error: IllegalArgumentException) {
            throw SyncException("Ungültige Server-URL", error)
        }
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                throw SyncException("Sync-Serverfehler HTTP ${response.code}")
            }
            return try {
                JSONObject(body)
            } catch (error: Exception) {
                throw SyncException("Ungültige Sync-Antwort", error)
            }
        }
    }

    private fun parseEnvelope(root: JSONObject, expectedMode: String): Envelope {
        val mode = root.getString("mode")
        if (mode != expectedMode) throw SyncException("Unerwarteter Sync-Modus: $mode")
        val schemaVersion = root.getInt("schema_version")
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw SyncException("Nicht unterstützte Kontakt-Schema-Version: $schemaVersion")
        }
        return Envelope(schemaVersion, root.getLong("org_id"), root.getLong("cursor"))
    }

    private fun assertSameScope(envelope: Envelope, orgId: Long?, cursor: Long?, schema: Int?) {
        if ((orgId != null && envelope.orgId != orgId) ||
            (cursor != null && envelope.cursor != cursor) ||
            (schema != null && envelope.schemaVersion != schema)) {
            throw SyncException("Snapshot-Seiten haben unterschiedliche Sync-Scopes")
        }
    }

    private fun parseKontakte(array: JSONArray): List<KontaktPayload> = buildList {
        for (index in 0 until array.length()) add(parseKontakt(array.getJSONObject(index)))
    }

    private fun parseKontakt(json: JSONObject): KontaktPayload {
        val kontakt = KontaktEntity(
            id = json.getLong("id"), typ = json.getString("typ"), anzeigename = json.getString("anzeigename"),
            vorname = json.nullableString("vorname"), nachname = json.nullableString("nachname"),
            funktion = json.nullableString("funktion"), organisation = json.nullableString("organisation"),
            email = json.nullableString("email"), erreichbarkeit = json.nullableString("erreichbarkeit"),
            notizen = json.nullableString("notizen"), aktiv = json.getBoolean("aktiv"),
            archiviert = json.getBoolean("archiviert"), version = json.getLong("version"),
        )
        val telefone = parseTelefone(json.getJSONArray("telefone"), kontakt.id)
        return KontaktPayload(kontakt, telefone)
    }

    private fun parseTelefone(array: JSONArray, kontaktId: Long): List<TelefonEntity> = buildList {
        for (index in 0 until array.length()) {
            val json = array.getJSONObject(index)
            val nummer = json.getString("nummer")
            add(TelefonEntity(
                id = json.getLong("id"), kontaktId = kontaktId, nummer = nummer,
                nummerNormalisiert = normalizeTelefonnummer(nummer), label = json.nullableString("label"),
                sort = json.getInt("sort"), bevorzugt = json.getBoolean("bevorzugt"),
                // Alte/teilweise Datensätze enthalten hier JSON null. Ein
                // fehlender SMS-Hinweis bedeutet sicherheitshalber "nein".
                smsEignung = json.optBoolean("sms_eignung", false),
            ))
        }
    }

    private fun parseZuordnungen(array: JSONArray): List<ZuordnungEntity> = buildList {
        for (index in 0 until array.length()) add(parseZuordnung(array.getJSONObject(index)))
    }

    private fun parseZuordnung(json: JSONObject) = ZuordnungEntity(
        id = json.getLong("id"), kontaktId = json.getLong("kontakt_id"), objektId = json.getLong("objekt_id"),
        rolle = json.getString("rolle"), sort = json.getInt("sort"),
        erreichbarkeit = json.nullableString("erreichbarkeit"),
    )

    private fun parseChanges(array: JSONArray): List<Change> = buildList {
        for (index in 0 until array.length()) {
            val json = array.getJSONObject(index)
            val entity = json.getString("entity")
            val operation = json.getString("operation")
            val id = json.getLong("id")
            when (entity to operation) {
                "kontakt" to "upsert" -> add(Change.KontaktUpsert(parseKontakt(json.getJSONObject("payload"))))
                "zuordnung" to "upsert" -> add(Change.ZuordnungUpsert(parseZuordnung(json.getJSONObject("payload"))))
                "kontakt" to "tombstone" -> add(Change.KontaktTombstone(id))
                "zuordnung" to "tombstone" -> add(Change.ZuordnungTombstone(id))
                else -> throw SyncException("Unbekannte Kontakt-Änderung: $entity/$operation")
            }
        }
    }

    private fun successStatus(cursor: Long, schemaVersion: Int, orgId: Long, baseUrl: String) =
        KontaktSyncStatusEntity(
            cursor = cursor, schemaVersion = schemaVersion, orgId = orgId, baseUrl = baseUrl,
            lastSuccessAtMs = System.currentTimeMillis(), lastError = null,
        )

    private suspend fun wipeDatabase(db: KontaktDatabase) {
        db.withTransaction {
            db.kontaktDao().deleteAllTelefone()
            db.kontaktDao().deleteAllZuordnungen()
            db.kontaktDao().deleteAllKontakte()
            db.syncStatusDao().deleteAll()
        }
    }

    private suspend fun fail(db: KontaktDatabase, message: String): Boolean {
        db.withTransaction {
            val old = db.syncStatusDao().get()
            db.syncStatusDao().put((old ?: KontaktSyncStatusEntity()).copy(lastError = message.take(MAX_ERROR_LENGTH)))
        }
        return false
    }

    private data class Envelope(val schemaVersion: Int, val orgId: Long, val cursor: Long)
    private data class KontaktPayload(val kontakt: KontaktEntity, val telefone: List<TelefonEntity>)
    private sealed interface Change {
        data class KontaktUpsert(val payload: KontaktPayload) : Change
        data class ZuordnungUpsert(val payload: ZuordnungEntity) : Change
        data class KontaktTombstone(val id: Long) : Change
        data class ZuordnungTombstone(val id: Long) : Change
    }

    private class SyncException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val MAX_ERROR_LENGTH = 1_000
    }
}

private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)
