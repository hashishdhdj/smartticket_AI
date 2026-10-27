package com.smartticket.experience;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import static com.smartticket.experience.ExperienceContract.*;

public final class ExperienceCardContract {
    private ExperienceCardContract() {}
    public enum FactScope { PROJECT_RULE, SIMULATED_PLATFORM_RULE_NOT_IMPLEMENTED_FEATURE,
                            SIMULATED_CUSTOMER_QUESTION_NO_PAYMENT_INTEGRATION }
    public enum SupportStatus { REFERENCED_DEMO, UNVERIFIED }
    public record Card(@NotBlank @Size(min=5,max=120) String problemType,
            @NotNull @Size(min=1,max=8) List<@NotBlank @Size(max=120) String> symptoms,
            @NotNull @Size(min=1,max=8) List<@NotBlank @Size(max=160) String> conditions,
            @NotNull @Size(min=1,max=8) List<@NotBlank @Size(max=180) String> steps,
            @NotNull @Size(max=8) List<@NotBlank @Size(max=160) String> contraindications,
            @NotNull @Size(max=4) List<@NotBlank @Size(max=160) String> counterexamples,
            @NotNull @Size(max=4) List<@NotBlank @Size(max=160) String> hypotheses,
            @NotNull @Size(max=8) List<@NotNull @Valid Evidence> evidenceRefs,
            @NotNull FactScope factScope,@NotNull SupportStatus supportStatus) implements StrictInput {}
    public record Prepare(@NotBlank String requestId,@NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Size(min=1,max=12) List<@NotNull @Positive Long> positiveInteractionIds,
            @NotNull @Size(max=8) List<@NotNull @Positive Long> counterexampleInteractionIds,
            @NotNull @Valid Card card) implements StrictInput {}
    public record Binding(@NotNull @PositiveOrZero Long expectedRevision,
            @NotBlank String submissionId) implements StrictInput {}
    public record StoredCard(String id,String hash,Card content,String question,String answer) {}
    public record Submission(String id,long ticketId,long ticketVersion,long authorId,String cardId,String requestHash,boolean duplicateCard) {}
    public record Reuse(String candidateId,long revision,String sourceFile) {}
    public record Related(String cardId,String problemType,double lexicalSimilarity,String reason) {}
    public record Prepared(Submission submission,StoredCard card,boolean exactDuplicate,Reuse reusableKnowledge,
                           List<Related> relatedDrafts,String extractionMethod) {}
    public record Version(String candidateId,long revision,String cardId,String submissionId,String cardHash,
                          String publicationHash,String state) {}
}
