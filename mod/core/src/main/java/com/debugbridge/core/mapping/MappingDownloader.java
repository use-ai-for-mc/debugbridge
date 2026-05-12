package com.debugbridge.core.mapping;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Downloads Mojang's official ProGuard mappings for a given Minecraft version.
 * <p>
 * Flow:
 * 1. Fetch version_manifest_v2.json
 * 2. Find the version entry, get its URL
 * 3. Fetch the version JSON, get client_mappings URL
 * 4. Download the ProGuard text
 */
public class MappingDownloader {
    private static final String MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    
    private final HttpClient client;
    
    public MappingDownloader() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
    
    /**
     * Download the client mappings for a specific MC version.
     * Returns the raw ProGuard text content.
     */
    public String download(String mcVersion) throws IOException, InterruptedException {
        // 1. Fetch version manifest
        String manifestJson = fetchString(MANIFEST_URL);
        JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();
        JsonArray versions = manifest.getAsJsonArray("versions");
        
        // 2. Find the matching version entry
        String versionUrl = null;
        for (JsonElement v : versions) {
            JsonObject vObj = v.getAsJsonObject();
            if (vObj.get("id").getAsString().equals(mcVersion)) {
                versionUrl = vObj.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IOException("Minecraft version not found in manifest: " + mcVersion);
        }
        
        // 3. Fetch version JSON
        String versionJson = fetchString(versionUrl);
        JsonObject versionData = JsonParser.parseString(versionJson).getAsJsonObject();
        JsonObject downloads = versionData.getAsJsonObject("downloads");
        if (downloads == null || !downloads.has("client_mappings")) {
            throw new IOException("No client_mappings found for version " + mcVersion);
        }
        String mappingsUrl = downloads.getAsJsonObject("client_mappings")
                .get("url").getAsString();
        
        // 4. Download the mappings
        return fetchString(mappingsUrl);
    }
    
    private String fetchString(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
        }
        return response.body();
    }
}
