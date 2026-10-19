package com.smartticket.mq;

public record KnowledgeJobMessage(int schemaVersion, String eventId, String jobId,
                                 String resolutionId, String kind, long revision,
                                 String traceId, String occurredAt) {}
