# ZX Sofá

Emulador de ZX Spectrum 128K para Android (móvil, tablet y Android TV) con tu propia
colección de juegos, portadas descargadas de internet y controles táctiles o de mando.

- **Emulación**: núcleo [Fuse](https://fuse-emulator.sourceforge.net/) (versión libretro), muy preciso.
  Modelos 16K, 48K, 128K, +2, +2A y +3. Formatos: `.tap .tzx .pzx .z80 .szx .sna .dsk .trd .scl .rzx`
  y `.zip` (se extrae el juego de dentro).
- **Colección local**: SQLite en el propio dispositivo. Tú añades los juegos.
- **Portadas y datos**: al añadir un juego se identifica por la huella MD5 del fichero en
  ZXDB (vía la API de [ZXInfo](https://api.zxinfo.dk/v3/)); si no, por título. Además, la
  pantalla «Buscar portada» busca en ZXDB y en [libretro-thumbnails](https://github.com/libretro-thumbnails/Sinclair_-_ZX_Spectrum),
  o deja elegir una imagen propia.
- **Partidas**: guardar/cargar en cualquier momento y «Continuar» donde lo dejaste.

## Compilar e instalar

1. Instala [Android Studio](https://developer.android.com/studio) (cualquier versión reciente).
2. *File → Open…* y elige esta carpeta `ZXSofa`. Espera a que termine la sincronización de Gradle.
3. Si te lo pide, instala desde *Tools → SDK Manager → SDK Tools*:
   - **NDK (Side by side)**
   - **CMake 3.22.1**
4. Conecta el móvil o la tele con la *depuración USB* (o *depuración por red* en Android TV)
   activada y pulsa ▶ *Run*.

Para sacar un APK e instalarlo a mano: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. Para la tele puedes copiarlo
con una app tipo *Send Files to TV* o con `adb install app-debug.apk`.

> La primera compilación tarda unos minutos porque compila el núcleo Fuse en C para
> tres arquitecturas (arm64, armv7 y x86_64).

## Uso

### Añadir juegos
- **Desde archivo** (botón `+`): elige uno o varios ficheros.
- **Desde otro dispositivo** (botón Wi-Fi): pensado para la tele. Abre en el navegador del
  móvil o del PC la dirección que aparece (p. ej. `http://192.168.1.40:8080`) y envía los juegos.

Cada juego nuevo se identifica solo y baja su portada. Si no acierta, entra en la ficha y
pulsa **Buscar portada** o **Editar**.

### Controles
| | Táctil | Mando Bluetooth | Mando de la tele |
|---|---|---|---|
| Joystick | Cruceta izquierda | Cruceta / stick | Flechas |
| Disparo | FUEGO | A, X, Y | OK |
| Saltar (arriba) | ↑ | B | — |
| ENTER / ESPACIO | Botones verdes | L1 / R1 | — |
| Teclado del Spectrum | Icono de teclado | SELECT | Menú → Teclado |
| Menú (pausa, guardar…) | Icono ☰ o Atrás | START | Atrás |

En la ficha de cada juego (**Editar**) eliges el modelo de Spectrum, el tipo de joystick
(Kempston, Sinclair, Cursor o teclas personalizadas como QAOP + Espacio) y qué teclas hacen
los botones extra.

Con un teclado físico conectado, las teclas van directas al Spectrum
(Mayúsculas = CAPS SHIFT, Ctrl/Alt = SYMBOL SHIFT).

## Estructura

```
app/src/main/cpp/            Núcleo nativo
  fuse-libretro/             Fuse para libretro (GPLv3, copia de github.com/libretro/fuse-libretro)
  zxcore.c/.h                Mini «frontend» libretro: vídeo, audio, entrada, partidas
  jni_bridge.c               Puente JNI con Kotlin
  test/zxtest.c              Prueba de escritorio (Linux) del núcleo
app/src/main/java/com/zxsofa/
  emu/                       Hilo del emulador, opciones del núcleo, teclas
  data/                      Base de datos SQLite, importación, ajustes
  net/                       Búsqueda de portadas (ZXInfo, libretro) y servidor Wi-Fi
  ui/                        Pantallas en Jetpack Compose (catálogo, ficha, juego…)
```

Para probar el núcleo en Linux sin Android:
```
cmake -S app/src/main/cpp -B build-host && cmake --build build-host -j
./build-host/zxtest juego.tap "Spectrum 128K" 300 salida
```

## Licencia

Fuse se distribuye bajo GPL v3, así que esta app también (ver `LICENSE`). Las ROM del
Spectrum incluidas en Fuse se distribuyen con permiso de Amstrad. Los juegos no van
incluidos: usa copias que tengas.
