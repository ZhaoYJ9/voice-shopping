const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/voice-test.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function page() {
    let now = 0, timerId = 0, closeCount = 0;
    const timers = new Map();
    const element = () => ({style: {}, classList: {add() {}}, appendChild() {}, addEventListener() {},
        textContent: '', innerHTML: '', scrollHeight: 0});
    const socket = {readyState: 1, send() {}, close() {closeCount++; this.readyState = 3;}};
    const context = vm.createContext({document: {getElementById: element, createElement: element,
        createTextNode: text => ({textContent: text})}, window: {addEventListener() {}},
        location: {protocol: 'http:', host: 'localhost:8080'}, WebSocket: {OPEN: 1}, __socket: socket,
        setTimeout(fn, ms) {timers.set(++timerId, {fn, time: now + ms}); return timerId;},
        clearTimeout(id) {timers.delete(id);}});
    vm.runInContext(script, context);
    vm.runInContext('ws=__socket; state=State.WAITING; userStopped=true;', context);
    function advance(ms) {
        const target = now + ms;
        while (true) {
            const due = [...timers.entries()].filter(([,t]) => t.time <= target).sort((a,b) => a[1].time-b[1].time)[0];
            if (!due) break;
            now = due[1].time; timers.delete(due[0]); due[1].fn();
        }
        now = target;
    }
    function message(msg) { context.__message = {data: JSON.stringify(msg)}; vm.runInContext('onMessage(__socket,__message)',context); }
    return {context, advance, message, closes: () => closeCount};
}

test('a pause between sentences does not end an unfinished response', () => {
    const p = page(); p.message({type: 'caption', text: '第一款适合日常跑步。'});
    p.advance(4000);
    assert.equal(p.closes(), 0);
});

test('completion waits until queued speech has played before closing', () => {
    const p = page(); vm.runInContext('audioCtx={currentTime:5}; playbackTime=7;',p.context);
    p.message({type: 'done'}); p.advance(1999); assert.equal(p.closes(),0);
    p.advance(1); assert.equal(p.closes(),1);
});

test('an earlier turn finishing while recording cannot close a later reply after stop', async () => {
    const p = page(); vm.runInContext('state=State.RECORDING; userStopped=false; recordStartAt=Date.now()-2000;',p.context);
    p.message({type: 'turn_done'}); p.advance(5000); assert.equal(p.closes(),0);
    await vm.runInContext('stop()',p.context); p.advance(0); assert.equal(p.closes(),0);
    p.message({type: 'asr', text: '补充预算一千元', final: true});
    p.message({type: 'turn_start'});
    p.message({type: 'caption', text: '第二轮回复'});
    p.message({type: 'turn_done'}); p.advance(5000); assert.equal(p.closes(),0);
    p.message({type: 'done'}); p.advance(0); assert.equal(p.closes(),1);
});

test('a queued ASR result preserves current caption until its turn starts', () => {
    const p = page(); p.message({type: 'caption', text: '当前回复'});
    p.message({type: 'asr', text: '补充一句', final: true});
    assert.equal(vm.runInContext('$caption.textContent', p.context), '当前回复');
    p.message({type: 'turn_start'});
    assert.equal(vm.runInContext('$caption.textContent', p.context), '');
});

test('a server error ends waiting instead of leaving a silent timeout', () => {
    const p = page(); p.message({type: 'error', message: '处理失败，请重试。'}); p.advance(0);
    assert.equal(p.closes(),1);
});
