package com.smartticket.phase3;

import com.smartticket.common.Result;
import com.smartticket.enums.ErrorCode;
import com.smartticket.exception.BusinessException;
import com.smartticket.mapper.TicketMapper;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AnalysisController {
    private final Phase3Repository repo;
    private final TicketMapper tickets;
    private final AccessPolicy access;
    private final Phase3Metrics metrics;

    public AnalysisController(Phase3Repository r, TicketMapper t, AccessPolicy a, Phase3Metrics m) {
        repo = r;
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
                        repo.db()
                                .queryForList(
                                        "SELECT status,COUNT(*) AS total FROM ai_analysis_task"
                                                + " GROUP BY status"),
                        "outbox",
                        repo.db()
                                .queryForList(
                                        "SELECT status,COUNT(*) AS total FROM outbox_event GROUP BY"
                                                + " status")));
    }
}
