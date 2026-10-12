package com.zhaoyijin.voiceshopping.security;

import com.zhaoyijin.voiceshopping.agent.AgentFactory;
import com.zhaoyijin.voiceshopping.controller.VoiceWebSocketHandler;
import com.zhaoyijin.voiceshopping.config.WebSocketConfig;
import cn.dev33.satoken.stp.StpUtil;
import com.zhaoyijin.voiceshopping.service.LongTermMemoryWriter;
import com.zhaoyijin.voiceshopping.service.OrchestratorService;
import com.zhaoyijin.voiceshopping.voice.AsrService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;
import org.springframework.web.socket.config.annotation.ServletWebSocketHandlerRegistry;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class VoiceProtocolNegotiationTest {
    @Test void registeredVoiceRouteRejectsUnauthenticatedConnections() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/voice");
        request.setQueryString("userId=7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        route().handleRequest(request, response);
        assertEquals(401, response.getStatus());
    }

    @Test void registeredVoiceRouteRejectsAnUntrustedOriginEvenWithAValidToken() throws Exception {
        String token = StpUtil.getStpLogic().createLoginSession(7L);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/voice");
            request.addHeader("Sec-WebSocket-Protocol", "voice.v1, Bearer-" + token);
            request.addHeader("Origin", "https://untrusted.example");
            MockHttpServletResponse response = new MockHttpServletResponse();
            route("https://shop.example").handleRequest(request, response);
            assertEquals(403, response.getStatus());
        } finally { StpUtil.logoutByTokenValue(token); }
    }

    @Test void explicitOriginsCannotDisableTheOriginBoundaryWithAWildcard() {
        for (String invalid : List.of("*", "https://*.example", "https://shop.example/path", "https://user@shop.example")) {
            assertThrows(IllegalArgumentException.class, () -> route(invalid), invalid);
        }
    }

    @Test void permitsTheConfiguredHttpsFrontendBehindAnHttpProxy() throws Exception {
        String token = StpUtil.getStpLogic().createLoginSession(7L);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/voice");
            request.addHeader("Sec-WebSocket-Protocol", "voice.v1, Bearer-" + token);
            request.addHeader("Origin", "https://shop.example");
            MockHttpServletResponse response = new MockHttpServletResponse();
            for (var interceptor : route("https://shop.example").getHandshakeInterceptors()) {
                assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                        new ServletServerHttpResponse(response), mock(WebSocketHandler.class), new HashMap<>()));
            }
            assertEquals(200, response.getStatus());
        } finally { StpUtil.logoutByTokenValue(token); }
    }

    @Test void negotiatesVoiceProtocolWithoutEchoingTheBearerCredential() {
        WebSocketHandler voice = new VoiceWebSocketHandler(mock(AsrService.class), new ObjectMapper(),
                mock(OrchestratorService.class), mock(LongTermMemoryWriter.class), mock(AgentFactory.class));
        assertEquals("voice.v1", new Negotiation().select(List.of("Bearer-secret-token", "voice.v1"), voice));
        assertNull(new Negotiation().select(List.of("Bearer-secret-token"), voice));
    }

    private WebSocketHttpRequestHandler route() {
        return route("");
    }

    private WebSocketHttpRequestHandler route(String origins) {
        VoiceWebSocketHandler voice = new VoiceWebSocketHandler(mock(AsrService.class), new ObjectMapper(),
                mock(OrchestratorService.class), mock(LongTermMemoryWriter.class), mock(AgentFactory.class));
        ServletWebSocketHandlerRegistry registry = new ServletWebSocketHandlerRegistry();
        WebSocketConfig config = new WebSocketConfig(voice);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-origins",
                    Map.of("voice-shopping.websocket.allowed-origins", origins)));
            context.refresh();
            context.getAutowireCapableBeanFactory().autowireBean(config);
        }
        config.registerWebSocketHandlers(registry);
        SimpleUrlHandlerMapping mapping = (SimpleUrlHandlerMapping) registry.getHandlerMapping();
        return (WebSocketHttpRequestHandler) mapping.getUrlMap().get("/ws/voice");
    }

    private static class Negotiation extends DefaultHandshakeHandler {
        String select(List<String> offered, WebSocketHandler handler) { return selectProtocol(offered, handler); }
    }
}
