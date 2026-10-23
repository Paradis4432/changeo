package ar.changeo.files.api;

public interface ReviewFilePolicy {
    void requireCurrentReview(ReviewFileAccess.Request request);
}
