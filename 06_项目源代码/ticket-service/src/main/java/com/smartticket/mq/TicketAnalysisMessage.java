package com.smartticket.mq;
public record TicketAnalysisMessage(
        int schemaVersion,
        String eventId,
        String taskId,
        Long ticketId,
        long inputVersion,
        String traceId,
        String occurredAt
) {}