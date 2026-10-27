package com.smartticket.experience.graph;

import com.smartticket.exception.ApiFailure;
import com.smartticket.knowledge.KnowledgeSource;
import com.smartticket.rag.KnowledgeSearchService;
import com.smartticket.security.ActorContext;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.smartticket.experience.graph.ExperienceGraph.*;

/** Hybrid retrieval seeds bounded two-hop paths; evidence is never joined across contributors. */
@Service
public class ExperienceGraphSearchService {
    public static final int MAX_SOURCES=2, MAX_SEEDS=6, MAX_NODES=48, MAX_EDGES=64, MAX_EVIDENCE=128;
    private final KnowledgeSearchService knowledge;
    private final ExperienceGraphReader reader;

    public ExperienceGraphSearchService(KnowledgeSearchService knowledge, ExperienceGraphReader reader) {
        this.knowledge=knowledge; this.reader=reader;
    }
    public boolean enabled() { return reader.enabled(); }

    public Map<String,Object> search(String query) {
        ActorContext.require();
        if (!enabled()) throw new ApiFailure(404,40400,"图谱检索未启用");
        if (query==null || query.isBlank() || query.length()>1000) throw new ApiFailure(400,40000,"检索文本长度无效");
        var retrieval=knowledge.search(query);
        List<Object> sources=new ArrayList<>(); Set<String> seen=new HashSet<>(); List<String> degraded=new ArrayList<>();
        retrieval.degradedSources().forEach(s->degraded.add(s.name()));
        int seeds=0;
        for (var hit:retrieval.hits()) {
            String file=hit.chunk().sourceFile();
            if (!file.startsWith("resolved-ticket/") || !seen.add(file)) continue;
            if (++seeds>MAX_SEEDS || sources.size()>=MAX_SOURCES) break;
            try {
                var key=KnowledgeSource.parse(file);
                var snapshot=reader.read(key);
                if (snapshot.isEmpty()) continue;
                var row=describe(key,snapshot.get());
                if (row.isPresent()) sources.add(row.get()); else degraded.add("GRAPH_LIMIT_EXCEEDED");
            } catch (IllegalArgumentException invalidSource) {
                degraded.add("INVALID_GRAPH_SOURCE");
            } catch (RuntimeException unavailable) {
                degraded.add("GRAPH_UNAVAILABLE"); // Do not expose SQL, actor IDs or private staff records.
            }
        }
        return Map.of("sources",List.copyOf(sources),"degraded",degraded.stream().distinct().toList(),
                "maxHops",2,"reason",sources.isEmpty()?"NO_VERIFIED_GRAPH_SOURCE":"VERIFIED_GRAPH_EVIDENCE",
                "fallback","可使用searchKnowledge查询文本；资料不足或适用前提不符时转人工，不执行业务操作");
    }

    /** Revalidate only sources actually delivered to the model, immediately before final output. */
    public boolean current(String sourceId,String graphHash) {
        ActorContext.require();
        try {
            if (sourceId==null || !sourceId.endsWith("#0")) return false;
            return reader.read(KnowledgeSource.parse(sourceId.substring(0,sourceId.length()-2)))
                    .map(s->s.publication().graphHash().equals(graphHash)).orElse(false);
        } catch (RuntimeException unavailable) { return false; }
    }

    static Optional<Map<String,Object>> describe(KnowledgeSource key,Snapshot graph) {
        if (graph.nodes().size()>MAX_NODES || graph.edges().size()>MAX_EDGES || graph.evidence().size()>MAX_EVIDENCE)
            return Optional.empty();
        var nodes=new HashMap<String,Node>(); graph.nodes().forEach(n->nodes.put(n.id(),n));
        var roots=graph.nodes().stream().filter(n->n.kind()==Kind.PROBLEM).toList();
        if (roots.size()!=1) return Optional.empty();
        var root=roots.get(0); Map<String,Integer> depths=new HashMap<>(); depths.put(root.id(),0);
        for (int depth=0;depth<2;depth++) {
            int d=depth;
            graph.edges().stream().filter(e->Objects.equals(depths.get(e.from()),d))
                    .forEach(e->depths.merge(e.to(),d+1,Math::min));
        }
        var edges=new LinkedHashMap<String,Edge>();
        for (var e:graph.edges()) if (depths.getOrDefault(e.from(),2)<2 && depths.containsKey(e.to())) edges.put(e.id(),e);
        // Never discard a prerequisite/negative fact to make a superficially complete graph response.
        if (edges.size()!=graph.edges().size() || depths.size()!=nodes.size()) return Optional.empty();
        var facts=new LinkedHashMap<String,Object>(); facts.put("problem",root.label());
        for (String field:List.of("symptoms","conditions","steps","contraindications","counterexamples","hypotheses","outcome")) {
            var values=graph.evidence().stream().filter(e->field.equals(e.details().field()))
                    .sorted(Comparator.comparingInt(e->e.details().ordinal()))
                    .map(e->nodes.get(edges.get(e.edgeId()).to()).label()).toList();
            facts.put(field,values);
        }
        List<Object> paths=new ArrayList<>(), contextPaths=new ArrayList<>();
        for (var e:edges.values()) {
            var support=graph.evidence().stream().filter(v->v.edgeId().equals(e.id())).map(Support::key).toList();
            if (support.isEmpty()) return Optional.empty();
            paths.add(Map.of("from",e.from(),"relation",e.relation().name(),"to",e.to(),"edgeId",e.id(),"evidenceKeys",support));
        }
        for (var support:graph.evidence()) {
            var d=support.details(); var edge=edges.get(support.edgeId());
            boolean order="stepOrder".equals(d.field());
            contextPaths.add(List.of(order?"steps/"+d.ordinal():"problem",edge.relation().name(),
                    order?"steps/"+(d.ordinal()+1):d.field()+"/"+d.ordinal(),support.key()));
        }
        var p=graph.publication(); var row=new LinkedHashMap<String,Object>();
        row.put("sourceId",key.file()+"#0"); row.put("title",root.label()); row.put("scope",SCOPE);
        row.put("candidateId",key.candidateId()); row.put("revision",key.revision()); row.put("cardId",p.cardId());
        row.put("factScope",p.factScope()); row.put("graphHash",p.graphHash()); row.put("textHash",p.textHash());
        row.put("compilerVersion",p.compilerVersion()); row.put("facts",facts); row.put("paths",List.copyOf(paths));
        row.put("contextPaths",List.copyOf(contextPaths));
        row.put("nodes",graph.nodes()); row.put("evidence",graph.evidence());
        row.put("references",graph.evidence().stream().flatMap(e->e.details().references().stream()).distinct().toList());
        row.put("notice","已审核合成经验，仅限演示；先核对适用条件与禁止条件。假设未证实，业务效果待核实；不代表执行成功或新增业务能力。");
        return Optional.of(Collections.unmodifiableMap(row));
    }
}
