package com.chargebee.app.models;

/** No-database fixture with the exact metadata accessor shapes used by the adapter. */
public final class SchJob {
    private final String type;
    public final String behavior;
    public SchJob(String type, String behavior) { this.type = type; this.behavior = behavior; }
    public JobType jobType_e() { return new JobType(type); }
    public Long id() { return 42L; }
    public Long siteId() { return 7L; }
    public Object props() { throw new AssertionError("job payload must not be inspected"); }
    @Override public String toString() { throw new AssertionError("job must not be stringified"); }
    public static final class JobType {
        private final String name;
        JobType(String name) { this.name = name; }
        public String getName() { return name; }
    }
}
