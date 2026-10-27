package com.smartticket.experience.graph;

import com.smartticket.common.Result;
import com.smartticket.experience.ExperienceContract.StrictInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge/candidates/{id}/graph")
public class ExperienceGraphController {
    private final ExperienceGraphService service;
    public ExperienceGraphController(ExperienceGraphService service){this.service=service;}
    public record Rebuild(@NotNull @Positive Long expectedRevision) implements StrictInput {}
    @GetMapping public Result<?> inspect(@PathVariable String id){return Result.success(service.inspect(id));}
    @PostMapping("/rebuild") public Result<?> rebuild(@PathVariable String id,@Valid @RequestBody Rebuild body){
        return Result.success(service.rebuild(id,body.expectedRevision()));
    }
}
