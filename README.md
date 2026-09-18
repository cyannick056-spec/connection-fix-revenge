# OldDiscordUI

Plugin visual para Discord 338.13 (6021) con Revenge en Android 13.

La versión 0.2.0 aplica una capa visual clásica y compacta:

- reduce tarjetas, bordes redondeados y espacios excesivos;
- elimina sombras y elevación modernas;
- conserva avatares e iconos realmente circulares;
- intercepta estilos creados con `StyleSheet`, `React.createElement` y el runtime JSX;
- restaura todos los parches al desactivar el plugin.

Esta versión todavía no reemplaza la navegación por el drawer antiguo. Ese cambio necesita parches específicos para los módulos internos de Discord 338.13 y se desarrollará por separado.

## Instalación

Añade como plugin la URL del `manifest.json` publicada en la rama `gh-pages`.

## Compatibilidad

- Discord: 338.13.x
- Android: 13
- Revenge sin root

