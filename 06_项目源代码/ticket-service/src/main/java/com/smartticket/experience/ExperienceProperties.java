package com.smartticket.experience;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "smartticket.experience")
public class ExperienceProperties {
    private boolean enabled = false;
    private boolean cardsEnabled = false;
    private boolean graphIndexEnabled = false;
    private boolean graphSearchEnabled = false;
}
