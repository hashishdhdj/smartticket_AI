package com.smartticket.knowledge;

import com.smartticket.rag.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeVisibilityTest {
    private static RetrievalHit hit(String file) {
        return new RetrievalHit(new KnowledgeChunk(file,"标题","问题",0,"正文"),0.9);
    }
    @Test
    void disabledWorkflowNeverExposesDynamicOrMalformedSources() {
        var repo=mock(KnowledgeWorkflowRepository.class);
        var config=new KnowledgeWorkflowProperties();
        var visibility=new KnowledgeVisibility(repo,config);
        var staticHit=hit("faq/01-账户问题.md");
        var dynamic=hit(new KnowledgeSource(UUID.randomUUID().toString(),1).file());
        assertEquals(List.of(staticHit),visibility.filter(List.of(staticHit,dynamic,hit("resolved-ticket/unknown"))));
        verifyNoInteractions(repo);
    }
    @Test
    void onlyTheCurrentlyApprovedIndexedRevisionPasses() {
        var repo=mock(KnowledgeWorkflowRepository.class);var config=new KnowledgeWorkflowProperties();config.setEnabled(true);
        String id=UUID.randomUUID().toString();var old=hit(new KnowledgeSource(id,1).file());var current=hit(new KnowledgeSource(id,3).file());
        when(repo.visibleSources(Set.of(id))).thenReturn(Set.of(current.chunk().sourceFile()));
        assertEquals(List.of(current),new KnowledgeVisibility(repo,config).filter(List.of(old,current)));
    }
    @Test
    void unavailableCatalogCannotFailOpen() {
        var repo=mock(KnowledgeWorkflowRepository.class);var config=new KnowledgeWorkflowProperties();config.setEnabled(true);
        when(repo.visibleSources(anySet())).thenThrow(new IllegalStateException("catalog unavailable"));
        assertThrows(IllegalStateException.class,()->new KnowledgeVisibility(repo,config)
                .filter(List.of(hit(new KnowledgeSource(UUID.randomUUID().toString(),1).file()))));
    }
}
