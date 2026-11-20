# Media Downloader Hub

App Android nativa (Kotlin · Jetpack Compose · Material 3 · MVVM · Room · WorkManager · Retrofit/OkHttp) para descargar contenido de **Pinterest, Instagram y TikTok** a `Download/Media Downloader/{Instagram,TikTok,Pinterest}/` (sin subcarpetas, sin sobrescribir: se agrega `_1`, `_2`… si el nombre existe).

## APK
Cada push a `main` ejecuta GitHub Actions (`.github/workflows/build.yml`), compila y publica un **Release** con `MediaDownloaderHub-debug.apk` y `MediaDownloaderHub-release.apk` (el release se firma con la clave de depuración: sirve para uso personal). Descárgalo desde la pestaña *Releases* en el teléfono e instálalo (permitir "orígenes desconocidos").

Compilar local: abrir en Android Studio (JDK 17) o `gradle assembleDebug`.

## Recibir enlaces
- **Compartir → Media Downloader Hub** desde Instagram/TikTok/Pinterest.
- **Pegar** el enlace en Inicio (también sugiere un enlace compatible del portapapeles al abrir la app).

## Conectar Pinterest (necesario para tableros)
1. Crea una app en https://developers.pinterest.com/apps/ .
2. En la app registra el redirect URI `mediadownloaderhub://pinterest-callback`.
3. En la app: Ajustes → Cuentas conectadas → **CONFIGURAR API**: pega App ID y App secret (se guardan cifrados) → **CONECTAR**.
4. Si tu app tiene acceso *Trial*, activa "Usar API sandbox". Si Pinterest rechaza el redirect con esquema propio, genera un token de acceso en el panel y pégalo en el mismo diálogo.

Scopes solicitados: `boards:read, boards:read_secret, pins:read, pins:read_secret, user_accounts:read`.

## Cómo descarga tableros
Identifica el tablero entre *tus* tableros (`/v5/boards`, incluye secretos), recorre **todas** las páginas (`page_size=250` + `bookmark`), suma los Pines de cada sección (`/sections/{id}/pins`), verifica cada archivo (HEAD / Range) antes de bajarlo, prueba `originals → 1200x → 600x` y marca el tablero como completo solo si no quedan pendientes ni fallidos. Los fallidos se listan y se reintentan.

## Limitaciones (sin funciones ficticias)
- **Pinterest**: la API oficial solo lee tableros propios. Videos: si la API no da la URL del mp4 se intenta leer la página pública del Pin; los Pines solo-HLS no se pueden descargar. El acceso Trial puede limitar lo que la API devuelve.
- **Instagram**: no hay API oficial de descarga. Solo se intenta contenido público leyendo la página embed; sin sesión muchas publicaciones no responden. **Stories no soportadas** (exigen sesión y no se piden contraseñas).
- **TikTok**: sin API oficial de descarga; se intenta contenido público leyendo los datos de la página. El audio de las publicaciones de imágenes no se descarga.
- Pausar cancela el archivo en curso y lo reinicia al reanudar (no hay reanudación por rangos).
- Instagram/TikTok pueden cambiar su HTML en cualquier momento y romper la extracción.

## Estado de pruebas
El código se compila en GitHub Actions. **No se probaron aún** las integraciones con cuentas reales (OAuth, tablero de +100 Pines, secciones, secretos, Instagram, TikTok); hay que probarlas en un dispositivo.
