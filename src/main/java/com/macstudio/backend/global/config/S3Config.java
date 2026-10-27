package com.macstudio.backend.global.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.storage.enabled", havingValue = "true")
public class S3Config {
    @Bean(destroyMethod = "close")
    S3Client s3Client(@Value("${app.storage.region}") String region,
            @Value("${app.storage.bucket}") String bucket) {
        if (bucket.isBlank()) {
            throw new IllegalStateException("S3_BUCKET is required when S3_ENABLED=true");
        }
        return S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(15)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30)))
                .build();
    }
}
