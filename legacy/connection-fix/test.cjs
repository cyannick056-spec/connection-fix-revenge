const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const source = fs.readFileSync(`${__dirname}/index.js`, "utf8");
const intervals = [];
const timeouts = [];
const alerts = [];
const toasts = [];
let fetchResult = { ok: true, status: 200 };
let reloads = 0;

function Component() {}
Component.prototype = {};

const context = {
    vendetta: {
        metro: {
            common: {
                React: { createElement: (type, props, ...children) => ({ type, props: props || {}, children }) },
                ReactNative: { AppState: { currentState: "active" } },
            },
        },
        ui: {
            components: { Forms: { FormRow: Component, FormSection: Component, FormText: Component } },
            alerts: { showConfirmationAlert: options => alerts.push(options) },
            toasts: { showToast: message => toasts.push(message) },
        },
        utils: { safeFetch: async () => fetchResult },
        logger: { warn() {} },
    },
    globalThis: null,
    nativeModuleProxy: { BundleUpdaterManager: { reload: () => reloads++ } },
    setInterval: callback => (intervals.push(callback), intervals.length),
    clearInterval() {},
    setTimeout: callback => (timeouts.push(callback), timeouts.length),
    console,
};
context.globalThis = context;

const plugin = vm.runInNewContext(source, context);
assert.equal(typeof plugin.onLoad, "function");
assert.equal(typeof plugin.onUnload, "function");
assert.equal(typeof plugin.settings, "function");

plugin.onLoad();
assert.equal(intervals.length, 1);
assert.equal(toasts[0], "Connection Fix está activo.");

const settings = plugin.settings();
const manualRow = settings.children[0];
manualRow.props.onPress();
assert.equal(timeouts.length, 1);
timeouts.shift()();
assert.equal(reloads, 1);

fetchResult = { ok: false, status: 503 };
(async () => {
    await intervals[0]();
    await intervals[0]();
    await intervals[0]();
    assert.equal(alerts.length, 1);
    assert.equal(alerts[0].confirmText, "Reconectar");
    alerts[0].onConfirm();
    timeouts.shift()();
    assert.equal(reloads, 2);
    plugin.onUnload();
    console.log("Legacy plugin tests passed");
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
