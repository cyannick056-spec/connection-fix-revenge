# Connection Fix para Revenge

Compatible con Discord Android 338.x y Revenge API 1.x.

## Qué hace

- Agrega **Ajustes → Revenge → Reconectar chat ahora**.
- Cada 30 segundos comprueba que la API de Discord responda.
- Después de tres fallos consecutivos, cancela las solicitudes HTTP bloqueadas y vacía el pool de conexiones de React Native.
- No reinicia Discord, no cambia el Wi‑Fi y no toca WebRTC, por lo que está diseñado para mantener la llamada de voz.

## Instalación

Instala el ZIP del plugin desde el administrador de plugins de Revenge y reinicia Discord una sola vez para cargarlo. Después, si mensajes o imágenes se congelan, usa **Reconectar chat ahora**.

## Nota

Es una primera versión dirigida a Discord 338.13. Si el botón muestra un error, abre los logs de Revenge y comparte la entrada `Connection Fix`; el nombre interno del cliente HTTP puede variar en futuras versiones de Discord.
