package cloud.einsatzleiter.smsgatewayplugin

import org.json.JSONObject

/** Parsed duty-state response. Presence is retained so malformed payloads cannot clear widget state. */
data class DutyStateResponse(
    val dutyActive: Boolean,
    val hasIncident: Boolean,
    val incident: EinsatzLiveState?,
    val hasGslQueue: Boolean,
    val gslQueue: GslQueueState?,
    val hasGslLive: Boolean,
    val gslLive: GslLiveState?,
) {
    companion object {
        fun parse(body: String): DutyStateResponse? {
            val root = try { JSONObject(body) } catch (_: Exception) { return null }
            val hasIncident = !root.isNull("incident")
            val hasGslQueue = !root.isNull("my_lage_queue")
            val hasGslLive = !root.isNull("lage")
            return DutyStateResponse(
                dutyActive = root.optBoolean("duty_active", false),
                hasIncident = hasIncident,
                incident = if (hasIncident) try { EinsatzLiveState.fromJson(root) } catch (_: Exception) { null } else null,
                hasGslQueue = hasGslQueue,
                gslQueue = if (hasGslQueue) try { GslQueueState.fromJson(root) } catch (_: Exception) { null } else null,
                hasGslLive = hasGslLive,
                gslLive = if (hasGslLive) try { GslLiveState.fromJson(root) } catch (_: Exception) { null } else null,
            )
        }
    }

    /** Only a response whose optional states parsed successfully may replace stored widget state. */
    fun applyToWidget(context: android.content.Context) {
        if (!hasIncident) EcpWidgetSupport.clearIncident(context) else incident?.let {
            EcpWidgetSupport.saveIncident(context, it)
        }
        if (!hasGslQueue) EcpWidgetSupport.clearGslQueue(context) else gslQueue?.let {
            EcpWidgetSupport.saveGslQueue(context, it)
        }
        if (!hasGslLive) EcpWidgetSupport.clearGslLive(context) else gslLive?.let {
            EcpWidgetSupport.saveGslLive(context, it)
        }
    }
}
