package com.web.labportalbackend.ai.service;

/** Server-owned collected values, persisted with the conversation turn, never accepted from the browser. */
public record AiShiftDialogueState(Long labId, String date, String startTime, String endTime,
                                   Integer capacity, String timeZone, Long suggestionId, boolean labConfirmed) {
    public AiShiftDialogueState(Long labId, String date, String startTime, String endTime,
                                Integer capacity, String timeZone) {
        this(labId, date, startTime, endTime, capacity, timeZone, null, true);
    }

    public AiShiftDialogueState withSuggestion(Long id) {
        return new AiShiftDialogueState(labId, date, startTime, endTime, capacity, timeZone, id, labConfirmed);
    }
}
