package ar.changeo.payments;

public class PaymentFailure extends IllegalStateException {
    public PaymentFailure(String message) { super(message); }
    public PaymentFailure(String message, Throwable cause) { super(message, cause); }
}
