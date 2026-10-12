const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/voice-test.html'), 'utf8')
    .match(/<script>([\s\S]*?)<\/script>/)[1];

function page() {
    const elements = new Map(), connections = [];
    const element = () => ({ value: '', style: {}, classList: { add() {} }, appendChild() {},
        addEventListener() {}, textContent: '', innerHTML: '', scrollHeight: 0 });
    const getElement = id => {
        if (!elements.has(id)) elements.set(id, element());
        return elements.get(id);
    };
    class Socket {
        static OPEN = 1;
        constructor(url, protocols) {
            this.readyState = 1;
            this.protocol = 'voice.v1';
            connections.push({ url, protocols });
        }
        close() { this.readyState = 3; }
    }
    const context = vm.createContext({
        document: { getElementById: getElement, createElement: element, createTextNode: text => ({ textContent: text }) },
        window: { addEventListener() {} }, location: { protocol: 'https:', host: 'shop.example' },
        WebSocket: Socket, crypto: { randomUUID: () => 'b1ee66b4-9c14-4468-9f3b-64d98d9ac501' },
        AudioContext: class { constructor() { throw new Error('fixture stops before microphone capture'); } },
        setTimeout() { return 1; }, clearTimeout() {},
    });
    vm.runInContext(script, context);
    vm.runInContext('state=State.IDLE;', context);
    return { context, connections, token: getElement('voice-token') };
}

test('no token cannot start a voice connection', async () => {
    const p = page();
    await vm.runInContext('start()', p.context);
    assert.equal(p.connections.length, 0);
    assert.equal(vm.runInContext('state', p.context), 'IDLE');
});

test('voice connection sends token as a subprotocol without credentials in the URL', async () => {
    const p = page(); p.token.value = 'valid-token';
    await vm.runInContext('start()', p.context);
    assert.equal(p.connections.length, 1);
    assert.deepEqual(Array.from(p.connections[0].protocols || []), ['voice.v1', 'Bearer-valid-token']);
    const url = new URL(p.connections[0].url);
    assert.equal(url.protocol, 'wss:');
    assert.equal(url.searchParams.get('sessionId'), 'b1ee66b4-9c14-4468-9f3b-64d98d9ac501');
    assert.equal(url.searchParams.has('userId'), false);
    assert.equal(url.searchParams.has('token'), false);
    assert.equal(url.toString().includes('valid-token'), false);
});

test('reconnecting retains the session while the token remains unchanged', async () => {
    const p = page(); p.token.value = 'valid-token';
    await vm.runInContext('start()', p.context);
    await vm.runInContext('start()', p.context);
    assert.equal(p.connections.length, 2);
    assert.equal(p.connections[0].url, p.connections[1].url);
});

test('a handshake that closes without opening does not leave the page waiting forever', async () => {
    const p = page(), listeners = new Map();
    p.context.__closing = {
        readyState: 0,
        addEventListener(name, fn) { listeners.set(name, fn); },
        removeEventListener(name) { listeners.delete(name); },
    };
    const pending = vm.runInContext('waitOpen(__closing)', p.context);
    const outcome = Promise.race([
        pending.then(() => 'opened', e => e.message),
        new Promise(resolve => setTimeout(() => resolve('still waiting'), 25)),
    ]);
    listeners.get('close')?.();
    assert.equal(await outcome, '连接未建立，请检查访问令牌或重新登录。');
});
