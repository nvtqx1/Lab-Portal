package com.web.labportalbackend.face.controller;

import com.web.labportalbackend.common.dto.Response;
import com.web.labportalbackend.face.dto.request.FaceConsentRequest;
import com.web.labportalbackend.face.dto.request.FaceGuidanceRequest;
import com.web.labportalbackend.face.dto.request.FaceCheckinRequest;
import com.web.labportalbackend.face.dto.request.FaceRegistrationRequest;
import com.web.labportalbackend.face.dto.response.FaceConsentResponse;
import com.web.labportalbackend.face.dto.response.FaceChallengeResponse;
import com.web.labportalbackend.face.dto.response.FaceGuidanceResponse;
import com.web.labportalbackend.face.dto.response.FaceCheckinResponse;
import com.web.labportalbackend.face.dto.response.FaceProfileResponse;
import com.web.labportalbackend.face.dto.response.FaceCheckinCandidateResponse;
import com.web.labportalbackend.face.service.FaceProfileService;
import com.web.labportalbackend.face.service.FaceCheckinService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/face")
@RequiredArgsConstructor
public class FaceProfileController {

    private final FaceProfileService faceProfileService;
    private final FaceCheckinService faceCheckinService;

    @GetMapping("/profiles")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List face profile metadata for administration")
    public ResponseEntity<Response<List<FaceProfileResponse>>> listProfiles() {
        return ResponseEntity.ok(Response.ok(faceProfileService.listProfiles()));
    }

    @PostMapping("/check-in")
    @PreAuthorize("hasRole('LAB_MANAGER')")
    @Operation(summary = "Identify and check in a registered student within the selected managed-lab slot")
    public ResponseEntity<Response<FaceCheckinResponse>> checkIn(
            @Valid @RequestBody FaceCheckinRequest request) {
        return ResponseEntity.ok(Response.ok("Đã hoàn tất đối chiếu khuôn mặt", faceCheckinService.checkIn(request)));
    }

    @GetMapping("/check-in/candidates")
    @PreAuthorize("hasRole('LAB_MANAGER')")
    @Operation(summary = "List approved bookings available for manager-operated face check-in")
    public ResponseEntity<Response<List<FaceCheckinCandidateResponse>>> checkInCandidates() {
        return ResponseEntity.ok(Response.ok(faceCheckinService.candidates()));
    }

    @PostMapping("/check-in/guidance")
    @PreAuthorize("hasRole('LAB_MANAGER')")
    @Operation(summary = "Evaluate a camera frame at the manager face check-in workstation")
    public ResponseEntity<Response<FaceGuidanceResponse>> checkInGuidance(
            @Valid @RequestBody FaceGuidanceRequest request) {
        return ResponseEntity.ok(Response.ok(faceCheckinService.guidance(request)));
    }

    @PostMapping("/check-in/passive-session")
    @PreAuthorize("hasRole('LAB_MANAGER')")
    @Operation(summary = "Start a passive camera observation for manager-operated face check-in")
    public ResponseEntity<Response<FaceChallengeResponse>> startCheckInPassiveSession() {
        return ResponseEntity.ok(Response.ok(faceCheckinService.startPassiveSession()));
    }

    @PostMapping("/consent")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Grant or withdraw the current user's face consent")
    public ResponseEntity<Response<FaceConsentResponse>> changeOwnConsent(
            @Valid @RequestBody FaceConsentRequest request) {
        return ResponseEntity.ok(Response.ok("Face consent updated", faceProfileService.changeConsent(null, request)));
    }

    @GetMapping("/consent")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the current user's face consent")
    public ResponseEntity<Response<FaceConsentResponse>> getOwnConsent() {
        return ResponseEntity.ok(Response.ok(faceProfileService.getConsent(null)));
    }

    @PostMapping("/guidance")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Evaluate a camera frame for real-time face capture guidance")
    public ResponseEntity<Response<FaceGuidanceResponse>> guidanceForOwnProfile(
            @Valid @RequestBody FaceGuidanceRequest request) {
        return ResponseEntity.ok(Response.ok(faceProfileService.guidance(null, request)));
    }

    @PostMapping("/challenge")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Start a signed active face-liveness challenge")
    public ResponseEntity<Response<FaceChallengeResponse>> startChallenge() {
        return ResponseEntity.ok(Response.ok(faceProfileService.startChallenge(null)));
    }

    @PostMapping("/register")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Register the current user's face profile")
    public ResponseEntity<Response<FaceProfileResponse>> registerOwnProfile(
            @Valid @RequestBody FaceRegistrationRequest request) {
        return ResponseEntity.ok(Response.ok("Face profile registered", faceProfileService.register(null, request)));
    }

    @PutMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Replace the current user's face profile")
    public ResponseEntity<Response<FaceProfileResponse>> updateOwnProfile(
            @Valid @RequestBody FaceRegistrationRequest request) {
        return ResponseEntity.ok(Response.ok("Face profile updated", faceProfileService.register(null, request)));
    }

    @GetMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the current user's face profile metadata")
    public ResponseEntity<Response<FaceProfileResponse>> getOwnProfile() {
        return ResponseEntity.ok(Response.ok(faceProfileService.getProfile(null)));
    }

    @DeleteMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Delete and invalidate the current user's face profile")
    public ResponseEntity<Response<Void>> deleteOwnProfile() {
        faceProfileService.deleteProfile(null);
        return ResponseEntity.ok(Response.ok("Face profile deleted"));
    }

    @PostMapping("/users/{userId}/consent")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Grant or withdraw a user's face consent as Admin")
    public ResponseEntity<Response<FaceConsentResponse>> changeUserConsent(
            @PathVariable Long userId,
            @Valid @RequestBody FaceConsentRequest request) {
        return ResponseEntity.ok(Response.ok("Face consent updated",
                faceProfileService.changeConsent(userId, request)));
    }

    @GetMapping("/users/{userId}/consent")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get a user's face consent as Admin")
    public ResponseEntity<Response<FaceConsentResponse>> getUserConsent(@PathVariable Long userId) {
        return ResponseEntity.ok(Response.ok(faceProfileService.getConsent(userId)));
    }

    @PostMapping("/users/{userId}/guidance")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Evaluate a camera frame for an Admin-managed user's face guidance")
    public ResponseEntity<Response<FaceGuidanceResponse>> guidanceForUser(
            @PathVariable Long userId,
            @Valid @RequestBody FaceGuidanceRequest request) {
        return ResponseEntity.ok(Response.ok(faceProfileService.guidance(userId, request)));
    }

    @PostMapping("/users/{userId}/challenge")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Start an active face-liveness challenge for an Admin-managed user")
    public ResponseEntity<Response<FaceChallengeResponse>> startChallengeForUser(@PathVariable Long userId) {
        return ResponseEntity.ok(Response.ok(faceProfileService.startChallenge(userId)));
    }

    @PostMapping("/users/{userId}/register")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register a user's face profile as Admin")
    public ResponseEntity<Response<FaceProfileResponse>> registerUserProfile(
            @PathVariable Long userId,
            @Valid @RequestBody FaceRegistrationRequest request) {
        return ResponseEntity.ok(Response.ok("Face profile registered",
                faceProfileService.register(userId, request)));
    }

    @PutMapping("/users/{userId}/profile")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace a user's face profile as Admin")
    public ResponseEntity<Response<FaceProfileResponse>> updateUserProfile(
            @PathVariable Long userId,
            @Valid @RequestBody FaceRegistrationRequest request) {
        return ResponseEntity.ok(Response.ok("Face profile updated",
                faceProfileService.register(userId, request)));
    }

    @GetMapping("/users/{userId}/profile")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get a user's face profile metadata as Admin")
    public ResponseEntity<Response<FaceProfileResponse>> getUserProfile(@PathVariable Long userId) {
        return ResponseEntity.ok(Response.ok(faceProfileService.getProfile(userId)));
    }

    @DeleteMapping("/users/{userId}/profile")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete and invalidate a user's face profile as Admin")
    public ResponseEntity<Response<Void>> deleteUserProfile(@PathVariable Long userId) {
        faceProfileService.deleteProfile(userId);
        return ResponseEntity.ok(Response.ok("Face profile deleted"));
    }
}
