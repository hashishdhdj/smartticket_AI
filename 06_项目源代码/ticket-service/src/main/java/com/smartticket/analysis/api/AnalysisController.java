package com.smartticket.analysis.api;

import com.smartticket.analysis.persistence.AnalysisTaskRepository;
import com.smartticket.mq.outbox.OutboxRepository;
import com.smartticket.governance.ApplicationMetrics;
import com.smartticket.security.AccessPolicy;

import com.smartticket.common.Result;
import com.smartticket.enums.ErrorCode;
import com.smartticket.exception.BusinessException;
import com.smartticket.mapper.TicketMapper;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AnalysisController {
    private final AnalysisTaskRepository repo;
    private final OutboxRepository outbox;
    private final TicketMapper tickets;
    private final AccessPolicy access;
    private final ApplicationMetrics metrics;

    public AnalysisController(AnalysisTaskRepository r, TicketMapper t, AccessPolicy a, ApplicationMetrics m, OutboxRepository outbox) {
        repo = r;
        this.outbox = outbox;
        tickets = t;
        access = a;
        metrics = m;
    }

    @GetMapping("/tickets/{id}/analysis")
    public Result<?> analysis(@PathVariable long id) {
        var t = tickets.selectById(id);
        if (t == null) throw new BusinessException(ErrorCode.TICKET_NOT_FOUND);
        access.read(t);
        return Result.success(repo.analysis(id));
    }

    @PostMapping("/tickets/{id}/analysis/replay")
    public Result<?> replay(
            @PathVariable long id, @RequestHeader("Idempotency-Key") String requestKey) {
        access.admin();
        var t = tickets.selectById(id);
        if (t == null) throw new BusinessException(ErrorCode.TICKET_NOT_FOUND);
        return Result.success(Map.of("taskId", repo.replayWithKey(t, requestKey)));
    }

    @GetMapping("/admin/metrics")
    public Result<?> metrics() {
        access.admin();
        return Result.success(
                Map.of(
                        "meters",
                        metrics.snapshot(),
                        "tasks",
                        repo.statusCounts(),
                        "outbox",
                        outbox.statusCounts()));
    }
}
