package com.zhaoyijin.voiceshopping.security;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;

public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String VOICE_PROTOCOL = "voice.v1";
    private static final String TOKEN_PREFIX = "Bearer-";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        List<String> headers = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (headers == null) return reject(response, HttpStatus.UNAUTHORIZED);
        List<String> protocols = headers.stream().flatMap(h -> Arrays.stream(h.split(",")))
                .map(String::trim).filter(p -> !p.isEmpty()).toList();
        List<String> credentials = protocols.stream().filter(p -> p.startsWith(TOKEN_PREFIX)).toList();
        if (credentials.size() > 1) return reject(response, HttpStatus.BAD_REQUEST);
        if (!protocols.contains(VOICE_PROTOCOL)) return reject(response, HttpStatus.BAD_REQUEST);
        if (credentials.isEmpty()) return reject(response, HttpStatus.UNAUTHORIZED);
        String token = credentials.get(0).substring(TOKEN_PREFIX.length());
        if (!token.matches("[A-Za-z0-9._~-]{1,512}")) return reject(response, HttpStatus.BAD_REQUEST);
        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) return reject(response, HttpStatus.UNAUTHORIZED);
        final long userId;
        try {
            userId = Long.parseLong(loginId.toString());
            if (userId <= 0) return reject(response, HttpStatus.UNAUTHORIZED);
        } catch (NumberFormatException e) {
            return reject(response, HttpStatus.UNAUTHORIZED);
        }

        List<String> ids = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().get("sessionId");
        String nonce = UUID.randomUUID().toString();
        if (ids != null) {
            if (ids.size() != 1 || ids.get(0) == null || !ids.get(0).matches(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                return reject(response, HttpStatus.BAD_REQUEST);
            }
            nonce = UUID.fromString(ids.get(0)).toString();
        }
        // 客户端只能指定会话随机标识，用户身份与会话命名空间均由服务端确定。
        attributes.put("userId", userId);
        attributes.put("sessionId", "voice-" + userId + "-" + nonce);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest r, ServerHttpResponse rp,
                               WebSocketHandler h, Exception e) {
    }

    private boolean reject(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        return false;
    }
}
