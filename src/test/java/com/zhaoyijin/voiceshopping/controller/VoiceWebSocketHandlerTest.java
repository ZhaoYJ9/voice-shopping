package com.zhaoyijin.voiceshopping.controller;

import com.zhaoyijin.voiceshopping.agent.AgentFactory;
import com.zhaoyijin.voiceshopping.entity.StreamChunk;
import com.zhaoyijin.voiceshopping.service.*;
import com.zhaoyijin.voiceshopping.voice.AsrService;
import io.reactivex.Flowable;
import org.junit.jupiter.api.*;
import org.springframework.web.socket.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoiceWebSocketHandlerTest {
    private final AsrService asr = mock(AsrService.class);
    private final OrchestratorService orchestrator = mock(OrchestratorService.class);
    private final WebSocketSession socket = mock(WebSocketSession.class);
    private final LongTermMemoryWriter memory = mock(LongTermMemoryWriter.class);
    private final AgentFactory agents = mock(AgentFactory.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CompletableFuture<String> recognition = new CompletableFuture<>();
    private final AtomicReference<BiConsumer<String, Boolean>> callback = new AtomicReference<>();
    private final List<WebSocketMessage<?>> frames = new CopyOnWriteArrayList<>();
    private final CountDownLatch inputEnded = new CountDownLatch(1);
    private VoiceWebSocketHandler handler;

    @BeforeEach
    void connect() throws Exception {
        when(asr.recognize(any(), any())).thenAnswer(i -> {
            callback.set(i.getArgument(1));
            i.<Flowable<ByteBuffer>>getArgument(0).subscribe(ignored -> {}, recognition::completeExceptionally,
                    inputEnded::countDown);
            return recognition;
        });
        when(socket.isOpen()).thenReturn(true);
        when(socket.getId()).thenReturn("socket");
        when(socket.getAttributes()).thenReturn(Map.of("sessionId", "test", "userId", 1L));
        doAnswer(i -> { frames.add(i.getArgument(0)); return null; }).when(socket).sendMessage(any());
        handler = new VoiceWebSocketHandler(asr, mapper, orchestrator, memory, agents);
        handler.afterConnectionEstablished(socket);
    }

    @AfterEach
    void disconnect() {
        handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
    }

    @Test
    void doneWaitsForRecognitionAndReplyCompletion() throws Exception {
        Sinks.Many<StreamChunk> reply = Sinks.many().unicast().onBackpressureBuffer();
        when(orchestrator.streamHandle("test", 1L, "介绍这三款")).thenReturn(reply.asFlux());
        callback.get().accept("介绍这三款", true);
        await().atMost(Duration.ofSeconds(3)).until(reply::currentSubscriberCount, count -> count == 1);
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"input_end\"}"));
        assertTrue(inputEnded.await(1, TimeUnit.SECONDS));
        assertFalse(types().contains("done"));
        recognition.complete("介绍这三款");
        assertFalse(types().contains("done"));
        reply.tryEmitComplete();
        await().atMost(Duration.ofSeconds(3)).until(() -> types().contains("done"));
        assertEquals(List.of("asr", "turn_start", "turn_done", "done"), types());
    }

    @Test
    void followUpWaitsForWholePreviousReplyAndLateAsrTailIsKept() throws Exception {
        Sinks.Many<StreamChunk> first = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<StreamChunk> second = Sinks.many().unicast().onBackpressureBuffer();
        when(orchestrator.streamHandle("test", 1L, "我想买鞋")).thenReturn(first.asFlux());
        when(orchestrator.streamHandle("test", 1L, "预算一千元")).thenReturn(second.asFlux());
        callback.get().accept("我想买鞋", true);
        await().atMost(Duration.ofSeconds(3)).until(first::currentSubscriberCount, count -> count == 1);
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"input_end\"}"));
        callback.get().accept("预算一千元", true); // 最后一条结果晚于停止按钮
        recognition.complete("我想买鞋预算一千元");
        verify(orchestrator, never()).streamHandle("test", 1L, "预算一千元");
        first.tryEmitNext(StreamChunk.text("第一轮回复"));
        first.tryEmitComplete();
        await().atMost(Duration.ofSeconds(3)).until(second::currentSubscriberCount, count -> count == 1);
        assertFalse(types().contains("done"));
        second.tryEmitNext(StreamChunk.text("第二轮回复"));
        second.tryEmitComplete();
        await().atMost(Duration.ofSeconds(3)).until(() -> types().contains("done"));
        assertEquals(2, Collections.frequency(types(), "turn_done"));
        assertEquals(1, Collections.frequency(types(), "done"));
        assertEquals(List.of("第一轮回复", "第二轮回复"), frames.stream()
                .filter(f -> f instanceof TextMessage).map(f -> mapper.readTree(((TextMessage) f).getPayload()))
                .filter(f -> "caption".equals(f.path("type").asString()))
                .map(f -> f.path("text").asString()).toList());
    }

    @Test
    void repeatedTextIsNotSilentlyDiscarded() {
        when(orchestrator.streamHandle("test", 1L, "预算一千元")).thenReturn(Flux.empty());
        callback.get().accept("预算一千元", true);
        callback.get().accept("预算一千元", true);
        recognition.complete("预算一千元预算一千元");
        await().atMost(Duration.ofSeconds(3)).until(() -> types().contains("done"));
        verify(orchestrator, times(2)).streamHandle("test", 1L, "预算一千元");
    }

    @Test
    void asrAndAudioNeverWriteToSocketConcurrently() throws Exception {
        Sinks.Many<StreamChunk> reply = Sinks.many().unicast().onBackpressureBuffer();
        when(orchestrator.streamHandle("test", 1L, "推荐跑鞋")).thenReturn(reply.asFlux());
        callback.get().accept("推荐跑鞋", true);
        await().atMost(Duration.ofSeconds(3)).until(reply::currentSubscriberCount, count -> count == 1);
        CountDownLatch audioEntered = new CountDownLatch(1), releaseAudio = new CountDownLatch(1);
        AtomicInteger writers = new AtomicInteger(), maxWriters = new AtomicInteger();
        doAnswer(i -> {
            maxWriters.accumulateAndGet(writers.incrementAndGet(), Math::max);
            try {
                if (i.getArgument(0) instanceof BinaryMessage) {
                    audioEntered.countDown();
                    assertTrue(releaseAudio.await(3, TimeUnit.SECONDS));
                }
                frames.add(i.getArgument(0));
            } finally { writers.decrementAndGet(); }
            return null;
        }).when(socket).sendMessage(any());
        Thread audio = new Thread(() ->
                reply.tryEmitNext(StreamChunk.audio(ByteBuffer.wrap(new byte[]{0, 0}))));
        audio.start();
        assertTrue(audioEntered.await(3, TimeUnit.SECONDS));
        Thread partial = new Thread(() -> callback.get().accept("还有", false));
        partial.start();
        try {
            await().atMost(Duration.ofSeconds(2)).until(() ->
                    partial.getState() == Thread.State.BLOCKED || !partial.isAlive());
            assertEquals(Thread.State.BLOCKED, partial.getState());
            assertEquals(1, maxWriters.get());
        } finally {
            releaseAudio.countDown();
            audio.join(3000);
            partial.join(3000);
        }
        assertFalse(types().contains("error"));
        assertEquals(1, maxWriters.get());
    }

    @Test
    void closingCancelsCurrentReplyRecognitionAndQueuedTurns() {
        CountDownLatch cancelled = new CountDownLatch(1);
        Sinks.Many<StreamChunk> first = Sinks.many().unicast().onBackpressureBuffer();
        when(orchestrator.streamHandle("test", 1L, "第一句"))
                .thenReturn(first.asFlux().doOnCancel(cancelled::countDown));
        callback.get().accept("第一句", true);
        await().atMost(Duration.ofSeconds(3)).until(first::currentSubscriberCount, count -> count == 1);
        callback.get().accept("第二句", true);
        handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
        assertEquals(0, cancelled.getCount());
        assertTrue(recognition.isCancelled());
        int sent = frames.size();
        first.tryEmitNext(StreamChunk.text("过期回复"));
        callback.get().accept("迟到识别", true);
        first.tryEmitComplete();
        assertEquals(sent, frames.size());
        verify(orchestrator, never()).streamHandle("test", 1L, "第二句");
        verify(memory).flushOnSessionEnd("test", 1L);
        verify(agents).remove("test");
    }

    @Test
    void streamFailureIsVisibleAndDoesNotLeakProviderDetails() {
        when(orchestrator.streamHandle("test", 1L, "介绍这三款"))
                .thenReturn(Flux.error(new IllegalStateException("private provider details")));
        callback.get().accept("介绍这三款", true);
        await().atMost(Duration.ofSeconds(3)).until(recognition::isCancelled);
        assertEquals(List.of("asr", "turn_start", "error"), types());
        assertFalse(frames.toString().contains("private provider details"));
    }

    @Test
    void synchronousFailureAlsoReachesClient() {
        when(orchestrator.streamHandle("test", 1L, "介绍这三款"))
                .thenThrow(new IllegalStateException("private provider details"));
        callback.get().accept("介绍这三款", true);
        await().atMost(Duration.ofSeconds(3)).until(recognition::isCancelled);
        assertTrue(types().contains("error"));
        assertFalse(types().contains("done"));
    }

    @Test
    void emptyRecordingStillFinishes() throws Exception {
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"input_end\"}"));
        recognition.complete("");
        assertEquals(List.of("done"), types());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void splitBudgetWaitsForAmountAndCreatesOneTurn() {
        when(orchestrator.streamHandle("test", 1L, "我把预算调整到七百再推荐一下。"))
                .thenReturn(Flux.empty());
        callback.get().accept("我把预算调整到。", true);
        verifyNoInteractions(orchestrator);
        assertEquals(List.of("asr"), types());
        callback.get().accept("七百再推荐一下。", true);
        recognition.complete("我把预算调整到七百再推荐一下。");
        await().atMost(Duration.ofSeconds(3)).until(() -> types().contains("done"));
        verify(orchestrator).streamHandle("test", 1L, "我把预算调整到七百再推荐一下。");
        assertEquals(1, Collections.frequency(types(), "turn_done"));
        assertFalse(types().contains("error"));
    }

    @Test
    void stoppingAfterBudgetFragmentFlushesItForClarification() throws Exception {
        when(orchestrator.streamHandle("test", 1L, "我把预算调整到。"))
                .thenReturn(Flux.just(StreamChunk.text("新的预算上限是多少元？")));
        callback.get().accept("我把预算调整到。", true);
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"input_end\"}"));
        verifyNoInteractions(orchestrator);
        recognition.complete("我把预算调整到。");
        await().atMost(Duration.ofSeconds(3)).until(() -> types().contains("done"));
        assertEquals(List.of("asr", "turn_start", "caption", "turn_done", "done"), types());
    }

    @Test
    void splitRangeWaitsForUpperBound() {
        when(orchestrator.streamHandle("test", 1L, "预算嗯1000到1500元。"))
                .thenReturn(Flux.empty());
        callback.get().accept("预算嗯1000到。", true);
        verifyNoInteractions(orchestrator);
        callback.get().accept("1500元。", true);
        recognition.complete("预算嗯1000到1500元。");
        await().atMost(Duration.ofSeconds(3)).until(() -> types().contains("done"));
        verify(orchestrator).streamHandle("test", 1L, "预算嗯1000到1500元。");
        assertEquals(1, Collections.frequency(types(), "turn_done"));
    }

    @Test
    void unrelatedRequestDoesNotGetJoinedToAnUnfinishedBudget() {
        when(orchestrator.streamHandle("test", 1L, "算了，推荐口红"))
                .thenReturn(Flux.empty());
        callback.get().accept("我把预算调整到。", true);
        callback.get().accept("算了，推荐口红", true);
        recognition.complete("算了，推荐口红");
        await().atMost(Duration.ofSeconds(3)).until(() -> types().contains("done"));
        verify(orchestrator).streamHandle("test", 1L, "算了，推荐口红");
        verifyNoMoreInteractions(orchestrator);
    }

    private List<String> types() {
        return frames.stream().filter(f -> f instanceof TextMessage)
                .map(f -> mapper.readTree(((TextMessage) f).getPayload()).path("type").asString()).toList();
    }
}
