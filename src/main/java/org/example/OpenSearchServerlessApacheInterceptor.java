package org.example;

import static org.apache.http.protocol.HttpCoreContext.HTTP_TARGET_HOST;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.apache.http.Header;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HttpContext;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.AwsSignerExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.signer.Signer;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

/**
 * Extended version of {@link io.github.acm19.aws.interceptor.http.AwsRequestSigningApacheInterceptor}
 * to always skip content length when using OpenSearch Serverless.
 * <a href="https://docs.aws.amazon.com/opensearch-service/latest/developerguide/serverless-clients.html#serverless-signing">signing request to serverless</a>
 */
public class OpenSearchServerlessApacheInterceptor implements HttpRequestInterceptor {
    /**
     * The service that we're connecting to.
     */
    private final String service;

    /**
     * The particular signer implementation.
     */
    private final Signer signer;

    /**
     * The source of AWS credentials for signing.
     */
    private final AwsCredentialsProvider awsCredentialsProvider;

    /**
     * The region signing region.
     */
    private final Region region;


    /**
     * @param service                service that we're connecting to
     * @param signer                 particular signer implementation
     * @param awsCredentialsProvider source of AWS credentials for signing
     * @param region                 signing region
     */
    public OpenSearchServerlessApacheInterceptor(final String service,
                                                 final Signer signer,
                                                 final AwsCredentialsProvider awsCredentialsProvider,
                                                 final Region region) {
        this.service = service;
        this.signer = signer;
        this.awsCredentialsProvider = awsCredentialsProvider;
        this.region = Objects.requireNonNull(region);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void process(final HttpRequest request, final HttpContext context)
            throws IOException {
        URIBuilder uriBuilder;
        try {
            uriBuilder = new URIBuilder(request.getRequestLine().getUri());
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URI", e);
        }

        // Copy Apache HttpRequest to AWS Request
        SdkHttpFullRequest.Builder requestBuilder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(request.getRequestLine().getMethod()))
                .uri(buildUri(context, uriBuilder));

        if (request instanceof HttpEntityEnclosingRequest) {
            HttpEntityEnclosingRequest httpEntityEnclosingRequest =
                    (HttpEntityEnclosingRequest) request;
            if (httpEntityEnclosingRequest.getEntity() != null) {
                InputStream content = httpEntityEnclosingRequest.getEntity().getContent();
                requestBuilder.contentStreamProvider(() -> (content));
            }
        }
        requestBuilder.rawQueryParameters(nvpToMapParams(uriBuilder.getQueryParams()));
        requestBuilder.headers(headerArrayToMap(request.getAllHeaders()));

        ExecutionAttributes attributes = new ExecutionAttributes();
        attributes.putAttribute(AwsSignerExecutionAttribute.AWS_CREDENTIALS, awsCredentialsProvider.resolveCredentials());
        attributes.putAttribute(AwsSignerExecutionAttribute.SERVICE_SIGNING_NAME, service);
        attributes.putAttribute(AwsSignerExecutionAttribute.SIGNING_REGION, region);

        // Sign it
        SdkHttpFullRequest signedRequest = signer.sign(requestBuilder.build(), attributes);

        // Now copy everything back
        request.setHeaders(mapToHeaderArray(signedRequest.headers()));
        // Copy Content-Length header back
        Header[] headers = request.getHeaders("content-length");
        if (headers != null) {
            Arrays.stream(headers)
                    .filter(h -> !"0".equals(h.getValue()))
                    .forEach(h -> request.addHeader(h));
        }
        if (request instanceof HttpEntityEnclosingRequest) {
            HttpEntityEnclosingRequest httpEntityEnclosingRequest =
                    (HttpEntityEnclosingRequest) request;
            if (httpEntityEnclosingRequest.getEntity() != null) {
                httpEntityEnclosingRequest.setEntity(httpEntityEnclosingRequest.getEntity());
            }
        }
    }

    private URI buildUri(final HttpContext context, URIBuilder uriBuilder) throws IOException {
        try {
            HttpHost host = (HttpHost) context.getAttribute(HTTP_TARGET_HOST);

            if (host != null) {
                uriBuilder.setHost(host.getHostName());
                uriBuilder.setScheme(host.getSchemeName());
                uriBuilder.setPort(host.getPort());
            }

            return uriBuilder.build();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URI", e);
        }
    }

    /**
     * @param params list of HTTP query params as NameValuePairs
     * @return a multimap of HTTP query params
     */
    private static Map<String, List<String>> nvpToMapParams(final List<NameValuePair> params) {
        Map<String, List<String>> parameterMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (NameValuePair nvp : params) {
            List<String> argsList =
                    parameterMap.computeIfAbsent(nvp.getName(), k -> new ArrayList<>());
            argsList.add(nvp.getValue());
        }
        return parameterMap;
    }

    /**
     * @param headers modelled Header objects
     * @return a Map of header entries
     */
    private static Map<String, List<String>> headerArrayToMap(final Header[] headers) {
        Map<String, List<String>> headersMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header header : headers) {
            if (!skipHeader(header)) {
                // System.out.println("put header " + header.getName());
                headersMap.put(header.getName(), headersMap
                        .getOrDefault(header.getName(),
                                new LinkedList<>(Collections.singletonList(header.getValue()))));
            }
        }
        return headersMap;
    }

    /**
     * @param header header line to check
     * @return true if the given header should be excluded when signing
     */
    private static boolean skipHeader(final Header header) {
        // https://docs.aws.amazon.com/opensearch-service/latest/developerguide/serverless-clients.html#serverless-signing
        return "content-length".equalsIgnoreCase(header.getName()) // Always strip Content-Length
                || "host".equalsIgnoreCase(header.getName()); // Host comes from endpoint
    }

    /**
     * @param mapHeaders Map of header entries
     * @return modelled Header objects
     */
    private static Header[] mapToHeaderArray(final Map<String, List<String>> mapHeaders) {
        Header[] headers = new Header[mapHeaders.size()];
        int i = 0;
        for (Map.Entry<String, List<String>> headerEntry : mapHeaders.entrySet()) {
            for (String value : headerEntry.getValue()) {
                headers[i++] = new BasicHeader(headerEntry.getKey(), value);
            }
        }
        return headers;
    }
}
