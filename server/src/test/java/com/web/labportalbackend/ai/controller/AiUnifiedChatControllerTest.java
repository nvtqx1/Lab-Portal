package com.web.labportalbackend.ai.controller;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.web.labportalbackend.ai.dto.response.AiUnifiedChatResponse;
import com.web.labportalbackend.ai.dto.response.AiActionResultResponse;
import com.web.labportalbackend.ai.dto.response.AiConversationDetailResponse;
import com.web.labportalbackend.ai.dto.response.AiConversationSummaryResponse;
import com.web.labportalbackend.ai.enums.AiUnifiedChatResponseType;
import com.web.labportalbackend.ai.service.AiUnifiedChatService;
import com.web.labportalbackend.ai.service.AiActionSuggestionService;
import com.web.labportalbackend.ai.service.AiConversationHistoryService;
import com.web.labportalbackend.auth.security.JwtAuthenticationFilter;
import java.util.List;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = AiUnifiedChatController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class
        )
)
@Import(AiUnifiedChatControllerTest.MethodSecurityTestConfig.class)
class AiUnifiedChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiUnifiedChatService unifiedChatService;

    @MockitoBean
    private AiActionSuggestionService actionSuggestionService;

    @MockitoBean
    private AiConversationHistoryService conversationHistoryService;

    @Test
    void authenticatedCallerUsesCommonEndpointWithoutClientSuppliedAuthority() throws Exception {
        when(unifiedChatService.chat(any(), eq("request-123"))).thenReturn(new AiUnifiedChatResponse(
                AiUnifiedChatResponseType.ANSWER, "LAB_ASSISTANT", "Safe answer", 12, 7, List.of()));

        mockMvc.perform(post("/api/ai/chat")
                        .contextPath("/api")
                        .with(csrf())
                        .with(user("student").roles("STUDENT"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer jwt-must-stay-in-spring")
                        .header("X-Request-Id", "request-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"Cho tôi xem các ca Lab ngày mai\",\"conversationId\":41}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("ANSWER"))
                .andExpect(jsonPath("$.data.assistantKey").value("LAB_ASSISTANT"))
                .andExpect(jsonPath("$.data.answer").value("Safe answer"))
                .andExpect(content().string(not(containsString("jwt-must-stay-in-spring"))));

        verify(unifiedChatService).chat(
                org.mockito.ArgumentMatchers.argThat(request ->
                        "Cho tôi xem các ca Lab ngày mai".equals(request.getInput())
                                && Long.valueOf(41L).equals(request.getConversationId())),
                eq("request-123"));
    }

    @Test
    void authorityFieldsAreRejectedBeforeOrchestration() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .contextPath("/api")
                        .with(csrf())
                        .with(user("student").roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input":"Hello","assistantKey":"ADMIN_ASSISTANT",
                                 "capability":"ADMIN_SYSTEM_SUMMARY","resourceId":1,"role":"ADMIN"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(unifiedChatService);
    }

    @Test
    void commonEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .contextPath("/api")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"Hello\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(unifiedChatService);
    }

    @Test
    void authenticatedCallerCanListAndRestoreOwnedConversations() throws Exception {
        when(conversationHistoryService.listCurrentUserConversations()).thenReturn(List.of(
                new AiConversationSummaryResponse(41L, "Tạo ca AI Research Lab",
                        Instant.parse("2026-09-06T08:00:00Z"))));
        when(conversationHistoryService.getCurrentUserConversation(41L, null, 30)).thenReturn(
                new AiConversationDetailResponse(41L, "Tạo ca AI Research Lab", List.of()));

        mockMvc.perform(get("/api/ai/conversations")
                        .contextPath("/api")
                        .with(user("manager").roles("LAB_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(41))
                .andExpect(jsonPath("$.data[0].title").value("Tạo ca AI Research Lab"));
        mockMvc.perform(get("/api/ai/conversations/41")
                        .contextPath("/api")
                        .with(user("manager").roles("LAB_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(41))
                .andExpect(jsonPath("$.data.messages").isEmpty());
    }

    @Test
    void invalidHistoryCursorAndSizeAreRejectedAtBoundary() throws Exception {
        mockMvc.perform(get("/api/ai/conversations/41").contextPath("/api")
                        .param("size", "31").with(user("manager").roles("LAB_MANAGER")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/ai/conversations/41").contextPath("/api")
                        .param("beforeId", "-1").with(user("manager").roles("LAB_MANAGER")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(conversationHistoryService);
    }

    @Test
    void requesterCanConfirmPreviewByServerOwnedIdentifierOnly() throws Exception {
        when(actionSuggestionService.confirm(41L)).thenReturn(
                new AiActionResultResponse(41L, "CREATE_LAB_SHIFT", "EXECUTED", 99L));

        mockMvc.perform(post("/api/ai/actions/41/confirm")
                        .contextPath("/api")
                        .with(csrf())
                        .with(user("manager").roles("LAB_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggestionId").value(41))
                .andExpect(jsonPath("$.data.status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.targetId").value(99));

        verify(actionSuggestionService).confirm(41L);
    }

    @Test
    void requesterCanCancelPreviewByServerOwnedIdentifierOnly() throws Exception {
        when(actionSuggestionService.cancel(41L)).thenReturn(
                new AiActionResultResponse(41L, "CREATE_LAB_SHIFT", "CANCELLED", null));

        mockMvc.perform(post("/api/ai/actions/41/cancel")
                        .contextPath("/api")
                        .with(csrf())
                        .with(user("manager").roles("LAB_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.targetId").doesNotExist());

        verify(actionSuggestionService).cancel(41L);
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }
}
