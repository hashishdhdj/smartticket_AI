package com.smartticket.experience;

import com.smartticket.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static com.smartticket.experience.ExperienceContract.*;

/** Internal staff notes; these endpoints do not send messages to customers. */
@RestController
@RequestMapping("/api/tickets/{id}")
public class ExperienceController {
    private final ExperienceService service;
    public ExperienceController(ExperienceService service) {this.service=service;}
    @PostMapping("/interactions")
    public Result<Interaction> append(@PathVariable long id,@Valid @RequestBody InteractionInput body) {
        return Result.success(service.append(id,body));
    }
    @GetMapping("/interactions")
    public Result<Page<Interaction>> list(@PathVariable long id,@RequestParam(defaultValue="0") long afterId,
                                          @RequestParam(defaultValue="20") int limit) {
        return Result.success(service.interactions(id,afterId,limit));
    }
    @PostMapping("/experience-feedback")
    public Result<Feedback> feedback(@PathVariable long id,@Valid @RequestBody FeedbackInput body) {
        return Result.success(service.feedback(id,body));
    }
    @GetMapping("/experience-feedback")
    public Result<Page<Feedback>> feedbackList(@PathVariable long id,@RequestParam(defaultValue="0") long afterId,
                                             @RequestParam(defaultValue="20") int limit) {
        return Result.success(service.feedback(id,afterId,limit));
    }
}
