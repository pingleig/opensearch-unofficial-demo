package org.example;

import java.io.IOException;
import java.util.Map;

import org.apache.http.HttpHost;
import org.junit.jupiter.api.Test;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4UnsignedPayloadSigner;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.opensearchserverless.OpenSearchServerlessClient;
import software.amazon.awssdk.services.opensearchserverless.model.BatchGetCollectionRequest;
import software.amazon.awssdk.services.opensearchserverless.model.ListCollectionsRequest;

/**
 * Test OpenSearch serverless.
 */
public class ServerlessTest {
    private static final String SERVICE = "aoss"; // NOTE: it is NOT es like managed domain
    private static final Region REGION = Region.US_WEST_2;

    // TODO: Create collection using SDK, it's a bit time consuming ... need to set the network, encryption and data access policy
    //  do it in console is a bit easier.

    @Test
    public void testListCollection() {
        OpenSearchServerlessClient client = createSdkClient();
        var res = client.listCollections(ListCollectionsRequest.builder().build());
        System.out.println("Found " + res.collectionSummaries().size() + " collections ");
        res.collectionSummaries().forEach(System.out::println);
        client.close();
    }

    @Test
    public void testCreateIndex() throws IOException {
        // TODO: change the collection mae base on what you created
        String collectionName = System.getProperty("user.name") + "-test";
        System.out.println("Collection " + collectionName);

        // Get endpoint
        OpenSearchServerlessClient sdkClient = createSdkClient();
        var res = sdkClient.batchGetCollection(BatchGetCollectionRequest.builder()
                .names(collectionName)
                .build());
        String endpoint = res.collectionDetails().get(0).collectionEndpoint();
        System.out.println("Collection endpoint " + endpoint);

        // Create the high level client
        var client = createRestHighLevelClient(endpoint);

        // Define mapping in Java code, better than putting raw JSON ... but still very primitive ...
        Map<String, String> keywordTypeMapping = Map.of("type", "keyword");
        Map<String, String> booleanTypeMapping = Map.of("type", "boolean");
        Map<String, String> doubleTypeMapping = Map.of("type", "double");
        Map<String, Object> traceSummariesMapping = Map.of(
                "orderId", keywordTypeMapping,
                "delivered", booleanTypeMapping,
                "amount", doubleTypeMapping
        );
        Map<String, Object> mapping = Map.of("properties", traceSummariesMapping);

        // Send request
        String indexName = "my-index-4";
        CreateIndexRequest createIndexRequest = new CreateIndexRequest(indexName);
        createIndexRequest.mapping(mapping);
        client.indices().create(createIndexRequest, RequestOptions.DEFAULT);
        client.close();
    }

    private RestHighLevelClient createRestHighLevelClient(String endpoint) {
        OpenSearchServerlessApacheInterceptor interceptor = new OpenSearchServerlessApacheInterceptor(
                SERVICE,
                Aws4UnsignedPayloadSigner.create(),
                awsCredentialsProvider(),
                Region.US_WEST_2);
        return new RestHighLevelClient(RestClient.builder(HttpHost.create(endpoint))
                .setHttpClientConfigCallback((c) -> c.addInterceptorLast(interceptor)));
    }


    // The SDK client can only create collection, it can NOT create index and ingest data etc.
    private OpenSearchServerlessClient createSdkClient() {
        return OpenSearchServerlessClient.builder()
                .region(REGION)
                .build();
    }

    private AwsCredentialsProvider awsCredentialsProvider() {
        return DefaultCredentialsProvider.create();
    }
}
