package ar.changeo.jobs;

import ar.changeo.agreements.api.*;
import ar.changeo.files.api.FileReference;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.JobAccess;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JobContentStore {
    final JdbcTemplate jdbc;
    private final JobAccess jobs;
    private final AgreementAccess agreements;
    JobContentStore(JdbcTemplate jdbc, JobAccess jobs, AgreementAccess agreements) { this.jdbc = jdbc; this.jobs = jobs; this.agreements = agreements; }
    Resource resource(UUID id, boolean lock) {
        return jdbc.query("select * from jobs_authored where id=?" + (lock ? " for update" : ""), (row, index) ->
                new Resource(id, row.getObject("job_id", UUID.class), Kind.valueOf(row.getString("kind")), row.getObject("actor_id", UUID.class),
                        row.getObject("subject_id", UUID.class), row.getObject("target_subject", UUID.class),
                        new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, row.getObject("term_quote", UUID.class), row.getObject("term_revision", UUID.class)),
                        row.getObject("current_revision", UUID.class), row.getObject("approved_revision", UUID.class), row.getLong("version")), id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Contenido del trabajo no disponible"));
    }
    Canonical lock(Resource discovered) {
        AgreementSnapshot terms;
        Resource resource;
        if (discovered.terms().resourceId().toString().compareTo(discovered.id().toString()) < 0) {
            terms = agreements.lock(discovered.terms()); resource = resource(discovered.id(), true);
        } else { resource = resource(discovered.id(), true); terms = agreements.lock(discovered.terms()); }
        if (!resource.terms().equals(discovered.terms()) || !resource.jobId().equals(terms.jobId())) { throw new IllegalArgumentException("Vínculo de términos incompatible"); }
        return new Canonical(resource, terms);
    }
    Revision revision(Resource resource, UUID revisionId) {
        var reference = resource.reference(revisionId);
        return jdbc.query("select * from jobs_revision where resource_id=? and id=?", (row, index) -> {
            String body = row.getString("body"); Integer score = row.getObject("score", Integer.class);
            if ((resource.kind() == Kind.REVIEW) != (score != null)) { throw new IllegalArgumentException("Puntuación incompatible"); }
            var input = new ReviewInput(reference, reviewText(resource, body, score), row.getString("declared_label"), row.getString("digest"), attachments(reference));
            if (!ContentDigest.payload(input.text(), input.declaredLabel(), input.attachments()).equals(input.digest())) { throw new IllegalArgumentException("Revisión incompatible"); }
            return new Revision(new AuthorContext(new ActorId(resource.actor()), new SubjectId(resource.subject()), Capability.SEND_MARKETPLACE_MESSAGE,
                    Optional.of(jobs.discover(resource.jobId()).conversation().jobContext())), body, score, input);
        }, resource.id(), revisionId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Revisión no disponible"));
    }
    List<FileReference> attachments(ContentReference reference) {
        return jdbc.query("select file_id,digest from jobs_attachment where revision_id=? order by file_id::text",
                (row, index) -> new FileReference(row.getObject(1, UUID.class), reference, row.getString(2)), reference.revisionId());
    }
    static String reviewText(Resource resource, String body, Integer score) {
        return "CHANGEO_WHOLE_JOB_CONTENT_V1\n" + resource.kind() + "\n" + resource.terms() + "\n" + Optional.ofNullable(score).map(Object::toString).orElse("") + "\n" + body;
    }
    enum Kind {
        PROGRESS, FEEDBACK, FINAL_DELIVERY, SUPPORT_NOTE, REVIEW;
        SurfaceKind surface() { return this == REVIEW ? SurfaceKind.COMPLETED_JOB_REVIEW : SurfaceKind.JOB_FEEDBACK; }
    }
    record Resource(UUID id, UUID jobId, Kind kind, UUID actor, UUID subject, UUID target, ContentReference terms, UUID current, UUID approved, long version) {
        ContentReference reference(UUID revision) { return new ContentReference(kind.surface(), id, revision); }
    }
    record Revision(AuthorContext author, String body, Integer score, ReviewInput input) {}
    record Canonical(Resource resource, AgreementSnapshot terms) {}
}
