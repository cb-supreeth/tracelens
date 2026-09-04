package com.chargebee.ems.sample;

import java.util.Arrays;
import java.util.List;

/** Fake persistence: deterministic answers derived from the customer id. */
public class CustomerRepository {

    public String findStatus(String customerId) {
        int hash = Math.abs(customerId.hashCode());
        if (hash % 7 == 0) {
            return "CANCELLED";
        }
        return hash % 5 == 0 ? "TRIAL" : "ACTIVE";
    }

    public List<String> findFeatures(String customerId) {
        int hash = Math.abs(customerId.hashCode());
        if (hash % 2 == 0) {
            return Arrays.asList("metered:seats", "flat:reports", "flat:exports");
        }
        return Arrays.asList("flat:reports", "metered:api_calls");
    }

    public boolean isGrandfathered(String customerId) {
        return Math.abs(customerId.hashCode()) % 11 == 0;
    }
}
