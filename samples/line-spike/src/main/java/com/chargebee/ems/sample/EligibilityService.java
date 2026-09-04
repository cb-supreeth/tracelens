package com.chargebee.ems.sample;

import java.util.List;

/** Mimics a domain service with branching, loops and a repository call. */
public class EligibilityService {

    private final CustomerRepository repository;

    public EligibilityService(CustomerRepository repository) {
        this.repository = repository;
    }

    public boolean checkEligibility(String customerId, boolean includeGrandfathered) {
        String status = repository.findStatus(customerId);
        if ("CANCELLED".equals(status)) {
            return false;
        }
        List<String> features = repository.findFeatures(customerId);
        int score = 0;
        for (String feature : features) {
            if (feature.startsWith("metered:")) {
                score += 2;
            } else {
                score += 1;
            }
        }
        if (includeGrandfathered && repository.isGrandfathered(customerId)) {
            score += 5;
        }
        try {
            return evaluateScore(score, status);
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private boolean evaluateScore(int score, String status) {
        if ("TRIAL".equals(status) && score > 10) {
            throw new IllegalStateException("trial accounts capped at score 10");
        }
        return score >= 3;
    }
}
