package com.freelocs.theprisons.modules.qol.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultScanTest {
    /** Runs the scan against a player who owns {@code owned} vaults; returns {count, probes}. */
    private static int[] run(int owned, int knownOpen) {
        VaultScan scan = new VaultScan(knownOpen);
        int probes = 0;
        for (int page = scan.next(); page != 0; page = scan.next()) {
            scan.record(page, page <= owned);
            probes++;
            assertTrue(probes < 40, "scan does not terminate");
        }
        return new int[]{scan.result(), probes};
    }

    @Test
    void findsEveryCountWithFewProbes() {
        for (int owned = 0; owned <= 120; owned++) {
            int[] result = run(owned, 0);
            assertEquals(owned, result[0], "owned " + owned);
            assertTrue(result[1] <= 2 * (32 - Integer.numberOfLeadingZeros(owned + 1)) + 1,
                    "too many probes for " + owned + ": " + result[1]);
        }
    }

    @Test
    void startsFromTheHighestPageAlreadySeen() {
        assertEquals(6, run(6, 6)[0]);
        assertEquals(6, run(6, 4)[0]);
        assertEquals(9, run(9, 3)[0]);
    }

    @Test
    void stopsAtTheCap() {
        assertEquals(VaultScan.MAX_PAGE, run(10_000, 0)[0]);
    }

    @Test
    void readsVaultNumbers() {
        assertEquals(3, VaultText.pageOfTitle("§8PV #3"));
        assertEquals(12, VaultText.pageOfTitle("Private Vault #12"));
        assertEquals(1, VaultText.pageOfTitle("Vault 1"));
        assertNull(VaultText.pageOfTitle("Bandit Shop"));
        assertEquals(7, VaultText.deniedPage("(!) You do not have access to PV #7."));
        assertEquals(0, VaultText.deniedPage("*** A ELITE VAULT RAID HAS STARTED! ***"));
        assertEquals(0, VaultText.deniedPage("(!) Close your Private Vault before attempting to enchant."));
    }
}
