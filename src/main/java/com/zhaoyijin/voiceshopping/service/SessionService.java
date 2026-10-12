package com.zhaoyijin.voiceshopping.service;

import com.zhaoyijin.voiceshopping.entity.SessionEntity;
import com.zhaoyijin.voiceshopping.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository repo;

    @Transactional
    public SessionEntity openIfAbsent(String sessionId, Long userId, String channel) {
        return repo.findById(sessionId).map(existing -> {
            if (!Objects.equals(existing.getUserId(), userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问此会话");
            }
            return existing;
        }).orElseGet(() -> {
            SessionEntity s = new SessionEntity();
            s.setId(sessionId);
            s.setUserId(userId);
            s.setChannel(channel == null ? "HOME_ENTRY" : channel);
            s.setStartedAt(LocalDateTime.now());
            s.setLocale("zh_cn");
            s.setTotalTokens(0);
            return repo.save(s);
        });
    }

    public Long findUserId(String sessionId) {
        return repo.findById(sessionId).map(SessionEntity::getUserId).orElse(null);
    }

}
