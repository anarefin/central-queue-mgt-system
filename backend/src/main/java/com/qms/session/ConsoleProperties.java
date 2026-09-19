package com.qms.session;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param visitorFields the visitor fields the console shows, by role wire name ({@code qms.console.visitor-fields.agent=code,category});
 *     a role with no entry sees the full set, and an entry with no fields shows none (FR-AGT-034, FR-SEC-020: "full configured field
 *     set plus notes" is the Agent console's default). Fields are {@code code}, {@code name}, {@code category} and {@code purpose_note}.
 */
@ConfigurationProperties("qms.console")
public record ConsoleProperties(@DefaultValue Map<String, List<String>> visitorFields) {

    public ConsoleProperties {
        for (var entry : visitorFields.entrySet()) {
            if (com.qms.platform.security.Role.tryFromWire(entry.getKey()).isEmpty()) {
                throw new IllegalArgumentException("qms.console.visitor-fields." + entry.getKey() + " is not a role");
            }
            for (String field : entry.getValue()) {
                if (VisitorField.fromWire(field.strip()).isEmpty()) {
                    throw new IllegalArgumentException("qms.console.visitor-fields." + entry.getKey() + ": unknown visitor field '" + field + "'");
                }
            }
        }
    }
}
