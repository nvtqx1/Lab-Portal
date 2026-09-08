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
import java.util.HashSet;
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
                || patch.clearFields() == null || !FIELDS.containsAll(patch.clearFields())
                || patch.timeMentions() == null || !validTimeMentions(patch.timeMentions())
                || patch.dateMention() != null && patch.clearFields().contains("date")
                || hasClearedTimeMention(patch, "START", "startTime")
                || hasClearedTimeMention(patch, "END", "endTime")) {
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
        boolean capacityCleared = patch.clearFields().contains("capacity");
        boolean zoneCleared = patch.clearFields().contains("timeZone");
        Integer capacity = capacityCleared
                ? lab.capacity()
                : patch.capacity() != null
                ? patch.capacity()
                : continuing && prior.capacitySource() == ValueSource.USER
                ? prior.capacity()
                : lab.capacity();
        String zone = zoneCleared
                ? defaults.timeZoneId()
                : patch.timeZone() != null
                ? patch.timeZone()
                : continuing && prior.timeZoneSource() == ValueSource.USER
                ? prior.timeZone()
                : defaults.timeZoneId();
        ValueSource capacitySource = capacityCleared
                ? ValueSource.CLEARED : patch.capacity() != null || continuing && prior.capacitySource() == ValueSource.USER
                ? ValueSource.USER : ValueSource.DEFAULT;
        ValueSource zoneSource = zoneCleared
                ? ValueSource.CLEARED : patch.timeZone() != null || continuing && prior.timeZoneSource() == ValueSource.USER
                ? ValueSource.USER : ValueSource.DEFAULT;
        List<String> missing = new ArrayList<>();
        ZoneId selectedZone = validZone(zone);
        if (selectedZone == null) {
            missing.add("múi giờ hợp lệ");
        } else {
            zone = selectedZone.getId();
        }
        String date = patch.clearFields().contains("date") ? null
                : patch.dateMention() != null ? validDate(patch.dateMention(), selectedZone) : prior.date();
        String start = timeValue("START", "startTime", patch.timeMentions(), prior.startTime(), patch.clearFields());
        String end = timeValue("END", "endTime", patch.timeMentions(), prior.endTime(), patch.clearFields());
        if (date == null) missing.add("ngày");
        if (start == null) missing.add("giờ bắt đầu");
        if (end == null) missing.add("giờ kết thúc");
        if (start != null && end != null && !LocalTime.parse(end).isAfter(LocalTime.parse(start))) {
            end = null;
            missing.add("giờ kết thúc sau giờ bắt đầu");
        }
        if (capacity == null || capacity <= 0) {
            missing.add("sức chứa lớn hơn 0");
        }
        if (date != null && start != null && selectedZone != null) {
            var local = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(start));
            var offsets = selectedZone.getRules().getValidOffsets(local);
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

    private static boolean validTimeMentions(List<TimeMention> mentions) {
        var roles = new HashSet<String>();
        for (var mention : mentions) {
            if (mention == null || !Set.of("START", "END").contains(mention.role())
                    || mention.hour() == null || mention.hour() < 0 || mention.hour() > 23
                    || mention.minute() == null || mention.minute() < 0 || mention.minute() > 59
                    || !roles.add(mention.role())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasClearedTimeMention(Patch patch, String role, String field) {
        return patch.clearFields().contains(field)
                && patch.timeMentions().stream().anyMatch(mention -> role.equals(mention.role()));
    }

    private static String timeValue(String role, String field, List<TimeMention> mentions, String previous,
                                    List<String> cleared) {
        if (cleared.contains(field)) return null;
        return mentions.stream().filter(mention -> role.equals(mention.role())).findFirst()
                .map(mention -> LocalTime.of(mention.hour(), mention.minute())
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                .orElse(previous);
    }

    private static String normalizedName(String value) {
        return Normalizer.normalize(value.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replaceAll("\\s+", " ");
    }

    private String validDate(DateMention mention, ZoneId selectedZone) {
        if (mention.day() == null || mention.month() == null || mention.day() < 1 || mention.day() > 31
                || mention.month() < 1 || mention.month() > 12
                || mention.year() != null && (mention.year() < 1 || mention.year() > 9999)) {
            return null;
        }
        if (mention.year() != null) {
            try { return LocalDate.of(mention.year(), mention.month(), mention.day()).toString(); }
            catch (DateTimeException exception) { return null; }
        }
        ZoneId comparisonZone = selectedZone == null ? defaults.timeZone() : selectedZone;
        LocalDate currentDate = LocalDate.now(clock.withZone(comparisonZone));
        for (int year = currentDate.getYear(); year <= currentDate.getYear() + 8; year++) {
            try {
                LocalDate candidate = LocalDate.of(year, mention.month(), mention.day());
                if (!candidate.isBefore(currentDate)) return candidate.toString();
            } catch (DateTimeException ignored) {
                // Continue to the next year for dates such as 29 February.
            }
        }
        return null;
    }

    private static ZoneId validZone(String supplied) {
        try {
            return ZoneId.of(supplied);
        } catch (DateTimeException | NullPointerException exception) {
            return null;
        }
    }

    public record Resolution(AiShiftDialogueState state, String question, ObjectNode draft) {}

    private record Patch(String kind, Long labRef, String requestedLabName, String mode, DateMention dateMention,
                         List<TimeMention> timeMentions, Integer capacity, String timeZone,
                         List<String> clearFields, Boolean requiresHumanReview) {}

    private record DateMention(Integer day, Integer month, Integer year) {}

    private record TimeMention(String role, Integer hour, Integer minute) {}
}
