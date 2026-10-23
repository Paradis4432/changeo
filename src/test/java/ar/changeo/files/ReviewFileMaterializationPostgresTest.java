package ar.changeo.files;

import ar.changeo.ChangeoApplication;
import ar.changeo.files.api.ReviewFileAccess;
import ar.changeo.moderation.ModerationFailure;
import ar.changeo.moderation.api.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ChangeoApplication.class,properties={"spring.flyway.schemas=review_files_test","spring.flyway.default-schema=review_files_test","spring.jpa.properties.hibernate.default_schema=review_files_test","spring.datasource.hikari.schema=review_files_test","changeo.moderation-preparation=off","changeo.mailbox=.runtime/review-files-mailbox","changeo.mfa-key=.runtime/review-files-mfa.key","changeo.files-root=.runtime/review-files-artifacts"})
class ReviewFileMaterializationPostgresTest {
    @Autowired ReviewFileAccess files;
    @Autowired PlatformTransactionManager transactions;
    @Test void deniesMissingInstalledGuardAndOuterTransactionEvenForEmptyAttachmentList() {
        var request=new ReviewFileAccess.Request(new ContentReference(SurfaceKind.REQUEST_BODY,UUID.randomUUID(),UUID.randomUUID()),UUID.randomUUID(),UUID.randomUUID(),0,List.of());
        assertThatThrownBy(()->files.materialize(request)).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->files.materialize(request))).isInstanceOf(ModerationFailure.class);
    }
}
