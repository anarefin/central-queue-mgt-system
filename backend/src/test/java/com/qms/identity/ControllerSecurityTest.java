package com.qms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.fixtures.controllers.SecuredAndPublicController;
import com.qms.fixtures.controllers.UnsecuredController;
import com.qms.platform.ApiPathConfig;
import com.qms.platform.security.PublicEndpoint;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-CFG-108: every controller method is either secured or explicitly public, so the §5.2 matrix cannot silently
 * drift from the code. Also checks that the filter chain's permit-all list and the {@code @PublicEndpoint} markers
 * describe the same set of endpoints.
 */
class ControllerSecurityTest {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** Controller methods that are neither secured nor public, or that claim to be both. */
    static List<String> violations(Collection<Class<?>> controllers) {
        List<String> found = new ArrayList<>();
        for (Class<?> controller : controllers) {
            for (Method method : handlerMethods(controller)) {
                boolean secured = has(method, controller, PreAuthorize.class);
                boolean pub = has(method, controller, PublicEndpoint.class);
                String where = controller.getSimpleName() + "." + method.getName();
                if (!secured && !pub) found.add(where + " has neither @PreAuthorize nor @PublicEndpoint");
                if (secured && pub) found.add(where + " is both secured and public");
            }
        }
        return found;
    }

    private static List<Method> handlerMethods(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .toList();
    }

    private static boolean has(Method method, Class<?> controller, Class<? extends java.lang.annotation.Annotation> annotation) {
        return AnnotatedElementUtils.hasAnnotation(method, annotation) || AnnotatedElementUtils.hasAnnotation(controller, annotation);
    }

    private static List<Class<?>> applicationControllers() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.qms")) {
            if (definition.getBeanClassName().startsWith("com.qms.fixtures.") || definition.getBeanClassName().contains("Test$")) continue;
            found.add(Class.forName(definition.getBeanClassName()));
        }
        return found;
    }

    /** Full request paths of one handler method, including {@code /api/v1} and the class-level mapping. */
    private static List<String> paths(Class<?> controller, Method method) {
        String[] classPaths = pathsOf(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
        String[] methodPaths = pathsOf(AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class));
        List<String> full = new ArrayList<>();
        for (String c : classPaths.length == 0 ? new String[] {""} : classPaths) {
            for (String m : methodPaths.length == 0 ? new String[] {""} : methodPaths) {
                full.add((ApiPathConfig.BASE_PATH + c + m).replaceAll("\\{[^/]+}", "x"));
            }
        }
        return full;
    }

    private static String[] pathsOf(RequestMapping mapping) {
        return mapping == null ? new String[0] : mapping.path();
    }

    @Test
    void everyControllerMethodIsSecuredOrExplicitlyPublic() throws Exception {
        List<Class<?>> controllers = applicationControllers();

        // Guards against the scan silently finding nothing.
        assertThat(controllers.stream().map(Class::getSimpleName))
                .contains("HealthController", "AuthController", "AuditController", "UserAdminController", "ApprovalController");
        assertThat(violations(controllers)).isEmpty();
    }

    @Test
    void theRuleFlagsAnUnsecuredMethodAndAContradiction() {
        List<String> found = violations(List.of(UnsecuredController.class, SecuredAndPublicController.class));

        assertThat(found).hasSize(2);
        assertThat(found).anyMatch(v -> v.startsWith("UnsecuredController.open") && v.contains("neither"));
        assertThat(found).anyMatch(v -> v.startsWith("SecuredAndPublicController.both") && v.contains("both"));
    }

    @Test
    void thePermitAllListAndThePublicEndpointMarkersDescribeTheSameEndpoints() throws Exception {
        List<String> publicPaths = new ArrayList<>();
        List<String> securedPaths = new ArrayList<>();
        for (Class<?> controller : applicationControllers()) {
            for (Method method : handlerMethods(controller)) {
                (has(method, controller, PublicEndpoint.class) ? publicPaths : securedPaths).addAll(paths(controller, method));
            }
        }
        List<String> permitAll = Arrays.asList(SecurityConfig.PUBLIC_PATHS);

        for (String path : publicPaths) {
            assertThat(permitAll.stream().anyMatch(pattern -> MATCHER.match(pattern, path)))
                    .as("@PublicEndpoint path " + path + " must be in the permit-all list").isTrue();
        }
        for (String path : securedPaths) {
            assertThat(permitAll.stream().anyMatch(pattern -> MATCHER.match(pattern, path)))
                    .as("secured path " + path + " must not be permitted without a token").isFalse();
        }
        for (String pattern : permitAll) {
            assertThat(publicPaths.stream().anyMatch(path -> MATCHER.match(pattern, path)))
                    .as("permit-all pattern " + pattern + " must match some @PublicEndpoint").isTrue();
        }
        assertThat(publicPaths.stream().collect(Collectors.toSet()))
                .contains("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/health/live");
    }
}
