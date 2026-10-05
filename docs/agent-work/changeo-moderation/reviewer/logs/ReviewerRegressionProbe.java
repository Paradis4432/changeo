package ar.changeo.moderation;

import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

public class ReviewerRegressionProbe extends ModerationPostgresTest {
    public static void main(String[] args) throws Exception {
        var ref = new ContentReference(SurfaceKind.REQUEST_BODY, UUID.randomUUID(), UUID.randomUUID());
        var input = new ReviewInput(ref, "synthetic:clear", "GENERAL", "a".repeat(64), List.of());
        var contradictory = new HashMap<>(new SyntheticReviewEngine(true).review(input));
        contradictory.put("reason", "PROHIBITED");
        var result = ReviewResult.validate(input, contradictory);
        System.out.println("CONTRADICTORY_RESULT action=" + result.action() + " reason=" + result.reason());
        var probe = new ReviewerRegressionProbe();
        var manager = new TestContextManager(ReviewerRegressionProbe.class);
        manager.beforeTestClass();
        manager.prepareTestInstance(probe);
        probe.setup();
        try {
            UUID author = ReflectionTestUtils.invokeMethod(probe, "verified", 30);
            @SuppressWarnings("unchecked")
            ThreadLocal<UUID> actor = (ThreadLocal<UUID>) ReflectionTestUtils.getField(probe, "actor");
            actor.set(author);
            ContentReference content = ReflectionTestUtils.invokeMethod(probe, "save", "synthetic:clear");
            probe.workbench.submit(content);
            ReflectionTestUtils.invokeMethod(probe, "runReview", content);
            ModerationWorker.Claim first = ReflectionTestUtils.invokeMethod(probe, "claim", content, "ACTIVATE");
            doThrow(new TransientDataAccessResourceException("reviewer synthetic transient failure")).when(probe.owner).activate(content);
            probe.worker.process(first);
            System.out.println("AFTER_TRANSIENT submission=" + probe.moderation.approval(content).state() + " task=" + probe.jdbc.queryForObject("select state from moderation_task where id=?", String.class, first.id()));
            reset(probe.owner);
            probe.clock.advance(6);
            var next = probe.worker.claim().orElseThrow();
            probe.worker.process(next);
            System.out.println("AFTER_RECOVERY submission=" + probe.moderation.approval(content).state() + " task=" + probe.jdbc.queryForObject("select state from moderation_task where id=?", String.class, first.id()) + " reason=" + probe.jdbc.queryForObject("select reason from moderation_task where id=?", String.class, first.id()));
            try {
                probe.reader.retrieve(content.resourceId());
                System.out.println("READ_AFTER_RECOVERY available=true");
            } catch (ModerationFailure denied) {
                System.out.println("READ_AFTER_RECOVERY available=false");
            }
        } finally {
            probe.cleanup();
            manager.afterTestClass();
            ((org.springframework.context.ConfigurableApplicationContext) manager.getTestContext().getApplicationContext()).close();
        }
    }
}
