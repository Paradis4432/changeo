package ar.changeo.jobs.api;

import ar.changeo.identity.api.SubjectId;
import java.util.*;

public interface JobLoyaltyAccess {
    CandidatePage discover(SubjectId provider, Optional<Cursor> cursor);
    LoyaltyEvidenceBatch discoverEvidence(SubjectId provider, Set<UUID> jobIds);
    Map<UUID, JobQualification> requireQualificationForUpdate(LoyaltyEvidenceBatch batch);
    record Cursor(UUID after, long cohortVersion) {}
    record CandidatePage(List<UUID> jobIds, long cohortVersion, Optional<Cursor> next) { public CandidatePage { jobIds = List.copyOf(jobIds); } }
}
