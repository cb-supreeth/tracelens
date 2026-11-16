package com.chargebee.tracelens.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargebeeJobTypesConfigTest {
    @TempDir Path directory;

    private TraceLensConfig load(String field) throws Exception {
        Path file = directory.resolve("tracelens.json");
        Files.writeString(file, "{\"applicationId\":\"job-test\"" + field + "}");
        return new TraceLensConfigLoader().load(file, true);
    }

    @Test
    void defaultsToDisabledIndependentlyOfScheduledInstrumentation() throws Exception {
        TraceLensConfig config = load("");
        assertThat(config.getChargebeeJobTypes()).isEmpty();
        assertThat(config.isInstrumentScheduled()).isTrue();
        assertThat(config.setInstrumentScheduled(false).getChargebeeJobTypes()).isEmpty();
        assertThat(load(",\"chargebeeJobTypes\":[]").getChargebeeJobTypes()).isEmpty();
    }

    @Test
    void exactJobTypesSurviveJsonRoundTripAndScheduledOptOut() throws Exception {
        TraceLensConfig config = load(",\"instrumentScheduled\":false,\"chargebeeJobTypes\":[\"compute_billing_metrics\",\"DailyJob.v2-1\"]");
        ObjectMapper mapper = new ObjectMapper();
        TraceLensConfig copy = mapper.readValue(mapper.writeValueAsString(config), TraceLensConfig.class);
        assertThat(copy.getChargebeeJobTypes()).containsExactly("compute_billing_metrics", "DailyJob.v2-1");
        assertThat(copy.isInstrumentScheduled()).isFalse();
        assertThat(copy.validate(true)).isEmpty();
    }

    @Test
    void rejectsNullInvalidNamesWildcardsAndDuplicates() {
        for (String field : Arrays.asList("null", "\"compute_billing_metrics\"", "{}", "[null]",
                "[\"\"]", "[\"compute_*\"]", "[\"a\",\"a\"]")) {
            assertThatThrownBy(() -> load(",\"chargebeeJobTypes\":" + field))
                    .as(field).isInstanceOf(RuntimeException.class);
        }
        for (String name : Arrays.asList("", " ", " name", "name ", "name\t", "name\n", "*",
                "name?", "[name]", "name/child", "name,other", "café", "a".repeat(129))) {
            assertThat(new TraceLensConfig().setChargebeeJobTypes(Collections.singletonList(name)).validate(true))
                    .as(name).anyMatch(error -> error.contains("chargebeeJobTypes"));
        }
        assertThat(new TraceLensConfig().setChargebeeJobTypes(null).validateChargebeeJobTypes()).isNotEmpty();
        assertThat(new TraceLensConfig().setChargebeeJobTypes(Arrays.asList("name", null)).validateChargebeeJobTypes()).isNotEmpty();
    }

    @Test
    void enforcesBoundsAndPreservesCaseSensitiveExactNames() {
        List<String> names = IntStream.range(0, 32).mapToObj(i -> "job_" + i).toList();
        assertThat(new TraceLensConfig().setChargebeeJobTypes(names).validateChargebeeJobTypes()).isEmpty();
        List<String> tooMany = new ArrayList<>(names);
        tooMany.add("job_32");
        assertThat(new TraceLensConfig().setChargebeeJobTypes(tooMany).validateChargebeeJobTypes()).isNotEmpty();
        assertThat(new TraceLensConfig().setChargebeeJobTypes(List.of("a".repeat(128), "job", "JOB"))
                .validateChargebeeJobTypes()).isEmpty();
    }

    @Test
    void copiesCallerListAndDoesNotExposeMutableList() {
        List<String> names = new ArrayList<>(List.of("compute_billing_metrics"));
        TraceLensConfig config = new TraceLensConfig().setChargebeeJobTypes(names);
        names.clear();
        assertThat(config.getChargebeeJobTypes()).containsExactly("compute_billing_metrics");
        assertThatThrownBy(() -> config.getChargebeeJobTypes().add("other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
