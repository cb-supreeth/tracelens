package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SetupServiceCatalogTest {
    @Test void exactSupportedServicesAndDefaultsRemainInTheirEstablishedOrder() {
        String[][] expected={{"cbApp","cb-app","cb-app","chargebee-app","7778"},
                {"ems","cb-ems","ems","cb-ems","7779"},{"plans","cb-plans","plans","cb-plans","7786"},
                {"ledger","cb-consumption-ledger","ledger","cb-consumption-ledger","7783"},
                {"go","cb-grant-orchestrator","go","cb-grant-orchestrator","7784"}};
        assertEquals(expected.length,SetupServiceCatalog.services().size());
        ObjectNode defaults=new SetupInstaller(Paths.get("/unused/tracelens.jar")).defaults();
        for(int i=0;i<expected.length;i++) {
            SetupServiceCatalog.Entry service=SetupServiceCatalog.services().get(i);String[] row=expected[i];
            assertEquals(row[0],service.key);assertSame(service,SetupServiceCatalog.get(row[0]));
            assertEquals(row[1],service.name);assertEquals(row[2],service.folder);assertEquals(row[3],service.checkout);
            assertEquals(Integer.parseInt(row[4]),service.debugPort);assertEquals(i==0,service.enabledByDefault);
            JsonNode value=defaults.path(service.key);
            assertEquals(service.debugPort,value.path("debugPort").asInt());assertEquals(i==0,value.path("enabled").asBoolean());
            assertEquals(Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize().resolve("work/"+row[3]).toString(),value.path("sourceRoot").asText());
            assertFalse(value.path("configured").asBoolean());assertFalse(value.path("installed").asBoolean());
            assertEquals("manual",value.path("launchMode").asText());assertTrue(value.path("httpIncludePaths").isArray());
        }
        ArrayList<String> keys=new ArrayList<>();defaults.fieldNames().forEachRemaining(keys::add);
        assertEquals(Arrays.asList("installDir","viewerPort","captureJdbcQueries","captureJdbcParameters","captureMode",
                "cbApp","ems","plans","ledger","go","configured"),keys);
    }
    @Test void installerAndLifecycleShareAnImmutableCatalogWithoutAddingMiddleware() {
        assertSame(SetupServiceCatalog.folders(),SetupServiceLifecycle.FOLDERS);
        assertEquals(new LinkedHashSet<>(Arrays.asList("cbApp","ems","plans","ledger","go")),SetupServiceCatalog.keys());
        assertThrows(UnsupportedOperationException.class,()->SetupServiceCatalog.services().clear());
        assertThrows(UnsupportedOperationException.class,()->SetupServiceCatalog.keys().remove("ems"));
        assertThrows(UnsupportedOperationException.class,()->SetupServiceLifecycle.FOLDERS.put("middleware","cb-ui-middleware"));
        assertThrows(IllegalArgumentException.class,()->SetupServiceCatalog.get("cb-ui-middleware"));
        assertThrows(IllegalArgumentException.class,()->SetupServiceLifecycle.selected(Json.obj("services",Json.arr("cb-ui-middleware"))));
        assertThrows(IllegalArgumentException.class,()->SetupServiceLifecycle.selected(Json.obj("services",Json.arr("ems","ems"))));
        assertEquals(SetupServiceCatalog.keys(),SetupServiceLifecycle.selected(Json.obj("services",Json.node(SetupServiceCatalog.keys()))));
        SetupInstaller installer=new SetupInstaller(Paths.get("/unused/tracelens.jar"));
        ObjectNode request=installer.defaults();request.set("middleware",Json.obj("enabled",true));
        assertThrows(IllegalArgumentException.class,()->installer.plan(request));
    }
}
