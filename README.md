# KizenTube

Cliente nativo de YouTube en **Kotlin + Media3/ExoPlayer** para **Android TV (Android 14)** y **Android Móvil**, diseñado desde cero sin WebViews ni parches JS sobre `youtube.com/tv`.

Construido para garantizar **0 ms de exposición visual** a Shorts, granjas de contenido y *brainrot* mediante filtrado determinista **antes de renderizar**.

## Características principales

- **Filtrado Pre-Render (0 ms):** Destruye Shorts nativos y disfrazados (`< 65s`), recopilaciones de memes/TikToks, títulos con fecha de subida cruda, clickbait con `*ASTERISCOS*` / `$1 vs $500,000`, sopa de hashtags y canales granja antes de crear una sola tarjeta en pantalla.
- **Juez IA Multi-LLM por lotes (opcional):** Soporta **Google Gemini**, **Groq** y endpoints **OpenAI-compatible** (OpenRouter, DeepSeek, Ollama local) con caché persistente y **Auto-Ban de canales** tras N videos bloqueados.
- **Bloqueo total de suscripciones (Solo con PIN):** Interruptor activo por defecto que oculta por completo el botón de *Suscribirse* incluso al entrar al perfil de un canal.
- **DeArrow Anti-Clickbait:** Reemplaza títulos exagerados y miniaturas clickbait por fotogramas y títulos calmados.
- **Cortafuegos de Autoplay encadenado:** Limita el *Siguiente video* al mismo canal o a canales aprobados en Lista Blanca (o desactivado).
- **Panel Web de Control en Vivo desde PC (`http://<IP>:8484`):** Protegido con PIN; permite ver qué se está reproduciendo, detener videos remotamente, auditar lo bloqueado/permitido, editar listas con teclado y subir nuevos `.apk` por Wi-Fi.
- **Interfaz Universal Adaptativa:** Modo **YouTube TV** en Smart TV / horizontal (barra lateral + 3 columnas + D-Pad) y modo **YouTube Móvil** en celular vertical (barra superior + 1 columna + barra inferior).
- **Reproductor Nativo Media3/ExoPlayer:** Sin anuncios, **1080p Full HD DASH**, selector de calidad, selector de velocidad (`0.5x`–`2.0x`) y fila horizontal de videos relacionados filtrados.
- **Auto-Actualizador integrado:** Botón `⬆️ Buscar Actualización` en Ajustes (PIN) conectado a **GitHub Releases (`Jawuilp/kizentube`)**.

## Compilación desde CLI (sin Android Studio)

Requiere **JDK 17** y **Android Command-Line Tools** (`platforms;android-34`, `build-tools;34.0.0`).

```bash
gradle testDebugUnitTest assembleDebug
```

El APK generado queda en `app/build/outputs/apk/debug/app-debug.apk`.
