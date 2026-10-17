package ar.changeo.agreements;

import ar.changeo.agreements.api.*;
import ar.changeo.files.api.FileReference;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.JobAccess;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class AgreementStore {
    final JdbcTemplate jdbc;
    private final JobAccess jobs;
    private final ObjectMapper json;
    AgreementStore(JdbcTemplate jdbc, JobAccess jobs, ObjectMapper json) { this.jdbc = jdbc; this.jobs = jobs; this.json = json; }
    Quote quote(UUID id, boolean lock) {
        return jdbc.query("select * from agreements_quote where id=?" + (lock ? " for update" : ""), (row, index) ->
                new Quote(id, row.getObject("job_id", UUID.class), row.getString("kind"), row.getObject("current_revision", UUID.class),
                        row.getObject("approved_revision", UUID.class), row.getLong("version")), id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Presupuesto no disponible"));
    }
    AgreementSnapshot revision(Quote quote, UUID revision) {
        return revision(quote, revision, false);
    }
    AgreementSnapshot revision(Quote quote, UUID revision, boolean lock) {
        var reference = new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, quote.id(), revision);
        return jdbc.query("select * from agreements_revision where quote_id=? and id=?" + (lock ? " for key share" : ""), (row, index) -> {
            var terms = json.readValue(row.getString("terms"), AgreementTerms.class);
            if (!terms.reviewText().equals(row.getString("review_text"))) { throw new IllegalArgumentException("Campos del presupuesto incompatibles"); }
            var attachments = attachments(reference);
            if (!ContentDigest.payload(terms.reviewText(), row.getString("declared_label"), attachments).equals(row.getString("digest"))) {
                throw new IllegalArgumentException("Revisión del presupuesto incompatible");
            }
            return new AgreementSnapshot(reference, quote.jobId(), quote.kind(), new AuthorContext(new ActorId(row.getObject("actor_id", UUID.class)),
                    new SubjectId(row.getObject("subject_id", UUID.class)), Capability.SEND_MARKETPLACE_MESSAGE,
                    Optional.of(jobs.discover(quote.jobId()).conversation().jobContext())), terms, row.getString("declared_label"), row.getString("digest"),
                    attachments, quote.version(), revision.equals(quote.current()), revision.equals(quote.approved()));
        }, quote.id(), revision).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Revisión del presupuesto no disponible"));
    }
    String encode(AgreementTerms terms) { return json.writeValueAsString(terms); }
    List<FileReference> attachments(ContentReference reference) {
        return jdbc.query("select file_id,digest from agreements_attachment where revision_id=? order by file_id::text",
                (row, index) -> new FileReference(row.getObject(1, UUID.class), reference, row.getString(2)), reference.revisionId());
    }
    record Quote(UUID id, UUID jobId, String kind, UUID current, UUID approved, long version) {}
}
