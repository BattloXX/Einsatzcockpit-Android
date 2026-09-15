package cloud.einsatzleiter.smsgatewayplugin

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cloud.einsatzleiter.smsgatewayplugin.databinding.ActivityKontaktListBinding
import cloud.einsatzleiter.smsgatewayplugin.databinding.ItemKontaktBinding
import cloud.einsatzleiter.smsgatewayplugin.kontakte.KontaktDatabase
import cloud.einsatzleiter.smsgatewayplugin.kontakte.KontaktEntity
import cloud.einsatzleiter.smsgatewayplugin.kontakte.KontaktOfflineSyncWorker
import cloud.einsatzleiter.smsgatewayplugin.kontakte.KontaktSyncStatusEntity
import cloud.einsatzleiter.smsgatewayplugin.kontakte.normalizeTelefonnummer
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native entry point for contacts already stored by the offline sync. */
class KontaktListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityKontaktListBinding
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var searchJob: Job? = null
    private lateinit var database: KontaktDatabase
    private val adapter = KontaktAdapter { kontakt ->
        startActivity(Intent(this, KontaktDetailActivity::class.java).putExtra(EXTRA_KONTAKT_ID, kontakt.id))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKontaktListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Kontakte offline"
        database = KontaktDatabase.get(applicationContext)

        binding.kontaktList.layoutManager = LinearLayoutManager(this)
        binding.kontaktList.adapter = adapter
        binding.syncNowButton.setOnClickListener {
            KontaktOfflineSyncWorker.triggerImmediateSync(applicationContext)
            binding.kontaktSyncStatus.text = "Synchronisierung angefordert …"
        }
        binding.kontaktSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                observeContacts(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        observeContacts("")
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun observeContacts(query: String) {
        searchJob?.cancel()
        val contacts: Flow<List<KontaktEntity>> = if (query.isBlank()) {
            database.kontaktDao().list()
        } else {
            combine(
                database.kontaktDao().searchByName(query.trim()),
                database.kontaktDao().searchByNumber(normalizeTelefonnummer(query)),
            ) { byName, byNumber ->
                (byName + byNumber).distinctBy { it.id }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.anzeigename }.thenBy { it.id })
            }
        }
        searchJob = screenScope.launch {
            contacts.collect { items ->
                adapter.submitList(items)
                refreshStatus()
            }
        }
    }

    private fun refreshStatus() {
        screenScope.launch {
            val state = withContext(Dispatchers.IO) {
                val status = database.syncStatusDao().get()
                val count = database.kontaktDao().count()
                status to count
            }
            binding.kontaktSyncStatus.text = formatStatus(state.first, state.second)
        }
    }

    private fun formatStatus(status: KontaktSyncStatusEntity?, count: Int): String {
        val lastSuccess = status?.lastSuccessAtMs
        if (lastSuccess == null) return "Noch kein Offline-Datenstand – $count Kontakte"
        val time = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(lastSuccess))
        val base = "Offline verfügbar – $count Kontakte – Stand $time"
        return if (!status.lastError.isNullOrBlank()) "$base – letzter Versuch fehlgeschlagen" else base
    }

    override fun onDestroy() {
        screenScope.cancel()
        super.onDestroy()
    }

    private class KontaktAdapter(
        private val onClick: (KontaktEntity) -> Unit,
    ) : RecyclerView.Adapter<KontaktAdapter.ViewHolder>() {
        private var items: List<KontaktEntity> = emptyList()

        fun submitList(newItems: List<KontaktEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(ItemKontaktBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemKontaktBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(kontakt: KontaktEntity) {
                binding.kontaktName.text = kontakt.anzeigename
                val subtitle = listOfNotNull(kontakt.funktion, kontakt.organisation)
                    .filter { it.isNotBlank() }
                    .joinToString(" – ")
                binding.kontaktSubtitle.text = subtitle
                binding.kontaktSubtitle.visibility = if (subtitle.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
                binding.root.setOnClickListener { onClick(kontakt) }
            }
        }
    }

    companion object {
        const val EXTRA_KONTAKT_ID = "kontakt_id"
    }
}
