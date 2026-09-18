(() => {
    const VERSION = "0.3.1-diagnostic";
    const { React, ReactNative } = vendetta.metro.common;
    const TARGETS = ["YouBar", "BottomTab", "MainTabs", "Guilds", "ChannelDrawer", "ChannelList", "MemberList", "MemberRow", "UserProfile", "UserSettings", "Navigation", "StandaloneChannel"];

    function safeName(value) {
        if (typeof value !== "function") return "";
        return String(value.displayName || value.name || "");
    }

    function inspectModule(module) {
        if (!module || (typeof module !== "object" && typeof module !== "function")) return null;
        let keys;
        try { keys = Object.keys(module).slice(0, 60); } catch { return null; }
        const functionNames = [];
        const evidence = new Set();
        for (const key of keys) {
            let value;
            try { value = module[key]; } catch { continue; }
            const name = safeName(value);
            if (name) functionNames.push(name);
            const identity = `${key} ${name}`.toLowerCase();
            for (const target of TARGETS) {
                if (identity.includes(target.toLowerCase())) evidence.add(target);
            }
            if (typeof value === "function" && evidence.size < 4) {
                try {
                    const source = Function.prototype.toString.call(value).slice(0, 1800).toLowerCase();
                    for (const target of TARGETS) {
                        if (source.includes(target.toLowerCase())) evidence.add(target);
                    }
                } catch {}
            }
        }
        if (!evidence.size) return null;
        return {
            evidence: [...evidence].sort(),
            keys: keys.slice(0, 24),
            functions: [...new Set(functionNames)].slice(0, 18),
        };
    }

    function scanModules() {
        const found = [];
        const seen = new Set();
        if (typeof vendetta.metro.findAll !== "function") {
            return { error: "Esta compilación de Revenge no expone metro.findAll.", matches: [] };
        }
        try {
            vendetta.metro.findAll(module => {
                const match = inspectModule(module);
                if (!match) return false;
                const signature = JSON.stringify(match);
                if (!seen.has(signature) && found.length < 80) {
                    seen.add(signature);
                    found.push(match);
                }
                return false;
            });
        } catch (error) {
            return { error: String(error?.message || error), matches: found };
        }
        return { error: null, matches: found };
    }

    function buildReport(result) {
        const lines = [
            "OLD_DISCORD_UI_DIAGNOSTIC",
            `plugin=${VERSION}`,
            "discord=338.13 (6021)",
            `revenge=${globalThis.vendetta ? "available" : "missing"}`,
            `findAll=${typeof vendetta.metro.findAll}`,
            `matches=${result.matches.length}`,
        ];
        if (result.error) lines.push(`error=${result.error}`);
        result.matches.forEach((match, index) => {
            lines.push("", `[${index + 1}] evidence=${match.evidence.join(",")}`);
            lines.push(`keys=${match.keys.join(",")}`);
            lines.push(`functions=${match.functions.join(",") || "(anonymous)"}`);
        });
        return lines.join("\n");
    }

    function copyReport(report) {
        const clipboard = vendetta.metro.findByProps("setString") || vendetta.metro.findByProps("setStringAsync");
        try {
            if (typeof clipboard?.setString === "function") clipboard.setString(report);
            else if (typeof clipboard?.setStringAsync === "function") clipboard.setStringAsync(report);
            else throw new Error("Clipboard module not found");
            vendetta.ui.toasts.showToast("Informe copiado.");
        } catch (error) {
            vendetta.logger.warn("OldDiscordUI: no se pudo copiar el informe", error);
            vendetta.ui.toasts.showToast("No se pudo copiar. Mantén pulsado el informe para seleccionarlo.");
        }
    }

    function Button({ label, onPress, disabled }) {
        return React.createElement(ReactNative.Pressable, {
            disabled, onPress,
            style: { backgroundColor: disabled ? "#3F4147" : "#5865F2", borderRadius: 8, paddingHorizontal: 16, paddingVertical: 13, alignItems: "center" },
        }, React.createElement(ReactNative.Text, { style: { color: "white", fontSize: 15, fontWeight: "700" } }, label));
    }

    function Settings() {
        const [report, setReport] = React.useState(vendetta.plugin.storage.lastDiagnosticReport || "Pulsa «Analizar Discord 338.13» y espera unos segundos.");
        const [scanning, setScanning] = React.useState(false);
        function runScan() {
            if (scanning) return;
            setScanning(true);
            setReport("Analizando módulos de navegación…");
            setTimeout(() => {
                try {
                    const result = scanModules();
                    const nextReport = buildReport(result);
                    setReport(nextReport);
                    vendetta.plugin.storage.lastDiagnosticReport = nextReport;
                    vendetta.ui.toasts.showToast(result.error ? "Diagnóstico terminado con una advertencia." : `Diagnóstico terminado: ${result.matches.length} coincidencias.`);
                } catch (error) {
                    setReport(buildReport({ error: String(error?.message || error), matches: [] }));
                } finally { setScanning(false); }
            }, 250);
        }
        return React.createElement(ReactNative.ScrollView, { contentContainerStyle: { padding: 16, gap: 12 } },
            React.createElement(ReactNative.Text, { style: { color: "white", fontSize: 20, fontWeight: "800" } }, "OldDiscordUI — Diagnóstico"),
            React.createElement(ReactNative.Text, { style: { color: "#B5BAC1", fontSize: 14, lineHeight: 20 } }, "Busca solamente módulos relacionados con navegación y diseño. No lee mensajes, usuarios, tokens ni contenido de tus servidores."),
            React.createElement(Button, { label: scanning ? "Analizando…" : "Analizar Discord 338.13", disabled: scanning, onPress: runScan }),
            React.createElement(Button, { label: "Copiar informe", disabled: scanning || !report.startsWith("OLD_DISCORD_UI"), onPress: () => copyReport(report) }),
            React.createElement(ReactNative.Text, { selectable: true, style: { color: "#DBDEE1", backgroundColor: "#111214", borderRadius: 8, padding: 12, fontFamily: "monospace", fontSize: 11, lineHeight: 16 } }, report),
        );
    }

    return {
        onLoad() { vendetta.ui.toasts.showToast(`OldDiscordUI ${VERSION} listo para diagnosticar.`); },
        onUnload() {},
        settings: Settings,
    };
})()

