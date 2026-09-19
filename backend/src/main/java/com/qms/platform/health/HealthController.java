package com.qms.platform.health;

import com.qms.platform.ApiException;
import com.qms.platform.security.PublicEndpoint;
import com.qms.platform.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness, readiness and dependency health as three separate answers (NFR-MNT-002):
 * <ul>
 *   <li>{@code live}: the process is running; never touches a dependency, so a slow database cannot get it restarted.
 *   <li>{@code ready}: the instance may receive traffic, i.e. the database answers.
 *   <li>{@code dependencies}: per-dependency state, always HTTP 200, for operators and the admin app.
 * </ul>
 */
@PublicEndpoint("Liveness, readiness and dependency probes are called by the load balancer and orchestrator without credentials")
@RestController
@RequestMapping("/health")
class HealthController {

    private final List<DependencyProbe> probes;

    HealthController(List<DependencyProbe> probes) {
        this.probes = probes;
    }

    @GetMapping("/live")
    Map<String, String> live() {
        return Map.of("status", "up");
    }

    @GetMapping("/ready")
    Map<String, String> ready() {
        DependencyProbe.State database = probe("database").check();
        if (database != DependencyProbe.State.UP) {
            throw new ApiException(ErrorCode.UNAVAILABLE, Map.of("dependency", "database"));
        }
        return Map.of("status", "up");
    }

    @GetMapping("/dependencies")
    Map<String, Object> dependencies() {
        Map<String, Object> report = new LinkedHashMap<>();
        boolean anyDown = false;
        for (DependencyProbe probe : probes) {
            DependencyProbe.State state = probe.check();
            anyDown |= state == DependencyProbe.State.DOWN;
            report.put(probe.name(), Map.of("status", state.wire()));
        }
        return Map.of("status", anyDown ? "down" : "up", "dependencies", report);
    }

    private DependencyProbe probe(String name) {
        return probes.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }
}
