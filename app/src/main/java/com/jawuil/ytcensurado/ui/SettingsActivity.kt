package com.jawuil.ytcensurado.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jawuil.ytcensurado.R
import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.model.AutoplayPolicy
import com.jawuil.ytcensurado.model.LlmProvider
import com.jawuil.ytcensurado.server.PcControlServer
import com.jawuil.ytcensurado.updater.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvSettingsPcUrl: TextView
    private lateinit var tvAccountStatus: TextView
    private lateinit var btnConnectAccount: Button
    private lateinit var swWhitelistMode: Switch
    private lateinit var swBlockShorts: Switch
    private lateinit var swSearchEnabled: Switch
    private lateinit var swSpanishOnly: Switch
    private lateinit var swDeArrow: Switch
    private lateinit var swLockSubscriptions: Switch
    private lateinit var tvAutoplayDesc: TextView
    private lateinit var btnCycleAutoplay: Button
    private lateinit var btnClearCache: Button
    private lateinit var tvAppVersionInfo: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var btnCheckUpdate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tvSettingsPcUrl = findViewById(R.id.tvSettingsPcUrl)
        tvAccountStatus = findViewById(R.id.tvAccountStatus)
        btnConnectAccount = findViewById(R.id.btnConnectAccount)
        swWhitelistMode = findViewById(R.id.swWhitelistMode)
        swBlockShorts = findViewById(R.id.swBlockShorts)
        swSearchEnabled = findViewById(R.id.swSearchEnabled)
        swSpanishOnly = findViewById(R.id.swSpanishOnly)
        swDeArrow = findViewById(R.id.swDeArrow)
        swLockSubscriptions = findViewById(R.id.swLockSubscriptions)
        tvAutoplayDesc = findViewById(R.id.tvAutoplayDesc)
        btnCycleAutoplay = findViewById(R.id.btnCycleAutoplay)
        btnClearCache = findViewById(R.id.btnClearCache)
        tvAppVersionInfo = findViewById(R.id.tvAppVersionInfo)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)

        findViewById<Button>(R.id.btnSettingsBack).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btnChangePin).setOnClickListener {
            promptChangePin()
        }

        btnConnectAccount.setOnClickListener {
            startActivity(Intent(this, YouTubeLoginActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.rowWhitelistMode).setOnClickListener {
            ConfigManager.whitelistOnlyMode = !ConfigManager.whitelistOnlyMode
            saveAndMarkReload()
        }

        findViewById<LinearLayout>(R.id.rowBlockShorts).setOnClickListener {
            ConfigManager.blockAllShorts = !ConfigManager.blockAllShorts
            saveAndMarkReload()
        }

        findViewById<LinearLayout>(R.id.rowSearchEnabled).setOnClickListener {
            ConfigManager.searchEnabledOnTv = !ConfigManager.searchEnabledOnTv
            saveAndMarkReload()
        }

        findViewById<LinearLayout>(R.id.rowSpanishOnly).setOnClickListener {
            ConfigManager.spanishOnlyOutsideWhitelist = !ConfigManager.spanishOnlyOutsideWhitelist
            ConfigManager.verdictCache.clear()
            saveAndMarkReload()
        }

        findViewById<LinearLayout>(R.id.rowDeArrow).setOnClickListener {
            ConfigManager.deArrowEnabled = !ConfigManager.deArrowEnabled
            saveAndMarkReload()
        }

        findViewById<LinearLayout>(R.id.rowLockSubscriptions).setOnClickListener {
            ConfigManager.lockSubscriptions = !ConfigManager.lockSubscriptions
            saveAndMarkReload()
        }

        btnCycleAutoplay.setOnClickListener {
            val policies = AutoplayPolicy.values()
            val nextIdx = (policies.indexOf(ConfigManager.autoplayPolicy) + 1) % policies.size
            ConfigManager.autoplayPolicy = policies[nextIdx]
            saveAndMarkReload()
        }

        findViewById<Button>(R.id.btnAddWhitelist).setOnClickListener {
            promptAddWhitelistChannel()
        }

        findViewById<Button>(R.id.btnAddKeyword).setOnClickListener {
            promptAddBlockedKeyword()
        }

        findViewById<Button>(R.id.btnConfigureAi).setOnClickListener {
            promptConfigureAi()
        }

        btnClearCache.setOnClickListener {
            ConfigManager.verdictCache.clear()
            ConfigManager.saveCache()
            refreshUi()
            Toast.makeText(this, "Caché de IA limpiada", Toast.LENGTH_SHORT).show()
        }

        btnCheckUpdate.setOnClickListener {
            checkAndInstallUpdate()
        }
    }

    override fun onResume() {
        super.onResume()
        PcControlServer.startServer(ConfigManager.serverPort)
        refreshUi()
    }

    private fun saveAndMarkReload() {
        ConfigManager.save()
        ConfigManager.pendingRemoteCommand = "RELOAD_FEED"
        refreshUi()
    }

    private fun refreshUi() {
        val ip = PcControlServer.getLocalIpAddress()
        tvSettingsPcUrl.text = "Panel Web PC: http://$ip:${ConfigManager.serverPort} (PIN: ${ConfigManager.pin})"

        if (ConfigManager.isAccountConnected()) {
            tvAccountStatus.text = "✅ Cuenta vinculada (${ConfigManager.whitelistedChannels.size} canales en Lista Blanca)"
            tvAccountStatus.setTextColor(Color.parseColor("#4ADE80"))
            btnConnectAccount.text = "Gestionar / Re-importar"
        } else {
            tvAccountStatus.text = "No vinculada — Conecta su cuenta para importar sus suscripciones a la Lista Blanca"
            tvAccountStatus.setTextColor(Color.parseColor("#AAAAAA"))
            btnConnectAccount.text = "Conectar Cuenta"
        }

        swWhitelistMode.isChecked = ConfigManager.whitelistOnlyMode
        swBlockShorts.isChecked = ConfigManager.blockAllShorts
        swSearchEnabled.isChecked = ConfigManager.searchEnabledOnTv
        swSpanishOnly.isChecked = ConfigManager.spanishOnlyOutsideWhitelist
        swDeArrow.isChecked = ConfigManager.deArrowEnabled
        swLockSubscriptions.isChecked = ConfigManager.lockSubscriptions

        tvAutoplayDesc.text = when (ConfigManager.autoplayPolicy) {
            AutoplayPolicy.SAME_CHANNEL_OR_WHITELIST -> "Mismo canal o canales de Lista Blanca (Recomendado)"
            AutoplayPolicy.SAME_CHANNEL_ONLY -> "Solo videos del mismo canal actual"
            AutoplayPolicy.ANY_FILTERED -> "Cualquier video aprobado por Heurística + Juez IA"
            AutoplayPolicy.DISABLED -> "Desactivado (se detiene al terminar el video)"
        }

        btnClearCache.text = "🧹 Limpiar Caché (${ConfigManager.verdictCache.size})"
        val ver = AppUpdater.getCurrentVersionName(this)
        tvAppVersionInfo.text = "KizenTube v$ver • GitHub: ${ConfigManager.updateRepo}"
    }

    private fun checkAndInstallUpdate() {
        btnCheckUpdate.isEnabled = false
        tvUpdateStatus.text = "⏳ Buscando última versión en ${ConfigManager.updateRepo}..."

        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) {
                AppUpdater.checkLatestRelease(this@SettingsActivity)
            }
            btnCheckUpdate.isEnabled = true

            if (release == null) {
                tvUpdateStatus.text = "No se encontró un APK en Releases de ${ConfigManager.updateRepo} (o sin conexión)."
                Toast.makeText(this@SettingsActivity, "No se encontraron actualizaciones publicadas", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val currentVer = AppUpdater.getCurrentVersionName(this@SettingsActivity)
            val statusMsg = if (release.isNewer) {
                "✨ Nueva versión disponible: ${release.tagName} (tienes v$currentVer)"
            } else {
                "✅ Ya tienes la última versión (v$currentVer / ${release.tagName}). Puedes reinstalar si quieres."
            }
            tvUpdateStatus.text = statusMsg

            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Actualización ${release.tagName}")
                .setMessage("$statusMsg\n\n${release.notes.take(400)}")
                .setPositiveButton(if (release.isNewer) "⬇️ Descargar e Instalar" else "Reinstalar") { _, _ ->
                    downloadAndInstall(release.apkDownloadUrl, release.tagName)
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private fun downloadAndInstall(url: String, tagName: String) {
        btnCheckUpdate.isEnabled = false
        tvUpdateStatus.text = "⬇️ Descargando $tagName (0%)..."

        lifecycleScope.launch {
            val apkFile = withContext(Dispatchers.IO) {
                AppUpdater.downloadApk(this@SettingsActivity, url) { pct ->
                    runOnUiThread {
                        tvUpdateStatus.text = "⬇️ Descargando $tagName ($pct%)..."
                    }
                }
            }
            btnCheckUpdate.isEnabled = true

            if (apkFile == null) {
                tvUpdateStatus.text = "❌ Error descargando el APK."
                Toast.makeText(this@SettingsActivity, "Error al descargar la actualización", Toast.LENGTH_LONG).show()
                return@launch
            }

            tvUpdateStatus.text = "✅ Descarga completa. Abriendo instalador..."
            val launched = AppUpdater.launchApkInstaller(this@SettingsActivity, apkFile)
            if (!launched) {
                tvUpdateStatus.text = "⚠️ Activa el permiso 'Instalar apps desconocidas' para YouTube y pulsa de nuevo."
                Toast.makeText(
                    this@SettingsActivity,
                    "Activa 'Permitir desde esta fuente' y vuelve a tocar Instalar",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun promptChangePin() {
        val input = EditText(this).apply {
            hint = "Nuevo PIN (mínimo 4 dígitos)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("Cambiar PIN de Seguridad")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val newPin = input.text?.toString()?.trim() ?: ""
                if (newPin.length >= 4) {
                    ConfigManager.pin = newPin
                    ConfigManager.save()
                    refreshUi()
                    Toast.makeText(this, "PIN actualizado", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "El PIN debe tener al menos 4 dígitos", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptAddWhitelistChannel() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val nameInput = EditText(this).apply { hint = "Nombre del canal (ej. Smile and Learn - Español)" }
        val handleInput = EditText(this).apply { hint = "@handle opcional" }
        layout.addView(nameInput)
        layout.addView(handleInput)

        AlertDialog.Builder(this)
            .setTitle("Aprobar Canal en Lista Blanca")
            .setView(layout)
            .setPositiveButton("Aprobar") { _, _ ->
                val name = nameInput.text?.toString()?.trim() ?: ""
                val handle = handleInput.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty() || handle.isNotEmpty()) {
                    ConfigManager.whitelistChannel(handle.ifBlank { name }, name.ifBlank { handle }, handle)
                    ConfigManager.pendingRemoteCommand = "RELOAD_FEED"
                    refreshUi()
                    Toast.makeText(this, "Canal aprobado en Lista Blanca", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptAddBlockedKeyword() {
        val input = EditText(this).apply {
            hint = "Ej: skibidi, brainrot, ranking..."
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("Agregar palabra o frase prohibida")
            .setView(input)
            .setPositiveButton("Bloquear") { _, _ ->
                val kw = input.text?.toString()?.trim() ?: ""
                if (kw.isNotEmpty()) {
                    ConfigManager.blockedKeywords.add(0, kw)
                    ConfigManager.verdictCache.clear()
                    saveAndMarkReload()
                    Toast.makeText(this, "Palabra bloqueada: $kw", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptConfigureAi() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val geminiInput = EditText(this).apply {
            hint = "Gemini API Key (AIzaSy...)"
            setText(ConfigManager.geminiApiKey)
        }
        val groqInput = EditText(this).apply {
            hint = "Groq API Key (gsk_...) — opcional"
            setText(ConfigManager.groqApiKey)
        }
        layout.addView(geminiInput)
        layout.addView(groqInput)

        AlertDialog.Builder(this)
            .setTitle("Juez IA Multi-LLM (Gemini / Groq)")
            .setMessage("Si llenas ambas, usará Gemini si está seleccionada o Groq si dejas Gemini vacía.")
            .setView(layout)
            .setPositiveButton("Guardar Keys") { _, _ ->
                val gKey = geminiInput.text?.toString()?.trim() ?: ""
                val grKey = groqInput.text?.toString()?.trim() ?: ""
                ConfigManager.geminiApiKey = gKey
                ConfigManager.groqApiKey = grKey
                ConfigManager.llmProvider = if (gKey.isNotEmpty()) LlmProvider.GEMINI else LlmProvider.GROQ
                ConfigManager.llmEnabled = gKey.isNotEmpty() || grKey.isNotEmpty()
                ConfigManager.verdictCache.clear()
                saveAndMarkReload()
                Toast.makeText(this, "API Keys guardadas", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
