package com.qms.issuance;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param directoryTimeout the hard timeout {@link VisitorDirectoryGateway} gives each visitor directory implementation
 *     before falling through to the next one (FR-INT-012, default 1.5 s).
 * @param registrationFields which OPTIONAL walk-in fields {@code POST /visitors} captures (FR-ISS-021): {@code email},
 *     {@code category}, {@code purpose}. {@code name} and {@code phone} are always captured; they are the minimum
 *     record itself. A field left out of this list is neither captured nor retained, even when the caller sends it
 *     (FR-SEC-023).
 */
@ConfigurationProperties("qms.visitor")
public record VisitorProperties(@DefaultValue("1500ms") Duration directoryTimeout, @DefaultValue({"email", "category", "purpose"}) List<String> registrationFields) {

    private static final Set<String> KNOWN_FIELDS = Set.of("email", "category", "purpose");

    public VisitorProperties {
        for (String field : registrationFields) {
            if (!KNOWN_FIELDS.contains(field)) throw new IllegalArgumentException("qms.visitor.registration-fields: unknown field '" + field + "'");
        }
        registrationFields = List.copyOf(registrationFields);
    }

    boolean captures(String field) {
        return registrationFields.contains(field);
    }
}
