package com.smartticket.reception;

import com.smartticket.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets/{ticketId}/reception")
public class ReceptionController {
    private final ReceptionRepository repo;
    public ReceptionController(ReceptionRepository repo) {this.repo=repo;}
    public record Response(
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String requestId,
            @Min(1) int expectedTurn,
            @NotBlank @Pattern(regexp="MESSAGE|RESOLVED|HUMAN") String action,
            @NotNull @Size(max=800) String message) {}

    @GetMapping public Result<?> view(@PathVariable long ticketId) {return Result.success(repo.view(ticketId));}

    @PostMapping("/responses")
    public Result<?> respond(@PathVariable long ticketId,@Valid @RequestBody Response body) {
        if(body.action().equals("MESSAGE") && body.message().isBlank())
            throw new com.smartticket.exception.ApiFailure(400,40000,"补充信息不能为空");
        return Result.success(repo.respond(ticketId,body.requestId(),body.expectedTurn(),body.action(),body.message()));
    }
}
