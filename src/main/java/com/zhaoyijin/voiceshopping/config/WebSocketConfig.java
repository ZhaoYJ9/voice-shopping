package com.zhaoyijin.voiceshopping.config;

import com.zhaoyijin.voiceshopping.controller.VoiceWebSocketHandler;
import com.zhaoyijin.voiceshopping.security.AuthHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceWebSocketHandler handler;

    @Value("${voice-shopping.websocket.allowed-origins:}")
    private String allowedOrigins = "";

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandlerRegistration registration = registry.addHandler(handler, "/ws/voice")
                .addInterceptors(new AuthHandshakeInterceptor());
        List<String> origins = Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toList();
        for (String origin : origins) {
            URI uri = URI.create(origin);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalArgumentException("语音允许来源必须是完整的 HTTP(S) Origin，不能使用通配符或路径");
            }
        }
        // 反向代理部署显式指定外部页面 Origin；未配置时保留 Spring 的同源检查。
        if (!origins.isEmpty()) registration.setAllowedOrigins(origins.toArray(String[]::new));
    }
}
