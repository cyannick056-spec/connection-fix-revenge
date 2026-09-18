(() => {
    const VERSION = "0.3.0";
    const PATCH_MARK = Symbol("OldDiscordUI.patched");
    const restores = [];

    const { React, ReactNative } = vendetta.metro.common;

    const hiddenModernComponents = [
        "YouBarFloatingShade",
        "YouBarNameplate",
        "MainTabsContentScrim",
        "BottomTabBar",
        "BottomTabs",
        "FloatingTabBar",
    ];

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
        return Math.round(value * 0.72);
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
                const replacement = Math.min(value, 2);
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

    function componentName(type) {
        if (typeof type === "string") return type;
        return String(type?.displayName || type?.name || "");
    }

    function shouldHideModernComponent(type) {
        const name = componentName(type);
        return hiddenModernComponents.some(part => name.includes(part));
    }

    function classicElementProps(type, props) {
        if (!props || typeof props !== "object") return props;
        const name = componentName(type);
        let next = classicProps(props);

        if (
            name.includes("GuildChannel") ||
            name.includes("ChannelList") ||
            name.includes("MemberRow") ||
            name.includes("MemberList") ||
            name.includes("Message")
        ) {
            next = {
                ...next,
                style: [
                    classicStyle(next?.style),
                    { borderRadius: 0, elevation: 0, shadowOpacity: 0 },
                ],
            };
        }

        return next;
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
            if (shouldHideModernComponent(type)) return null;
            return original.call(this, type, classicElementProps(type, props), ...children);
        });
    }

    function patchJsxRuntime() {
        const runtime = vendetta.metro.findByProps("jsx", "jsxs", "Fragment");
        if (!runtime) return;
        for (const key of ["jsx", "jsxs", "jsxDEV"]) {
            replaceMethod(runtime, key, original => function (type, props, ...rest) {
                if (shouldHideModernComponent(type)) return null;
                return original.call(this, type, classicElementProps(type, props), ...rest);
            });
        }
    }

    function patchDesignTokens() {
        const candidates = [
            vendetta.metro.findByProps("BACKGROUND_MOBILE_PRIMARY", "BACKGROUND_MOBILE_SECONDARY"),
            vendetta.metro.findByProps("CHANNEL_DRAWER_CORNER_RADIUS"),
            vendetta.metro.findByProps("CARD_RADIUS", "BUTTON_RADIUS"),
        ].filter(Boolean);

        for (const candidate of candidates) {
            const targets = [candidate, candidate.modules?.mobile].filter(Boolean);
            for (const target of targets) {
                for (const key of Object.keys(target)) {
                    if (!/RADIUS|ELEVATION|SHADOW/i.test(key)) continue;
                    const previous = target[key];
                    if (typeof previous !== "number") continue;
                    try {
                        target[key] = 0;
                        restores.push(() => {
                            try { target[key] = previous; } catch {}
                        });
                    } catch {}
                }
            }
        }
    }

    function patchModernNavigationSurfaces() {
        const modules = [
            vendetta.metro.findByProps("YouBarFloatingShade"),
            vendetta.metro.findByProps("MainTabsContentScrim"),
            vendetta.metro.findByProps("BottomTabBar"),
        ].filter(Boolean);

        for (const module of modules) {
            for (const key of Object.keys(module)) {
                const value = module[key];
                if (typeof value !== "function") continue;
                const name = componentName(value) || key;
                if (!hiddenModernComponents.some(part => name.includes(part) || key.includes(part))) continue;
                const original = value;
                const replacement = () => null;
                module[key] = replacement;
                restores.push(() => {
                    if (module[key] === replacement) module[key] = original;
                });
            }
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
                patchDesignTokens();
                patchModernNavigationSurfaces();
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
