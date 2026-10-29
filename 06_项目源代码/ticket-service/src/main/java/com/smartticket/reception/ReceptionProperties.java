package com.smartticket.reception;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@org.springframework.validation.annotation.Validated
@ConfigurationProperties(prefix="smartticket.reception")
public class ReceptionProperties {
    private boolean enabled = false;
    private boolean asyncEnabled = true;
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(10)
    private int maxAttempts = 3;
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(10)
    private int maxTurns = 6;
}
