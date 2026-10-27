package com.smartticket.experience.graph;

import java.util.List;
import com.smartticket.experience.ExperienceContract.Evidence;

public final class ExperienceGraph {
    private ExperienceGraph() {}
    public static final String SCOPE="SHARED_DEMO";
    public static final String COMPILER="experience-graph-v1";
    public enum Kind { PROBLEM,SYMPTOM,CONDITION,STEP,OUTCOME,COUNTEREXAMPLE,HYPOTHESIS }
    public enum Relation { HAS_SYMPTOM,REQUIRES,ADDRESSED_BY,HAS_OUTCOME,CONTRAINDICATED,HAS_COUNTEREXAMPLE,HAS_HYPOTHESIS,NEXT_STEP }
    public record Node(String id,Kind kind,String key,String label) {}
    public record Edge(String id,String from,Relation relation,String to) {}
    public record Details(String field,int ordinal,String factScope,String supportStatus,List<Evidence> references) {}
    public record Support(String edgeId,String key,Details details) {}
    public record Plan(String cardId,String submissionId,String cardHash,String textHash,String graphHash,String factScope,
                       List<Node> nodes,List<Edge> edges,List<Support> evidence) {}
    public record Manifest(String candidateId,long revision,String sourceFile,String state,String cardId,String submissionId,
                           String cardHash,String textHash,String graphHash,String compilerVersion,String factScope,int evidenceCount) {}
    public record Snapshot(Manifest publication,List<Node> nodes,List<Edge> edges,List<Support> evidence) {}
}
