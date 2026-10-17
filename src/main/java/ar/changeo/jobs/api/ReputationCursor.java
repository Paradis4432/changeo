package ar.changeo.jobs.api;

public record ReputationCursor(long offset, long cohortVersion) {
    public ReputationCursor { if (offset < 0 || offset > 100000 || cohortVersion < 0) { throw new IllegalArgumentException("Continuación inválida"); } }
}
