package com.example.demo.pc.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    @Value("${app.ratelimit.requests-per-minute}")
    private int requestsPerMinute;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        String clientIP = resolveClientIP(request);
        Bucket bucket = buckets.computeIfAbsent(clientIP, ip -> createNewBucket());
        if (bucket.tryConsume(1)) {
            return true;
        }
        response.setStatus(429);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Rate limit exceeded. Try again shortly.\"}");
        return false;
    }

    private Bucket createNewBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(requestsPerMinute)
                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private String resolveClientIP(HttpServletRequest request) {

        String Forwarded = request.getHeader("X-Forwarded-For");
        if (Forwarded != null && !Forwarded.isBlank()) {
            return Forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
