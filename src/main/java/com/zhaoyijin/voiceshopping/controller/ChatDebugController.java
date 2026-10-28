package com.zhaoyijin.voiceshopping.controller;

import cn.dev33.satoken.exception.NotLoginException;
import com.zhaoyijin.voiceshopping.dto.EmotionResult;
import com.zhaoyijin.voiceshopping.security.CurrentUser;
import com.zhaoyijin.voiceshopping.service.OrchestratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatDebugController {

    private final OrchestratorService orchestrator;
    private final CurrentUser currentUser;

    @PostMapping
    public EmotionResult chat(@RequestParam String sessionId,
                              @RequestBody String utterance) {
        final Long userId;
        try {
            userId = currentUser.id();
        } catch (NotLoginException e) {
            throw new ResponseStatusException(UNAUTHORIZED, "请先登录或重新获取有效令牌");
        }
        return orchestrator.handle(sessionId, userId, utterance);
    }
}
