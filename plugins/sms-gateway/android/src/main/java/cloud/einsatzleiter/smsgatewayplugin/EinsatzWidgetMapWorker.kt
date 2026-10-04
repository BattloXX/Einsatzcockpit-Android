package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** Downloads and rasterizes the small OSM map used by the incident widget. */
class EinsatzWidgetMapWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    companion object {
        private const val WORK_NAME = "einsatz-widget-map"
        private const val ZOOM = 16
        private const val TILE_SIZE = 256
        private const val WIDTH = 512
        private const val HEIGHT = 320
        private const val FILE_PREFIX = "ec_widget_map_"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<EinsatzWidgetMapWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cachedFile(context: Context, lat: Double?, lng: Double?): File? {
            if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return null
            return File(context.cacheDir, fileName(lat, lng))
        }

        fun clearCache(context: Context) {
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith(FILE_PREFIX) }
                ?.forEach { it.delete() }
        }

        private fun fileName(lat: Double, lng: Double): String = String.format(
            Locale.US,
            "${FILE_PREFIX}z%d_%.5f_%.5f.png",
            ZOOM,
            lat,
            lng,
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun doWork(): Result {
        val state = EcpWidgetSupport.incident(applicationContext) ?: return Result.success()
        val lat = state.lat ?: return Result.success()
        val lng = state.lng ?: return Result.success()
        if (!lat.isFinite() || !lng.isFinite()) return Result.success()
        val target = cachedFile(applicationContext, lat, lng) ?: return Result.success()
        if (target.isFile && target.length() > 0L) return Result.success()

        return try {
            val bitmap = createMap(lat, lng) ?: return Result.success()
            val temporary = File(applicationContext.cacheDir, "${target.name}.tmp")
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bitmap.recycle()
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return Result.success()
            }
            EcpWidgetSupport.updateAll(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }

    private fun createMap(lat: Double, lng: Double): Bitmap? {
        val worldTiles = 2.0.pow(ZOOM.toDouble())
        val centerX = ((lng + 180.0) / 360.0) * worldTiles * TILE_SIZE
        val latitudeRadians = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val centerY = (1.0 - ln(tan(latitudeRadians) + 1.0 / kotlin.math.cos(latitudeRadians)) / Math.PI) /
            2.0 * worldTiles * TILE_SIZE
        val centerTileX = floor(centerX / TILE_SIZE).toInt()
        val centerTileY = floor(centerY / TILE_SIZE).toInt()
        val mosaic = Bitmap.createBitmap(TILE_SIZE * 3, TILE_SIZE * 3, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mosaic)

        for (row in -1..1) {
            for (column in -1..1) {
                val tileX = Math.floorMod(centerTileX + column, worldTiles.toInt())
                val tileY = (centerTileY + row).coerceIn(0, worldTiles.toInt() - 1)
                val tile = loadTile(tileX, tileY) ?: run {
                    mosaic.recycle()
                    return null
                }
                canvas.drawBitmap(tile, (column + 1) * TILE_SIZE.toFloat(), (row + 1) * TILE_SIZE.toFloat(), null)
                tile.recycle()
            }
        }

        val localX = centerX - centerTileX * TILE_SIZE + TILE_SIZE
        val localY = centerY - centerTileY * TILE_SIZE + TILE_SIZE
        val source = Rect(
            (localX - WIDTH / 2.0).toInt(),
            (localY - HEIGHT / 2.0).toInt(),
            (localX + WIDTH / 2.0).toInt(),
            (localY + HEIGHT / 2.0).toInt(),
        )
        val output = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(mosaic, source, Rect(0, 0, WIDTH, HEIGHT), Paint(Paint.FILTER_BITMAP_FLAG))
        mosaic.recycle()
        drawMarker(output)
        return output
    }

    private fun loadTile(x: Int, y: Int): Bitmap? {
        val request = Request.Builder()
            .url("https://tile.openstreetmap.org/$ZOOM/$x/$y.png")
            .header("User-Agent", "Einsatzcockpit/1.0 (+https://einsatzcockpit.com)")
            .header("Referer", "https://einsatzcockpit.com/")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.byteStream()?.use(BitmapFactory::decodeStream)
        }
    }

    private fun drawMarker(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(212, 34, 37) }
        canvas.drawCircle(centerX, centerY, 12f, white)
        canvas.drawCircle(centerX, centerY, 9f, red)
    }
}
