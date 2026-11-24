package com.macstudio.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;

class S3ConfigTests {
    private final S3Config config = new S3Config();

    @Test
    void usesNamedProfileWhenConfigured() {
        assertThat(config.credentialsProvider(" mac-dev "))
                .isInstanceOf(ProfileCredentialsProvider.class);
    }

    @Test
    void usesDefaultCredentialChainWhenProfileIsBlank() {
        assertThat(config.credentialsProvider("  "))
                .isInstanceOf(DefaultCredentialsProvider.class);
    }
}
