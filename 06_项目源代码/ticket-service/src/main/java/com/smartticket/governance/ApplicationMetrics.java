package com.smartticket.governance;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Component
public class ApplicationMetrics {
    private final MeterRegistry registry = new SimpleMeterRegistry();

    public void count(String name, String outcome) {
        registry.counter("smartticket." + name, "outcome", outcome).increment();
    }

    public void time(String name, long ms) {
        registry.timer("smartticket." + name).record(Duration.ofMillis(Math.max(0, ms)));
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new TreeMap<>();
        registry.getMeters()
                .forEach(
                        m -> {
                            Map<String, Double> values = new TreeMap<>();
                            m.measure()
                                    .forEach(
                                            v -> values.put(v.getStatistic().name(), v.getValue()));
                            out.put(m.getId().getName() + m.getId().getTags(), values);
                        });
        return out;
    }
}
