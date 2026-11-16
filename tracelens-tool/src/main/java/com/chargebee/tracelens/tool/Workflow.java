package com.chargebee.tracelens.tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import static com.chargebee.tracelens.tool.Json.*;

/** Shared CLI/MCP workflows; operator-owned paths are never model arguments. */
public final class Workflow {
    private Workflow(){}
    public static Path defaultCaptureDir(){return CaptureStore.defaultDirectory();}
    public static ObjectNode resolveCapture(CaptureStore store,String reference){
        if(reference==null||reference.isEmpty()||reference.length()>128)throw Captures.invalid("capture reference must be a capture ID or exact saved name (1–128 characters)");
        List<JsonNode>ids=new ArrayList<>(),names=new ArrayList<>();for(JsonNode row:store.list().path("captures")){if(reference.equals(Captures.text(row,"captureId")))ids.add(row);if(reference.equals(Captures.text(row,"label")))names.add(row);}
        List<JsonNode>matches=ids.isEmpty()?names:ids;if(matches.isEmpty())throw Captures.invalid("saved capture not found; list captures in the configured capture directory");if(matches.size()!=1)throw Captures.invalid("saved name is ambiguous; list captures and use an exact captureId");return store.get(Captures.text(matches.get(0),"captureId"));
    }
    public static ObjectNode saveRequestCapture(Federation discovery,CaptureStore store,String name,String revision,ObjectNode filters){
        Captures.validateCaptureInputs("validation",name,revision);ObjectNode selection=Investigation.investigateRequest(discovery,filters);
        if(!"selected".equals(Captures.text(selection,"status"))){selection.put("saved",false);return selection;}
        ObjectNode capture=Captures.buildCapture(discovery,Captures.text(selection,"selectedTraceId"),name,revision),metadata=store.save(capture);
        return obj("schemaVersion",1,"status","saved","saved",true,"capture",metadata,"warnings",capture.get("warnings"),"segmentWarnings",CaptureTools.segmentWarnings(capture),
            "nextSteps",arr("Save a separately reproduced candidate after changing/restarting the application.","Compare the two capture IDs or unique saved names; no running service is needed.","Captures are local files, distinct from browser pins. Review before sharing."));
    }
    public static ObjectNode compareSavedCaptures(CaptureStore store,String before,String after){return Captures.compareCaptures(resolveCapture(store,before),resolveCapture(store,after));}
}
