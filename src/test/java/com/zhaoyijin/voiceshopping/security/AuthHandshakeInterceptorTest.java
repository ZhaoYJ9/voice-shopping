package com.zhaoyijin.voiceshopping.security;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthHandshakeInterceptorTest {
    private static final String NONCE = "b1ee66b4-9c14-4468-9f3b-64d98d9ac501";
    private final AuthHandshakeInterceptor interceptor = new AuthHandshakeInterceptor();
    private final WebSocketHandler handler = mock(WebSocketHandler.class);

    @Test void acceptsARealSaTokenLoginAndRejectsItAfterLogout() {
        String token = StpUtil.getStpLogic().createLoginSession(7L);
        try {
            Result loggedIn = handshake("sessionId=" + NONCE, "voice.v1, Bearer-" + token);
            assertTrue(loggedIn.accepted());
            assertEquals(7L, loggedIn.attributes().get("userId"));
            StpUtil.logoutByTokenValue(token);
            Result loggedOut = handshake("sessionId=" + NONCE, "voice.v1, Bearer-" + token);
            assertFalse(loggedOut.accepted());
            assertEquals(401, loggedOut.status());
        } finally {
            StpUtil.logoutByTokenValue(token);
        }
    }

    @Test void authenticatesTokenWithoutTrustingQueryUserId() {
        try (MockedStatic<StpUtil> tokens = mockStatic(StpUtil.class)) {
            tokens.when(() -> StpUtil.getLoginIdByToken("valid-token")).thenReturn("7");
            Result r = handshake("sessionId=" + NONCE + "&userId=999", "voice.v1, Bearer-valid-token");
            assertTrue(r.accepted());
            assertEquals(7L, r.attributes().get("userId"));
            assertEquals("voice-7-" + NONCE, r.attributes().get("sessionId"));
            assertFalse(r.attributes().containsValue("valid-token"));
        }
    }

    @Test void refusesMissingTokenEvenWhenQuerySuppliesIdentity() {
        Result r = handshake("userId=1&token=valid-token", "voice.v1");
        assertFalse(r.accepted());
        assertEquals(401, r.status());
        assertTrue(r.attributes().isEmpty());
    }

    @Test void refusesInvalidOrExpiredToken() {
        try (MockedStatic<StpUtil> tokens = mockStatic(StpUtil.class)) {
            Result r = handshake("sessionId=" + NONCE, "voice.v1, Bearer-expired-token");
            assertFalse(r.accepted());
            assertEquals(401, r.status());
            assertTrue(r.attributes().isEmpty());
        }
    }

    @Test void refusesAmbiguousCredentialsAcrossHeaderLines() {
        Result r = handshake("sessionId=" + NONCE, "voice.v1, Bearer-first", "Bearer-second");
        assertFalse(r.accepted());
        assertEquals(400, r.status());
    }

    @Test void requiresTheSupportedVoiceProtocol() {
        Result r = handshake("sessionId=" + NONCE, "Bearer-valid-token");
        assertFalse(r.accepted());
        assertEquals(400, r.status());
    }

    @Test void rejectsMalformedSessionIdsWithoutOpeningASession() {
        try (MockedStatic<StpUtil> tokens = mockStatic(StpUtil.class)) {
            tokens.when(() -> StpUtil.getLoginIdByToken("valid-token")).thenReturn("7");
            for (String query : new String[]{"sessionId", "sessionId=", "sessionId=../other", "sessionId=" + NONCE + "&sessionId=" + NONCE}) {
                Result r = handshake(query, "voice.v1, Bearer-valid-token");
                assertFalse(r.accepted(), query);
                assertEquals(400, r.status(), query);
                assertTrue(r.attributes().isEmpty());
            }
        }
    }

    @Test void separatesTwoUsersUsingTheSameClientSessionId() {
        try (MockedStatic<StpUtil> tokens = mockStatic(StpUtil.class)) {
            tokens.when(() -> StpUtil.getLoginIdByToken("first-token")).thenReturn("7");
            tokens.when(() -> StpUtil.getLoginIdByToken("second-token")).thenReturn("8");
            Result first = handshake("sessionId=" + NONCE, "voice.v1, Bearer-first-token");
            Result second = handshake("sessionId=" + NONCE, "voice.v1, Bearer-second-token");
            assertTrue(first.accepted());
            assertTrue(second.accepted());
            assertEquals("voice-7-" + NONCE, first.attributes().get("sessionId"));
            assertEquals("voice-8-" + NONCE, second.attributes().get("sessionId"));
        }
    }

    @Test void generatesANewSessionIdWhenNoneWasProvided() {
        try (MockedStatic<StpUtil> tokens = mockStatic(StpUtil.class)) {
            tokens.when(() -> StpUtil.getLoginIdByToken("valid-token")).thenReturn("7");
            Result first = handshake(null, "voice.v1, Bearer-valid-token");
            Result second = handshake(null, "voice.v1, Bearer-valid-token");
            assertTrue(first.accepted());
            assertTrue(first.attributes().get("sessionId").toString().startsWith("voice-7-"));
            assertNotEquals(first.attributes().get("sessionId"), second.attributes().get("sessionId"));
        }
    }

    private Result handshake(String query, String... protocols) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/voice");
        request.setQueryString(query);
        for (String p : protocols) request.addHeader("Sec-WebSocket-Protocol", p);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response), handler, attributes);
        return new Result(accepted, response.getStatus(), attributes);
    }

    private record Result(boolean accepted, int status, Map<String, Object> attributes) {}
}
