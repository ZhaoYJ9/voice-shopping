package com.zhaoyijin.voiceshopping.controller;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpUtil;
import com.zhaoyijin.voiceshopping.security.CurrentUser;
import com.zhaoyijin.voiceshopping.service.OrchestratorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatDebugControllerTest {
    @Mock private OrchestratorService orchestrator;
    @Spy private CurrentUser currentUser = new CurrentUser();
    @InjectMocks private ChatDebugController controller;

    private MockMvc mvc;
    private SaTokenConfig previousConfig;
    private SaTokenContext previousContext;
    private final List<String> tokens = new ArrayList<>();

    @BeforeEach void setUp() {
        previousConfig = SaManager.getConfig();
        previousContext = SaManager.getSaTokenContext();
        SaManager.setConfig(new SaTokenConfig().setTokenName("Authorization")
                .setTokenPrefix("Bearer").setIsReadHeader(true).setIsReadCookie(false));
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach void tearDown() {
        try {
            tokens.forEach(StpUtil::logoutByTokenValue);
        } finally {
            SaManager.setSaTokenContext(previousContext);
            SaManager.setConfig(previousConfig);
        }
    }

    @Test void missingTokenCannotBeReplacedWithAUserIdParameter() throws Exception {
        mvc.perform(chat().param("userId", "999")).andExpect(status().isUnauthorized());
        verifyNoInteractions(orchestrator);
    }

    @Test void invalidTokenDoesNotReachTheOrchestrator() throws Exception {
        mvc.perform(chat().param("userId", "999")
                .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(orchestrator);
    }

    @Test void usesAuthenticatedIdentityInsteadOfCallerSuppliedUserId() throws Exception {
        String token = login(7L);
        mvc.perform(chat().param("userId", "999").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(orchestrator).handle("chat-session", 7L, "确认");
        verifyNoMoreInteractions(orchestrator);
    }

    @Test void authenticatedRequestDoesNotNeedAUserIdParameter() throws Exception {
        String token = login(7L);
        mvc.perform(chat().header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(orchestrator).handle("chat-session", 7L, "确认");
    }

    @Test void loggedOutTokenDoesNotReachTheOrchestrator() throws Exception {
        String token = login(7L);
        StpUtil.logoutByTokenValue(token);

        mvc.perform(chat().param("userId", "7").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(orchestrator);
    }

    private String login(Long userId) {
        String token = StpUtil.getStpLogic().createLoginSession(userId);
        tokens.add(token);
        return token;
    }

    private MockHttpServletRequestBuilder chat() {
        return post("/api/v1/chat").param("sessionId", "chat-session")
                .contentType("text/plain;charset=UTF-8").content("确认");
    }
}
