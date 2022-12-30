package org.example;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.junit.jupiter.api.Test;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.rest_client.RestClientTransport;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Test OpenSearch docker container
 */
public class LocalTest {
    @Test
    public void testCreateIndex() throws IOException {
        OpenSearchClient client = createLocalJavaClient();
        Property TYPE_KEYWORD = Property.of(p -> p.keyword(k -> k));
        Property TYPE_BOOLEAN = Property.of(p -> p.boolean_(b -> b));
        Property TYPE_DOUBLE = Property.of(p -> p.double_(d -> d));
        String indexName = "my-index";

        if (!client.indices().exists(b -> b.index(indexName)).value()) {
            client.indices().create(b -> {
                Map<String, Property> properties = new HashMap<>();
                properties.put("orderId", TYPE_KEYWORD);
                properties.put("delivered", TYPE_BOOLEAN);
                properties.put("amount", TYPE_DOUBLE);
                b.index(indexName)
                        .mappings(tb -> tb.properties(properties));
                return b;
            });
            System.out.println("Created index " + indexName);
        } else {
            System.out.println("Index " + indexName + " already exists");
        }

        String orderId = Instant.now().toString();

        // Index data
        Map<String, Object> order = Map.of(
                "orderId", orderId,
                "delivered", true,
                "amount", ThreadLocalRandom.current().nextDouble(10)
        );
        IndexRequest<Map<String, Object>> indexRequest = IndexRequest.of(i -> i.index(indexName)
                .document(order)
        );
        IndexResponse indexResponse = client.index(indexRequest);
        System.out.println("Index " + indexResponse.id() + " version " + indexResponse.version());

        // Search
        // https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/searching.html
        // NOTE: We can use a concrete java class for both index and return
        SearchResponse<ObjectNode> searchResponse = client.search(s -> s
                        .index(indexName)
                        .query(q -> q.matchAll(m -> m))
                , ObjectNode.class);
        List<Hit<ObjectNode>> hits = searchResponse.hits().hits();
        for (Hit<ObjectNode> hit : hits) {
            System.out.println("hit " + hit.source());
        }
    }

    public OpenSearchClient createLocalJavaClient() {
        OpenSearchTransport transport = new RestClientTransport(createLocalRestClient().build(), new JacksonJsonpMapper());
        return new OpenSearchClient(transport);
    }

    // Disable cert verification for using local client
    // See https://github.com/opensearch-project/opensearch-java/pull/308
    public RestClientBuilder createLocalRestClient() {
        String endpoint = "https://localhost:9200";
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials("admin", "admin"));
        return RestClient.builder(HttpHost.create(endpoint))
                .setHttpClientConfigCallback((c) -> {
                    // Disable cert verification
                    // https://stackoverflow.com/questions/2703161/how-to-ignore-ssl-certificate-errors-in-apache-httpclient-4-0
                    // because default uses a demo cert from https://github.com/opensearch-project/security/blob/207cfcc379ffd4127e32b9fdfdd75ea394b48d0e/tools/install_demo_configuration.sh#L201
                    try {
                        c.setSSLContext(new SSLContextBuilder().loadTrustMaterial(null, TrustAllStrategy.INSTANCE).build())
                                .setSSLHostnameVerifier(new NoopHostnameVerifier());
                    } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
                        throw new RuntimeException(e);
                    }
                    return c.setDefaultCredentialsProvider(credentialsProvider);
                });
    }
}
