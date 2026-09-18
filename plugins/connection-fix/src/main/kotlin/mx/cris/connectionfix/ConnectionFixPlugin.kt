@file:JvmName("ConnectionFixPlugin")

package mx.cris.connectionfix

import io.github.revenge.plugins.plugin
import io.github.revenge.xposed.api.registerNativeMethod

private const val METHOD = "mx.cris.connection-fix.repair"

@Suppress("UNUSED")
val connectionFixPlugin = plugin {
    start {
        registerNativeMethod(METHOD) {
            try {
                val provider = Class.forName(
                    "com.facebook.react.modules.network.OkHttpClientProvider",
                    true,
                    Thread.currentThread().contextClassLoader,
                )
                val client = provider.getMethod("getOkHttpClient").invoke(null)

                // Cancel only ordinary React Native HTTP requests and discard stale pooled sockets.
                // Discord voice is handled by WebRTC and is deliberately left untouched.
                val dispatcher = client.javaClass.getMethod("dispatcher").invoke(client)
                dispatcher.javaClass.getMethod("cancelAll").invoke(dispatcher)

                val pool = client.javaClass.getMethod("connectionPool").invoke(client)
                pool.javaClass.getMethod("evictAll").invoke(pool)

                log.i("Discord HTTP connection pool repaired")
                "Conexión de mensajes e imágenes renovada."
            } catch (error: Throwable) {
                log.e("Unable to repair Discord HTTP connection pool", error)
                throw error
            }
        }
    }
}
