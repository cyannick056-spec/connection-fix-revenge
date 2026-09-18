(() => {
    const CHECK_URL = "https://discord.com/api/v9/experiments";
    const CHECK_EVERY_MS = 30_000;
    const CHECK_TIMEOUT_MS = 10_000;
    const FAILURE_LIMIT = 3;

    const { React, ReactNative } = vendetta.metro.common;
    const { FormRow, FormSection, FormText } = vendetta.ui.components.Forms;

    let timer;
    let failures = 0;
    let checking = false;
    let promptOpen = false;

    function getUpdater() {
        const legacy = globalThis.nativeModuleProxy?.BundleUpdaterManager;
        if (legacy) return legacy;

        return globalThis.__turboModuleProxy?.("BundleUpdaterManager");
    }

    function reloadNow() {
        const updater = getUpdater();
        if (!updater?.reload) {
            vendetta.ui.toasts.showToast("No se encontró el recargador de Discord.");
            return;
        }

        vendetta.ui.toasts.showToast("Reconectando Discord…");
        setTimeout(() => updater.reload(), 400);
    }

    function showReconnectPrompt() {
        if (promptOpen) return;
        promptOpen = true;

        vendetta.ui.alerts.showConfirmationAlert({
            title: "La conexión parece bloqueada",
            content: "Discord falló tres comprobaciones seguidas. ¿Quieres reconectar ahora? La llamada podría cortarse unos segundos.",
            confirmText: "Reconectar",
            cancelText: "Ignorar",
            isDismissable: false,
            onConfirm: () => {
                promptOpen = false;
                failures = 0;
                reloadNow();
            },
            onCancel: () => {
                promptOpen = false;
                failures = 0;
            },
        });
    }

    async function checkConnection() {
        if (checking || promptOpen || ReactNative.AppState?.currentState !== "active") return;
        checking = true;

        try {
            const response = await vendetta.utils.safeFetch(
                CHECK_URL,
                { cache: "no-store" },
                CHECK_TIMEOUT_MS,
            );
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            failures = 0;
        } catch (error) {
            failures += 1;
            vendetta.logger.warn(`Comprobación fallida (${failures}/${FAILURE_LIMIT})`, error);
            if (failures >= FAILURE_LIMIT) showReconnectPrompt();
        } finally {
            checking = false;
        }
    }

    function Settings() {
        return React.createElement(
            FormSection,
            { title: "CONEXIÓN" },
            React.createElement(FormRow, {
                label: "Reconectar ahora",
                subLabel: "Recarga Discord para recuperar mensajes e imágenes.",
                onPress: reloadNow,
            }),
            React.createElement(
                FormText,
                null,
                "El aviso semiautomático comprueba la conexión cada 30 segundos y pregunta después de tres fallos.",
            ),
        );
    }

    return {
        onLoad() {
            timer = setInterval(checkConnection, CHECK_EVERY_MS);
            vendetta.ui.toasts.showToast("Connection Fix está activo.");
        },
        onUnload() {
            if (timer) clearInterval(timer);
            timer = undefined;
            failures = 0;
            checking = false;
            promptOpen = false;
        },
        settings: Settings,
    };
})()
