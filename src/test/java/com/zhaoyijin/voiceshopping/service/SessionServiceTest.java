package com.zhaoyijin.voiceshopping.service;

import com.zhaoyijin.voiceshopping.entity.SessionEntity;
import com.zhaoyijin.voiceshopping.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionServiceTest {
    @Test void cannotReuseAnotherUsersExistingSession() {
        SessionRepository repo = mock(SessionRepository.class);
        SessionEntity existing = new SessionEntity();
        existing.setId("someone-elses-session"); existing.setUserId(7L);
        when(repo.findById("someone-elses-session")).thenReturn(Optional.of(existing));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> new SessionService(repo).openIfAbsent("someone-elses-session", 8L, "HOME_ENTRY"));
        assertEquals(403, e.getStatusCode().value());
    }

    @Test void ownerCanReconnectToTheirExistingSession() {
        SessionRepository repo = mock(SessionRepository.class);
        SessionEntity existing = new SessionEntity();
        existing.setId("own-session"); existing.setUserId(7L);
        when(repo.findById("own-session")).thenReturn(Optional.of(existing));
        assertSame(existing, new SessionService(repo).openIfAbsent("own-session", 7L, "HOME_ENTRY"));
    }
}
