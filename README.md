# Lotería Nacional — App Android nativa (Kotlin)

App 100% nativa (Kotlin + Material 3, sin WebView) para **loteria-nacional-v2**
(backend Go/Gin). Misma cuenta, mismo saldo, mismos sorteos y mismo casino.

## Estado (Fase 1 — 2026-11-16)

Funciona ya:

- Login con el backend (`POST /api/auth/login`), token JWT guardado local.
- Saldo y datos del usuario (`GET /api/auth/me`), refrescado al abrir la app.
- Próximos sorteos abiertos (`GET /api/sorteos/proximos`) con su lotería.
- Apuesta nativa (`POST /api/apuestas`): respeta `max_numeros` y `longitud_num`
  de cada lotería, valida repetidos y muestra total a pagar antes de confirmar.
- Tema esmeralda/oro propio (el mismo estilo del casino web), icono adaptativo "LN".

Pendiente (fases siguientes): rifas, mis apuestas, límites/autoexclusión, casino
nativo y recibarga de saldo.

## Backend

Por defecto apunta a `http://100.89.167.67:18081`. Se puede cambiar con
`./gradlew -PLOTERIA_URL=https://... assembleDebug` o editando `LOTERIA_URL`
en `app/build.gradle.kts`.

## Build

```
export ANDROID_HOME=/mnt/d/AndroidSDK
./gradlew assembleDebug        # APK en app/build/outputs/apk/debug/app-debug.apk
```

El APK debug de esta fase está en `C:\Users\Peter\Downloads\LoteriaNacional-0.1.0-debug.apk`.
