const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/voice-test.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function pageWithSocket(readyState) {
    const frames = [];
    const element = () => ({
        style: {}, classList: { add() {} }, appendChild() {}, addEventListener() {},
        textContent: '', innerHTML: '', scrollHeight: 0,
    });
    const elements = new Map();
    const socket = {
        readyState,
        send(frame) {
            if (typeof frame === 'string') { frames.push(frame); return; }
            // Match the real server's binary message limit, rather than accepting any frame.
            if (frame.byteLength > 8192) throw new RangeError('WebSocket message exceeds 8192 bytes');
            frames.push(new Uint8Array(frame));
        },
    };
    const context = vm.createContext({
        document: {
            getElementById(id) {
                if (!elements.has(id)) elements.set(id, element());
                return elements.get(id);
            },
            createElement: element,
            createTextNode: text => ({ textContent: text }),
        },
        window: { addEventListener() {} },
        location: { protocol: 'http:', host: 'localhost:8080' },
        WebSocket: { OPEN: 1 },
        setTimeout() { return 1; }, clearTimeout() {},
        __socket: socket,
    });
    vm.runInContext(script, context);
    vm.runInContext('state = State.RECORDING; ws = __socket; recordStartAt = Date.now() - 2000;', context);
    return { context, frames };
}

test('stopping recording sends the complete silence tail without exceeding the server limit', async () => {
    const { context, frames } = pageWithSocket(1);
    await vm.runInContext('stop()', context);

    assert.deepEqual(JSON.parse(frames.at(-1)), {type: 'input_end'});
    const audio = frames.slice(0, -1);
    assert.ok(audio.length > 0);
    assert.equal(audio.reduce((total, frame) => total + frame.byteLength, 0), 25600);
    for (const frame of audio) {
        assert.ok(frame.byteLength <= 8192);
        assert.ok(frame.every(sample => sample === 0));
    }
    assert.equal(vm.runInContext('state', context), 'WAITING');
});

test('stopping after the socket closes does not try to send audio', async () => {
    const { context, frames } = pageWithSocket(3);
    await vm.runInContext('stop()', context);
    assert.equal(frames.length, 0);
});
