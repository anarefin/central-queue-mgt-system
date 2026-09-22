package com.qms.issuance.setup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the five shipped vertical profiles from {@code profiles/<wire>.json} on the classpath (SRS §3.4). One
 * generic loader over a fixed list of ids, never a branch per profile (CFG-001): adding a sixth profile is one more
 * resource file and one more {@link VerticalProfileId} constant, not one more line of behaviour here.
 */
@Component
public class VerticalProfileCatalog {

    private final Map<VerticalProfileId, VerticalProfileDefinition> definitions;

    VerticalProfileCatalog(JsonMapper mapper) {
        Map<VerticalProfileId, VerticalProfileDefinition> loaded = new EnumMap<>(VerticalProfileId.class);
        for (VerticalProfileId id : VerticalProfileId.values()) {
            loaded.put(id, load(mapper, id));
        }
        this.definitions = Map.copyOf(loaded);
    }

    private static VerticalProfileDefinition load(JsonMapper mapper, VerticalProfileId id) {
        ClassPathResource resource = new ClassPathResource("profiles/" + id.wire() + ".json");
        try (var in = resource.getInputStream()) {
            return mapper.readValue(in, VerticalProfileDefinition.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing or unreadable vertical profile: " + id.wire(), e);
        }
    }

    public VerticalProfileDefinition get(VerticalProfileId id) {
        return definitions.get(id);
    }

    public Map<VerticalProfileId, VerticalProfileDefinition> all() {
        return definitions;
    }
}
