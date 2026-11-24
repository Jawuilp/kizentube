package com.jawuil.ytcensurado.ui

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.innertube.InnerTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class YouTubeLoginActivity : AppCompatActivity() {

    private lateinit var qrPanel: LinearLayout
    private lateinit var webPanel: LinearLayout
    private lateinit var ivQrCode: ImageView
    private lateinit var tvActivationCode: TextView
    private lateinit var tvVerificationUrl: TextView
    private lateinit var tvQrStatus: TextView
    private lateinit var qrProgress: ProgressBar

    private lateinit var webView: WebView
    private lateinit var tvWebStatus: TextView
    private lateinit var btnSaveAndImport: Button

    private var pollingJob: Job? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val accountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (!email.isNullOrBlank()) {
            switchToWebMode(prefillEmail = email)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F0F"))
        }

        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        // Top Bar (Adaptive: 2 tidy rows on portrait mobile, single wide bar on TV/landscape)
        val headerWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161616"))
            setPadding(16, 12, 16, 12)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnBack = Button(this).apply {
            text = "← Volver"
            isAllCaps = false
            setBackgroundColor(Color.parseColor("#2B2B2B"))
            setTextColor(Color.WHITE)
            setPadding(18, 8, 18, 8)
            setOnClickListener { finish() }
        }

        val tvHeaderTitle = TextView(this).apply {
            text = "  Conectar YouTube"
            setTextColor(Color.WHITE)
            textSize = if (isPortrait) 16f else 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnDeviceAccount = Button(this).apply {
            text = if (isPortrait) "👤 Cuentas" else "👤 Cuentas del Dispositivo"
            isAllCaps = false
            setBackgroundColor(Color.parseColor("#2B2B2B"))
            setTextColor(Color.WHITE)
            setPadding(14, 8, 14, 8)
            setOnClickListener { launchDeviceAccountPicker() }
        }

        val btnToggleWeb = Button(this).apply {
            text = if (isPortrait) "🌐 Web" else "🌐 Login Navegador"
            isAllCaps = false
            setBackgroundColor(Color.parseColor("#2B2B2B"))
            setTextColor(Color.WHITE)
            setPadding(14, 8, 14, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8 }
            setOnClickListener {
                if (webPanel.visibility == View.VISIBLE) {
                    switchToQrMode()
                } else {
                    switchToWebMode(null)
                }
            }
        }

        val btnUnlink = Button(this).apply {
            text = if (isPortrait) "🚪 Salir" else "🚪 Desvincular"
            isAllCaps = false
            setBackgroundColor(Color.parseColor("#991B1B"))
            setTextColor(Color.WHITE)
            setPadding(14, 8, 14, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8 }
            setOnClickListener {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                ConfigManager.youtubeCookie = ""
                ConfigManager.oauthAccessToken = ""
                ConfigManager.oauthRefreshToken = ""
                ConfigManager.oauthTokenExpiry = 0L
                ConfigManager.save()
                Toast.makeText(this@YouTubeLoginActivity, "Cuenta desvinculada", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        topBar.addView(btnBack)
        topBar.addView(tvHeaderTitle)

        if (isPortrait) {
            val secondRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 0)
            }
            secondRow.addView(btnDeviceAccount)
            secondRow.addView(btnToggleWeb)
            secondRow.addView(btnUnlink)
            headerWrapper.addView(topBar)
            headerWrapper.addView(secondRow)
        } else {
            topBar.addView(btnDeviceAccount)
            topBar.addView(btnToggleWeb)
            topBar.addView(btnUnlink)
            headerWrapper.addView(topBar)
        }

        // MODE 1: QR / TV CODE PAIRING PANEL (Vertical on Mobile, Side-by-side on TV)
        qrPanel = LinearLayout(this).apply {
            orientation = if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(if (isPortrait) 20 else 36, 20, if (isPortrait) 20 else 36, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val qrLeftCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(14, 14, 14, 14)
        }

        ivQrCode = ImageView(this).apply {
            val sizePx = if (isPortrait) 220 else 260
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        }
        qrLeftCard.addView(ivQrCode)

        val qrRightInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isPortrait) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            setPadding(if (isPortrait) 0 else 40, if (isPortrait) 20 else 0, 0, 0)
            layoutParams = if (isPortrait) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }

        val tvStep1 = TextView(this).apply {
            text = "1. Escanea el código QR con la cámara de tu celular\n    (donde ya tienes la cuenta de YouTube iniciada)"
            setTextColor(Color.parseColor("#F1F1F1"))
            textSize = 16f
        }

        tvVerificationUrl = TextView(this).apply {
            text = "2. O entra desde tu celular / PC a:  google.com/device"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 15f
            setPadding(0, 14, 0, 6)
        }

        tvActivationCode = TextView(this).apply {
            text = "Cargando código..."
            setTextColor(Color.parseColor("#FF0033"))
            textSize = 34f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, 6, 0, 14)
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        qrProgress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(32, 32)
        }

        tvQrStatus = TextView(this).apply {
            text = "  Esperando confirmación desde tu celular..."
            setTextColor(Color.parseColor("#4ADE80"))
            textSize = 14f
        }

        statusRow.addView(qrProgress)
        statusRow.addView(tvQrStatus)

        val btnNewCode = Button(this).apply {
            text = "🔄 Generar nuevo código QR"
            isAllCaps = false
            setBackgroundColor(Color.parseColor("#272727"))
            setTextColor(Color.WHITE)
            setPadding(20, 10, 20, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            setOnClickListener { startQrPairingFlow() }
        }

        qrRightInfo.addView(tvStep1)
        qrRightInfo.addView(tvVerificationUrl)
        qrRightInfo.addView(tvActivationCode)
        qrRightInfo.addView(statusRow)
        qrRightInfo.addView(btnNewCode)

        qrPanel.addView(qrLeftCard)
        qrPanel.addView(qrRightInfo)

        // MODE 2: WEBVIEW FALLBACK PANEL (for Device Account Picker or manual login)
        webPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val webActionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1D1D1D"))
            setPadding(20, 10, 20, 10)
        }

        tvWebStatus = TextView(this).apply {
            text = "Inicia sesión y luego pulsa 'Guardar e Importar Suscripciones'"
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnSaveAndImport = Button(this).apply {
            text = "✅ Guardar e Importar Suscripciones"
            isAllCaps = false
            setBackgroundColor(Color.parseColor("#16A34A"))
            setTextColor(Color.WHITE)
            setPadding(20, 10, 20, 10)
            setOnClickListener { captureCookiesAndImportSubscriptions() }
        }

        webActionBar.addView(tvWebStatus)
        webActionBar.addView(btnSaveAndImport)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val cookie = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: ""
                    if (cookie.contains("SAPISID=") || cookie.contains("__Secure-3PAPISID=")) {
                        tvWebStatus.text = "✅ Sesión detectada — Pulsa el botón verde"
                        tvWebStatus.setTextColor(Color.parseColor("#4ADE80"))
                    }
                }
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webPanel.addView(webActionBar)
        webPanel.addView(webView)

        root.addView(headerWrapper)
        root.addView(qrPanel)
        root.addView(webPanel)
        setContentView(root)

        startQrPairingFlow()
    }

    private fun launchDeviceAccountPicker() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )
        } else {
            @Suppress("DEPRECATION")
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                false,
                null,
                null,
                null,
                null
            )
        }
        accountPickerLauncher.launch(intent)
    }

    private fun switchToQrMode() {
        webPanel.visibility = View.GONE
        qrPanel.visibility = View.VISIBLE
        startQrPairingFlow()
    }

    private fun switchToWebMode(prefillEmail: String?) {
        pollingJob?.cancel()
        qrPanel.visibility = View.GONE
        webPanel.visibility = View.VISIBLE
        val loginUrl = if (!prefillEmail.isNullOrBlank()) {
            "https://accounts.google.com/ServiceLogin?service=youtube&Email=${URLEncoder.encode(prefillEmail, "UTF-8")}&continue=https://www.youtube.com"
        } else {
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com"
        }
        webView.loadUrl(loginUrl)
    }

    private fun startQrPairingFlow() {
        pollingJob?.cancel()
        tvActivationCode.text = "Generando..."
        tvQrStatus.text = "  Conectando con YouTube..."
        qrProgress.visibility = View.VISIBLE

        pollingJob = lifecycleScope.launch {
            val deviceInfo = withContext(Dispatchers.IO) { requestDeviceCode() }
            if (deviceInfo == null) {
                tvActivationCode.text = "Error"
                tvQrStatus.text = "  No se pudo generar el código. Usa 'Login Navegador'."
                qrProgress.visibility = View.GONE
                return@launch
            }

            val deviceCode = deviceInfo.getString("device_code")
            val userCode = deviceInfo.getString("user_code")
            val verificationUrl = deviceInfo.optString("verification_url", "https://www.google.com/device")
            val intervalSec = deviceInfo.optInt("interval", 5).coerceAtLeast(5)

            tvActivationCode.text = userCode
            tvVerificationUrl.text = "2. O entra desde tu celular / PC a:  ${verificationUrl.removePrefix("https://")}"
            tvQrStatus.text = "  Esperando que apruebes el código en tu celular..."

            val qrUrl = if (verificationUrl.contains("?")) {
                "$verificationUrl&user_code=$userCode"
            } else {
                "$verificationUrl?user_code=$userCode"
            }
            ivQrCode.setImageBitmap(generateQrBitmap(qrUrl, 300))

            // Poll /o/oauth2/token every intervalSec until user confirms on their phone
            while (isActive) {
                delay(intervalSec * 1000L)
                val tokenJson = withContext(Dispatchers.IO) { pollDeviceToken(deviceCode) } ?: continue
                if (tokenJson.has("access_token")) {
                    val access = tokenJson.getString("access_token")
                    val refresh = tokenJson.optString("refresh_token", "")
                    val expiresIn = tokenJson.optLong("expires_in", 3600L)

                    ConfigManager.oauthAccessToken = access
                    if (refresh.isNotBlank()) {
                        ConfigManager.oauthRefreshToken = refresh
                    }
                    ConfigManager.oauthTokenExpiry = System.currentTimeMillis() + expiresIn * 1000L
                    ConfigManager.save()

                    tvQrStatus.text = "  ✅ ¡Cuenta conectada! Importando suscripciones..."
                    val count = withContext(Dispatchers.IO) {
                        InnerTubeClient.importAccountSubscriptionsToWhitelist()
                    }
                    ConfigManager.pendingRemoteCommand = "RELOAD_FEED"
                    Toast.makeText(
                        this@YouTubeLoginActivity,
                        "✅ Cuenta vinculada por QR ($count canales importados)",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    break
                } else {
                    val err = tokenJson.optString("error", "")
                    if (err == "expired_token" || err == "access_denied") {
                        tvQrStatus.text = "  Código expirado o cancelado. Pulsa 'Generar nuevo código QR'."
                        qrProgress.visibility = View.GONE
                        break
                    }
                }
            }
        }
    }

    private fun requestDeviceCode(): JSONObject? {
        return try {
            val scope = URLEncoder.encode(
                "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content",
                "UTF-8"
            )
            val bodyStr = "client_id=${InnerTubeClient.TV_OAUTH_CLIENT_ID}&scope=$scope&device_model=${Build.MODEL}"
            val req = Request.Builder()
                .url("https://www.youtube.com/o/oauth2/device/code")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(bodyStr.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: return null
                if (!resp.isSuccessful) return null
                JSONObject(raw)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun pollDeviceToken(deviceCode: String): JSONObject? {
        return try {
            val bodyStr = "client_id=${InnerTubeClient.TV_OAUTH_CLIENT_ID}&code=$deviceCode&grant_type=http://oauth.net/grant_type/device/1.0"
            val req = Request.Builder()
                .url("https://www.youtube.com/o/oauth2/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(bodyStr.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: return null
                JSONObject(raw)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun generateQrBitmap(content: String, size: Int): Bitmap {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun captureCookiesAndImportSubscriptions() {
        CookieManager.getInstance().flush()
        val ytCookie = CookieManager.getInstance().getCookie("https://www.youtube.com")
            ?: CookieManager.getInstance().getCookie("https://m.youtube.com")
            ?: ""

        if (!ytCookie.contains("SAPISID=") && !ytCookie.contains("__Secure-3PAPISID=")) {
            Toast.makeText(
                this,
                "Aún no has terminado de iniciar sesión en Google/YouTube",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        ConfigManager.youtubeCookie = ytCookie
        ConfigManager.save()
        btnSaveAndImport.isEnabled = false
        tvWebStatus.text = "⏳ Importando canales suscritos a la Lista Blanca..."

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                InnerTubeClient.importAccountSubscriptionsToWhitelist()
            }
            ConfigManager.pendingRemoteCommand = "RELOAD_FEED"
            Toast.makeText(
                this@YouTubeLoginActivity,
                "✅ Cuenta vinculada ($count canales importados a Lista Blanca)",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        try {
            webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
