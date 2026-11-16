package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** An execution-only partial sentinel preserves already read evidence when the operation budget ends. */
final class BoundedEventPages implements Iterable<JsonNode>, Iterator<JsonNode> {
    interface Reader { ObjectNode read(long after); }
    private final BoundedReads.Budget budget;
    private final Reader reader;
    private long after;
    private boolean more = true, limited;
    private int pages;
    BoundedEventPages(BoundedReads.Budget budget, Reader reader) { this.budget = budget; this.reader = reader; }
    public Iterator<JsonNode> iterator() { return this; }
    public boolean hasNext() { return more; }
    public JsonNode next() {
        if (!more) throw new NoSuchElementException();
        try {
            if (++pages > ChunkedSnapshotStore.MAX_CHUNKS) throw new BoundedReads.Limit("page");
            final long cursor = after;
            ObjectNode page = budget.read(() -> reader.read(cursor), SegmentDetails.PAGE_BYTES);
            after = page.path("nextAfter").asLong(); more = page.path("hasMore").asBoolean(); return page;
        } catch (BoundedReads.Limit exhausted) {
            limited = true; more = false;
            return Json.obj("events", Json.arr(), "partial", true, "warnings", Json.arr(exhausted.getMessage()));
        }
    }
    ObjectNode describeLimit(ObjectNode model) {
        if (limited) for (JsonNode warning : model.path("warnings"))
            if ("paged_evidence_partial".equals(Json.text(warning, "code")))
                ((ObjectNode) warning).put("code", "read_budget_exhausted");
        return model;
    }
}
