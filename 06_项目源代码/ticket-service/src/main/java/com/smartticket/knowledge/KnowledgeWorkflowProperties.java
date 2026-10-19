package com.smartticket.knowledge;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "smartticket.knowledge")
public class KnowledgeWorkflowProperties {
    private boolean enabled = false;
    private boolean asyncEnabled = false;
    @Min(1) @Max(5) private int maxAttempts = 3;
    @Min(30) @Max(600) private int leaseSeconds = 90;
}
