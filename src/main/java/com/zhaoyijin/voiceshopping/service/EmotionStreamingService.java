package com.zhaoyijin.voiceshopping.service;

import tools.jackson.databind.ObjectMapper;
import com.zhaoyijin.voiceshopping.agent.AgentFactory;
import com.zhaoyijin.voiceshopping.dto.RecommendResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmotionStreamingService {

    private final AgentFactory factory;
    private final SessionMoodDetector moodDetector;
    private final ObjectMapper mapper;

    public Flux<String> streamWrap(String sessionId, String userUtterance, RecommendResult rec) {
        String mood = moodDetector.detect(sessionId, userUtterance);
        Map<String, Object> input = Map.of(
                "userUtterance", userUtterance,
                "sessionMood", mood,
                "userNeeds", userUtterance,
                "products", rec.items()
        );

        ReActAgent agent = factory.get(sessionId).emotion();
        try {
            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(mapper.writeValueAsString(input))
                    .build();
            // 增量片段与结束时的完整结果是两类事件，完整结果不能再次送入字幕/TTS。
            StreamOptions options = StreamOptions.builder()
                    .eventTypes(EventType.REASONING)
                    .incremental(true)
                    .includeReasoningChunk(true)
                    .includeReasoningResult(false)
                    .build();
            return agent.stream(userMsg, options)
                    .filter(e -> e.getType() == EventType.REASONING && !e.isLast())
                    .map(e -> e.getMessage().getTextContent())
                    // 相同的相邻增量也可能是正常叠词，不再按内容猜测去重。
                    .filter(text -> text != null && !text.isEmpty());
        } catch (Exception e) {
            return Flux.error(e);
        }
    }
}
