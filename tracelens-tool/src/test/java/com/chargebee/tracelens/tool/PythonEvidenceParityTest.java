package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.io.InputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Complete model snapshots recorded from the replaced Python pure functions.
 * Fixtures are checked in: neither tests nor shipped Java require Python.
 */
class PythonEvidenceParityTest {
    @TestFactory Collection<DynamicTest> referenceModels() throws Exception {
        JsonNode fixtures;
        try(InputStream in=getClass().getResourceAsStream("/evidence-python-reference.json")){
            assertNotNull(in);fixtures=Json.MAPPER.readTree(in);
        }
        List<DynamicTest>tests=new ArrayList<>();
        for(JsonNode fixture:fixtures)tests.add(DynamicTest.dynamicTest(fixture.path("name").asText(),()->{
            JsonNode input=fixture.get("input"),options=fixture.path("options"),expected=fixture.get("expected");ObjectNode actual;
            switch(fixture.path("operation").asText()){
                case "execution":actual=Execution.build(input,options.path("max_nodes").asInt(Execution.DEFAULT_MAX_NODES));break;
                case "visualization":actual=Visualization.build(input);break;
                case "dependencies":actual=Dependencies.extractBoundaries(input,options.get("aliases"));break;
                case "errors":actual=ErrorGroups.build(input,options.get("filters"),options.path("now_ms").asDouble());break;
                default:throw new AssertionError("unknown fixture operation");
            }
            sameJson(expected,actual,"$");
        }));
        return tests;
    }
    private static void sameJson(JsonNode expected,JsonNode actual,String path){
        assertNotNull(actual,path);
        if(expected.isNumber()&&actual.isNumber()){assertEquals(expected.doubleValue(),actual.doubleValue(),path);return;}
        if(expected.isObject()){
            assertTrue(actual.isObject(),path);Set<String>keys=new TreeSet<>(),other=new TreeSet<>();expected.fieldNames().forEachRemaining(keys::add);actual.fieldNames().forEachRemaining(other::add);
            assertEquals(keys,other,path+" keys");for(String key:keys)sameJson(expected.get(key),actual.get(key),path+"."+key);
        }else if(expected.isArray()){
            assertTrue(actual.isArray(),path);assertEquals(expected.size(),actual.size(),path+" length");for(int i=0;i<expected.size();i++)sameJson(expected.get(i),actual.get(i),path+"["+i+"]");
        }else assertEquals(expected,actual,path);
    }
}
