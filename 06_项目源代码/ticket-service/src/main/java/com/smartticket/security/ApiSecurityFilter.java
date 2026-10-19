package com.smartticket.security;

import com.smartticket.config.RuntimeProperties;
import com.smartticket.exception.ApiFailure;
import com.smartticket.governance.ApplicationMetrics;
import com.smartticket.governance.BudgetLedger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.common.Result;
import com.smartticket.enums.ErrorCode;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "smartticket.phase3.security-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ApiSecurityFilter extends OncePerRequestFilter {
    private final RuntimeProperties config;
    private final ObjectMapper json;
    private final BudgetLedger budget;
    private final ApplicationMetrics metrics;
    private final Map<Long, long[]> windows = new ConcurrentHashMap<>();

    public ApiSecurityFilter(RuntimeProperties c, ObjectMapper j, BudgetLedger b, ApplicationMetrics m) {
        config = c;
        json = j;
        budget = b;
        metrics = m;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        return !r.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest r, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String trace = UUID.randomUUID().toString();
        response.setHeader("X-Trace-Id", trace);
        MDC.put("traceId", trace);
        long start = System.nanoTime();
        try {
            if (config.isSecurityEnabled()) {
                String token = r.getHeader("X-API-Key");
                var user =
                        config.getUsers().stream()
                                .filter(
                                        u ->
                                                u.getId() > 0
                                                        && u.getRole() != null
                                                        && Set.of("ADMIN", "HANDLER", "SUBMITTER")
                                                                .contains(u.getRole())
                                                        && u.getToken() != null
                                                        && !u.getToken().isBlank()
                                                        && token != null
                                                        && MessageDigest.isEqual(
                                                                u.getToken()
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8),
                                                                token.getBytes(
                                                                        StandardCharsets.UTF_8)))
                                .findFirst()
                                .orElseThrow(() -> new ApiFailure(401, 40100, "需要有效身份凭据"));
                ActorContext.set(new ActorContext.Actor(user.getId(), user.getRole()));
                if (r.getRequestURI().startsWith("/api/ai/")
                        || r.getRequestURI().equals("/api/agent/runs")
                        || r.getRequestURI().endsWith("/analysis/replay")) {
                    limit(user.getId());
                    budget.request(user.getId());
                }
            }
            chain.doFilter(r, response);
        } catch (ApiFailure failure) {
            response.setStatus(failure.status());
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(
                    response.getWriter(),
                    Result.fail(failure.code(), failure.getMessage()));
            metrics.count("security", String.valueOf(failure.status()));
        } catch (Exception unexpected) {
            // Filter failures happen before MVC advice (for example a budget-store outage).
            logger.error("API filter infrastructure failure", unexpected);
            if (response.isCommitted()) throw new ServletException("API request failed after response commit", unexpected);
            response.resetBuffer();
            response.setStatus(500);
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getWriter(), Result.fail(ErrorCode.SYSTEM_ERROR));
            metrics.count("security", "500");
        } finally {
            ActorContext.clear();
            MDC.remove("traceId");
            metrics.time(
                    "http.latency",
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private void limit(long id) {
        long minute = System.currentTimeMillis() / 60000;
        var window = windows.computeIfAbsent(id, k -> new long[] {minute, 0});
        synchronized (window) {
            if (window[0] != minute) {
                window[0] = minute;
                window[1] = 0;
            }
            if (++window[1] > config.getRequestsPerMinute())
                throw new ApiFailure(429, 42900, "请求过于频繁，请稍后重试");
        }
    }
}
