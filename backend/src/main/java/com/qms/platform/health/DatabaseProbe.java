package com.qms.platform.health;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class DatabaseProbe implements DependencyProbe {

    private final JdbcTemplate jdbc;

    DatabaseProbe(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "database";
    }

    @Override
    public State check() {
        try {
            Integer one = jdbc.queryForObject("select 1", Integer.class);
            return Integer.valueOf(1).equals(one) ? State.UP : State.DOWN;
        } catch (RuntimeException unreachable) {
            return State.DOWN;
        }
    }
}
