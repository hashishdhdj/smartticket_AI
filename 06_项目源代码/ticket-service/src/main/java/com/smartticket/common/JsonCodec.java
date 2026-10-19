package com.smartticket.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Shared serialization; persistence ownership stays in each module. */
@Component
public class JsonCodec {
    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Serialization failed", failure);
        }
    }
}
