package com.smartticket.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;

@Data
@Component
@ConfigurationProperties(prefix = "smartticket.phase3")
public class RuntimeProperties {
    private boolean securityEnabled = true, recordTasks = true, asyncEnabled = true;
    private int requestsPerMinute = 20, dailyRequests = 100, dailyTokens = 50000;
    private int maxModelCalls = 4, maxToolCalls = 6, maxOutputTokens = 512, maxRunTokens = 12000;
    private int deadlineSeconds = 60, maxAttempts = 3, leaseSeconds = 90;
    private List<User> users = new ArrayList<>();

    @Data
    public static class User {
        private long id;
        private String role, token;
    }
}
