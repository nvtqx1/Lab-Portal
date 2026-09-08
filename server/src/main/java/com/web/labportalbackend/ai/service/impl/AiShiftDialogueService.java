package com.web.labportalbackend.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.web.labportalbackend.ai.enums.AiAssistantSystemRole;
import com.web.labportalbackend.ai.config.AiShiftDefaults;
import com.web.labportalbackend.ai.service.AiCurrentActorProvider;
import com.web.labportalbackend.ai.service.AiShiftDialogueState;
import com.web.labportalbackend.ai.service.AiShiftDialogueState.ValueSource;
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
    private final AiShiftDefaults defaults;
    private static final Set<String> FIELDS = Set.of("date", "startTime", "endTime", "capacity", "timeZone");

    @org.springframework.beans.factory.annotation.Autowired
    public AiShiftDialogueService(ObjectMapper mapper, LaboratoryRepository labs, AiCurrentActorProvider actors,
                                  AiShiftDefaults defaults) {
        this(mapper, labs, actors, Clock.systemUTC(), defaults);
    }

    AiShiftDialogueService(ObjectMapper mapper, LaboratoryRepository labs, AiCurrentActorProvider actors, Clock clock,
                           AiShiftDefaults defaults) {
        this.mapper = mapper;
        this.labs = labs;
        this.actors = actors;
        this.clock = clock;
        this.defaults = defaults;
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
        // The candidate was already resolved from the manager's authorized scope. A manager
        // currently has one managed Lab candidate, so an omitted Lab name means "that Lab";
        // only an explicitly different name must require clarification.
        boolean labConfirmed = patch.requestedLabName() == null
                ? !continuing || previous.labConfirmed()
                : normalizedName(lab.name()).equals(normalizedName(patch.requestedLabName()));
        var prior = continuing
                ? previous : new AiShiftDialogueState(labId, null, null, null, null, null);
        String date = value("date", patch.date(), prior.date(), patch.clearFields());
        String start = value("startTime", patch.startTime(), prior.startTime(), patch.clearFields());
        String end = value("endTime", patch.endTime(), prior.endTime(), patch.clearFields());
        String zone = defaults.timeZoneId();
        Integer capacity = lab.capacity();
        ValueSource capacitySource = ValueSource.DEFAULT;
        ValueSource zoneSource = ValueSource.DEFAULT;
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
        if (patch.capacity() != null && !patch.capacity().equals(capacity)) {
            missing.add("sức chứa cố định của Lab là " + capacity);
        }
        if (patch.timeZone() != null && !sameZone(patch.timeZone(), defaults.timeZone())) {
            missing.add("múi giờ cố định của hệ thống là " + zone);
        }
        if (capacity == null || capacity <= 0) {
            throw new IllegalStateException("Managed Lab capacity is invalid");
        }
        if (date != null && start != null) {
            var local = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(start));
            var offsets = defaults.timeZone().getRules().getValidOffsets(local);
            if (offsets.size() != 1) {
                missing.add("giờ bắt đầu không mơ hồ trong múi giờ đã chọn");
                start = null;
            } else if (!local.toInstant(offsets.getFirst()).isAfter(clock.instant())) {
                missing.add("ngày/giờ bắt đầu trong tương lai (thời điểm đã nhập đã qua)");
                date = null;
            }
        }
        var state = new AiShiftDialogueState(labId, date, start, end, capacity, zone, null, labConfirmed,
                capacitySource, zoneSource);
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

    private static boolean sameZone(String supplied, ZoneId expected) {
        try {
            return ZoneId.of(supplied).equals(expected);
        } catch (DateTimeException | NullPointerException exception) {
            return false;
        }
    }

    public record Resolution(AiShiftDialogueState state, String question, ObjectNode draft) {}

    private record Patch(String kind, Long labRef, String requestedLabName, String mode, String date, String startTime, String endTime,
                         Integer capacity, String timeZone, List<String> clearFields, Boolean requiresHumanReview) {}
}
