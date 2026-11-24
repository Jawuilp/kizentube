package com.jawuil.ytcensurado.server

import android.util.Log
import com.jawuil.ytcensurado.censorship.BrainrotHeuristics
import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.model.AutoplayPolicy
import com.jawuil.ytcensurado.model.LlmProvider
import com.jawuil.ytcensurado.model.VideoItem
import com.jawuil.ytcensurado.updater.AppUpdater
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.net.NetworkInterface
import java.util.Collections

class PcControlServer(port: Int = 8484) : NanoHTTPD(port) {

    companion object {
        @Volatile
        private var instance: PcControlServer? = null

        fun startServer(port: Int = ConfigManager.serverPort) {
            Thread {
                try {
                    if (instance?.isAlive == true) return@Thread
                    instance?.stop()
                    instance = PcControlServer(port).apply {
                        start(SOCKET_READ_TIMEOUT, false)
                    }
                    Log.i("PcControlServer", "Dashboard HTTP server listening on port $port")
                } catch (e: Exception) {
                    Log.e("PcControlServer", "Failed starting server on port $port", e)
                }
            }.start()
        }

        fun stopServer() {
            try {
                instance?.stop()
                instance = null
            } catch (_: Exception) {}
        }

        fun getLocalIpAddress(): String {
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress) {
                            val sAddr = addr.hostAddress ?: continue
                            if (sAddr.indexOf(':') < 0) {
                                return sAddr
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return "127.0.0.1"
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val resp = serveInternal(session)
        resp.addHeader("Connection", "close")
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun serveInternal(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        // Serve Dashboard HTML
        if (method == Method.GET && (uri == "/" || uri == "/index.html")) {
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", buildDashboardHtml())
        }

        // Check PIN for API endpoints
        val headerPin = session.headers["x-pin"] ?: session.parameters["pin"]?.firstOrNull() ?: ""
        if (uri.startsWith("/api/") && headerPin != ConfigManager.pin) {
            val err = JSONObject().put("error", "PIN incorrecto").toString()
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", err)
        }

        return try {
            when {
                uri == "/api/state" && method == Method.GET -> handleGetState()
                uri == "/api/config" && method == Method.POST -> handleUpdateConfig(session)
                uri == "/api/channel/block" && method == Method.POST -> handleBlockChannel(session)
                uri == "/api/channel/unblock" && method == Method.POST -> handleUnblockChannel(session)
                uri == "/api/channel/whitelist" && method == Method.POST -> handleWhitelistChannel(session)
                uri == "/api/channel/unwhitelist" && method == Method.POST -> handleUnwhitelistChannel(session)
                uri == "/api/remote" && method == Method.POST -> handleRemoteCommand(session)
                uri == "/api/test-title" && method == Method.POST -> handleTestTitle(session)
                uri == "/api/upload-apk" && method == Method.POST -> handleUploadApk(session)
                uri == "/api/clear-cache" && method == Method.POST -> {
                    ConfigManager.verdictCache.clear()
                    ConfigManager.saveCache()
                    jsonOk()
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Not found\"}")
            }
        } catch (e: Exception) {
            val err = JSONObject().put("error", e.message ?: "Error interno").toString()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", err)
        }
    }

    private fun readBodyJson(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val postData = files["postData"] ?: "{}"
        return JSONObject(postData)
    }

    private fun jsonOk(extra: JSONObject = JSONObject()): Response {
        extra.put("ok", true)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", extra.toString())
    }

    private fun handleGetState(): Response {
        val root = JSONObject()
        root.put("config", JSONObject(ConfigManager.toJsonString()))
        root.put("cacheSize", ConfigManager.verdictCache.size)

        ConfigManager.currentlyPlayingVideo?.let { np ->
            val npObj = JSONObject()
            npObj.put("videoId", np.videoId)
            npObj.put("title", np.title)
            npObj.put("channelName", np.channelName)
            npObj.put("channelId", np.channelId)
            npObj.put("channelHandle", np.channelHandle)
            npObj.put("thumbnailUrl", np.thumbnailUrl)
            npObj.put("durationText", np.durationText)
            root.put("nowPlaying", npObj)
        }

        val logsArr = JSONArray()
        ConfigManager.auditLogs.take(150).forEach { log ->
            val o = JSONObject()
            o.put("timestamp", log.timestamp)
            o.put("videoId", log.videoId)
            o.put("title", log.title)
            o.put("channelName", log.channelName)
            o.put("channelId", log.channelId)
            o.put("channelHandle", log.channelHandle)
            o.put("durationText", log.durationText)
            o.put("thumbnailUrl", log.thumbnailUrl)
            o.put("allowed", log.allowed)
            o.put("layer", log.layer)
            o.put("reason", log.reason)
            o.put("score", log.score)
            logsArr.put(o)
        }
        root.put("auditLogs", logsArr)

        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", root.toString())
    }

    private fun handleUpdateConfig(session: IHTTPSession): Response {
        val body = readBodyJson(session)
        ConfigManager.fromJsonString(body.toString())
        ConfigManager.verdictCache.clear()
        ConfigManager.save()
        ConfigManager.pendingRemoteCommand = "RELOAD_FEED"
        return jsonOk()
    }

    private fun handleBlockChannel(session: IHTTPSession): Response {
        val body = readBodyJson(session)
        val id = body.optString("id")
        val name = body.optString("name")
        val handle = body.optString("handle")
        val reason = body.optString("reason", "Bloqueado desde Panel PC")
        ConfigManager.blockChannel(id, name, handle, false, reason)

        // If the currently playing video belongs to this channel, stop it immediately on the TV!
        val np = ConfigManager.currentlyPlayingVideo
        if (np != null && ConfigManager.isChannelBlocked(np.channelId, np.channelHandle, np.channelName)) {
            ConfigManager.pendingRemoteCommand = "STOP_VIDEO"
        } else {
            ConfigManager.pendingRemoteCommand = "RELOAD_FEED"
        }
        return jsonOk()
    }

    private fun handleUnblockChannel(session: IHTTPSession): Response {
        val body = readBodyJson(session)
        val id = body.optString("id")
        val name = body.optString("name")
        ConfigManager.blockedChannels.removeAll {
            it.id.equals(id, true) || (name.isNotBlank() && it.name.equals(name, true))
        }
        ConfigManager.verdictCache.clear()
        ConfigManager.save()
        return jsonOk()
    }

    private fun handleWhitelistChannel(session: IHTTPSession): Response {
        val body = readBodyJson(session)
        val id = body.optString("id")
        val name = body.optString("name")
        val handle = body.optString("handle")
        ConfigManager.whitelistChannel(id, name, handle)
        ConfigManager.pendingRemoteCommand = "RELOAD_FEED"
        return jsonOk()
    }

    private fun handleUnwhitelistChannel(session: IHTTPSession): Response {
        val body = readBodyJson(session)
        val id = body.optString("id")
        val name = body.optString("name")
        ConfigManager.whitelistedChannels.removeAll {
            it.id.equals(id, true) || (name.isNotBlank() && it.name.equals(name, true))
        }
        ConfigManager.verdictCache.clear()
        ConfigManager.save()
        return jsonOk()
    }

    private fun handleRemoteCommand(session: IHTTPSession): Response {
        val body = readBodyJson(session)
        val cmd = body.optString("command")
        val arg = body.optString("arg")
        ConfigManager.pendingRemoteCommand = cmd
        ConfigManager.pendingRemoteArg = arg
        return jsonOk()
    }

    private fun handleTestTitle(session: IHTTPSession): Response {
        val body = readBodyJson(session)
        val title = body.optString("title", "")
        val channel = body.optString("channel", "Canal de Prueba")
        val handle = body.optString("handle", "@canal")
        val duration = body.optInt("durationSeconds", 180)

        val dummy = VideoItem(
            videoId = "test_id",
            title = title,
            channelName = channel,
            channelHandle = handle,
            thumbnailUrl = "",
            durationSeconds = duration,
            durationText = "${duration / 60}:${(duration % 60).toString().padStart(2, '0')}"
        )
        val verdict = BrainrotHeuristics.evaluate(dummy)
        val res = JSONObject()
        res.put("allowed", verdict.allowed)
        res.put("score", verdict.score)
        res.put("layer", verdict.layer)
        res.put("reason", verdict.reason)
        return jsonOk(res)
    }

    private fun handleUploadApk(session: IHTTPSession): Response {
        val ctx = ConfigManager.appContext
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"Contexto no disponible\"}")

        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength <= 100_000L) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Archivo APK inválido o vacío\"}")
        }

        val apkFile = AppUpdater.getUpdateApkFile(ctx)
        val input = session.inputStream
        FileOutputStream(apkFile).use { out ->
            val buf = ByteArray(16 * 1024)
            var remaining = contentLength
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val read = input.read(buf, 0, toRead)
                if (read <= 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
        }

        val launched = AppUpdater.launchApkInstaller(ctx, apkFile)
        val res = JSONObject()
        res.put("launched", launched)
        res.put("sizeBytes", apkFile.length())
        return jsonOk(res)
    }

    private fun buildDashboardHtml(): String {
        return """<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>YouTube TV Blindado — Panel de Control PC</title>
<style>
  :root {
    --bg: #0f0f0f;
    --card: #1a1a1a;
    --border: #2e2e2e;
    --accent: #ff0033;
    --green: #16a34a;
    --text: #f1f1f1;
    --muted: #aaaaaa;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, -apple-system, sans-serif; }
  body { background: var(--bg); color: var(--text); padding: 20px; max-width: 1280px; margin: 0 auto; }
  header { display: flex; justify-content: space-between; align-items: center; padding-bottom: 16px; border-bottom: 1px solid var(--border); margin-bottom: 20px; flex-wrap: wrap; gap: 12px; }
  .brand { display: flex; align-items: center; gap: 12px; }
  .yt-pill { background: var(--accent); color: #fff; font-weight: 800; padding: 6px 14px; border-radius: 8px; letter-spacing: 0.5px; }
  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(370px, 1fr)); gap: 18px; margin-bottom: 20px; }
  .card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 18px; }
  .card h2 { font-size: 16px; margin-bottom: 12px; color: #fff; display: flex; justify-content: space-between; align-items: center; }
  label { display: block; font-size: 13px; color: var(--muted); margin-top: 10px; margin-bottom: 4px; }
  input[type="text"], input[type="password"], input[type="number"], select, textarea {
    width: 100%; background: #111; border: 1px solid #383838; color: #fff; padding: 9px 11px; border-radius: 8px; font-size: 13px;
  }
  textarea { min-height: 95px; resize: vertical; font-family: monospace; }
  .row { display: flex; gap: 10px; align-items: center; margin-top: 8px; }
  .toggle-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid #252525; font-size: 14px; }
  button { background: #2b2b2b; color: #fff; border: 1px solid #444; padding: 8px 14px; border-radius: 8px; cursor: pointer; font-weight: 600; font-size: 13px; }
  button:hover { background: #383838; }
  button.primary { background: var(--accent); border-color: var(--accent); }
  button.success { background: var(--green); border-color: var(--green); }
  button.danger { background: #991b1b; border-color: #b91c1c; }
  .tag-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; max-height: 200px; overflow-y: auto; }
  .tag { background: #262626; border: 1px solid #3a3a3a; padding: 5px 10px; border-radius: 999px; font-size: 12px; display: flex; align-items: center; gap: 8px; }
  .tag span.x { cursor: pointer; color: #ff6b6b; font-weight: bold; }
  .audit-table { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 10px; }
  .audit-table th, .audit-table td { text-align: left; padding: 10px 8px; border-bottom: 1px solid #262626; }
  .badge-ok { background: rgba(22,163,74,0.2); color: #4ade80; padding: 3px 8px; border-radius: 6px; font-weight: 700; font-size: 11px; }
  .badge-block { background: rgba(220,38,38,0.25); color: #f87171; padding: 3px 8px; border-radius: 6px; font-weight: 700; font-size: 11px; }
  #loginOverlay { position: fixed; inset: 0; background: rgba(0,0,0,0.92); display: flex; align-items: center; justify-content: center; z-index: 999; }
</style>
</head>
<body>

<div id="loginOverlay">
  <div class="card" style="width: 340px; text-align: center;">
    <div class="yt-pill" style="display:inline-block; margin-bottom:12px;">YOUTUBE TV BLINDADO</div>
    <h2>Ingresa tu PIN de Control</h2>
    <input type="password" id="pinInput" placeholder="PIN (por defecto 1234)" style="text-align:center; font-size:18px; margin: 12px 0;" onkeydown="if(event.key==='Enter') unlock()">
    <button class="primary" style="width:100%; padding:11px;" onclick="unlock()">Entrar al Panel</button>
    <p id="loginErr" style="color:#f87171; font-size:13px; margin-top:8px;"></p>
  </div>
</div>

<header>
  <div class="brand">
    <span class="yt-pill">YT CENSURADO TV</span>
    <div>
      <div style="font-weight:700; font-size:18px;">Control Total en Vivo (Android TV &amp; Móvil)</div>
      <div style="font-size:12px; color:var(--muted);">0ms Anti-Shorts + Heurística Anti-Brainrot + Juez Multi-LLM (Gemini / Groq)</div>
    </div>
  </div>
  <div class="row">
    <button onclick="forceReloadTv()">🔄 Recargar Feed en TV</button>
    <button class="primary" onclick="saveAllConfig()">💾 Guardar Todos los Cambios</button>
  </div>
</header>

<div class="grid">
  <!-- LIVE TV MONITOR -->
  <div class="card">
    <h2>📺 Reproduciendo Ahora en la TV <span id="liveDot" style="color:#4ade80; font-size:12px;">● EN VIVO</span></h2>
    <div id="nowPlayingBox" style="padding: 12px; background:#121212; border-radius:8px; font-size:13px; color:var(--muted);">
      Ningún video reproduciéndose en este momento.
    </div>
    <div style="margin-top:14px; border-top:1px solid #262626; padding-top:12px;">
      <h2 style="font-size:14px;">🧪 Probador Instantáneo de Títulos Anti-Brainrot</h2>
      <div class="row">
        <input type="text" id="testTitleInput" placeholder="Pega un título (ej. Top chinese memes Ultimate LIST)">
        <button onclick="testTitle()">Probar</button>
      </div>
      <div id="testResultBox" style="margin-top:8px; font-size:12px;"></div>
    </div>
  </div>

  <!-- HARD RULES & AUTOPLAY FIREWALL -->
  <div class="card">
    <h2>🛡️ Cortafuegos Principal y Autoplay</h2>
    <div class="toggle-row">
      <span><b>Modo Lista Blanca Estricta</b> (Solo canales aprobados)</span>
      <input type="checkbox" id="whitelistOnlyMode">
    </div>
    <div class="toggle-row">
      <span><b>Erradicar 100% Shorts</b> (Nativos y disfrazados)</span>
      <input type="checkbox" id="blockAllShorts" checked>
    </div>
    <div class="toggle-row">
      <span><b>Mostrar Lupa de Búsqueda en la TV</b></span>
      <input type="checkbox" id="searchEnabledOnTv">
    </div>
    <div class="toggle-row">
      <span><b>Solo Español</b> (Bloquear granjas en inglés/árabe fuera de Lista Blanca)</span>
      <input type="checkbox" id="spanishOnlyOutsideWhitelist">
    </div>
    <div class="toggle-row">
      <span><b>DeArrow Anti-Clickbait</b> (Miniaturas y títulos limpios sin caras exageradas)</span>
      <input type="checkbox" id="deArrowEnabled">
    </div>
    <div class="toggle-row">
      <span><b>🔒 Bloquear suscripciones</b> (Sin botón de Suscribirse en perfiles ni reproductor)</span>
      <input type="checkbox" id="lockSubscriptions" checked>
    </div>
    <div class="row">
      <div style="flex:1;">
        <label>Duración Mínima (seg) — Mata Shorts horizontales</label>
        <input type="number" id="minDurationSeconds" value="65">
      </div>
      <div style="flex:1;">
        <label>Umbral Heurístico Brainrot (15-90)</label>
        <input type="number" id="heuristicThreshold" value="40">
      </div>
    </div>
    <label>Política del Cortafuegos de Autoplay (Siguiente video)</label>
    <select id="autoplayPolicy">
      <option value="SAME_CHANNEL_OR_WHITELIST">Solo mismo canal o canales de Lista Blanca (Recomendado)</option>
      <option value="SAME_CHANNEL_ONLY">Solo videos del mismo canal actual</option>
      <option value="ANY_FILTERED">Cualquier video que apruebe Heurística + Juez IA</option>
      <option value="DISABLED">Autoplay Desactivado (Detener al terminar)</option>
    </select>
    <div class="row">
      <div style="flex:1;">
        <label>Auto-Banear canal tras N videos Brainrot</label>
        <input type="number" id="autoBanChannelStrikeLimit" value="2">
      </div>
      <div style="flex:1;">
        <label>PIN de Seguridad (TV y Panel)</label>
        <input type="text" id="pinConfig" value="1234">
      </div>
    </div>
    <div style="margin-top:12px; border-top:1px solid #262626; padding-top:10px;">
      <label>Repositorio GitHub para Auto-Actualización (ej. Jawuilp/kizentube)</label>
      <input type="text" id="updateRepo" value="Jawuilp/kizentube">
      <label style="margin-top:10px;">🚀 Subir nuevo APK directo desde tu PC (sin USB ni cables)</label>
      <div class="row">
        <input type="file" id="apkUploadInput" accept=".apk" style="font-size:12px;">
        <button class="success" onclick="uploadApkToTv()">⬆️ Instalar en TV</button>
      </div>
      <div id="apkUploadStatus" style="margin-top:6px; font-size:12px; color:#4ade80;"></div>
    </div>
  </div>

  <!-- MULTI-LLM INTELLIGENT JUDGE -->
  <div class="card">
    <h2>🧠 Capa 2: Juez IA Multi-LLM (Gemini / Groq / OpenAI)</h2>
    <div class="toggle-row">
      <span><b>Activar Análisis Inteligente por Lotes con IA</b></span>
      <input type="checkbox" id="llmEnabled">
    </div>
    <label>Proveedor de IA</label>
    <select id="llmProvider">
      <option value="GEMINI">Google Gemini API (Recomendado: gemini-2.0-flash / gemini-2.5-flash)</option>
      <option value="GROQ">Groq Cloud (Ultra-rápido: llama-3.3-70b-versatile)</option>
      <option value="OPENAI_COMPATIBLE">OpenAI / OpenRouter / DeepSeek / Ollama Local</option>
    </select>
    <div class="row">
      <div style="flex:1;">
        <label>Gemini API Key</label>
        <input type="password" id="geminiApiKey" placeholder="AIzaSy...">
      </div>
      <div style="flex:1;">
        <label>Modelo Gemini</label>
        <input type="text" id="geminiModel" value="gemini-2.0-flash">
      </div>
    </div>
    <div class="row">
      <div style="flex:1;">
        <label>Groq API Key</label>
        <input type="password" id="groqApiKey" placeholder="gsk_...">
      </div>
      <div style="flex:1;">
        <label>Modelo Groq</label>
        <input type="text" id="groqModel" value="llama-3.3-70b-versatile">
      </div>
    </div>
    <label>Instrucción del Juez IA (Filtro Anti-Brainrot)</label>
    <textarea id="llmSystemPrompt"></textarea>
    <div class="row" style="justify-content:space-between; margin-top:8px;">
      <span id="cacheInfo" style="font-size:12px; color:var(--muted);">Caché IA: 0 videos</span>
      <button onclick="clearLlmCache()">🧹 Limpiar Caché IA</button>
    </div>
  </div>
</div>

<div class="grid">
  <!-- WHITELIST CHANNELS -->
  <div class="card">
    <h2>✅ Lista Blanca (Canales 100% Seguros / Suscripciones)</h2>
    <div class="row">
      <input type="text" id="newWlName" placeholder="Nombre del canal (ej. National Geographic Español)">
      <input type="text" id="newWlHandle" placeholder="@handle (opcional)">
      <button class="success" onclick="addWhitelistChannel()">+ Aprobar</button>
    </div>
    <div id="whitelistContainer" class="tag-list"></div>
  </div>

  <!-- BLACKLIST CHANNELS -->
  <div class="card">
    <h2>🚫 Canales Bloqueados (Manuales + Auto-Baneados por IA)</h2>
    <div class="row">
      <input type="text" id="newBlName" placeholder="Nombre del canal a bloquear">
      <input type="text" id="newBlHandle" placeholder="@handle (ej. @MrBeast)">
      <button class="danger" onclick="addBlacklistChannel()">+ Bloquear</button>
    </div>
    <div id="blacklistContainer" class="tag-list"></div>
  </div>

  <!-- KEYWORDS & REGEX -->
  <div class="card">
    <h2>🔤 Palabras Prohibidas y Temas de Inicio</h2>
    <label>Palabras / Frases Prohibidas (una por línea — mata título, canal o descripción)</label>
    <textarea id="blockedKeywordsArea"></textarea>
    <label>Regex Estructurales Anti-Recopilaciones / Clickbait (una por línea)</label>
    <textarea id="blockedRegexesArea"></textarea>
    <label>Temas Seguros para alimentar el Inicio (uno por línea)</label>
    <textarea id="safeHomeTopicsArea" style="min-height:65px;"></textarea>
  </div>
</div>

<!-- REAL-TIME AUDIT LOG -->
<div class="card">
  <h2>📊 Radar en Vivo: Videos Bloqueados y Videos Mostrados en la TV</h2>
  <p style="font-size:12px; color:var(--muted); margin-bottom:10px;">
    Aquí ves en tiempo real todo lo que el motor interceptó antes de pintarse en la TV. Con un clic puedes bloquear o aprobar cualquier canal al instante.
  </p>
  <table class="audit-table">
    <thead>
      <tr>
        <th>Estado</th>
        <th>Título del Video</th>
        <th>Canal</th>
        <th>Duración</th>
        <th>Capa / Motivo del Motor</th>
        <th>Acción Rápida en 1 Clic</th>
      </tr>
    </thead>
    <tbody id="auditTableBody"></tbody>
  </table>
</div>

<script>
let PIN = localStorage.getItem('yt_censurado_pin') || '';
let currentConfig = {};

function unlock() {
  PIN = document.getElementById('pinInput').value.trim();
  fetchState(true);
}

async function api(path, method='GET', body=null) {
  const opts = { method, headers: { 'X-Pin': PIN, 'Content-Type': 'application/json' } };
  if (body) opts.body = JSON.stringify(body);
  const res = await fetch(path, opts);
  if (res.status === 401) {
    document.getElementById('loginOverlay').style.display = 'flex';
    document.getElementById('loginErr').innerText = 'PIN incorrecto';
    throw new Error('Unauthorized');
  }
  document.getElementById('loginOverlay').style.display = 'none';
  localStorage.setItem('yt_censurado_pin', PIN);
  return res.json();
}

async function fetchState(populateInputs = false) {
  try {
    const data = await api('/api/state');
    currentConfig = data.config;
    document.getElementById('cacheInfo').innerText = 'Caché IA: ' + (data.cacheSize || 0) + ' videos evaluados';

    if (populateInputs) {
      document.getElementById('whitelistOnlyMode').checked = !!currentConfig.whitelistOnlyMode;
      document.getElementById('blockAllShorts').checked = !!currentConfig.blockAllShorts;
      document.getElementById('searchEnabledOnTv').checked = !!currentConfig.searchEnabledOnTv;
      document.getElementById('spanishOnlyOutsideWhitelist').checked = !!currentConfig.spanishOnlyOutsideWhitelist;
      document.getElementById('deArrowEnabled').checked = currentConfig.deArrowEnabled !== false;
      document.getElementById('lockSubscriptions').checked = currentConfig.lockSubscriptions !== false;
      document.getElementById('minDurationSeconds').value = currentConfig.minDurationSeconds || 65;
      document.getElementById('heuristicThreshold').value = currentConfig.heuristicThreshold || 40;
      document.getElementById('autoplayPolicy').value = currentConfig.autoplayPolicy || 'SAME_CHANNEL_OR_WHITELIST';
      document.getElementById('autoBanChannelStrikeLimit').value = currentConfig.autoBanChannelStrikeLimit ?? 2;
      document.getElementById('pinConfig').value = currentConfig.pin || '1234';
      document.getElementById('updateRepo').value = currentConfig.updateRepo || 'Jawuilp/kizentube';

      document.getElementById('llmEnabled').checked = !!currentConfig.llmEnabled;
      document.getElementById('llmProvider').value = currentConfig.llmProvider || 'GEMINI';
      document.getElementById('geminiApiKey').value = currentConfig.geminiApiKey || '';
      document.getElementById('geminiModel').value = currentConfig.geminiModel || 'gemini-2.0-flash';
      document.getElementById('groqApiKey').value = currentConfig.groqApiKey || '';
      document.getElementById('groqModel').value = currentConfig.groqModel || 'llama-3.3-70b-versatile';
      document.getElementById('llmSystemPrompt').value = currentConfig.llmSystemPrompt || '';

      document.getElementById('blockedKeywordsArea').value = (currentConfig.blockedKeywords || []).join('\n');
      document.getElementById('blockedRegexesArea').value = (currentConfig.blockedRegexes || []).join('\n');
      document.getElementById('safeHomeTopicsArea').value = (currentConfig.safeHomeTopics || []).join('\n');
    }

    renderChannels();
    renderNowPlaying(data.nowPlaying);
    renderAuditLogs(data.auditLogs || []);
  } catch (e) {}
}

function renderNowPlaying(np) {
  const box = document.getElementById('nowPlayingBox');
  if (!np) {
    box.innerHTML = 'Ningún video reproduciéndose ahora mismo en la TV.';
    return;
  }
  box.innerHTML = `
    <div style="display:flex; gap:12px; align-items:center;">
      <img src="${'$'}{np.thumbnailUrl}" style="width:120px; border-radius:8px;">
      <div style="flex:1;">
        <div style="font-weight:700; color:#fff; font-size:15px;">${'$'}{escapeHtml(np.title)}</div>
        <div style="color:var(--muted); margin-top:4px;">Canal: <b>${'$'}{escapeHtml(np.channelName)}</b> (${'$'}{escapeHtml(np.channelHandle || np.channelId)}) • ${'$'}{np.durationText}</div>
        <div class="row" style="margin-top:10px;">
          <button class="danger" onclick="stopTvVideo()">🛑 Detener Video en TV</button>
          <button class="danger" onclick="quickBlock('${'$'}{escapeJs(np.channelId)}','${'$'}{escapeJs(np.channelName)}','${'$'}{escapeJs(np.channelHandle)}')">🚫 Bloquear Canal y Detener</button>
          <button class="success" onclick="quickWhitelist('${'$'}{escapeJs(np.channelId)}','${'$'}{escapeJs(np.channelName)}','${'$'}{escapeJs(np.channelHandle)}')">✅ Aprobar Canal</button>
        </div>
      </div>
    </div>
  `;
}

function renderChannels() {
  const wl = document.getElementById('whitelistContainer');
  wl.innerHTML = (currentConfig.whitelistedChannels || []).map(c => `
    <div class="tag">
      <span>✅ <b>${'$'}{escapeHtml(c.name)}</b> ${'$'}{c.handle ? '('+escapeHtml(c.handle)+')' : ''}</span>
      <span class="x" onclick="removeWhitelist('${'$'}{escapeJs(c.id)}','${'$'}{escapeJs(c.name)}')">✕</span>
    </div>
  `).join('') || '<span style="font-size:12px; color:var(--muted);">Aún no hay canales en Lista Blanca.</span>';

  const bl = document.getElementById('blacklistContainer');
  bl.innerHTML = (currentConfig.blockedChannels || []).map(c => `
    <div class="tag" title="${'$'}{escapeHtml(c.reason || '')}">
      <span>🚫 <b>${'$'}{escapeHtml(c.name)}</b> ${'$'}{c.handle ? '('+escapeHtml(c.handle)+')' : ''} ${'$'}{c.autoBanned ? '[AUTO-IA]' : ''}</span>
      <span class="x" onclick="removeBlacklist('${'$'}{escapeJs(c.id)}','${'$'}{escapeJs(c.name)}')">✕</span>
    </div>
  `).join('');
}

function renderAuditLogs(logs) {
  const tbody = document.getElementById('auditTableBody');
  tbody.innerHTML = logs.map(l => `
    <tr>
      <td>${'$'}{l.allowed ? '<span class="badge-ok">PERMITIDO</span>' : '<span class="badge-block">BLOQUEADO ('+l.score+')</span>'}</td>
      <td style="font-weight:600; color:#fff;">${'$'}{escapeHtml(l.title)}</td>
      <td>${'$'}{escapeHtml(l.channelName)} <span style="color:var(--muted); font-size:11px;">${'$'}{escapeHtml(l.channelHandle)}</span></td>
      <td>${'$'}{escapeHtml(l.durationText)}</td>
      <td style="font-size:12px; color:${'$'}{l.allowed ? '#86efac' : '#fca5a5'};"><b>[${'$'}{escapeHtml(l.layer)}]</b> ${'$'}{escapeHtml(l.reason)}</td>
      <td>
        <button class="danger" style="padding:4px 8px; font-size:11px;" onclick="quickBlock('${'$'}{escapeJs(l.channelId)}','${'$'}{escapeJs(l.channelName)}','${'$'}{escapeJs(l.channelHandle)}')">🚫 Bloquear Canal</button>
        <button class="success" style="padding:4px 8px; font-size:11px;" onclick="quickWhitelist('${'$'}{escapeJs(l.channelId)}','${'$'}{escapeJs(l.channelName)}','${'$'}{escapeJs(l.channelHandle)}')">✅ Whitelist</button>
      </td>
    </tr>
  `).join('');
}

async function saveAllConfig() {
  currentConfig.whitelistOnlyMode = document.getElementById('whitelistOnlyMode').checked;
  currentConfig.blockAllShorts = document.getElementById('blockAllShorts').checked;
  currentConfig.searchEnabledOnTv = document.getElementById('searchEnabledOnTv').checked;
  currentConfig.spanishOnlyOutsideWhitelist = document.getElementById('spanishOnlyOutsideWhitelist').checked;
  currentConfig.deArrowEnabled = document.getElementById('deArrowEnabled').checked;
  currentConfig.lockSubscriptions = document.getElementById('lockSubscriptions').checked;
  currentConfig.minDurationSeconds = parseInt(document.getElementById('minDurationSeconds').value) || 65;
  currentConfig.heuristicThreshold = parseInt(document.getElementById('heuristicThreshold').value) || 40;
  currentConfig.autoplayPolicy = document.getElementById('autoplayPolicy').value;
  currentConfig.autoBanChannelStrikeLimit = parseInt(document.getElementById('autoBanChannelStrikeLimit').value) || 2;
  currentConfig.pin = document.getElementById('pinConfig').value.trim() || '1234';
  currentConfig.updateRepo = document.getElementById('updateRepo').value.trim() || 'Jawuilp/kizentube';

  currentConfig.llmEnabled = document.getElementById('llmEnabled').checked;
  currentConfig.llmProvider = document.getElementById('llmProvider').value;
  currentConfig.geminiApiKey = document.getElementById('geminiApiKey').value.trim();
  currentConfig.geminiModel = document.getElementById('geminiModel').value.trim();
  currentConfig.groqApiKey = document.getElementById('groqApiKey').value.trim();
  currentConfig.groqModel = document.getElementById('groqModel').value.trim();
  currentConfig.llmSystemPrompt = document.getElementById('llmSystemPrompt').value.trim();

  currentConfig.blockedKeywords = document.getElementById('blockedKeywordsArea').value.split('\n').map(s=>s.trim()).filter(Boolean);
  currentConfig.blockedRegexes = document.getElementById('blockedRegexesArea').value.split('\n').map(s=>s.trim()).filter(Boolean);
  currentConfig.safeHomeTopics = document.getElementById('safeHomeTopicsArea').value.split('\n').map(s=>s.trim()).filter(Boolean);

  PIN = currentConfig.pin;
  await api('/api/config', 'POST', currentConfig);
  alert('Configuración guardada y aplicada en vivo en la TV.');
  fetchState(true);
}

async function quickBlock(id, name, handle) {
  await api('/api/channel/block', 'POST', { id: id || handle || name, name, handle });
  fetchState(false);
}
async function quickWhitelist(id, name, handle) {
  await api('/api/channel/whitelist', 'POST', { id: id || handle || name, name, handle });
  fetchState(false);
}
async function addWhitelistChannel() {
  const name = document.getElementById('newWlName').value.trim();
  const handle = document.getElementById('newWlHandle').value.trim();
  if (!name && !handle) return;
  await quickWhitelist(handle || name, name || handle, handle);
  document.getElementById('newWlName').value = '';
  document.getElementById('newWlHandle').value = '';
}
async function addBlacklistChannel() {
  const name = document.getElementById('newBlName').value.trim();
  const handle = document.getElementById('newBlHandle').value.trim();
  if (!name && !handle) return;
  await quickBlock(handle || name, name || handle, handle);
  document.getElementById('newBlName').value = '';
  document.getElementById('newBlHandle').value = '';
}
async function removeBlacklist(id, name) {
  await api('/api/channel/unblock', 'POST', { id, name });
  fetchState(false);
}
async function removeWhitelist(id, name) {
  await api('/api/channel/unwhitelist', 'POST', { id, name });
  fetchState(false);
}
async function stopTvVideo() {
  await api('/api/remote', 'POST', { command: 'STOP_VIDEO' });
  fetchState(false);
}
async function forceReloadTv() {
  await api('/api/remote', 'POST', { command: 'RELOAD_FEED' });
  fetchState(false);
}
async function clearLlmCache() {
  await api('/api/clear-cache', 'POST');
  fetchState(false);
}
async function uploadApkToTv() {
  const input = document.getElementById('apkUploadInput');
  const status = document.getElementById('apkUploadStatus');
  if (!input.files || !input.files[0]) { alert('Selecciona primero un archivo .apk'); return; }
  const file = input.files[0];
  status.innerText = '⬆️ Enviando ' + file.name + ' (' + (file.size / 1048576).toFixed(2) + ' MB)...';
  try {
    const res = await fetch('/api/upload-apk', { method: 'POST', headers: { 'X-Pin': PIN }, body: file });
    const data = await res.json();
    if (data.ok) {
      status.innerText = data.launched
        ? '✅ APK recibido — Acepta el instalador en la pantalla de la TV/celular.'
        : '⚠️ APK recibido — Activa el permiso de instalar apps desconocidas en el dispositivo.';
    } else {
      status.innerText = '❌ Error: ' + (data.error || 'falló la subida');
    }
  } catch (e) {
    status.innerText = '❌ Error de red enviando APK';
  }
}
async function testTitle() {
  const title = document.getElementById('testTitleInput').value.trim();
  if (!title) return;
  const r = await api('/api/test-title', 'POST', { title });
  const box = document.getElementById('testResultBox');
  box.innerHTML = r.allowed
    ? `<span class="badge-ok">PASARÍA A CAPA 2 (IA)</span> ${'$'}{escapeHtml(r.reason)}`
    : `<span class="badge-block">FULMINADO EN 0ms (${'$'}{r.score} pts)</span> [${'$'}{escapeHtml(r.layer)}] ${'$'}{escapeHtml(r.reason)}`;
}
function escapeHtml(s) {
  return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
function escapeJs(s) {
  return String(s||'').replace(/\\/g,'\\\\').replace(/'/g,"\\'");
}

if (PIN) {
  document.getElementById('pinInput').value = PIN;
  fetchState(true);
}
setInterval(() => { if (PIN) fetchState(false); }, 4000);
</script>
</body>
</html>"""
    }
}
