package com.qms.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns each request a correlation id (NFR-MNT-001), exposes it to the JSON logger through MDC, returns it in the
 * {@code X-Trace-Id} header, and is the same value carried as {@code trace_id} in error bodies (§20.3).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TraceIds.HEADER);
        String traceId = TraceIds.isAcceptable(incoming) ? incoming : TraceIds.next();
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }

    public static String currentTraceId() {
        String id = MDC.get(TraceIds.MDC_KEY);
        return id != null ? id : TraceIds.next();
    }
}
