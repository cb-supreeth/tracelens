package com.amazonaws.services.s3;

import java.io.File;

/** SDK-shaped fixture, including overload delegation and local-only signing. */
public class AmazonS3Client {
    public Object putObject(String bucket, String key, File file) { return putObject((Object) file); }
    public Object putObject(Object request) { return request; }
    public String generatePresignedUrl(String bucket, String key) { return "https://private.invalid/secret"; }
}
