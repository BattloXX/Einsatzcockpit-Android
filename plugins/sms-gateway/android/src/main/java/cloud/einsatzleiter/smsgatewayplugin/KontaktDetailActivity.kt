package cloud.einsatzleiter.smsgatewayplugin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cloud.einsatzleiter.smsgatewayplugin.databinding.ActivityKontaktDetailBinding
import cloud.einsatzleiter.smsgatewayplugin.kontakte.KontaktDatabase
import cloud.einsatzleiter.smsgatewayplugin.kontakte.KontaktDetail
import cloud.einsatzleiter.smsgatewayplugin.kontakte.TelefonEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only detail view for one offline contact. */
class KontaktDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityKontaktDetailBinding
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKontaktDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val kontaktId = intent.getLongExtra(KontaktListActivity.EXTRA_KONTAKT_ID, NO_KONTAKT_ID)
        if (kontaktId == NO_KONTAKT_ID) {
            finish()
            return
        }
        screenScope.launch {
            val detail = withContext(Dispatchers.IO) {
                KontaktDatabase.get(applicationContext).kontaktDao().detail(kontaktId)
            }
            if (detail == null) finish() else showDetail(detail)
        }
    }

    private fun showDetail(detail: KontaktDetail) {
        val kontakt = detail.kontakt
        supportActionBar?.title = "Kontakt"
        binding.kontaktName.text = kontakt.anzeigename
        binding.kontaktFullName.text = listOfNotNull(kontakt.vorname, kontakt.nachname)
            .filter { it.isNotBlank() }.joinToString(" ")
        setOptionalText(binding.kontaktFullName, binding.kontaktFullName.text, "")
        setOptionalText(binding.kontaktFunction, kontakt.funktion, "Funktion: ")
        setOptionalText(binding.kontaktOrganisation, kontakt.organisation, "Organisation: ")
        setOptionalText(binding.kontaktReachability, kontakt.erreichbarkeit, "Erreichbarkeit: ")
        setOptionalText(binding.kontaktNotes, kontakt.notizen, "Notizen: ")

        val email = kontakt.email?.trim().orEmpty()
        binding.kontaktEmail.visibility = if (email.isBlank()) View.GONE else View.VISIBLE
        binding.kontaktEmail.text = email
        if (email.isNotBlank()) binding.kontaktEmail.setOnClickListener {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
        }

        binding.phoneHeading.visibility = if (detail.telefone.isEmpty()) View.GONE else View.VISIBLE
        detail.telefone.sortedBy { it.sort }.forEach { addPhone(it) }
        binding.assignmentHeading.visibility = if (detail.zuordnungen.isEmpty()) View.GONE else View.VISIBLE
        detail.zuordnungen.sortedBy { it.sort }.forEach { zuordnung ->
            addText(binding.assignmentContainer, "Objekt #${zuordnung.objektId} – Rolle: ${zuordnung.rolle}")
        }
    }

    private fun addPhone(telefon: TelefonEntity) {
        val label = telefon.label?.takeIf { it.isNotBlank() }?.let { "$it: " }.orEmpty()
        addText(binding.phoneContainer, "$label${telefon.nummer}", clickable = true) {
            val actions = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${telefon.nummer}"))
            startActivity(actions)
        }
        addText(binding.phoneContainer, "SMS an ${telefon.nummer}", clickable = true) {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${telefon.nummer}")))
        }
    }

    private fun setOptionalText(view: TextView, value: CharSequence?, prefix: String) {
        val text = value?.toString()?.trim().orEmpty()
        view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        view.text = if (text.isBlank()) "" else "$prefix$text"
    }

    private fun addText(container: LinearLayout, text: String, clickable: Boolean = false, onClick: (() -> Unit)? = null) {
        TextView(this).apply {
            this.text = text
            setPadding(0, 8, 0, 8)
            if (clickable) {
                setTextColor(resolveThemeColor(android.R.attr.textColorLink))
                setOnClickListener { onClick?.invoke() }
            }
            container.addView(this)
        }
    }

    private fun resolveThemeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        screenScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val NO_KONTAKT_ID = Long.MIN_VALUE
    }
}
