package com.dineflow;

import java.nio.file.Files;
import java.nio.file.Path;

public class DineFlowTest {
    public static void main(String[] args) throws Exception {
        Path tmpDb = Files.createTempFile("dineflow_test_", ".db");
        tmpDb.toFile().deleteOnExit();

        try (RestaurantStore store = new RestaurantStore(tmpDb.toString())) {
            String addRes = store.addOrderItem("T1", 1, 2);
            assert addRes.contains("\"ok\":true") : "Adding KOT item to T1 must succeed";

            String kotPart = addRes.split("\"kotId\":")[1].split(",")[0];
            long kotId = Long.parseLong(kotPart);

            assert store.advanceKitchenItem(kotId).contains("\"PREPARING\"");
            assert store.advanceKitchenItem(kotId).contains("\"READY\"");
            assert store.advanceKitchenItem(kotId).contains("\"SERVED\"");

            String receipt = store.settleTableBill("T1");
            assert receipt.contains("\"ok\":true") : "Settling bill on T1 must succeed";
            assert receipt.contains("\"total\":588.00") : "Receipt total must equal 588.00: " + receipt;

            String dupSettle = store.settleTableBill("T1");
            assert dupSettle.contains("\"ok\":false") : "Settling vacant table must fail";
        }

        System.out.println("ALL DINEFLOW ASSERTION TESTS PASSED (Table State, KOT Routing, KDS Lifecycle, 5% GST Billing)");
    }
}
