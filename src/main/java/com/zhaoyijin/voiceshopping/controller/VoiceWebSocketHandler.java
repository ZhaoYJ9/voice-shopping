package com.zhaoyijin.voiceshopping.controller;

import com.zhaoyijin.voiceshopping.agent.AgentFactory;
import com.zhaoyijin.voiceshopping.entity.StreamChunk;
import com.zhaoyijin.voiceshopping.service.LongTermMemoryWriter;
import com.zhaoyijin.voiceshopping.service.BudgetUtterance;
import com.zhaoyijin.voiceshopping.service.OrchestratorService;
import com.zhaoyijin.voiceshopping.voice.AsrService;
import io.reactivex.processors.FlowableProcessor;
import io.reactivex.processors.PublishProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {
    private final AsrService asr;
    private final ObjectMapper mapper;
    private final OrchestratorService orchestratorService;
    private final LongTermMemoryWriter longTermMemoryWriter;
    private final AgentFactory agentFactory;

    private final Map<String, VoiceConnection> connections = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        VoiceConnection connection = new VoiceConnection(session);
        connections.put(session.getId(), connection);
        // 整轮（含 TTS）完成后才开始下一轮，避免共享 Agent/会话状态和回复交叠。
        connection.reply.update(connection.utterances.asFlux()
                .concatMap(utterance -> reply(connection, utterance), 1)
                .subscribe(ignored -> {}, error -> fail(connection, error),
                        () -> sendStatus(connection, "done", "")));
        try {
            connection.recognition = asr.recognize(connection.audio, (text, isEnd) -> {
                if (connection.closed || text == null || text.isBlank()) return;
                try {
                    sendJson(connection, Map.of("type", "asr", "text", text, "final", isEnd));
                    if (isEnd) {
                        synchronized (connection.utterances) {
                            if (!connection.closed) {
                                String utterance = text;
                                if (connection.pendingBudget != null) {
                                    if (BudgetUtterance.startsWithAmount(text)) {
                                        utterance = connection.pendingBudget.replaceFirst("[。！？!?，,：:…\\s]+$", "") + text;
                                    }
                                    connection.pendingBudget = null;
                                }
                                if (BudgetUtterance.isIncomplete(utterance)) {
                                    connection.pendingBudget = utterance;
                                } else {
                                    connection.utterances.emitNext(utterance, Sinks.EmitFailureHandler.FAIL_FAST);
                                }
                            }
                        }
                    }
                } catch (Exception error) {
                    fail(connection, error);
                }
            });
            connection.recognition.whenComplete((text, error) -> {
                if (connection.closed) return;
                synchronized (connection.utterances) {
                    if (error != null) connection.utterances.tryEmitError(error);
                    else {
                        // 停止后仍缺金额，让业务层澄清；必须在队列完成之前发送。
                        if (connection.pendingBudget != null) {
                            connection.utterances.tryEmitNext(connection.pendingBudget);
                            connection.pendingBudget = null;
                        }
                        connection.utterances.tryEmitComplete();
                    }
                }
            });
            if (connection.closed) connection.recognition.cancel(true);
        } catch (Exception error) {
            fail(connection, error);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        VoiceConnection connection = connections.get(session.getId());
        if (connection != null && !connection.closed) connection.audio.onNext(message.getPayload());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        VoiceConnection connection = connections.get(session.getId());
        if (connection == null || connection.closed) return;
        if ("input_end".equals(mapper.readTree(message.getPayload()).path("type").asString())) {
            // 结束输入，继续接收 ASR 尾句；识别和回复队列全部完成后才发送 done。
            connection.audio.onComplete();
        }
    }

    private Flux<StreamChunk> reply(VoiceConnection connection, String utterance) {
        // streamHandle 含阻塞调用，必须延迟到本轮实际开始，并移出 ASR 回调线程。
        return Flux.defer(() -> {
            if (connection.closed) return Flux.<StreamChunk>empty();
            sendStatus(connection, "turn_start", "");
            return orchestratorService.streamHandle(connection.sessionId(), connection.userId(), utterance);
        }).doOnNext(chunk -> {
            try {
                switch (chunk.type()) {
                    case TEXT -> sendJson(connection, Map.of("type", "caption", "text", chunk.text()));
                    case AUDIO -> connection.send(new BinaryMessage(chunk.audio()));
                    case PRODUCTS -> sendJson(connection, Map.of("type", "recommendation", "items", chunk.products()));
                }
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }).doOnComplete(() -> sendStatus(connection, "turn_done", ""))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void fail(VoiceConnection connection, Throwable error) {
        if (connection.closed) return;
        log.error("流式失败 sessionId={}", connection.sessionId(), error);
        sendStatus(connection, "error", "处理失败，请稍后重试。");
        connection.dispose();
    }

    private void sendStatus(VoiceConnection connection, String type, String message) {
        try {
            sendJson(connection, Map.of("type", type, "message", message));
        } catch (Exception error) {
            log.warn("状态回包失败 sessionId={}: {}", connection.sessionId(), error.getMessage());
        }
    }

    private void sendJson(VoiceConnection connection, Object payload) throws IOException {
        connection.send(new TextMessage(mapper.writeValueAsString(payload)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        VoiceConnection connection = connections.remove(session.getId());
        if (connection == null) return;
        connection.dispose();
        if (connection.sessionId() != null && connection.userId() != null) {
            longTermMemoryWriter.flushOnSessionEnd(connection.sessionId(), connection.userId());
            agentFactory.remove(connection.sessionId());
        }
    }

    private static final class VoiceConnection {
        private final WebSocketSession session;
        private final Object sendLock = new Object();
        private final FlowableProcessor<ByteBuffer> audio = PublishProcessor.<ByteBuffer>create().toSerialized();
        private final Sinks.Many<String> utterances = Sinks.many().unicast().onBackpressureBuffer();
        private final Disposable.Swap reply = Disposables.swap();
        private volatile CompletableFuture<String> recognition;
        private volatile boolean closed;
        // 与 utterances 使用同一把锁，由 final 回调和识别完成回调共同访问。
        private String pendingBudget;

        private VoiceConnection(WebSocketSession session) { this.session = session; }
        private String sessionId() { return (String) session.getAttributes().get("sessionId"); }
        private Long userId() { return (Long) session.getAttributes().get("userId"); }

        private void send(WebSocketMessage<?> message) throws IOException {
            // ASR、字幕、音频、状态共用同一把锁；Tomcat 不允许并发 sendMessage。
            synchronized (sendLock) {
                if (!closed && session.isOpen()) session.sendMessage(message);
            }
        }

        private void dispose() {
            closed = true;
            reply.dispose();
            if (recognition != null) recognition.cancel(true);
            audio.onComplete();
        }
    }
}
