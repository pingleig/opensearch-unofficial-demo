# Unofficial OpenSearch Demo

## Overview

Shows how to work with OpenSearch docker, managed domain and serverless in Java
because the official guide does not give end to end example when you:

- Have to use OpenSearch 1.x (v2 ships with a transport that works with managed domain)
- Want to use AWS SDK v2

It is NOT meant for production, use at your own risk. This is NOT an official AWS supported repo.

## Links

- Official guide https://github.com/awsdocs/amazon-opensearch-service-developer-guide
- Sign request for
  serverles https://docs.aws.amazon.com/opensearch-service/latest/developerguide/serverless-clients.html#serverless-signing
- https://code.dblock.org/2022/07/11/making-sigv4-authenticated-requests-to-managed-opensearch.html
  and https://github.com/dblock/opensearch-java-client-demo

## TODO

- [ ] docker compose https://github.com/opensearch-project/opensearch-java/issues/236
- [ ] managed domain?

## Usage

- See test folder, run the unit tests in your IDE directly after configuring your AWS credential.
- Region is hard coded to `us-west-2`.
- Create Serverless collection in AWS console

## Known Issues

### Servleess Signing

Requirement https://docs.aws.amazon.com/opensearch-service/latest/developerguide/serverless-clients.html#serverless-signing

- It uses a different service name `aoss` instead of `es`
- It cannot sign content length, so you have to skip it, which is not the case in
  [acm19/aws-request-signing-apache-interceptor](https://github.com/acm19/aws-request-signing-apache-interceptor/blob/031791a8f11c3ecb2d687137ba36cc805442687a/src/main/java/io/github/acm19/aws/interceptor/http/AwsRequestSigningApacheInterceptor.java#L132)
- Need to copy back `Content-Length` header, which is
  in [v1 interceptor](https://github.com/awsdocs/amazon-opensearch-service-developer-guide/blob/master/sample_code/AmazonOpenSearchJavaClient-main/src/main/java/com/amazonaws/http/AWSRequestSigningApacheInterceptor.java#L110-L116)

```java
class OpenSearchServerlessApacheInterceptor {
    // Right
    private static boolean skipHeader(final Header header) {
        // https://docs.aws.amazon.com/opensearch-service/latest/developerguide/serverless-clients.html#serverless-signing
        return "content-length".equalsIgnoreCase(header.getName()) // Always strip Content-Length
                || "host".equalsIgnoreCase(header.getName()); // Host comes from endpoint
    }
}

class AwsRequestSigningApacheInterceptor {
    // WRONG: only skip CONTENT-LENGTH when it is zero
    private static boolean skipHeader(final Header header) {
        return (HTTP.CONTENT_LEN.equalsIgnoreCase(header.getName())
                && "0".equals(header.getValue())) // Strip Content-Length: 0
                || HTTP.TARGET_HOST.equalsIgnoreCase(header.getName()); // Host comes from endpoint
    }
}
```