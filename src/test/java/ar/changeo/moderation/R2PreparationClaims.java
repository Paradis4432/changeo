package ar.changeo.moderation;

import ar.changeo.files.api.ReviewFileAccess;
import ar.changeo.moderation.api.ContentReference;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class R2PreparationClaims {
    private R2PreparationClaims() {}

    public static ReviewFileAccess.Request claim(ApplicationContext application, ContentReference expected) {
        var store = application.getBean(ModerationStore.class);
        var transaction = new TransactionTemplate(application.getBean(PlatformTransactionManager.class));
        return transaction.execute(status -> {
            var submission = store.submission(expected, false);
            var claim = store.claim("REVIEW", 90).orElseThrow();
            if (!claim.submission().equals(submission.id())) {
                throw new IllegalStateException("Claim escaped the expected owned fixture");
            }
            return new ReviewFileAccess.Request(expected, claim.id(), claim.token(), claim.generation(), store.input(submission).attachments());
        });
    }
}
