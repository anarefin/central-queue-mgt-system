package com.qms.issuance;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The v1 visitor directory (FR-INT-010): the local {@code visitor} table, matched by external code or phone. */
@Component
class LocalVisitorDirectory implements VisitorDirectory {

    private final VisitorRepository visitors;

    LocalVisitorDirectory(VisitorRepository visitors) {
        this.visitors = visitors;
    }

    @Override
    public Optional<Match> lookup(String query) {
        return visitors.findByCodeOrPhone(query)
                .map(v -> new Match(v.id(), v.externalCode(), v.name(), v.category(), v.phone(), Map.of()));
    }
}
