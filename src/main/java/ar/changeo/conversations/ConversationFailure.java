package ar.changeo.conversations;

public class ConversationFailure extends ar.changeo.moderation.ModerationFailure {
    public ConversationFailure(String message) { super(message); }
    public static ConversationFailure unavailable() { return new ConversationFailure("Conversación no disponible para esta acción"); }
}
