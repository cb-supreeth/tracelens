package com.chargebee.ems.sample;

/**
 * Spike workload: runs a realistic-ish service flow in a hot loop so we can
 * (a) prove line-level capture works and (b) measure per-op overhead with and
 * without the agent.
 */
public final class SpikeApp {

    private SpikeApp() {
    }

    public static void main(String[] args) {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;
        EligibilityService service = new EligibilityService(new CustomerRepository());

        // warmup
        for (int i = 0; i < 20_000; i++) {
            service.checkEligibility("cust-" + (i % 100), i % 3 == 0);
        }

        long start = System.nanoTime();
        long eligible = 0;
        for (int i = 0; i < iterations; i++) {
            if (service.checkEligibility("cust-" + (i % 100), i % 3 == 0)) {
                eligible++;
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[spike] iterations=" + iterations
                + " eligible=" + eligible
                + " elapsedMs=" + elapsedMs);
    }
}
