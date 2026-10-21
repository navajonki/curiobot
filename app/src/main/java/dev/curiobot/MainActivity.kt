package dev.curiobot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import dev.curiobot.log.Telemetry
import dev.curiobot.ui.LcdView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The device: a 1-bit screen and three buttons. Hold the screen to open the
 * dev panel.
 */
class MainActivity : ComponentActivity(), Game.Host {
    private val game by lazy { (application as CuriobotApp).game }
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var pendingPermission: ((Boolean) -> Unit)? = null
    private lateinit var lcdView: LcdView
    private var frames = 0
    private var framesSince = 0L

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Telemetry.log("camera_permission", JSONObject().put("granted", granted))
        pendingPermission?.invoke(granted)
        pendingPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        game.host = this
        game.start()
        if (intent?.getBooleanExtra("selftest", false) == true) {
            // Used by automated device tests: run the photo pipeline once the models are up.
            lcdView.postDelayed({ startActivity(Intent(this, DevActivity::class.java).putExtra("selftest", true)) }, 1500)
        }
    }

    override fun onResume() {
        super.onResume()
        Telemetry.log("app_resume", JSONObject())
        Telemetry.startVitals()
        game.resume()
    }

    override fun onPause() {
        Telemetry.log("app_pause", JSONObject())
        Telemetry.stopVitals()
        game.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (game.host === this) game.host = null
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val shell = ContextCompat.getColor(this, R.color.shell)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(shell)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(40), dp(18), dp(24))
        }
        val bezel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { setColor(0xFF15141A.toInt()); cornerRadius = dp(14).toFloat() }
        }
        lcdView = LcdView(this) { game.ui to game.cameraFrame }
        lcdView.setOnLongClickListener {
            startActivity(Intent(this, DevActivity::class.java)); true
        }
        bezel.addView(lcdView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(bezel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "CURIOBOT"
            setTextColor(0xFF8D8A99.toInt())
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(14), 0, dp(28))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((label, b) in listOf("◀" to Game.Button.LEFT, "●" to Game.Button.SELECT, "▶" to Game.Button.RIGHT)) {
            val btn = Button(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.button_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@MainActivity, R.color.button))
                }
                stateListAnimator = null
                setOnClickListener { game.press(b) }
                contentDescription = when (b) { Game.Button.LEFT -> "Previous"; Game.Button.SELECT -> "Select"; Game.Button.RIGHT -> "Next" }
            }
            val size = if (b == Game.Button.SELECT) dp(84) else dp(68)
            row.addView(btn, LinearLayout.LayoutParams(size, size).apply { setMargins(dp(12), 0, dp(12), 0) })
        }
        root.addView(row)
        root.addView(TextView(this).apply {
            text = "hold the screen for the dev panel"
            setTextColor(0xFF5E5B69.toInt())
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(28), 0, 0)
        })
        return root
    }

    // ---------- Game.Host ----------

    override fun haptic() {
        lcdView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun ensureCameraPermission(onResult: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
        } else {
            pendingPermission = onResult
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.onFailure { Telemetry.error("camera_provider_failed", it) }.getOrNull()
                ?: return@addListener
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            frames = 0
            framesSince = System.currentTimeMillis()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    val raw = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    val upright = if (rot == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                        Matrix().apply { postRotate(rot.toFloat()) }, true).also { raw.recycle() }
                    game.onCameraFrame(upright)
                    frames++
                    val now = System.currentTimeMillis()
                    if (now - framesSince > 10_000) {
                        Telemetry.log("camera_stats", JSONObject().put("fps", frames * 1000.0 / (now - framesSince))
                            .put("w", upright.width).put("h", upright.height))
                        frames = 0; framesSince = now
                    }
                } catch (e: Throwable) {
                    Telemetry.error("camera_frame_failed", e)
                } finally {
                    image.close()
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            }.onFailure { Telemetry.error("camera_bind_failed", it) }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
