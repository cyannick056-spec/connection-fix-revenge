const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const source = fs.readFileSync(`${__dirname}/index.js`, "utf8");
const storage = {};
const intervals = [];
const timeouts = [];
const alerts = [];
const toasts = [];
const voiceJoins = [];
let fetchResult = { ok: true, status: 200 };
let reloads = 0;

function Component() {}

function createContext() {
    const context = {
        vendetta: {
            plugin: { storage },
            metro: {
                common: {
                    React: {
                        Fragment: Symbol("Fragment"),
                        createElement: (type, props, ...children) => ({ type, props: props || {}, children }),
                        useState: initial => [initial, () => {}],
                    },
                    ReactNative: {
                        AppState: { currentState: "active" },
                        Pressable: Component,
                        Text: Component,
                        View: Component,
                        ScrollView: Component,
                        Switch: Component,
                    },
                    channels: { getVoiceChannelId: () => "voice-1" },
                },
                find: () => undefined,
                findByProps: prop => {
                    if (prop === "selectVoiceChannel") {
                        return { selectVoiceChannel: channelId => voiceJoins.push(channelId) };
                    }
                    if (prop === "transitionToGuild") return { transitionToGuild() {} };
                    return undefined;
                },
                findByStoreName: name =>
                    name === "ChannelStore"
                        ? { getChannel: () => ({ guild_id: "guild-1", name: "General" }) }
                        : undefined,
            },
            patcher: { after: () => () => {} },
            ui: {
                components: { Forms: {} },
                alerts: { showConfirmationAlert: options => alerts.push(options) },
                toasts: { showToast: message => toasts.push(message) },
            },
            utils: { safeFetch: async () => fetchResult },
            logger: { warn() {}, error() {} },
        },
        nativeModuleProxy: { BundleUpdaterManager: { reload: () => reloads++ } },
        setInterval: callback => (intervals.push(callback), intervals.length),
        clearInterval() {},
        setTimeout: (callback, delay) => (timeouts.push({ callback, delay }), timeouts.length),
        console,
    };
    context.globalThis = context;
    return context;
}

function loadPlugin() {
    return vm.runInNewContext(source, createContext());
}

let plugin = loadPlugin();
assert.equal(typeof plugin.onLoad, "function");
assert.equal(typeof plugin.settings, "function");
plugin.onLoad();
assert.equal(intervals.length, 1);
assert.ok(timeouts.some(item => item.delay === 2_000));

const settings = plugin.settings();
const manualButton = settings.children[1];
manualButton.props.onPress();
assert.equal(storage.pendingReconnect.channelId, "voice-1");
timeouts.find(item => item.delay === 400).callback();
assert.equal(reloads, 1);

plugin = loadPlugin();
plugin.onLoad();
timeouts.filter(item => item.delay === 1_500).at(-1).callback();
const voicePrompt = alerts.at(-1);
assert.equal(voicePrompt.title, "Volver a la llamada");
voicePrompt.onConfirmSecondary();
assert.equal(storage.autoChannels["voice-1"].channelName, "General");
assert.deepEqual(voiceJoins, ["voice-1"]);

fetchResult = { ok: false, status: 503 };
(async () => {
    const check = intervals.at(-1);
    await check();
    await check();
    await check();
    assert.equal(alerts.at(-1).title, "La conexión parece bloqueada");
    plugin.onUnload();
    console.log("Legacy plugin tests passed");
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
