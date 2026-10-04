package com.zhaoyijin.voiceshopping.voice;

import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.common.ResultCallback;
import io.reactivex.processors.PublishProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TtsServiceTest {
    private TtsService service() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "model", "cosyvoice-v1");
        ReflectionTestUtils.setField(service, "voice", "longwan");
        return service;
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputCompletionDoesNotFinishAudioBeforeProviderTail() {
        AtomicReference<ResultCallback<SpeechSynthesisResult>> callback = new AtomicReference<>();
        try (var construction = mockConstruction(SpeechSynthesizer.class,
                (mock, context) -> callback.set((ResultCallback<SpeechSynthesisResult>) context.arguments().get(1)))) {
            var text = PublishProcessor.<String>create();
            var audio = service().synthesize(text).test();
            text.onNext("你好");
            text.onComplete();
            SpeechSynthesizer synthesizer = construction.constructed().get(0);
            verify(synthesizer).streamingCall("你好");
            verify(synthesizer).asyncStreamingComplete();
            verify(synthesizer, never()).streamingComplete();
            audio.assertNotComplete();
            callback.get().onComplete();
            audio.assertComplete().assertNoErrors();
        }
    }

    @Test
    void disconnectStopsSynthesisAndDetachesTextSubscription() {
        try (var construction = mockConstruction(SpeechSynthesizer.class)) {
            var text = PublishProcessor.<String>create();
            var audio = service().synthesize(text).test();
            assertTrue(text.hasSubscribers());
            text.onNext("你好");
            audio.cancel();
            text.onNext("不应继续合成");
            assertFalse(text.hasSubscribers());
            SpeechSynthesizer synthesizer = construction.constructed().get(0);
            verify(synthesizer).streamingCancel();
            verify(synthesizer, never()).streamingCall("不应继续合成");
        }
    }
}
