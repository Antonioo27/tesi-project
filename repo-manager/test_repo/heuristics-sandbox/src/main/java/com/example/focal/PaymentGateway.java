package com.example.focal;

public class PaymentGateway {
    public boolean charge(double amount) {
        return amount >= 0.0;
    }
}
