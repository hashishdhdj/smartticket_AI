package com.smartticket.knowledge;

import com.smartticket.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class KnowledgeReviewController {
    private final KnowledgeReviewService service;
    public KnowledgeReviewController(KnowledgeReviewService service) { this.service=service; }

    public record Revision(@NotNull @PositiveOrZero Long expectedRevision) {}
    public record Approval(@NotNull @PositiveOrZero Long expectedRevision,
                           @NotBlank @Size(min=5,max=500) String question,
                           @NotBlank @Size(min=10,max=1500) String answer,
                           @NotBlank String scope,@NotNull Boolean redactionConfirmed) {}

    @GetMapping("/tickets/{id}/knowledge")
    public Result<?> ticket(@PathVariable long id) { return Result.success(service.ticketState(id)); }
    @GetMapping("/knowledge/candidates")
    public Result<?> list(@RequestParam(defaultValue="20") int limit) { return Result.success(service.list(limit)); }
    @GetMapping("/knowledge/candidates/{id}")
    public Result<?> detail(@PathVariable String id) { return Result.success(service.detail(id)); }
    @PostMapping("/knowledge/candidates/{id}/approve")
    public Result<?> approve(@PathVariable String id,@Valid @RequestBody Approval body) {
        return Result.success(service.approve(id,body.expectedRevision(),body.question(),body.answer(),body.scope(),body.redactionConfirmed()));
    }
    @PostMapping("/knowledge/candidates/{id}/reject")
    public Result<?> reject(@PathVariable String id,@Valid @RequestBody Revision body) { return Result.success(service.reject(id,body.expectedRevision())); }
    @PostMapping("/knowledge/candidates/{id}/retract")
    public Result<?> retract(@PathVariable String id,@Valid @RequestBody Revision body) { return Result.success(service.retract(id,body.expectedRevision())); }
    @PostMapping("/knowledge/jobs/{id}/retry")
    public Result<?> retry(@PathVariable String id,@Valid @RequestBody Revision body) { service.retry(id,body.expectedRevision());return Result.success(); }
}
