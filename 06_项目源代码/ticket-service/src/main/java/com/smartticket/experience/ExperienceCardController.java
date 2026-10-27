package com.smartticket.experience;

import com.smartticket.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static com.smartticket.experience.ExperienceCardContract.*;

@RestController
@RequestMapping("/api")
public class ExperienceCardController {
    private final ExperienceCardService service;
    public ExperienceCardController(ExperienceCardService service){this.service=service;}
    @PostMapping("/tickets/{id}/experience-cards")
    public Result<?> prepare(@PathVariable long id,@Valid @RequestBody Prepare body){return Result.success(service.prepare(id,body));}
    @GetMapping("/tickets/{id}/experience-cards")
    public Result<?> list(@PathVariable long id,@RequestParam(defaultValue="20") int limit){return Result.success(service.list(id,limit));}
    @GetMapping("/tickets/{id}/experience-cards/{submission}")
    public Result<?> detail(@PathVariable long id,@PathVariable String submission){return Result.success(service.detail(id,submission));}
    @PostMapping("/knowledge/candidates/{id}/experience")
    public Result<?> bind(@PathVariable String id,@Valid @RequestBody Binding body){return Result.success(service.bind(id,body));}
    @GetMapping("/knowledge/candidates/{id}/experience")
    public Result<?> binding(@PathVariable String id){return Result.success(service.binding(id));}
}
