package dev.curiobot.log

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import dev.curiobot.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured dev logging. Every event goes to logcat, a ring buffer for the dev
 * panel, and a local queue file. A background loop uploads the queue in batches
 * to Supabase (insert-only) whenever there's a network, so nothing is lost when
 * the phone is offline. Photos are never logged.
 */
@SuppressLint("StaticFieldLeak")
object Telemetry {
    private const val TAG = "Curiobot"
    private const val BATCH = 200
    private const val MAX_STORED_BYTES = 20L * 1024 * 1024
    private const val MAX_STRING = 4000

    lateinit var deviceId: String; private set
    val sessionId: String = Instant.now().toString().substring(0, 19).replace(':', '-') + "-" +
        UUID.randomUUID().toString().take(4)
    val build = "${BuildConfig.VERSION_NAME}+${BuildConfig.BUILD_TIME}"

    private lateinit var app: Context
    private lateinit var dir: File
    private val seq = AtomicLong(0)
    private val lock = Any()
    private val recent = ArrayDeque<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vitalsJob: Job? = null

    @Volatile var lastUploadAt = 0L; private set
    @Volatile var lastUploadError: String? = null; private set
    @Volatile var uploadedTotal = 0L; private set

    val uploadEnabled get() = BuildConfig.DEV_LOGGING && Prefs.uploadLogs

    fun init(ctx: Context) {
        app = ctx.applicationContext
        dir = File(app.filesDir, "telemetry").apply { mkdirs() }
        deviceId = Prefs.deviceId
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crash(t.name, e) }
            previous?.uncaughtException(t, e)
        }
        log("app_start", JSONObject().apply {
            put("device", Build.MANUFACTURER + " " + Build.MODEL)
            put("soc", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "")
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("abi", Build.SUPPORTED_ABIS.joinToString(","))
            put("ram_mb", totalRamMb())
            put("build", build)
            put("upload_enabled", uploadEnabled)
        })
        scope.launch { uploadLoop() }
    }

    fun log(event: String, data: JSONObject = JSONObject(), level: String = "info") {
        val n = seq.incrementAndGet()
        val o = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("device_id", deviceId)
            put("session_id", sessionId)
            put("seq", n)
            put("build", build)
            put("level", level)
            put("event", event)
            put("data", trim(data))
        }
        val line = o.toString()
        Log.println(priority(level), TAG, "$event ${o.optJSONObject("data")}")
        synchronized(lock) {
            recent.addLast("${o.getString("ts").substring(11, 19)} [$level] $event ${o.optJSONObject("data")}")
            while (recent.size > 300) recent.removeFirst()
            runCatching { File(dir, "queue.jsonl").appendText(line + "\n") }
        }
    }

    fun warn(event: String, data: JSONObject = JSONObject()) = log(event, data, "warn")

    fun error(event: String, e: Throwable?, data: JSONObject = JSONObject()) {
        if (e != null) {
            data.put("error", e.javaClass.simpleName + ": " + e.message)
            data.put("stack", e.stackTraceToString().take(3000))
        }
        log(event, data, "error")
    }

    private fun crash(thread: String, e: Throwable) {
        log("crash", JSONObject().apply {
            put("thread", thread)
            put("error", e.javaClass.name + ": " + e.message)
            put("stack", e.stackTraceToString().take(6000))
        }, "crash")
    }

    fun recentLines(): List<String> = synchronized(lock) { recent.toList() }

    fun pendingCount(): Int = synchronized(lock) {
        dir.listFiles()?.filter { it.name.endsWith(".jsonl") }?.sumOf { f ->
            runCatching { f.useLines { it.count() } }.getOrDefault(0)
        } ?: 0
    }

    /** Upload whatever is queued right now (dev panel button, app pause). */
    fun flushSoon() {
        scope.launch { runCatching { uploadOnce() } }
    }

    fun startVitals() {
        if (vitalsJob?.isActive == true) return
        vitalsJob = scope.launch {
            while (isActive) {
                log("vitals", vitals())
                delay(60_000)
            }
        }
    }

    fun stopVitals() {
        vitalsJob?.cancel()
        vitalsJob = null
    }

    fun vitals(): JSONObject {
        val rt = Runtime.getRuntime()
        val am = app.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val bat = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pm = app.getSystemService(PowerManager::class.java)
        return JSONObject().apply {
            put("java_heap_mb", (rt.totalMemory() - rt.freeMemory()) / 1_048_576)
            put("native_heap_mb", Debug.getNativeHeapAllocatedSize() / 1_048_576)
            put("pss_mb", Debug.getPss() / 1024)
            put("avail_ram_mb", mi.availMem / 1_048_576)
            put("low_memory", mi.lowMemory)
            put("battery_pct", bat?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: -1)
            put("battery_temp_c", (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
            put("charging", (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            put("thermal", pm.currentThermalStatus)
        }
    }

    private suspend fun uploadLoop() {
        delay(3_000)
        while (scope.isActive) {
            runCatching { uploadOnce() }
            delay(15_000)
        }
    }

    private fun uploadOnce() {
        if (!uploadEnabled || !online()) return
        // Rotate the live queue so new events keep flowing while we upload.
        synchronized(lock) {
            val q = File(dir, "queue.jsonl")
            if (q.exists() && q.length() > 0) q.renameTo(File(dir, "batch-${System.currentTimeMillis()}.jsonl"))
        }
        enforceCap()
        val batches = dir.listFiles { f -> f.name.startsWith("batch-") }?.sortedBy { it.name } ?: return
        for (f in batches) {
            val lines = f.readLines().filter { it.isNotBlank() }
            var ok = true
            for (chunk in lines.chunked(BATCH)) {
                val events = JSONArray()
                for (l in chunk) runCatching { events.put(JSONObject(l)) }
                val err = post(JSONObject().put("events", events))
                if (err != null) {
                    ok = false
                    lastUploadError = err
                    Log.w(TAG, "log upload failed: $err")
                    break
                }
                uploadedTotal += chunk.size
            }
            if (!ok) return
            f.delete()
            lastUploadAt = System.currentTimeMillis()
            lastUploadError = null
        }
    }

    /** Returns null on success, or an error description. */
    private fun post(body: JSONObject): String? {
        val c = URL(BuildConfig.LOG_URL).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("apikey", BuildConfig.LOG_KEY)
            c.setRequestProperty("Authorization", "Bearer ${BuildConfig.LOG_KEY}")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code in 200..299) null
            else "http $code " + (c.errorStream?.bufferedReader()?.readText()?.take(300) ?: "")
        } catch (e: Exception) {
            e.javaClass.simpleName + ": " + e.message
        } finally {
            c.disconnect()
        }
    }

    /** Never let unsent logs eat the phone's storage. */
    private fun enforceCap() {
        val files = dir.listFiles { f -> f.name.startsWith("batch-") }?.sortedBy { it.name } ?: return
        var total = files.sumOf { it.length() }
        var dropped = 0
        for (f in files) {
            if (total <= MAX_STORED_BYTES) break
            total -= f.length(); f.delete(); dropped++
        }
        if (dropped > 0) warn("telemetry_dropped", JSONObject().put("files", dropped))
    }

    private fun online(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun totalRamMb(): Long {
        val am = app.getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / 1_048_576
    }

    private fun trim(o: JSONObject): JSONObject {
        for (k in o.keys().asSequence().toList()) {
            val v = o.opt(k)
            if (v is String && v.length > MAX_STRING) o.put(k, v.take(MAX_STRING) + "…")
        }
        return o
    }

    private fun priority(level: String) = when (level) {
        "debug" -> Log.DEBUG
        "warn" -> Log.WARN
        "error", "crash" -> Log.ERROR
        else -> Log.INFO
    }
}

/** Small settings store shared by the app and the dev panel. */
@SuppressLint("StaticFieldLeak")
object Prefs {
    private lateinit var app: Context
    private val sp by lazy { app.getSharedPreferences("curiobot", Context.MODE_PRIVATE) }

    fun init(ctx: Context) { app = ctx.applicationContext }

    val deviceId: String
        get() = sp.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("device_id", it).apply()
        }

    var uploadLogs: Boolean
        get() = sp.getBoolean("upload_logs", true)
        set(v) = sp.edit().putBoolean("upload_logs", v).apply()

    /** Hours become minutes: needs, boredom and naps move fast enough to test. */
    var testPace: Boolean
        get() = sp.getBoolean("test_pace", false)
        set(v) = sp.edit().putBoolean("test_pace", v).apply()

    /** Written synchronously so it survives a native crash mid-attempt. */
    var gpuAttemptInFlight: Boolean
        get() = sp.getBoolean("gpu_in_flight", false)
        set(v) { sp.edit().putBoolean("gpu_in_flight", v).commit() }

    var gpuBroken: Boolean
        get() = sp.getBoolean("gpu_broken", false)
        set(v) { sp.edit().putBoolean("gpu_broken", v).commit() }

    /** "auto" tries the GPU first, then CPU. */
    var backend: String
        get() = sp.getString("backend", "auto") ?: "auto"
        set(v) = sp.edit().putString("backend", v).apply()
}
