(() => {
    const CHECK_URL = "https://discord.com/api/v9/experiments";
    const CHECK_EVERY_MS = 30_000;
    const CHECK_TIMEOUT_MS = 10_000;
    const FAILURE_LIMIT = 3;
    const RECONNECT_WINDOW_MS = 120_000;

    const { React, ReactNative, channels } = vendetta.metro.common;
    const storage = vendetta.plugin.storage;

    let timer;
    let failures = 0;
    let checking = false;
    let promptOpen = false;
    let unpatchHeader;

    function getUpdater() {
        const legacy = globalThis.nativeModuleProxy?.BundleUpdaterManager;
        if (legacy) return legacy;
        return globalThis.__turboModuleProxy?.("BundleUpdaterManager");
    }

    function getCurrentVoiceChannel() {
        try {
            const channelId = channels?.getVoiceChannelId?.();
            if (!channelId) return null;
            const channelStore = vendetta.metro.findByStoreName("ChannelStore");
            const channel = channelStore?.getChannel?.(channelId);
            return {
                channelId,
                guildId: channel?.guild_id,
                channelName: channel?.name || "el canal de voz",
            };
        } catch (error) {
            vendetta.logger.warn("No se pudo leer el canal de voz actual", error);
            return null;
        }
    }

    function rememberCall() {
        const voice = getCurrentVoiceChannel();
        if (!voice) {
            delete storage.pendingReconnect;
            return;
        }
        storage.pendingReconnect = { ...voice, requestedAt: Date.now() };
    }

    function reloadNow() {
        const updater = getUpdater();
        if (!updater?.reload) {
            vendetta.ui.toasts.showToast("No se encontró el recargador de Discord.");
            return;
        }
        rememberCall();
        vendetta.ui.toasts.showToast("Reconectando Discord…");
        setTimeout(() => updater.reload(), 400);
    }

    function findVoiceActions() {
        return (
            vendetta.metro.findByProps("selectVoiceChannel") ||
            vendetta.metro.findByProps("connectToVoiceChannel") ||
            vendetta.metro.findByProps("joinVoiceChannel")
        );
    }

    function joinVoice(pending) {
        try {
            const navigation = vendetta.metro.findByProps("transitionToGuild");
            navigation?.transitionToGuild?.(pending.guildId, pending.channelId);
            const actions = findVoiceActions();
            if (actions?.selectVoiceChannel) actions.selectVoiceChannel(pending.channelId);
            else if (actions?.connectToVoiceChannel) actions.connectToVoiceChannel(pending.channelId);
            else if (actions?.joinVoiceChannel) actions.joinVoiceChannel(pending.channelId);
            else throw new Error("No voice connection action found");
            vendetta.ui.toasts.showToast(`Reconectando a ${pending.channelName}…`);
        } catch (error) {
            vendetta.logger.error("No se pudo volver a la llamada", error);
            vendetta.ui.toasts.showToast("No se pudo volver automáticamente a la llamada.");
        }
    }

    function saveAutoChannel(pending) {
        storage.autoChannels = {
            ...(storage.autoChannels || {}),
            [pending.channelId]: {
                guildId: pending.guildId,
                channelName: pending.channelName,
            },
        };
    }

    function handlePendingReconnect() {
        const pending = storage.pendingReconnect;
        delete storage.pendingReconnect;
        if (!pending || Date.now() - pending.requestedAt > RECONNECT_WINDOW_MS) return;

        const autoChannels = storage.autoChannels || {};
        if (storage.autoReconnectEnabled !== false && autoChannels[pending.channelId]) {
            vendetta.ui.toasts.showToast(`Reconexión automática: ${pending.channelName}`);
            setTimeout(() => joinVoice(pending), 1_200);
            return;
        }

        vendetta.ui.alerts.showConfirmationAlert({
            title: "Volver a la llamada",
            content: `Estabas conectado a ${pending.channelName}. ¿Quieres regresar?`,
            confirmText: "Reconectar esta vez",
            secondaryConfirmText: "Siempre en este canal",
            cancelText: "No ahora",
            isDismissable: false,
            onConfirm: () => joinVoice(pending),
            onConfirmSecondary: () => {
                saveAutoChannel(pending);
                vendetta.ui.toasts.showToast(`Preferencia guardada para ${pending.channelName}.`);
                joinVoice(pending);
            },
        });
    }

    function showConnectionPrompt() {
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
            if (failures >= FAILURE_LIMIT) showConnectionPrompt();
        } finally {
            checking = false;
        }
    }

    function QuickButton() {
        return React.createElement(
            ReactNative.Pressable,
            {
                accessibilityLabel: "Reconectar Discord",
                onPress: reloadNow,
                style: {
                    position: "absolute",
                    right: 52,
                    top: 6,
                    zIndex: 1000,
                    width: 36,
                    height: 36,
                    borderRadius: 18,
                    alignItems: "center",
                    justifyContent: "center",
                    backgroundColor: "rgba(88, 101, 242, 0.92)",
                },
            },
            React.createElement(
                ReactNative.Text,
                { style: { color: "white", fontSize: 22, lineHeight: 25 } },
                "↻",
            ),
        );
    }

    function installQuickButton() {
        const headerModule = vendetta.metro.find(module => {
            if (!module || typeof module !== "object") return false;
            return Object.values(module).some(value => {
                if (typeof value !== "function") return false;
                const name = value.displayName || value.name || "";
                return name === "ChannelHeader" || name === "ChatHeader";
            });
        });
        if (!headerModule) {
            vendetta.logger.warn("No se encontró la cabecera del chat. El botón rápido no se mostrará.");
            return;
        }
        const key = Object.keys(headerModule).find(name => {
            const value = headerModule[name];
            if (typeof value !== "function") return false;
            const componentName = value.displayName || value.name || "";
            return componentName === "ChannelHeader" || componentName === "ChatHeader";
        });
        if (!key) return;
        unpatchHeader = vendetta.patcher.after(key, headerModule, (_args, result) =>
            React.createElement(React.Fragment, null, result, React.createElement(QuickButton)),
        );
    }

    function Settings() {
        const [autoEnabled, setAutoEnabled] = React.useState(
            storage.autoReconnectEnabled !== false,
        );
        const [savedCount, setSavedCount] = React.useState(
            Object.keys(storage.autoChannels || {}).length,
        );
        return React.createElement(
            ReactNative.ScrollView,
            { contentContainerStyle: { padding: 16, gap: 12 } },
            React.createElement(
                ReactNative.Text,
                { style: { color: "white", fontSize: 13, fontWeight: "700", marginBottom: 2 } },
                "CONEXIÓN",
            ),
            React.createElement(
                ReactNative.Pressable,
                {
                    onPress: reloadNow,
                    style: {
                        backgroundColor: "#5865F2",
                        borderRadius: 12,
                        paddingHorizontal: 16,
                        paddingVertical: 14,
                    },
                },
                React.createElement(
                    ReactNative.Text,
                    { style: { color: "white", fontSize: 16, fontWeight: "700" } },
                    "Reconectar ahora",
                ),
                React.createElement(
                    ReactNative.Text,
                    { style: { color: "#E3E5E8", fontSize: 13, marginTop: 4 } },
                    "Recarga Discord y recuerda la llamada actual.",
                ),
            ),
            React.createElement(
                ReactNative.View,
                {
                    style: {
                        backgroundColor: "#2B2D31",
                        borderRadius: 12,
                        paddingHorizontal: 16,
                        paddingVertical: 12,
                        flexDirection: "row",
                        alignItems: "center",
                    },
                },
                React.createElement(
                    ReactNative.View,
                    { style: { flex: 1, paddingRight: 12 } },
                    React.createElement(
                        ReactNative.Text,
                        { style: { color: "white", fontSize: 15, fontWeight: "600" } },
                        "Reconexión automática de voz",
                    ),
                    React.createElement(
                        ReactNative.Text,
                        { style: { color: "#B5BAC1", fontSize: 13, marginTop: 4 } },
                        "Solo se usa en canales donde elegiste Siempre en este canal.",
                    ),
                ),
                React.createElement(ReactNative.Switch, {
                    value: autoEnabled,
                    onValueChange: value => {
                        storage.autoReconnectEnabled = value;
                        setAutoEnabled(value);
                    },
                }),
            ),
            React.createElement(
                ReactNative.Pressable,
                {
                    disabled: savedCount === 0,
                    onPress: () => {
                        storage.autoChannels = {};
                        setSavedCount(0);
                        vendetta.ui.toasts.showToast("Preferencias de voz eliminadas.");
                    },
                    style: {
                        backgroundColor: "#2B2D31",
                        borderRadius: 12,
                        paddingHorizontal: 16,
                        paddingVertical: 14,
                        opacity: savedCount === 0 ? 0.5 : 1,
                    },
                },
                React.createElement(
                    ReactNative.Text,
                    { style: { color: "white", fontSize: 15, fontWeight: "600" } },
                    "Borrar canales guardados",
                ),
                React.createElement(
                    ReactNative.Text,
                    { style: { color: "#B5BAC1", fontSize: 13, marginTop: 4 } },
                    `${savedCount} canal(es) guardado(s).`,
                ),
            ),
            React.createElement(
                ReactNative.Text,
                { style: { color: "#B5BAC1", fontSize: 13, lineHeight: 19 } },
                "El detector comprueba la conexión cada 30 segundos y pregunta después de tres fallos.",
            ),
        );
    }

    return {
        onLoad() {
            setTimeout(() => {
                try {
                    installQuickButton();
                } catch (error) {
                    vendetta.logger.warn(
                        "No se pudo instalar el botón rápido. El resto del plugin seguirá activo.",
                        error,
                    );
                }
            }, 2_000);
            timer = setInterval(checkConnection, CHECK_EVERY_MS);
            setTimeout(handlePendingReconnect, 1_500);
            vendetta.ui.toasts.showToast("Connection Fix está activo.");
        },
        onUnload() {
            if (timer) clearInterval(timer);
            unpatchHeader?.();
            timer = undefined;
            unpatchHeader = undefined;
            failures = 0;
            checking = false;
            promptOpen = false;
        },
        settings: Settings,
    };
})()
