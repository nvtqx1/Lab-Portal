package com.web.labportalbackend.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.web.labportalbackend.ai.enums.AiAssistantSystemRole;
import com.web.labportalbackend.ai.service.AiCurrentActorProvider;
import com.web.labportalbackend.ai.service.AiShiftDialogueState;
import com.web.labportalbackend.ai.service.AiSuggestionPayloadValidationException;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Clock;
import java.time.LocalDateTime;
import java.text.Normalizer;
import java.util.Locale;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Applies a model patch to server-owned state and computes readiness independently of the model. */
@Service
public class AiShiftDialogueService {
    private final ObjectMapper mapper;
    private final LaboratoryRepository labs;
    private final AiCurrentActorProvider actors;
    private final Clock clock;
    private static final Set<String> FIELDS = Set.of("date", "startTime", "endTime", "capacity", "timeZone");

    @org.springframework.beans.factory.annotation.Autowired
    public AiShiftDialogueService(ObjectMapper mapper, LaboratoryRepository labs, AiCurrentActorProvider actors) {
        this(mapper, labs, actors, Clock.systemUTC());
    }

    AiShiftDialogueService(ObjectMapper mapper, LaboratoryRepository labs, AiCurrentActorProvider actors, Clock clock) {
        this.mapper = mapper;
        this.labs = labs;
        this.actors = actors;
        this.clock = clock;
    }

    public Resolution resolve(Long labId, String answer, AiShiftDialogueState previous) {
        Patch patch;
        try {
            patch = mapper.readerFor(Patch.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(answer);
        } catch (JsonProcessingException exception) {
            throw new AiSuggestionPayloadValidationException();
        }
        if (!"LAB_SHIFT_CREATE_INTERPRETATION".equals(patch.kind()) || !labId.equals(patch.labRef())
                || !Boolean.TRUE.equals(patch.requiresHumanReview())
                || !Set.of("NEW", "CONTINUE").contains(patch.mode() == null ? "" : patch.mode())
                || patch.clearFields() == null || !FIELDS.containsAll(patch.clearFields())) {
            throw new AiSuggestionPayloadValidationException();
        }
        // Re-read defaults through the same actor-scoped projection used by the authorized context.
        var actor = actors.requireCurrentActor();
        if (actor.role() != AiAssistantSystemRole.LAB_MANAGER
                || !labs.existsAiContextManagedLab(actor.id(), labId, actor.role().name())) {
            throw new AccessDeniedException("Lab is outside the manager's scope");
        }
        var lab = labs.findAiContextLaboratory(actor.id(), labId, actor.role().name())
                .orElseThrow(() -> new AccessDeniedException("Lab is outside the manager's scope"));
        boolean continuing = "CONTINUE".equals(patch.mode()) && previous != null && labId.equals(previous.labId());
        boolean labConfirmed = patch.requestedLabName() != null
                ? normalizedName(lab.name()).equals(normalizedName(patch.requestedLabName()))
                : continuing && previous.labConfirmed();
        var prior = continuing
                ? previous : new AiShiftDialogueState(labId, null, null, null, null, null);
        String date = value("date", patch.date(), prior.date(), patch.clearFields());
        String start = value("startTime", patch.startTime(), prior.startTime(), patch.clearFields());
        String end = value("endTime", patch.endTime(), prior.endTime(), patch.clearFields());
        String zone = value("timeZone", patch.timeZone(), prior.timeZone(), patch.clearFields());
        Integer capacity = patch.clearFields().contains("capacity") ? null
                : patch.capacity() != null ? patch.capacity() : prior.capacity();
        if (!continuing && zone == null && !patch.clearFields().contains("timeZone")) zone = "Asia/Ho_Chi_Minh";
        if (!continuing && capacity == null && !patch.clearFields().contains("capacity")) capacity = lab.capacity();
        List<String> missing = new ArrayList<>();
        date = validDate(date);
        start = validTime(start);
        end = validTime(end);
        if (date == null) missing.add("ngày");
        if (start == null) missing.add("giờ bắt đầu");
        if (end == null) missing.add("giờ kết thúc");
        if (start != null && end != null && !LocalTime.parse(end).isAfter(LocalTime.parse(start))) {
            end = null;
            missing.add("giờ kết thúc sau giờ bắt đầu");
        }
        try { ZoneId.of(zone); } catch (DateTimeException | NullPointerException exception) {
            zone = null;
            missing.add("múi giờ hợp lệ");
        }
        if (capacity == null || capacity <= 0) {
            capacity = null;
            missing.add("sức chứa lớn hơn 0");
        }
        if (date != null && start != null && zone != null) {
            var local = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(start));
            var offsets = ZoneId.of(zone).getRules().getValidOffsets(local);
            if (offsets.size() != 1) {
                missing.add("giờ bắt đầu không mơ hồ trong múi giờ đã chọn");
                start = null;
            } else if (!local.toInstant(offsets.getFirst()).isAfter(clock.instant())) {
                missing.add("ngày/giờ bắt đầu trong tương lai (thời điểm đã nhập đã qua)");
                date = null;
            }
        }
        var state = new AiShiftDialogueState(labId, date, start, end, capacity, zone, null, labConfirmed);
        if (!labConfirmed) {
            return new Resolution(state, "Tôi chưa xác định chắc Lab bạn yêu cầu. Bạn hãy nhập tên đầy đủ của Lab được cấp quyền: "
                    + lab.name() + ".", null);
        }
        if (!missing.isEmpty()) {
            return new Resolution(state, "Bạn vui lòng bổ sung " + String.join(", ", missing) + ".", null);
        }
        ObjectNode draft = mapper.createObjectNode();
        draft.put("kind", "LAB_SHIFT_CREATE_DRAFT").put("labRef", labId)
                .put("startLocalDateTime", date + "T" + start)
                .put("endLocalDateTime", date + "T" + end)
                .put("timeZone", zone).put("capacity", capacity).put("requiresHumanReview", true);
        return new Resolution(state, null, draft);
    }

    private static String value(String field, String supplied, String previous, List<String> cleared) {
        return cleared.contains(field) ? null : supplied != null ? supplied : previous;
    }

    private static String normalizedName(String value) {
        return Normalizer.normalize(value.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replaceAll("\\s+", " ");
    }

    private static String validDate(String value) {
        try { return LocalDate.parse(value).toString(); }
        catch (DateTimeException | NullPointerException exception) { return null; }
    }

    private static String validTime(String value) {
        try { return LocalTime.parse(value).format(DateTimeFormatter.ofPattern("HH:mm:ss")); }
        catch (DateTimeException | NullPointerException exception) { return null; }
    }

    public record Resolution(AiShiftDialogueState state, String question, ObjectNode draft) {}

    private record Patch(String kind, Long labRef, String requestedLabName, String mode, String date, String startTime, String endTime,
                         Integer capacity, String timeZone, List<String> clearFields, Boolean requiresHumanReview) {}
}
