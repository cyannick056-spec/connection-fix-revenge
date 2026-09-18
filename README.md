# OldDiscordUI

Plugin visual para Discord 338.13 (6021) con Revenge en Android 13.

La versión 0.3.0 aplica el modo clásico para Discord móvil:

- elimina la navegación inferior y superficies flotantes del rediseño;
- reduce tarjetas, bordes redondeados y espacios excesivos;
- elimina sombras y elevación modernas;
- conserva avatares e iconos realmente circulares;
- intercepta estilos creados con `StyleSheet`, `React.createElement` y el runtime JSX;
- restaura todos los parches al desactivar el plugin.

Discord 338.13 ya no incluye todos los componentes antiguos originales. El plugin reutiliza sus paneles laterales disponibles y oculta la navegación moderna para aproximar la interfaz clásica sin reemplazar el APK.

## Instalación

Añade como plugin la URL del `manifest.json` publicada en la rama `gh-pages`.

## Compatibilidad

- Discord: 338.13.x
- Android: 13
- Revenge sin root
