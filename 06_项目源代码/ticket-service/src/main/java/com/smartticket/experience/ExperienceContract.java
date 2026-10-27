package com.smartticket.experience;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.smartticket.exception.ApiFailure;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

/** Staff statements are evidence inputs, never independently verified facts. */
public final class ExperienceContract {
    private ExperienceContract() {}
    public enum Kind { STAFF_REPLY, TROUBLESHOOTING, CORRECTION }
    public enum Origin { STAFF_ENTERED, AI_SYNTHETIC_DEMO }
    public enum Outcome { ADOPTED, EFFECTIVE, FAILED, CORRECTED, UNVERIFIED }
    public enum FeedbackOrigin { STAFF_OBSERVATION, SIMULATED_DEMO }

    public interface StrictInput {
        @JsonAnySetter
        default void unknown(String name, Object value) {
            throw new ApiFailure(400,40000,"请求含未支持字段");
        }
    }
    public record Evidence(@NotBlank @Size(max=240) String path,
                           @NotBlank @Size(max=200) String title) implements StrictInput {}
    public record InteractionInput(@NotBlank String requestId,
            @NotNull @PositiveOrZero Long expectedVersion, @NotNull Kind kind,
            @NotBlank @Size(min=5,max=4000) String content, @NotNull Origin origin,
            @Positive Long correctsInteractionId,
            @NotNull @Size(max=8) List<@NotNull @Valid Evidence> evidenceRefs) implements StrictInput {}
    public record FeedbackInput(@NotBlank String requestId,
            @NotNull @PositiveOrZero Long expectedVersion, @NotNull @Positive Long interactionId,
            @NotNull Outcome outcome, @NotNull FeedbackOrigin origin,
            @NotBlank @Size(min=5,max=2000) String comment,
            @Positive Long correctionInteractionId) implements StrictInput {}

    public record Interaction(long id,long ticketId,String requestId,long ticketVersion,long authorId,
            String authorRole,Kind kind,String content,Origin origin,Long correctsInteractionId,
            List<Evidence> evidenceRefs,LocalDateTime createdAt) {}
    public record Feedback(long id,long ticketId,String requestId,long ticketVersion,long authorId,
            String authorRole,long interactionId,Outcome outcome,FeedbackOrigin origin,String comment,
            Long correctionInteractionId,LocalDateTime createdAt) {}
    public record Page<T>(List<T> records,Long nextAfterId) {}
}
