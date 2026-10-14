package com.daaspro.imported.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(@Value("${daaspro.minio.endpoint}") String endpoint,
                                   @Value("${daaspro.minio.access-key}") String accessKey,
                                   @Value("${daaspro.minio.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    @Bean(initMethod = "ensureBucket")
    @ConditionalOnProperty(name = "daaspro.minio.init-bucket", havingValue = "true", matchIfMissing = true)
    public BucketInitializer bucketInitializer(MinioClient client,
                                               @Value("${daaspro.minio.bucket}") String bucket) {
        return new BucketInitializer(client, bucket);
    }

    public static class BucketInitializer {
        private final MinioClient client;
        private final String bucket;

        public BucketInitializer(MinioClient client, String bucket) {
            this.client = client;
            this.bucket = bucket;
        }

        public void ensureBucket() {
            try {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
            } catch (Exception e) {
                throw new IllegalStateException("MinIO bucket init failed", e);
            }
        }
    }
}
