package ar.changeo.moderation;

public class ModerationFailure extends RuntimeException {
    public ModerationFailure(String message) { super(message); }
}
