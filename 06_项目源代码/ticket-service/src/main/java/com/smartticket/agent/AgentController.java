package com.smartticket.agent;

import com.smartticket.common.Result;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentService agent;
    private final AssignmentActions actions;

    public AgentController(AgentService a, AssignmentActions w) {
        agent = a;
        actions = w;
    }

    public record Request(
            @jakarta.validation.constraints.NotBlank
                    @jakarta.validation.constraints.Size(max = 2000)
                    String message) {}

    @PostMapping("/runs")
    public Result<?> run(@jakarta.validation.Valid @RequestBody Request r) {
        return Result.success(agent.run(r.message()));
    }

    @PostMapping("/actions/{id}/confirm")
    public Result<?> confirm(@PathVariable String id) {
        return Result.success(actions.confirm(id));
    }
}
