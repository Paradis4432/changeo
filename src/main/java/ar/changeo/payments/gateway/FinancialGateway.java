package ar.changeo.payments.gateway;

public interface FinancialGateway {
    GatewayObservation execute(GatewayRequest request);
    GatewayObservation query(GatewayRequest request);
    boolean authenticate(GatewayObservation observation);
}
