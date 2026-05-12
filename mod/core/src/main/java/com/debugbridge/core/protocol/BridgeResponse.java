package com.debugbridge.core.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class BridgeResponse {
    public String id;
    public boolean success;
    public JsonElement result;
    public String output;
    public String error;
    
    private BridgeResponse() {
    }
    
    public static BridgeResponse success(String id, JsonElement result, String output) {
        BridgeResponse r = new BridgeResponse();
        r.id = id;
        r.success = true;
        r.result = result;
        r.output = output;
        return r;
    }
    
    public static BridgeResponse error(String id, String error) {
        BridgeResponse r = new BridgeResponse();
        r.id = id;
        r.success = false;
        r.error = error;
        return r;
    }
    
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("success", success);
        if (result != null) obj.add("result", result);
        if (output != null && !output.isEmpty()) obj.addProperty("output", output);
        if (error != null) obj.addProperty("error", error);
        return obj;
    }
}
