package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Snapshots saved under an earlier configuration must stay findable and copyable, never lost or altered. */
class SnapshotRecoveryTest {
    @TempDir Path temp;
    private static String namespace(char c){return String.join("",Collections.nCopies(64,String.valueOf(c)));}
    private static final String CURRENT=namespace('a'),EARLIER=namespace('b'),OLDEST=namespace('c');
    private Path base(){try{return temp.toRealPath().resolve("snapshots");}catch(Exception e){throw new AssertionError(e);}}
    private ViewerSnapshotStore store(String namespace){return new ViewerSnapshotStore(base(),namespace);}
    private static String save(ViewerSnapshotStore store,String name){ObjectNode input=CaptureWorkflowTest.snapshotInput();input.put("name",name);return store.save(input).path("id").asText();}
    private static List<String> names(JsonNode rows){List<String> result=new ArrayList<>();for(JsonNode row:rows)result.add(row.path("name").asText());return result;}
    private static Map<String,byte[]> files(Path dir)throws Exception{Map<String,byte[]> result=new TreeMap<>();try(DirectoryStream<Path> stream=Files.newDirectoryStream(dir)){for(Path p:stream)if(Files.isRegularFile(p))result.put(p.getFileName().toString(),Files.readAllBytes(p));}return result;}

    @Test void aFreshConfigurationReportsSnapshotsSavedUnderEarlierOnes(){
        save(store(EARLIER),"before fix");save(store(EARLIER),"after fix");save(store(OLDEST),"old demo");
        ViewerSnapshotStore current=store(CURRENT);
        assertThat(current.list().path("count").asInt()).isZero();
        ObjectNode report=current.otherConfigurations();
        assertThat(report.path("snapshotCount").asInt()).isEqualTo(3);
        assertThat(report.path("configurations")).hasSize(2);
        JsonNode newest=report.path("configurations").get(0);
        assertThat(newest.path("namespace").asText()).isEqualTo(OLDEST);
        assertThat(names(report.path("configurations").get(1).path("snapshots"))).containsExactlyInAnyOrder("before fix","after fix");
        assertThat(report.path("warnings")).isEmpty();
    }

    @Test void theReportExcludesTheCurrentConfigurationEmptyFoldersAndUnrelatedNames()throws Exception{
        ViewerSnapshotStore current=store(CURRENT);save(current,"mine");
        Files.createDirectories(base().resolve(namespace('d')));Files.createDirectories(base().resolve("not-a-namespace"));
        ObjectNode report=current.otherConfigurations();
        assertThat(report.path("configurations")).isEmpty();
        assertThat(report.path("snapshotCount").asInt()).isZero();
        assertThat(store(namespace('e')).otherConfigurations().path("snapshotCount").asInt()).isEqualTo(1);
    }

    @Test void reportingNeverWritesIntoAnEarlierFolderOrCreatesTheCurrentOne()throws Exception{
        save(store(EARLIER),"kept");Map<String,byte[]> before=files(base().resolve(EARLIER));
        store(CURRENT).otherConfigurations();
        Map<String,byte[]> after=files(base().resolve(EARLIER));
        assertThat(after.keySet()).isEqualTo(before.keySet());
        for(String name:before.keySet())assertThat(after.get(name)).isEqualTo(before.get(name));
        assertThat(Files.exists(base().resolve(CURRENT))).isFalse();
    }

    @Test void copyingBringsSnapshotsIntoThisConfigurationAndLeavesTheSourceUntouched()throws Exception{
        ViewerSnapshotStore earlier=store(EARLIER);String id=save(earlier,"before fix");save(earlier,"after fix");
        Map<String,byte[]> before=files(base().resolve(EARLIER));
        ViewerSnapshotStore current=store(CURRENT);
        ObjectNode result=current.copyFrom(EARLIER);
        assertThat(names(result.path("copied"))).containsExactlyInAnyOrder("before fix","after fix");
        assertThat(result.path("skipped")).isEmpty();
        assertThat(names(current.list().path("snapshots"))).containsExactlyInAnyOrder("before fix","after fix");
        assertThat(current.get(id)).isEqualTo(earlier.get(id));
        Map<String,byte[]> after=files(base().resolve(EARLIER));
        assertThat(after.keySet()).isEqualTo(before.keySet());
        for(String name:before.keySet())assertThat(after.get(name)).isEqualTo(before.get(name));
        assertThat(earlier.list().path("count").asInt()).isEqualTo(2);
    }

    @Test void copyingTwiceSkipsWhatIsAlreadyHereAndNeverOverwrites(){
        ViewerSnapshotStore earlier=store(EARLIER);String id=save(earlier,"original name");
        ViewerSnapshotStore current=store(CURRENT);current.copyFrom(EARLIER);current.rename(id,"renamed here");
        ObjectNode again=current.copyFrom(EARLIER);
        assertThat(again.path("copied")).isEmpty();
        assertThat(again.path("skipped").get(0).path("reason").asText()).isEqualTo("Already in this configuration.");
        assertThat(current.get(id).path("name").asText()).isEqualTo("renamed here");
        assertThat(current.list().path("count").asInt()).isEqualTo(1);
    }

    @Test void copyingStopsAtThisConfigurationsLimitAndSaysWhatDidNotFit(){
        ViewerSnapshotStore earlier=store(EARLIER);for(int i=0;i<3;i++)save(earlier,"earlier "+i);
        ViewerSnapshotStore current=store(CURRENT);for(int i=0;i<ViewerSnapshotStore.MAX_COUNT-1;i++)save(current,"mine "+i);
        ObjectNode result=current.copyFrom(EARLIER);
        assertThat(result.path("copied")).hasSize(1);
        assertThat(result.path("skipped")).hasSize(2);
        assertThat(result.path("skipped").get(0).path("reason").asText()).contains("limit reached");
        assertThat(current.list().path("count").asInt()).isEqualTo(ViewerSnapshotStore.MAX_COUNT);
        assertThat(earlier.list().path("count").asInt()).isEqualTo(3);
    }

    @Test void theCheapSummaryCountsOnlyWhatIsMissingHereAndEmptiesAfterCopying(){
        ViewerSnapshotStore earlier=store(EARLIER);save(earlier,"one");save(earlier,"two");save(store(OLDEST),"three");
        ViewerSnapshotStore current=store(CURRENT);save(current,"mine");
        assertThat(current.earlierSummary().path("snapshotCount").asInt()).isEqualTo(3);
        assertThat(current.earlierSummary().path("configurations").asInt()).isEqualTo(2);
        current.copyFrom(EARLIER);
        assertThat(current.earlierSummary().path("snapshotCount").asInt()).isEqualTo(1);
        assertThat(current.otherConfigurations().path("configurations")).hasSize(1);
        current.copyFrom(OLDEST);
        assertThat(current.earlierSummary().path("snapshotCount").asInt()).isZero();
        assertThat(current.otherConfigurations().path("snapshotCount").asInt()).isZero();
        assertThat(earlier.list().path("count").asInt()).isEqualTo(2);
    }

    @Test void theSummaryIsZeroForAFirstEverViewerWithNoSnapshotDirectory(){
        assertThat(store(CURRENT).earlierSummary().path("snapshotCount").asInt()).isZero();
        assertThat(Files.exists(base())).isFalse();
    }

    @Test void theNamespaceCannotBeUsedAsAPathAndMustExist(){
        ViewerSnapshotStore current=store(CURRENT);save(current,"mine");
        for(String bad:new String[]{"../"+EARLIER,"..","/etc","",EARLIER.toUpperCase(Locale.ROOT),EARLIER.substring(1),EARLIER+"/x"})
            assertThatThrownBy(()->current.copyFrom(bad)).as(bad).isInstanceOf(ToolException.class).hasMessageContaining("Invalid viewer namespace");
        assertThatThrownBy(()->current.copyFrom(null)).isInstanceOf(ToolException.class);
        assertThatThrownBy(()->current.copyFrom(CURRENT)).isInstanceOf(ToolException.class).hasMessageContaining("already in this configuration");
        assertThatThrownBy(()->current.copyFrom(OLDEST)).isInstanceOfSatisfying(ToolException.class,e->assertThat(e.status).isEqualTo(404));
    }
}
