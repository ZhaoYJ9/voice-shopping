package com.zhaoyijin.voiceshopping.service;

import com.zhaoyijin.voiceshopping.agent.AgentFactory;
import com.zhaoyijin.voiceshopping.dto.RecommendResult;
import com.zhaoyijin.voiceshopping.voice.SentenceAggregator;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmotionStreamingServiceTest {
    final AgentFactory factory = mock(AgentFactory.class);
    final ReActAgent agent = mock(ReActAgent.class);
    final SessionMoodDetector mood = mock(SessionMoodDetector.class);
    final EmotionStreamingService service = new EmotionStreamingService(factory, mood, new ObjectMapper());
    final RecommendResult recommendations = new RecommendResult(List.of(), "professional");
    static final String BODY = "，我明白你想要性价比高的男跑鞋。"
            + "这款日常训练鞋 Cumulus 25 有中等缓震，很适合日常跑步保护膝盖。"
            + "入门款 Pegasus 39 性价比很高，对新手特别友好。"
            + "还有 Nike Pegasus 40 缓震跑鞋，高缓震设计非常适合水泥路跑步。"
            + "你觉得哪一款更合适呢？";

    EmotionStreamingServiceTest() {
        when(factory.get("repeat")).thenReturn(new AgentFactory.AgentSet(null, null, null, agent));
        when(mood.detect("repeat", "推荐1000以内的男跑鞋")).thenReturn("neutral");
    }

    @Test
    void recordedFullResultIsNotSynthesizedAgainAfterARepeatedOpeningToken() {
        String full = "好的好的" + BODY;
        events(Flux.just(chunk("好的"), chunk("好的"), chunk(BODY), last(full)));

        // 检查实际送入字幕/TTS 前的句子流，不只检查文本去重工具。
        assertEquals(full, String.join("", SentenceAggregator.aggregate(reply()).collectList().block()));
        ArgumentCaptor<StreamOptions> options = ArgumentCaptor.forClass(StreamOptions.class);
        verify(agent).stream(any(Msg.class), options.capture());
        assertTrue(options.getValue().isIncremental());
        assertTrue(options.getValue().isIncludeReasoningChunk());
        assertFalse(options.getValue().isIncludeReasoningResult());
    }

    @Test
    void finalSnapshotWithDifferentPunctuationIsNotNewSpeech() {
        events(Flux.just(chunk("好的" + BODY), last("好的好的" + BODY.replace("，", ","))));
        assertEquals("好的" + BODY, String.join("", reply().collectList().block()));
    }

    @Test
    void preservesLegitimateRepeatedDeltaText() {
        events(Flux.just(chunk("哈"), chunk("哈"), chunk("，"), chunk("慢"), chunk("慢"), chunk("挑。"), last("哈哈，慢慢挑。")));
        assertEquals("哈哈，慢慢挑。", String.join("", reply().collectList().block()));
    }

    @Test
    void onlySpokenReasoningChunksReachTheCaptionAndAudioPipeline() {
        events(Flux.just(event(EventType.TOOL_RESULT, "工具结果", true), chunk(""),
                chunk("这款适合你。"), event(EventType.AGENT_RESULT, "这款适合你。", true), last("这款适合你。")));
        assertEquals(List.of("这款适合你。"), reply().collectList().block());
    }

    @Test
    void firstChunkArrivesImmediatelyAndCancellationStopsTheSource() {
        Sinks.Many<Event> source = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean cancelled = new AtomicBoolean();
        events(source.asFlux().doOnCancel(() -> cancelled.set(true)));
        List<String> received = new ArrayList<>();
        var subscription = reply().subscribe(received::add);
        source.tryEmitNext(chunk("先说第一句。"));
        assertEquals(List.of("先说第一句。"), received);
        subscription.dispose();
        assertTrue(cancelled.get());
    }

    private Flux<String> reply() {
        return service.streamWrap("repeat", "推荐1000以内的男跑鞋", recommendations);
    }

    private void events(Flux<Event> events) {
        // 同时适配修复前后的调用方式，让这组测试能复现原有故障。
        when(agent.stream(any(Msg.class))).thenReturn(events);
        when(agent.stream(any(Msg.class), any(StreamOptions.class))).thenReturn(events);
    }

    private Event chunk(String text) { return event(EventType.REASONING, text, false); }
    private Event last(String text) { return event(EventType.REASONING, text, true); }
    private Event event(EventType type, String text, boolean last) {
        return new Event(type, Msg.builder().id("reply-1").role(MsgRole.ASSISTANT).textContent(text).build(), last);
    }
}
