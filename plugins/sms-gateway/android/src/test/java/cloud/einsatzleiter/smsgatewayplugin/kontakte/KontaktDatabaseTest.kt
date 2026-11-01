package cloud.einsatzleiter.smsgatewayplugin.kontakte

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KontaktDatabaseTest {
    @Test
    fun opensDatabaseAndRoundTripsKontaktWithRelations() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            KontaktDatabase::class.java,
        ).build()
        try {
            val kontakt = KontaktEntity(
                id = 1L,
                typ = "person",
                anzeigename = "Anna Test",
                vorname = "Anna",
                nachname = "Test",
                funktion = null,
                organisation = null,
                email = null,
                erreichbarkeit = null,
                notizen = null,
                aktiv = true,
                archiviert = false,
                version = 1L,
            )
            val telefon = TelefonEntity(2L, kontakt.id, "+43 123", "+43123", "Mobil", 0, true, true)
            val zuordnung = ZuordnungEntity(3L, kontakt.id, 4L, "Kontakt", 0, null)
            val dao = database.kontaktDao()
            dao.insertKontakte(listOf(kontakt))
            dao.insertTelefone(listOf(telefon))
            dao.insertZuordnungen(listOf(zuordnung))

            assertEquals(1, dao.count())
            assertEquals(KontaktDetail(kontakt, listOf(telefon), listOf(zuordnung)), dao.detail(kontakt.id))

            val status = KontaktSyncStatusEntity(cursor = 5L, schemaVersion = 1, orgId = 6L)
            database.syncStatusDao().put(status)
            assertEquals(status, database.syncStatusDao().get())
        } finally {
            database.close()
        }
    }
}
