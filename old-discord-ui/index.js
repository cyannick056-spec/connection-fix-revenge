(() => {
    const VERSION = "0.2.0";
    const PATCH_MARK = Symbol("OldDiscordUI.patched");
    const restores = [];

    const { React, ReactNative } = vendetta.metro.common;

    const radiusKeys = new Set([
        "borderRadius",
        "borderTopLeftRadius",
        "borderTopRightRadius",
        "borderBottomLeftRadius",
        "borderBottomRightRadius",
        "borderStartStartRadius",
        "borderStartEndRadius",
        "borderEndStartRadius",
        "borderEndEndRadius",
    ]);

    const spacingKeys = new Set([
        "padding", "paddingHorizontal", "paddingVertical", "paddingTop",
        "paddingRight", "paddingBottom", "paddingLeft", "paddingStart", "paddingEnd",
        "margin", "marginHorizontal", "marginVertical", "marginTop",
        "marginRight", "marginBottom", "marginLeft", "marginStart", "marginEnd",
        "gap", "rowGap", "columnGap",
    ]);

    function isCircle(style) {
        const width = Number(style.width);
        const height = Number(style.height);
        const radius = Number(style.borderRadius);
        if (Number.isFinite(width) && Number.isFinite(height) && width === height) {
            return radius >= width / 2;
        }
        return radius >= 999;
    }

    function compactNumber(value) {
        if (!Number.isFinite(value) || Math.abs(value) < 12) return value;
        return Math.round(value * 0.82);
    }

    function classicStyle(style) {
        if (!style || typeof style !== "object") return style;
        if (Array.isArray(style)) return style.map(classicStyle);

        const circular = isCircle(style);
        let changed = false;
        const next = { ...style };

        for (const key of Object.keys(next)) {
            const value = next[key];

            if (radiusKeys.has(key) && typeof value === "number" && !circular) {
                const replacement = Math.min(value, 4);
                if (replacement !== value) {
                    next[key] = replacement;
                    changed = true;
                }
                continue;
            }

            if (spacingKeys.has(key) && typeof value === "number") {
                const replacement = compactNumber(value);
                if (replacement !== value) {
                    next[key] = replacement;
                    changed = true;
                }
                continue;
            }

            if (key === "elevation" && value !== 0) {
                next[key] = 0;
                changed = true;
                continue;
            }

            if (key === "shadowOpacity" && value !== 0) {
                next[key] = 0;
                changed = true;
                continue;
            }

            if ((key === "shadowRadius" || key === "shadowOffset") && value != null) {
                next[key] = key === "shadowRadius" ? 0 : { width: 0, height: 0 };
                changed = true;
            }
        }

        return changed ? next : style;
    }

    function classicProps(props) {
        if (!props || typeof props !== "object" || !props.style) return props;
        const style = classicStyle(props.style);
        return style === props.style ? props : { ...props, style };
    }

    function replaceMethod(target, key, wrapper) {
        if (!target || typeof target[key] !== "function" || target[key][PATCH_MARK]) return false;
        const original = target[key];
        const replacement = wrapper(original);
        Object.defineProperty(replacement, PATCH_MARK, { value: true });
        target[key] = replacement;
        restores.push(() => {
            if (target[key] === replacement) target[key] = original;
        });
        return true;
    }

    function patchStyleSheet() {
        replaceMethod(ReactNative.StyleSheet, "create", original => function (styles) {
            const transformed = {};
            for (const [name, style] of Object.entries(styles || {})) {
                transformed[name] = classicStyle(style);
            }
            return original.call(this, transformed);
        });
    }

    function patchCreateElement() {
        replaceMethod(React, "createElement", original => function (type, props, ...children) {
            return original.call(this, type, classicProps(props), ...children);
        });
    }

    function patchJsxRuntime() {
        const runtime = vendetta.metro.findByProps("jsx", "jsxs", "Fragment");
        if (!runtime) return;
        for (const key of ["jsx", "jsxs", "jsxDEV"]) {
            replaceMethod(runtime, key, original => function (type, props, ...rest) {
                return original.call(this, type, classicProps(props), ...rest);
            });
        }
    }

    function restoreAll() {
        while (restores.length) {
            try {
                restores.pop()();
            } catch (error) {
                vendetta.logger.warn("OldDiscordUI: no se pudo restaurar un parche", error);
            }
        }
    }

    return {
        onLoad() {
            try {
                patchStyleSheet();
                patchCreateElement();
                patchJsxRuntime();
                vendetta.ui.toasts.showToast(`OldDiscordUI ${VERSION} — Carga confirmada`);
            } catch (error) {
                restoreAll();
                vendetta.logger.error("OldDiscordUI no pudo iniciarse", error);
                vendetta.ui.toasts.showToast("OldDiscordUI no pudo iniciarse.");
            }
        },
        onUnload() {
            restoreAll();
            vendetta.ui.toasts.showToast("OldDiscordUI desactivado. Reinicia Discord para refrescar toda la interfaz.");
        },
    };
})()

