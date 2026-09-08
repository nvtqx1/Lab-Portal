package com.web.labportalbackend.booking.service;

import com.web.labportalbackend.booking.dto.request.CreateTimeSlotRequest;
import com.web.labportalbackend.booking.dto.request.CancelTimeSlotRequest;
import com.web.labportalbackend.booking.dto.response.TimeSlotResponse;
import java.util.List;

/**
 * Service interface for time slot management.
 */
public interface TimeSlotService {
    TimeSlotResponse createSlot(CreateTimeSlotRequest request);
    List<TimeSlotResponse> getSlotsByLab(Long labId);
    List<TimeSlotResponse> getSlotHistoryByLab(Long labId);
    TimeSlotResponse getSlotById(Long slotId);
    TimeSlotResponse updateSlotStatus(Long slotId, String status);
    TimeSlotResponse cancelSlot(Long slotId, CancelTimeSlotRequest request);
    TimeSlotResponse completeSlot(Long slotId);
    void deleteSlot(Long slotId);
}
