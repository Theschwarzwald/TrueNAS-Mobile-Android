package com.example.truenasapp.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------- models

data class SystemInfo(
    val hostname: String,
    val version: String,
    val model: String,
    val uptimeSeconds: Long,
    val memoryBytes: Long,
    val cores: Int,
)

data class Pool(val name: String, val status: String, val healthy: Boolean, val size: Long, val allocated: Long)

data class NasAlert(val level: String, val text: String)

data class FileEntry(val name: String, val path: String, val isDir: Boolean, val size: Long)

data class NasApp(
    val name: String,
    val state: String,
    val version: String,
    val webUrl: String?,
    val iconUrl: String?,
    val upgradeAvailable: Boolean,
    val latestVersion: String,
)

/** An app you can install from the TrueNAS app store (catalog). */
data class CatalogApp(
    val name: String,
    val title: String,
    val description: String,
    val iconUrl: String?,
    val train: String,
    val version: String,
    val installed: Boolean,
    val categories: List<String>,
)

/** One snapshot of the live numbers that drive the dashboard charts. */
data class Realtime(
    val cpuPercent: Double? = null,
    val cpuTempC: Double? = null,
    val memTotal: Double? = null,
    val memAvailable: Double? = null,
    val arcSize: Double? = null,
    val netRxBytesPerSec: Double? = null,
    val netTxBytesPerSec: Double? = null,
) {
    val memPercent: Double?
        get() = if (memTotal != null && memAvailable != null && memTotal > 0)
            ((1 - memAvailable / memTotal) * 100).coerceIn(0.0, 100.0) else null
}

// ---------------------------------------------------------------- API

/** Friendly Kotlin functions on top of the raw JSON-RPC methods. */
class TrueNasApi(private val client: TrueNasClient, private val store: SecureStore) {

    suspend fun systemInfo(): SystemInfo {
        val o = client.call("system.info") as JSONObject
        return SystemInfo(
            hostname = o.str("hostname"),
            version = o.str("version"),
            model = o.str("system_product").ifBlank { o.str("model") },
            uptimeSeconds = o.optDouble("uptime_seconds", 0.0).toLong(),
            memoryBytes = o.optLong("physmem"),
            cores = o.optInt("cores"),
        )
    }

    suspend fun pools(): List<Pool> =
        (client.call("pool.query") as JSONArray).objects().map {
            Pool(
                name = it.str("name"),
                status = it.str("status"),
                healthy = it.optBoolean("healthy", true),
                size = it.optLong("size"),
                allocated = it.optLong("allocated"),
            )
        }

    suspend fun alerts(): List<NasAlert> =
        (client.call("alert.list") as JSONArray).objects()
            .filter { !it.optBoolean("dismissed", false) }
            .map { NasAlert(it.str("level"), it.str("formatted").ifBlank { it.str("text") }) }

    suspend fun listDir(path: String): List<FileEntry> {
        val raw = client.call(
            "filesystem.listdir",
            listOf(path, emptyList<Any>(), mapOf("limit" to 5000)),
        ) as JSONArray
        return raw.objects()
            .map { FileEntry(it.str("name"), it.str("path"), it.str("type") == "DIRECTORY", it.optLong("size")) }
            .filterNot { path == "/mnt" && it.name.startsWith(".") } // hide .ix-apps etc. at the top
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    suspend fun apps(): List<NasApp> {
        val host = store.serverUrl?.let { Uri.parse(it).host } ?: ""
        return (client.call("app.query") as JSONArray).objects().map { app ->
            val portals = app.optJSONObject("portals")
            val allPortals = portals?.keys()?.asSequence()
                ?.map { portals.str(it).replace("0.0.0.0", host) }?.filter { it.isNotBlank() }?.toList().orEmpty()
            com.example.truenasapp.ui.ContainerAccess.rememberPortals(app.str("name"), allPortals)
            val web = portals?.keys()?.asSequence()?.firstOrNull()?.let { portals.str(it) }
            NasApp(
                name = app.str("name"),
                state = app.str("state"),
                version = app.str("human_version").ifBlank { app.str("version") },
                webUrl = web?.ifBlank { null }?.replace("0.0.0.0", host),
                iconUrl = absoluteUrl(app.optJSONObject("metadata")?.str("icon")),
                upgradeAvailable = app.optBoolean("upgrade_available", false),
                latestVersion = app.str("latest_version"),
            )
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun startApp(name: String) = runJob("app.start", listOf(name))
    suspend fun stopApp(name: String) = runJob("app.stop", listOf(name))

    /** Stops the app, then starts it again. */
    suspend fun restartApp(name: String) {
        stopApp(name)
        startApp(name)
    }

    /** TrueNAS "redeploy": stops the app, pulls its images again, and starts it. */
    suspend fun redeployApp(name: String) = runJob("app.redeploy", listOf(name), 20 * 60_000L)

    /** Removes an app. [deleteData] also deletes the ix volumes TrueNAS created for it. */
    suspend fun deleteApp(name: String, deleteData: Boolean) = runJob(
        "app.delete",
        listOf(name, mapOf("remove_images" to true, "remove_ix_volumes" to deleteData)),
        10 * 60_000L,
    )

    /** Updates an installed app to the newest version in the catalog. */
    suspend fun upgradeApp(name: String) =
        runJob("app.upgrade", listOf(name, mapOf("app_version" to "latest")), 20 * 60_000L)

    /** Everything in the app store, sorted by title. */
    suspend fun catalog(): List<CatalogApp> {
        val raw = client.call(
            "app.available",
            listOf(emptyList<Any>(), mapOf("order_by" to listOf("title"))),
            timeoutMs = 60_000,
        ) as JSONArray
        return raw.objects().map { a ->
            val cats = a.optJSONArray("categories")
            CatalogApp(
                name = a.str("name"),
                title = a.str("title").ifBlank { a.str("name") },
                description = a.str("description"),
                iconUrl = absoluteUrl(a.str("icon_url").ifBlank { a.str("icon") }),
                train = a.str("train"),
                version = a.str("latest_app_version").ifBlank { a.str("latest_version") },
                installed = a.optBoolean("installed", false),
                categories = if (cats == null) emptyList() else (0 until cats.length()).map { cats.optString(it) },
            )
        }
    }

    /**
     * Installs a store app with its default settings. Apps that need extra answers
     * (passwords, folders...) will be rejected by TrueNAS with a message; those can be set
     * up in Expert mode.
     */
    suspend fun installApp(appName: String, app: CatalogApp, values: Map<String, Any?> = emptyMap()) = runJob(
        "app.create",
        listOf(
            mapOf(
                "app_name" to appName,
                "catalog_app" to app.name,
                "train" to app.train,
                "version" to "latest",
                "values" to values,
            )
        ),
        20 * 60_000L,
    )

    /**
     * The install form for a store app, the same questions the desktop web UI shows:
     * returns the version's `schema` object (`groups` + `questions`).
     */
    suspend fun appInstallSchema(app: CatalogApp): JSONObject {
        val details = client.call(
            "catalog.get_app_details",
            listOf(app.name, mapOf("train" to app.train)),
            timeoutMs = 90_000,
        ) as JSONObject
        details.optJSONObject("schema")?.let { if (it.has("questions")) return it }
        val versions = details.optJSONObject("versions") ?: throw TrueNasException("This app has no install form")
        val latest = details.str("latest_version").takeIf { versions.has(it) }
            ?: versions.keys().asSequence().maxWithOrNull(::compareVersions)
            ?: throw TrueNasException("This app has no versions to install")
        return versions.getJSONObject(latest).optJSONObject("schema")
            ?: throw TrueNasException("This app has no install form")
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun absoluteUrl(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("/") -> (store.serverUrl ?: "") + url
        else -> url
    }

    /** Many TrueNAS actions are background "jobs": we get an id back and poll until done. */
    private suspend fun runJob(method: String, params: List<Any?>, timeoutMs: Long = 5 * 60_000L) {
        val jobId = (client.call(method, params) as Number).toInt()
        withTimeout(timeoutMs) {
            while (true) {
                val jobs = client.call("core.get_jobs", listOf(listOf(listOf("id", "=", jobId)))) as JSONArray
                val job = jobs.optJSONObject(0)
                when (job?.str("state")) {
                    "SUCCESS" -> return@withTimeout
                    "FAILED", "ABORTED" -> throw TrueNasException(job.str("error").ifBlank { "Task failed" })
                }
                delay(1_500)
            }
        }
    }

    /** Live CPU / RAM numbers. Subscribes while collected, unsubscribes when stopped. */
    fun realtimeUpdates(): Flow<Realtime> = client.events
        .filter { it.optString("collection") == "reporting.realtime" }
        .mapNotNull { it.optJSONObject("fields")?.let(::parseRealtime) }
        .onStart { client.subscribe("reporting.realtime") }
        .onCompletion { client.unsubscribe("reporting.realtime") }

    private fun parseRealtime(fields: JSONObject): Realtime {
        // The field layout has shifted a bit between TrueNAS releases, so read it defensively.
        val cpu = fields.optJSONObject("cpu")
        val cpuUsage = listOfNotNull(cpu?.optJSONObject("cpu"), cpu?.optJSONObject("average"), cpu)
            .firstNotNullOfOrNull { it.num("usage") }
        val cpuTemp = cpu.num("temperature_celsius")
            ?: cpu?.optJSONObject("cpu").num("temp")
            ?: cpu.num("temperature")

        val mem = fields.optJSONObject("memory")

        var rx = 0.0
        var tx = 0.0
        var sawNet = false
        val ifaces = fields.optJSONObject("interfaces")
        ifaces?.keys()?.forEach { name ->
            // Count real network ports only, not app/VM bridges (they'd double-count the same traffic).
            val virtual = name == "lo" || listOf("veth", "docker", "br", "vnet", "kube", "cni", "flannel", "virbr")
                .any { name.startsWith(it) }
            if (virtual) return@forEach
            val o = ifaces.optJSONObject(name) ?: return@forEach
            o.num("received_bytes_rate")?.let { rx += it; sawNet = true }
            o.num("sent_bytes_rate")?.let { tx += it; sawNet = true }
        }

        return Realtime(
            cpuPercent = cpuUsage?.coerceIn(0.0, 100.0),
            cpuTempC = cpuTemp,
            memTotal = mem.num("physical_memory_total"),
            memAvailable = mem.num("physical_memory_available"),
            arcSize = mem.num("arc_size"),
            netRxBytesPerSec = if (sawNet) rx else null,
            netTxBytesPerSec = if (sawNet) tx else null,
        )
    }

    /** Reads a number that might be a plain number, a list, or a per-core map (averaged). */
    private fun JSONObject?.num(key: String): Double? {
        val v = this?.opt(key) ?: return null
        val values: List<Double> = when (v) {
            is Number -> listOf(v.toDouble())
            is JSONArray -> (0 until v.length()).mapNotNull { (v.opt(it) as? Number)?.toDouble() }
            is JSONObject -> v.keys().asSequence().mapNotNull { (v.opt(it) as? Number)?.toDouble() }.toList()
            else -> emptyList()
        }.filter { !it.isNaN() }
        return if (values.isEmpty()) null else values.average()
    }

    /** Downloads a file from the NAS into the phone's Downloads folder. Returns the file name. */
    suspend fun downloadToDevice(context: Context, entry: FileEntry): String = withContext(Dispatchers.IO) {
        val result = client.call("core.download", listOf("filesystem.get", listOf(entry.path), entry.name)) as JSONArray
        val url = (store.serverUrl ?: throw TrueNasException("No server")) + result.getString(1)

        client.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw TrueNasException("Download failed (HTTP ${resp.code})")
            val body = resp.body ?: throw TrueNasException("Empty download")

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, entry.name)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw TrueNasException("Couldn't create the file on your phone")
            try {
                resolver.openOutputStream(uri)!!.use { out -> body.byteStream().copyTo(out) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        entry.name
    }
}

// ---------------------------------------------------------------- JSON helpers

/** optString returns the text "null" for JSON nulls; this returns "" instead. */
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
