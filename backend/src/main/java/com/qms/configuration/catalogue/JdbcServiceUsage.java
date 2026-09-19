package com.qms.configuration.catalogue;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Looks in the {@code ticket} table (SRS §18, {@code ticket.service_id}) once issuance creates it. Until then no
 * ticket can exist, so the answer is no; the query is only built when the table is there, so this needs no change
 * when issuance arrives.
 */
@Component
class JdbcServiceUsage implements ServiceUsage {

    private final JdbcTemplate jdbc;

    JdbcServiceUsage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasTickets(UUID serviceId) {
        Boolean tableExists = jdbc.queryForObject("SELECT to_regclass('ticket') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(tableExists)) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ticket WHERE service_id = ?)", Boolean.class, serviceId));
    }
}
