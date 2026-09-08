package com.web.labportalbackend.ai.config;

import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiShiftDefaults {

    private final ZoneId timeZone;

    public AiShiftDefaults(@Value("${app.ai.default-time-zone}") String timeZone) {
        this.timeZone = ZoneId.of(timeZone);
    }

    public ZoneId timeZone() {
        return timeZone;
    }

    public String timeZoneId() {
        return timeZone.getId();
    }
}
