package com.debugbridge.core.protocol;

import com.google.gson.JsonObject;

public class BridgeRequest {
    public String id;
    public String type;
    public JsonObject payload;

    public BridgeRequest() {
    }

    public BridgeRequest(String id, String type, JsonObject payload) {
        this.id = id;
        this.type = type;
        this.payload = payload;
    }
}
