# Media Downloader Hub

App Android nativa (Kotlin · Jetpack Compose · Material 3 · MVVM · Room · WorkManager · Retrofit/OkHttp) para descargar contenido de **Pinterest, Instagram y TikTok** a `Download/Media Downloader/{Instagram,TikTok,Pinterest}/` (sin subcarpetas, sin sobrescribir: se agrega `_1`, `_2`… si el nombre existe).

## APK
Cada push a `main` ejecuta GitHub Actions (`.github/workflows/build.yml`), compila y publica un **Release** con `MediaDownloaderHub-debug.apk` y `MediaDownloaderHub-release.apk` (el release se firma con la clave de depuración: sirve para uso personal). Descárgalo desde la pestaña *Releases* en el teléfono e instálalo (permitir "orígenes desconocidos").

Compilar local: abrir en Android Studio (JDK 17) o `gradle assembleDebug`.

## Recibir enlaces
- **Compartir → Media Downloader Hub** desde Instagram/TikTok/Pinterest.
- **Pegar** el enlace en Inicio (también sugiere un enlace compatible del portapapeles al abrir la app).

## Iniciar sesión (v1.2)
En Ajustes → Cuentas conectadas puedes iniciar sesión en **Pinterest, Instagram y TikTok** dentro de la app (WebView con la página real de cada red). **La app nunca ve tu contraseña**: guarda solo las cookies de sesión, cifradas (AES-256), y las usa para las consultas y como `cookies.txt` temporal para yt-dlp.
- **Pinterest**: necesario para tableros (también secretos) y secciones.
- **Instagram**: necesario para imágenes, carruseles, reels y **Stories** (vía la API web con tu sesión).
- **TikTok**: opcional.
- Aviso: usar tu cuenta con herramientas automáticas puede hacer que la red limite tu cuenta temporalmente. "Continuar con Google" suele no funcionar dentro de un WebView: usa correo/usuario y contraseña.

## Cómo descarga
- **Instagram y TikTok**: usa **yt-dlp** (librería youtubedl-android + ffmpeg, se actualiza sola una vez por día y desde Ajustes → Motor de descargas). Si falla, intenta una lectura directa de la página pública.
- **Pinterest, sin cuenta**: Pines y tableros públicos (con secciones y paginación completa de 250 en 250) se leen con los mismos endpoints JSON que usa la web. Videos: mp4 directo; si solo hay HLS, usa yt-dlp.
- **Pinterest con cuenta (opcional, avanzado)**: solo para tableros secretos propios. Registrar el redirect `mediadownloaderhub://pinterest-callback` en una app de developers.pinterest.com, o pegar un token de acceso (Ajustes → Pinterest → Avanzado).
- Cada archivo se verifica antes de descargarlo; un tablero solo se marca completo si no quedan pendientes ni fallidos; los fallidos se reintentan.

## Limitaciones (sin funciones ficticias)
- Stories de Instagram: solo con sesión iniciada; las historias destacadas no están soportadas.
- Instagram suele pedir sesión incluso para contenido público: en esos casos la app muestra el motivo y no se puede descargar sin cuenta.
- Instagram, TikTok y Pinterest cambian sus sistemas seguido; si algo deja de funcionar, actualizar yt-dlp suele arreglarlo.
- Pinterest puede limitar o bloquear la lectura pública (se reintenta con espera); en tableros enormes puede tardar.
- Pausar cancela el archivo en curso y lo reinicia al reanudar.
- El APK pesa más por incluir Python, yt-dlp y ffmpeg.

## Estado de pruebas
Compila en GitHub Actions. **No se probó aún con enlaces reales** (OAuth, tableros de +100 Pines, secciones, secretos, Instagram, TikTok): hay que probarlo en un dispositivo.
