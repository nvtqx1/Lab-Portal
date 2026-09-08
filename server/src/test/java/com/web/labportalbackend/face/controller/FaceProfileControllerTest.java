package com.web.labportalbackend.face.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.web.labportalbackend.auth.security.JwtAuthenticationFilter;
import com.web.labportalbackend.face.dto.response.FaceConsentResponse;
import com.web.labportalbackend.face.dto.response.FaceCheckinResponse;
import com.web.labportalbackend.face.dto.response.FaceGuidanceResponse;
import com.web.labportalbackend.face.dto.response.FaceProfileResponse;
import com.web.labportalbackend.face.enums.FaceConsentStatus;
import com.web.labportalbackend.face.enums.FaceProfileStatus;
import com.web.labportalbackend.face.service.FaceProfileService;
import com.web.labportalbackend.face.service.FaceCheckinService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = FaceProfileController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class
        )
)
@Import(FaceProfileControllerTest.MethodSecurityTestConfig.class)
class FaceProfileControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean FaceProfileService faceProfileService;
    @MockitoBean FaceCheckinService faceCheckinService;

    @Test
    void adminCanListFaceProfileMetadata() throws Exception {
        when(faceProfileService.listProfiles()).thenReturn(List.of(new FaceProfileResponse(
                9L, FaceProfileStatus.ACTIVE, "opencv-sface-2021dec",
                Instant.parse("2026-09-01T12:00:00Z"))));

        mockMvc.perform(get("/api/face/profiles").contextPath("/api")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(9))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].embeddingModel").value("opencv-sface-2021dec"));
    }

    @Test
    void nonAdminCannotListFaceProfileMetadata() throws Exception {
        mockMvc.perform(get("/api/face/profiles").contextPath("/api")
                        .with(user("student").roles("STUDENT")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(faceProfileService);
    }

    @Test
    void authenticatedOwnerCanChangeOwnConsent() throws Exception {
        when(faceProfileService.changeConsent(eq(null), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new FaceConsentResponse(7L, FaceConsentStatus.GRANTED,
                        Instant.parse("2026-08-31T00:00:00Z")));

        mockMvc.perform(post("/api/face/consent").contextPath("/api")
                        .with(csrf()).with(user("student").roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"GRANTED\",\"reason\":\"opt in\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(7))
                .andExpect(jsonPath("$.data.status").value("GRANTED"));

        verify(faceProfileService).changeConsent(eq(null),
                argThat(request -> request.status() == FaceConsentStatus.GRANTED));
    }

    @Test
    void managerCanSubmitCameraFaceCheckinContract() throws Exception {
        when(faceCheckinService.checkIn(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new FaceCheckinResponse(11L, 7L, "Trung Nguyễn", true, "MATCH", 0.91, 0.88,
                        null, Instant.parse("2026-08-31T00:00:00Z")));

        mockMvc.perform(post("/api/face/check-in").contextPath("/api")
                        .with(csrf()).with(user("manager").roles("LAB_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":21,"imageBase64":"aW1hZ2U=","contentType":"image/jpeg",
                                 "challengeToken":"signed-challenge","challengeFrames":[
                                   {"imageBase64":"aW1hZ2U=","contentType":"image/jpeg"},
                                   {"imageBase64":"aW1hZ2U=","contentType":"image/jpeg"},
                                   {"imageBase64":"aW1hZ2U=","contentType":"image/jpeg"},
                                   {"imageBase64":"aW1hZ2U=","contentType":"image/jpeg"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingId").value(11))
                .andExpect(jsonPath("$.data.studentName").value("Trung Nguyễn"))
                .andExpect(jsonPath("$.data.checkedIn").value(true))
                .andExpect(jsonPath("$.data.result").value("MATCH"));
    }

    @Test
    void studentCannotSubmitFaceCheckin() throws Exception {
        mockMvc.perform(post("/api/face/check-in").contextPath("/api")
                        .with(csrf()).with(user("student").roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":21,"imageBase64":"aW1hZ2U=","contentType":"image/jpeg",
                                 "challengeToken":"signed-challenge","challengeFrames":[
                                   {"imageBase64":"aW1hZ2U=","contentType":"image/jpeg"},
                                   {"imageBase64":"aW1hZ2U=","contentType":"image/jpeg"},
                                   {"imageBase64":"aW1hZ2U=","contentType":"image/jpeg"},
                                   {"imageBase64":"aW1hZ2U=","contentType":"image/jpeg"}]}
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(faceCheckinService);
    }

    @Test
    void authenticatedOwnerCanRequestLiveFaceGuidance() throws Exception {
        when(faceProfileService.guidance(eq(null), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new FaceGuidanceResponse(
                        1, true, true, true, true, true, true,
                        0.5, 0.48, 0.35, 0.55, null));

        mockMvc.perform(post("/api/face/guidance").contextPath("/api")
                        .with(csrf()).with(user("student").roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageBase64\":\"aW1hZ2U=\",\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.singleFace").value(true))
                .andExpect(jsonPath("$.data.faceInGuide").value(true))
                .andExpect(jsonPath("$.data.centerX").value(0.5));
    }

    @Test
    void userScopedAdminRouteRejectsNonAdmin() throws Exception {
        mockMvc.perform(post("/api/face/users/9/consent").contextPath("/api")
                        .with(csrf()).with(user("student").roles("STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"WITHDRAWN\",\"reason\":\"requested\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(faceProfileService);
    }

    @Test
    void userScopedAdminRouteAllowsAdmin() throws Exception {
        when(faceProfileService.changeConsent(eq(9L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new FaceConsentResponse(9L, FaceConsentStatus.WITHDRAWN,
                        Instant.parse("2026-08-31T00:00:00Z")));

        mockMvc.perform(post("/api/face/users/9/consent").contextPath("/api")
                        .with(csrf()).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"WITHDRAWN\",\"reason\":\"requested\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(9));
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }
}
