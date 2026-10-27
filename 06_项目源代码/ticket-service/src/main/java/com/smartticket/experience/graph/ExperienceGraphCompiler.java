package com.smartticket.experience.graph;

import com.smartticket.common.JsonCodec;
import com.smartticket.experience.ExperienceCardCodec;
import com.smartticket.experience.ExperienceCardContract.*;
import com.smartticket.knowledge.KnowledgeSource;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.smartticket.experience.graph.ExperienceGraph.*;

/** Deterministic field-to-relation rules; no model guesses or executed generated code. */
@Component
public class ExperienceGraphCompiler {
    private final JsonCodec json;
    private final ExperienceCardCodec cards;
    public ExperienceGraphCompiler(JsonCodec json,ExperienceCardCodec cards){this.json=json;this.cards=cards;}
    public Plan compile(StoredCard stored,String submission) {
        Card c=cards.normalize(stored.content());
        if(!c.equals(stored.content())||!cards.hash(c).equals(stored.hash())||!stored.question().equals(c.problemType())
                ||!cards.answer(c).equals(stored.answer())||c.supportStatus()!=SupportStatus.REFERENCED_DEMO)
            throw new IllegalStateException("Experience card is not a canonical publishable snapshot");
        var nodes=new TreeMap<String,Node>();var edges=new TreeMap<String,Edge>();var support=new ArrayList<Support>();
        var problem=node(Kind.PROBLEM,c.problemType(),nodes);
        connect(c,problem,Kind.SYMPTOM,Relation.HAS_SYMPTOM,"symptoms",c.symptoms(),nodes,edges,support);
        connect(c,problem,Kind.CONDITION,Relation.REQUIRES,"conditions",c.conditions(),nodes,edges,support);
        connect(c,problem,Kind.STEP,Relation.ADDRESSED_BY,"steps",c.steps(),nodes,edges,support);
        connect(c,problem,Kind.CONDITION,Relation.CONTRAINDICATED,"contraindications",c.contraindications(),nodes,edges,support);
        connect(c,problem,Kind.COUNTEREXAMPLE,Relation.HAS_COUNTEREXAMPLE,"counterexamples",c.counterexamples(),nodes,edges,support);
        connect(c,problem,Kind.HYPOTHESIS,Relation.HAS_HYPOTHESIS,"hypotheses",c.hypotheses(),nodes,edges,support);
        connect(c,problem,Kind.OUTCOME,Relation.HAS_OUTCOME,"outcome",List.of("业务效果待核实（非独立验证）"),nodes,edges,support);
        for(int i=0;i+1<c.steps().size();i++) {
            var from=node(Kind.STEP,c.steps().get(i),nodes);var to=node(Kind.STEP,c.steps().get(i+1),nodes);
            add(c,from,to,Relation.NEXT_STEP,"stepOrder",i,edges,support);
        }
        support.sort(Comparator.comparing(Support::edgeId).thenComparing(Support::key));
        var ns=List.copyOf(nodes.values());var es=List.copyOf(edges.values());var ev=List.copyOf(support);
        String graphHash=KnowledgeSource.hash(json.encode(List.of(COMPILER,stored.hash(),ns,es,ev)));
        String textHash=KnowledgeSource.hash(KnowledgeSource.content(stored.question(),stored.answer()));
        return new Plan(stored.id(),submission,stored.hash(),textHash,graphHash,c.factScope().name(),ns,es,ev);
    }
    private Node node(Kind kind,String value,Map<String,Node> nodes) {
        if(value.indexOf(0)>=0||value.isBlank())throw new IllegalArgumentException("Invalid graph concept");
        String id=KnowledgeSource.hash(json.encode(List.of(SCOPE,kind.name(),value)));
        return nodes.computeIfAbsent(id,ignored->new Node(id,kind,value,value));
    }
    private void connect(Card c,Node from,Kind kind,Relation relation,String field,List<String> values,
            Map<String,Node> nodes,Map<String,Edge> edges,List<Support> evidence) {
        for(int i=0;i<values.size();i++)add(c,from,node(kind,values.get(i),nodes),relation,field,i,edges,evidence);
    }
    private void add(Card c,Node from,Node to,Relation relation,String field,int ordinal,Map<String,Edge> edges,List<Support> evidence) {
        String id=KnowledgeSource.hash(json.encode(List.of(SCOPE,from.id(),relation.name(),to.id())));
        edges.putIfAbsent(id,new Edge(id,from.id(),relation,to.id()));
        evidence.add(new Support(id,field+"/"+ordinal,new Details(field,ordinal,c.factScope().name(),c.supportStatus().name(),c.evidenceRefs())));
    }
}
