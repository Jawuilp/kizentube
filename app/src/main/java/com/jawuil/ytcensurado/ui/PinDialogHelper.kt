package com.jawuil.ytcensurado.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.model.AutoplayPolicy
import com.jawuil.ytcensurado.model.VideoItem
import com.jawuil.ytcensurado.server.PcControlServer

object PinDialogHelper {

    fun requirePin(context: Context, title: String = "Control Parental — Ingresa tu PIN", onVerified: () -> Unit) {
        val input = EditText(context).apply {
            hint = "PIN de 4 dígitos"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage("Panel Web PC activo en: http://${PcControlServer.getLocalIpAddress()}:${ConfigManager.serverPort}")
            .setView(input)
            .setPositiveButton("Desbloquear") { _, _ ->
                val entered = input.text?.toString()?.trim() ?: ""
                if (entered == ConfigManager.pin) {
                    onVerified()
                } else {
                    Toast.makeText(context, "PIN incorrecto", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun showVideoParentalMenu(
        context: Context,
        video: VideoItem,
        onActionApplied: () -> Unit
    ) {
        requirePin(context, "Acción sobre: ${video.channelName}") {
            val options = arrayOf(
                "🚫 Bloquear canal '${video.channelName}' para siempre",
                "✅ Aprobar canal '${video.channelName}' en Lista Blanca",
                "🔤 Bloquear una palabra de '${video.title.take(35)}...'"
            )
            AlertDialog.Builder(context)
                .setTitle("Control de Canal / Video")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            ConfigManager.blockChannel(
                                id = video.channelId.ifBlank { video.channelHandle.ifBlank { video.channelName } },
                                name = video.channelName,
                                handle = video.channelHandle,
                                autoBanned = false,
                                reason = "Bloqueado con PIN desde la TV"
                            )
                            Toast.makeText(context, "Canal bloqueado: ${video.channelName}", Toast.LENGTH_SHORT).show()
                            onActionApplied()
                        }
                        1 -> {
                            ConfigManager.whitelistChannel(
                                id = video.channelId.ifBlank { video.channelHandle.ifBlank { video.channelName } },
                                name = video.channelName,
                                handle = video.channelHandle
                            )
                            Toast.makeText(context, "Canal en Lista Blanca: ${video.channelName}", Toast.LENGTH_SHORT).show()
                            onActionApplied()
                        }
                        2 -> promptAddBlockedKeyword(context, video.title, onActionApplied)
                    }
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private fun promptAddBlockedKeyword(context: Context, initialText: String, onSaved: () -> Unit) {
        val input = EditText(context).apply {
            setText(initialText)
            selectAll()
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(context)
            .setTitle("Agregar palabra o frase prohibida")
            .setView(input)
            .setPositiveButton("Bloquear palabra") { _, _ ->
                val kw = input.text?.toString()?.trim() ?: ""
                if (kw.isNotEmpty()) {
                    ConfigManager.blockedKeywords.add(0, kw)
                    ConfigManager.verdictCache.clear()
                    ConfigManager.save()
                    Toast.makeText(context, "Palabra bloqueada: $kw", Toast.LENGTH_SHORT).show()
                    onSaved()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun showTvParentalDashboard(context: Context, onConfigChanged: () -> Unit) {
        requirePin(context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
            onConfigChanged()
        }
    }

    private fun promptAddWhitelistChannel(context: Context, onSaved: () -> Unit) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val nameInput = EditText(context).apply { hint = "Nombre del canal (ej. Smile and Learn - Español)" }
        val handleInput = EditText(context).apply { hint = "@handle opcional" }
        val info = TextView(context).apply {
            text = "También puedes gestionar todo cómodamente desde tu PC en http://${PcControlServer.getLocalIpAddress()}:${ConfigManager.serverPort}"
            textSize = 12f
        }
        layout.addView(nameInput)
        layout.addView(handleInput)
        layout.addView(info)

        AlertDialog.Builder(context)
            .setTitle("Aprobar Canal en Lista Blanca")
            .setView(layout)
            .setPositiveButton("Aprobar") { _, _ ->
                val name = nameInput.text?.toString()?.trim() ?: ""
                val handle = handleInput.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty() || handle.isNotEmpty()) {
                    ConfigManager.whitelistChannel(handle.ifBlank { name }, name.ifBlank { handle }, handle)
                    Toast.makeText(context, "Canal agregado a Lista Blanca", Toast.LENGTH_SHORT).show()
                    onSaved()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
