(() => {
    const VERSION = "1.0.0";
    const { React, ReactNative } = vendetta.metro.common;
    const storage = vendetta.plugin.storage;
    const restores = [];
    const defaults = { reduceAnimations: true, removeShadows: true, disableAutoplay: true };
    const option = name => storage[name] ?? defaults[name];

    function replace(target, key, factory) {
        if (!target || typeof target[key] !== "function") return false;
        const original = target[key];
        const replacement = factory(original);
        try { target[key] = replacement; } catch { return false; }
        restores.push(() => {
            try { if (target[key] === replacement) target[key] = original; } catch {}
        });
        return true;
    }

    function patchAnimations() {
        if (!option("reduceAnimations")) return 0;
        let count = 0;
        if (replace(ReactNative.Animated, "timing", original => function (value, config = {}) {
            const duration = Number(config.duration);
            return original.call(this, value, {
                ...config,
                duration: Number.isFinite(duration) ? Math.min(duration, 140) : 120,
            });
        })) count++;
        if (replace(ReactNative.Animated, "spring", original => function (value, config = {}) {
            return original.call(this, value, {
                ...config,
                speed: Math.max(Number(config.speed) || 12, 24),
                bounciness: Math.min(Number(config.bounciness) || 0, 2),
            });
        })) count++;
        if (replace(ReactNative.LayoutAnimation, "configureNext", original => function (config, ...rest) {
            if (!config || typeof config !== "object") return original.call(this, config, ...rest);
            return original.call(this, {
                ...config,
                duration: Math.min(Number(config.duration) || 120, 140),
            }, ...rest);
        })) count++;
        return count;
    }

    function patchShadows() {
        if (!option("removeShadows")) return 0;
        return replace(ReactNative.StyleSheet, "create", original => function (styles) {
            const next = {};
            for (const [name, style] of Object.entries(styles || {})) {
                if (!style || typeof style !== "object" || Array.isArray(style)) {
                    next[name] = style;
                    continue;
                }
                const clean = { ...style };
                if ("elevation" in clean) clean.elevation = 0;
                if ("shadowOpacity" in clean) clean.shadowOpacity = 0;
                if ("shadowRadius" in clean) clean.shadowRadius = 0;
                if ("shadowOffset" in clean) clean.shadowOffset = { width: 0, height: 0 };
                next[name] = clean;
            }
            return original.call(this, next);
        }) ? 1 : 0;
    }

    function patchSetting(setting, value, key) {
        if (!setting?.getSetting || !setting?.updateSetting) return false;
        try {
            if (!storage.originalSettings) storage.originalSettings = {};
            if (!(key in storage.originalSettings)) storage.originalSettings[key] = setting.getSetting();
            setting.updateSetting(value);
            restores.push(() => {
                const previous = storage.originalSettings?.[key];
                if (previous !== undefined) setting.updateSetting(previous);
            });
            return true;
        } catch { return false; }
    }

    function disableAutoplay() {
        if (!option("disableAutoplay")) return 0;
        let count = 0;
        for (const [prop, value, key] of [
            ["GifAutoPlay", false, "gifAutoplay"],
            ["AnimateEmoji", false, "animateEmoji"],
            ["AnimateStickers", 0, "animateStickers"],
        ]) {
            const module = vendetta.metro.findByProps(prop);
            if (module && patchSetting(module[prop], value, key)) count++;
        }
        return count;
    }

    function applyPatches() {
        const result = {
            animations: patchAnimations(),
            shadows: patchShadows(),
            autoplay: disableAutoplay(),
        };
        storage.lastResult = result;
        return result;
    }

    function Toggle({ label, note, name }) {
        const [enabled, setEnabled] = React.useState(option(name));
        return React.createElement(ReactNative.View, {
            style: { flexDirection: "row", alignItems: "center", paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: "#2B2D31" },
        },
        React.createElement(ReactNative.View, { style: { flex: 1, paddingRight: 12 } },
            React.createElement(ReactNative.Text, { style: { color: "white", fontSize: 15, fontWeight: "600" } }, label),
            React.createElement(ReactNative.Text, { style: { color: "#B5BAC1", fontSize: 12, marginTop: 3 } }, note),
        ),
        React.createElement(ReactNative.Switch, {
            value: enabled,
            onValueChange: value => {
                storage[name] = value;
                setEnabled(value);
                vendetta.ui.toasts.showToast("Reinicia Discord para aplicar el cambio.");
            },
        }));
    }

    function Settings() {
        const result = storage.lastResult || {};
        return React.createElement(ReactNative.ScrollView, { contentContainerStyle: { padding: 16 } },
            React.createElement(ReactNative.Text, { style: { color: "white", fontSize: 20, fontWeight: "800" } }, `Discord Performance ${VERSION}`),
            React.createElement(ReactNative.Text, { style: { color: "#B5BAC1", fontSize: 13, marginVertical: 10 } }, `Activos: animaciones ${result.animations || 0}, sombras ${result.shadows || 0}, autoplay ${result.autoplay || 0}.`),
            React.createElement(Toggle, { label: "Reducir animaciones", note: "Acorta transiciones y rebotes largos.", name: "reduceAnimations" }),
            React.createElement(Toggle, { label: "Eliminar sombras", note: "Reduce composición gráfica innecesaria.", name: "removeShadows" }),
            React.createElement(Toggle, { label: "Reducir autoplay", note: "Desactiva animaciones automáticas disponibles.", name: "disableAutoplay" }),
            React.createElement(ReactNative.Text, { style: { color: "#F0B232", fontSize: 12, marginTop: 14 } }, "No modifica llamadas, conexión ni mensajes."),
        );
    }

    return {
        onLoad() {
            try {
                const result = applyPatches();
                const total = Object.values(result).reduce((sum, value) => sum + value, 0);
                vendetta.ui.toasts.showToast(`Discord Performance activo: ${total} parches.`);
            } catch (error) {
                vendetta.logger.error("Discord Performance no pudo iniciar", error);
                vendetta.ui.toasts.showToast("Discord Performance no pudo iniciar.");
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

