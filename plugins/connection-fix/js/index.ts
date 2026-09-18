import { ToastActionCreators } from '@revenge-mod/discord/actions'
import {
    onSettingsModulesLoaded,
    registerSettingsItem,
    refreshSettings,
} from '@revenge-mod/discord/modules/settings'
import { callNativeMethod } from '@revenge-mod/modules/native'

const METHOD = 'mx.cris.connection-fix.repair' as const
const CHECK_URL = 'https://discord.com/api/v9/experiments'
const CHECK_EVERY_MS = 30_000
const FAILURE_LIMIT = 3

let timer: ReturnType<typeof setInterval> | undefined
let failures = 0
let repairing = false

function toast(content: string) {
    ToastActionCreators.open({
        key: `connection-fix-${Date.now()}`,
        content,
    })
}

async function repair(showResult = true) {
    if (repairing) return
    repairing = true
    try {
        const result = await callNativeMethod(METHOD, [])
        failures = 0
        if (showResult) toast(result)
    } catch (error) {
        console.error('[Connection Fix] Repair failed', error)
        if (showResult) toast('No se pudo reparar la conexión. Revisa los logs de Revenge.')
    } finally {
        repairing = false
    }
}

async function connectionCheck() {
    try {
        const controller = new AbortController()
        const timeout = setTimeout(() => controller.abort(), 10_000)
        const response = await fetch(CHECK_URL, {
            method: 'GET',
            cache: 'no-store',
            signal: controller.signal,
        })
        clearTimeout(timeout)

        if (!response.ok) throw new Error(`HTTP ${response.status}`)
        failures = 0
    } catch (error) {
        failures++
        console.warn(`[Connection Fix] Check failed (${failures}/${FAILURE_LIMIT})`, error)
        if (failures >= FAILURE_LIMIT) await repair(false)
    }
}

export default plugin({
    start({ cleanup }) {
        cleanup(
            onSettingsModulesLoaded(() => {
                cleanup(
                    registerSettingsItem('ConnectionFixRepair', {
                        parent: 'Revenge',
                        type: 'pressable',
                        useTitle: () => 'Reconectar chat ahora',
                        useDescription: () =>
                            'Renueva mensajes e imágenes sin cerrar Discord ni tocar la llamada de voz.',
                        onPress: () => void repair(true),
                    }),
                    refreshSettings,
                )
                refreshSettings()
            }),
        )

        timer = setInterval(() => void connectionCheck(), CHECK_EVERY_MS)
        cleanup(() => {
            if (timer !== undefined) clearInterval(timer)
            timer = undefined
        })
    },

    stop() {
        if (timer !== undefined) clearInterval(timer)
        timer = undefined
        failures = 0
    },
})

declare module '@revenge-mod/modules/native' {
    export interface NativeMethods {
        'mx.cris.connection-fix.repair': [args: [], returnValue: string]
    }
}
