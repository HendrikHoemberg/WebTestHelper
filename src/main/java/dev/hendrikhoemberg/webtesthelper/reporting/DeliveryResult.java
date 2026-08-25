package dev.hendrikhoemberg.webtesthelper.reporting;

public record DeliveryResult(
        boolean success,
        String error
) {
    public static DeliveryResult successful() {
        return new DeliveryResult(true, null);
    }

    public static DeliveryResult failed(String error) {
        return new DeliveryResult(false, error);
    }
}
