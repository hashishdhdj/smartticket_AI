package com.smartticket.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.rag.config.EmbeddingConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmbeddingConfigProtocolTest {
    @Test
    void fixedDimensionProviderOmitsDimensionsButStillReturns1024Vector() throws Exception {
        verifyDimensions(false);
    }

    @Test
    void configurableDimensionProviderSendsExplicitDimensions() throws Exception {
        verifyDimensions(true);
    }

    @SuppressWarnings("unchecked")
    private void verifyDimensions(boolean sendDimensions) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            captured.set(mapper.readTree(exchange.getRequestBody()));
            byte[] body = mapper.writeValueAsBytes(Map.of("object", "list", "model", "BAAI/bge-m3",
                    "data", List.of(Map.of("object", "embedding", "index", 0,
                    "embedding", Collections.nCopies(1024, 0.1))),
                    "usage", Map.of("prompt_tokens", 2, "total_tokens", 2)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            KnowledgeVectorProperties properties = new KnowledgeVectorProperties();
            var embedding = properties.getEmbedding();
            embedding.setApiKey("local-protocol-test-key");
            embedding.setModel("BAAI/bge-m3");
            embedding.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            embedding.setSendDimensions(sendDimensions);
            ObjectProvider<RestClient.Builder> builders = mock(ObjectProvider.class);
            when(builders.getIfAvailable(any())).thenReturn(RestClient.builder());
            ObjectProvider<RetryTemplate> retries = mock(ObjectProvider.class);
            when(retries.getIfAvailable(any())).thenReturn(new RetryTemplate());
            var model = new EmbeddingConfig().embeddingModel(properties, builders, retries);
            List<float[]> result = model.embed(List.of("synthetic protocol test"));
            assertEquals(1, result.size());
            assertEquals(1024, result.get(0).length);
            assertEquals("BAAI/bge-m3", captured.get().get("model").asText());
            if (sendDimensions) assertEquals(1024, captured.get().get("dimensions").asInt());
            else assertFalse(captured.get().has("dimensions"));
        } finally { server.stop(0); }
    }
}
