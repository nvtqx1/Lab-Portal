package com.web.labportalbackend.booking.controller;

import com.web.labportalbackend.booking.dto.request.CreateTimeSlotRequest;
import com.web.labportalbackend.booking.dto.request.CancelTimeSlotRequest;
import com.web.labportalbackend.booking.dto.response.TimeSlotResponse;
import com.web.labportalbackend.booking.service.TimeSlotService;
import com.web.labportalbackend.common.dto.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "TimeSlot", description = "Lab time slot management endpoints")
@SecurityRequirement(name = "Bearer Authentication")
public class TimeSlotController {

    private final TimeSlotService timeSlotService;

    @GetMapping("/labs/{labId}/slots")
    @Operation(summary = "Get time slots by lab", description = "Retrieve time slots for a specific lab")
    public ResponseEntity<Response<List<TimeSlotResponse>>> getSlotsByLab(
            @PathVariable Long labId
    ) {
        log.info("Fetching time slots for lab: {}", labId);
        List<TimeSlotResponse> slots = timeSlotService.getSlotsByLab(labId);
        return ResponseEntity.ok(Response.ok("Time slots retrieved successfully", slots));
    }

    @GetMapping("/labs/{labId}/slots/history")
    @PreAuthorize("hasRole('LAB_MANAGER')")
    @Operation(summary = "Get managed laboratory time slot history",
            description = "Retrieve ended or terminal time slots for the laboratory managed by the current user")
    public ResponseEntity<Response<List<TimeSlotResponse>>> getSlotHistoryByLab(
            @PathVariable Long labId
    ) {
        List<TimeSlotResponse> slots = timeSlotService.getSlotHistoryByLab(labId);
        return ResponseEntity.ok(Response.ok("Đã tải lịch sử ca sử dụng", slots));
    }

    @PostMapping("/slots")
    @PreAuthorize("hasRole('LAB_MANAGER')")
    @Operation(summary = "Create time slot", description = "Create a new lab time slot with capacity")
    public ResponseEntity<Response<TimeSlotResponse>> createSlot(
            @Valid @RequestBody CreateTimeSlotRequest request
    ) {
        log.info("Creating time slot for lab: {}", request.getLabId());
        TimeSlotResponse slot = timeSlotService.createSlot(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Response.ok("Time slot created successfully", slot));
    }

    @GetMapping("/slots/{slotId}")
    @Operation(summary = "Get time slot by ID", description = "Retrieve a specific time slot")
    public ResponseEntity<Response<TimeSlotResponse>> getSlotById(@PathVariable Long slotId) {
        TimeSlotResponse slot = timeSlotService.getSlotById(slotId);
        return ResponseEntity.ok(Response.ok("Time slot retrieved successfully", slot));
    }

    @PatchMapping("/slots/{slotId}/status")
    @PreAuthorize("hasRole('LAB_MANAGER')")
    @Operation(summary = "Update time slot status without active bookings", description = "A slot with active bookings must use the dedicated cancel or complete operations so affected users are notified")
    public ResponseEntity<Response<TimeSlotResponse>> updateSlotStatus(
            @PathVariable Long slotId,
            @RequestParam String status
    ) {
        TimeSlotResponse slot = timeSlotService.updateSlotStatus(slotId, status);
        return ResponseEntity.ok(Response.ok("Time slot status updated successfully", slot));
    }

    @PatchMapping("/slots/{slotId}/cancel")
    @PreAuthorize("hasRole('LAB_MANAGER')")
    @Operation(summary = "Cancel time slot", description = "Cancel a time slot and notify registered students")
    public ResponseEntity<Response<TimeSlotResponse>> cancelSlot(
            @PathVariable Long slotId,
            @RequestBody(required = false) CancelTimeSlotRequest request
    ) {
        TimeSlotResponse slot = timeSlotService.cancelSlot(slotId, request != null ? request : new CancelTimeSlotRequest());
        return ResponseEntity.ok(Response.ok("Time slot cancelled successfully", slot));
    }

    @PatchMapping("/slots/{slotId}/complete")
    @PreAuthorize("hasRole('LAB_MANAGER')")
    @Operation(summary = "Complete an active lab session", description = "Close a currently running time slot managed by the current lab manager")
    public ResponseEntity<Response<TimeSlotResponse>> completeSlot(@PathVariable Long slotId) {
        return ResponseEntity.ok(Response.ok("Đã kết thúc ca sử dụng lab.", timeSlotService.completeSlot(slotId)));
    }

    @DeleteMapping("/slots/{slotId}")
    @PreAuthorize("hasRole('LAB_MANAGER')")
    @Operation(summary = "Delete time slot", description = "Soft-delete a time slot only when it has no active bookings; use cancellation or completion for active bookings so users are notified")
    public ResponseEntity<Response<Void>> deleteSlot(@PathVariable Long slotId) {
        timeSlotService.deleteSlot(slotId);
        return ResponseEntity.ok(Response.ok("Time slot deleted successfully"));
    }
}
