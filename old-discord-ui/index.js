(() => {
    const VERSION = "0.4.0";
    const { React, ReactNative } = vendetta.metro.common;
    const restores = [];
    const storage = vendetta.plugin.storage;

    function replace(target, key, replacement) {
        if (!target || typeof target[key] !== "function") return false;
        const original = target[key];
        target[key] = replacement;
        restores.push(() => {
            if (target[key] === replacement) target[key] = original;
        });
        return true;
    }

    function setValue(target, key, value) {
        if (!target || !(key in target)) return false;
        const original = target[key];
        try {
            target[key] = value;
            restores.push(() => {
                try { target[key] = original; } catch {}
            });
            return true;
        } catch {
            return false;
        }
    }

    function patchSetting(setting, value, name) {
        if (!setting?.getSetting || !setting?.updateSetting) return false;
        try {
            if (!storage.originalSettings) storage.originalSettings = {};
            if (!(name in storage.originalSettings)) {
                storage.originalSettings[name] = setting.getSetting();
            }
            setting.updateSetting(value);
            restores.push(() => {
                const previous = storage.originalSettings?.[name];
                if (previous !== undefined) setting.updateSetting(previous);
            });
            return true;
        } catch (error) {
            vendetta.logger.warn(`OldDiscordUI: no se pudo cambiar ${name}`, error);
            return false;
        }
    }

    function disableSettingsRedesign() {
        const experiments = vendetta.metro.findByProps(
            "UserSettingsRedesign4CExperiment",
            "UserSettingsRedesign4DExperiment",
        );
        if (!experiments) return 0;
        let count = 0;
        for (const key of [
            "useIsEligibleForUserSettingsRedesign4CExperiment",
            "getIsEligibleForUserSettingsRedesign4CExperiment",
            "useIsEligibleForUserSettingsRedesign4DExperiment",
            "getIsEligibleForUserSettingsRedesign4DExperiment",
        ]) {
            if (replace(experiments, key, () => false)) count++;
        }
        return count;
    }

    function configureClassicGestures() {
        const userSettings = vendetta.metro.findByProps(
            "LaunchPadModeSetting",
            "SwipeRightToLeftModeSetting",
        );
        if (!userSettings) return 0;
        let count = 0;
        if (patchSetting(userSettings.LaunchPadModeSetting, 0, "launchPadMode")) count++;
        if (patchSetting(userSettings.SwipeRightToLeftModeSetting, 1, "swipeRightToLeftMode")) count++;
        return count;
    }

    function configureLegacyChat() {
        const userSettings = vendetta.metro.findByProps(
            "UseLegacyChatInput",
            "UseThreadSidebar",
        );
        if (!userSettings) return 0;
        let count = 0;
        if (patchSetting(userSettings.UseLegacyChatInput, true, "useLegacyChatInput")) count++;
        if (patchSetting(userSettings.UseThreadSidebar, true, "useThreadSidebar")) count++;
        return count;
    }

    function compactProfiles() {
        const profile = vendetta.metro.findByProps(
            "PROFILE_SIDE_PADDING",
            "CARD_PADDING",
            "PROFILE_CONTENT_BOTTOM_PADDING",
        );
        if (!profile) return 0;
        let count = 0;
        for (const [key, value] of [
            ["PROFILE_SIDE_PADDING", 10],
            ["CARD_PADDING", 8],
            ["PROFILE_CONTENT_BOTTOM_PADDING", 8],
            ["PROFILE_CONTENT_WITHOUT_STATUS_TOP_PADDING", 8],
            ["AVATAR_CUSTOM_STATUS_GAP", 4],
        ]) {
            if (setValue(profile, key, value)) count++;
        }
        return count;
    }

    function flattenKnownStyles() {
        const StyleSheet = ReactNative.StyleSheet;
        if (!StyleSheet?.create) return 0;
        const original = StyleSheet.create;
        const replacement = function (styles) {
            const next = {};
            for (const [name, style] of Object.entries(styles || {})) {
                if (!style || typeof style !== "object" || Array.isArray(style)) {
                    next[name] = style;
                    continue;
                }
                const changed = { ...style };
                for (const key of Object.keys(changed)) {
                    if (/border.*radius/i.test(key) && typeof changed[key] === "number") {
                        const width = Number(changed.width);
                        const height = Number(changed.height);
                        const circle = width > 0 && width === height && changed[key] >= width / 2;
                        if (!circle) changed[key] = Math.min(changed[key], 3);
                    }
                    if (key === "elevation" || key === "shadowOpacity" || key === "shadowRadius") {
                        changed[key] = 0;
                    }
                }
                next[name] = changed;
            }
            return original.call(this, next);
        };
        StyleSheet.create = replacement;
        restores.push(() => {
            if (StyleSheet.create === replacement) StyleSheet.create = original;
        });
        return 1;
    }

    function applyClassicMode() {
        const result = {
            experiments: disableSettingsRedesign(),
            gestures: configureClassicGestures(),
            chat: configureLegacyChat(),
            profile: compactProfiles(),
            styles: flattenKnownStyles(),
        };
        storage.lastPatchResult = result;
        return result;
    }

    function Settings() {
        const result = storage.lastPatchResult || {};
        const rows = [
            ["Experimentos modernos anulados", result.experiments || 0],
            ["Gestos clásicos configurados", result.gestures || 0],
            ["Opciones clásicas de chat", result.chat || 0],
            ["Constantes de perfil compactadas", result.profile || 0],
            ["Interceptor de estilos", result.styles || 0],
        ];
        return React.createElement(
            ReactNative.ScrollView,
            { contentContainerStyle: { padding: 16, gap: 12 } },
            React.createElement(
                ReactNative.Text,
                { style: { color: "white", fontSize: 20, fontWeight: "800" } },
                `OldDiscordUI ${VERSION}`,
            ),
            React.createElement(
                ReactNative.Text,
                { style: { color: "#B5BAC1", fontSize: 14, lineHeight: 20 } },
                "Modo clásico dirigido para Discord 338.13. Reinicia Discord después de activarlo.",
            ),
            ...rows.map(([label, value]) =>
                React.createElement(
                    ReactNative.View,
                    {
                        key: label,
                        style: {
                            flexDirection: "row",
                            justifyContent: "space-between",
                            paddingVertical: 10,
                            borderBottomWidth: 1,
                            borderBottomColor: "#2B2D31",
                        },
                    },
                    React.createElement(ReactNative.Text, { style: { color: "#DBDEE1", flex: 1 } }, label),
                    React.createElement(ReactNative.Text, { style: { color: value ? "#23A55A" : "#F0B232", fontWeight: "700" } }, String(value)),
                ),
            ),
        );
    }

    return {
        onLoad() {
            try {
                const result = applyClassicMode();
                const total = Object.values(result).reduce((sum, value) => sum + value, 0);
                vendetta.ui.toasts.showToast(`OldDiscordUI ${VERSION}: ${total} parches aplicados. Reinicia Discord.`);
            } catch (error) {
                vendetta.logger.error("OldDiscordUI no pudo iniciar", error);
                vendetta.ui.toasts.showToast("OldDiscordUI no pudo aplicar el modo clásico.");
            }
        },
        onUnload() {
            while (restores.length) {
                try { restores.pop()(); } catch {}
            }
        },
        settings: Settings,
    };
})()

