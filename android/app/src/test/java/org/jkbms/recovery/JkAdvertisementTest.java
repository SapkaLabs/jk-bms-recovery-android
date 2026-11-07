package org.jkbms.recovery;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;

public class JkAdvertisementTest {
    @Test public void recognizesRenamedJkWithManufacturer0B65AndFfe0() {
        JkAdvertisement evidence = new JkAdvertisement("603134249001929-04", Set.of(JkAdvertisement.SERVICE), Set.of(0x0B65), true);
        assertEquals(JkAdvertisement.Match.SIGNATURE, evidence.match());
    }

    @Test public void recognizesAlternateManufacturer4B4A() {
        assertEquals(JkAdvertisement.Match.SIGNATURE,
                new JkAdvertisement(null, Set.of(JkAdvertisement.SERVICE), Set.of(0x4B4A), true).match());
    }

    @Test public void knownManufacturerAloneIsNotEnoughToIdentifyJk() {
        assertEquals(JkAdvertisement.Match.OTHER,
                new JkAdvertisement("Living room TV", Set.of(), Set.of(0x0B65), true).match());
    }

    @Test public void sharedUartServiceIsOnlyAPossibleJk() {
        assertEquals(JkAdvertisement.Match.SERVICE_ONLY,
                new JkAdvertisement("Custom battery", Set.of(JkAdvertisement.SERVICE), Set.of(), true).match());
    }

    @Test public void recognizesKnownNameStylesRegardlessOfCase() {
        for (String name : new String[]{"JK_B2A24S15P", "JK-B2A20S20P", " jk_bms ", "JK BMS", "JKBMS123", "Jikong battery", "JK"}) {
            assertEquals(name, JkAdvertisement.Match.NAME, new JkAdvertisement(name, Set.of(), Set.of(), true).match());
        }
    }

    @Test public void hidesTvPhonesAndIncidentalJkSubstring() {
        for (String name : new String[]{"Samsung Q80CA", "Q-Series Soundbar", "Phone", "Living room JK TV", "JKeyboard"}) {
            assertFalse(name, new JkAdvertisement(name, Set.of(), Set.of(), true).match().candidate);
        }
    }

    @Test public void unrelatedServiceDoesNotMatch() {
        assertEquals(JkAdvertisement.Match.OTHER, new JkAdvertisement(null,
                Set.of(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")), Set.of(), true).match());
    }

    @Test public void hidesNonConnectableAdvertisementsEvenWithJkSignature() {
        JkAdvertisement evidence = new JkAdvertisement("JK_BMS", Set.of(JkAdvertisement.SERVICE), Set.of(0x0B65), false);
        assertEquals(JkAdvertisement.Match.NOT_CONNECTABLE, evidence.match());
        assertFalse(evidence.match().candidate);
    }

    @Test public void mergesServiceAndManufacturerAcrossAdvertisingAndScanResponse() {
        JkAdvertisement first = new JkAdvertisement("Custom battery", Set.of(JkAdvertisement.SERVICE), Set.of(), true);
        JkAdvertisement second = new JkAdvertisement(null, Set.of(), Set.of(0x0B65), true);
        JkAdvertisement merged = first.merge(second);
        assertEquals("Custom battery", merged.name);
        assertEquals(JkAdvertisement.Match.SIGNATURE, merged.match());
        assertFalse(first.sameEvidence(merged));
    }

    @Test public void laterSparseAdvertisementDoesNotLoseEarlierSignature() {
        JkAdvertisement first = new JkAdvertisement("My battery", Set.of(JkAdvertisement.SERVICE), Set.of(0x4B4A), true);
        JkAdvertisement merged = first.merge(new JkAdvertisement(null, Set.of(), Set.of(), true));
        assertEquals(JkAdvertisement.Match.SIGNATURE, merged.match());
        assertTrue(first.sameEvidence(merged));
    }

    @Test public void evidenceCannotBeChangedByMutatingInputCollections() {
        Set<UUID> services = new HashSet<>(Set.of(JkAdvertisement.SERVICE));
        Set<Integer> manufacturers = new HashSet<>(Set.of(0x0B65));
        JkAdvertisement evidence = new JkAdvertisement("Battery", services, manufacturers, true);
        services.clear();
        manufacturers.clear();
        assertEquals(JkAdvertisement.Match.SIGNATURE, evidence.match());
        assertThrows(UnsupportedOperationException.class, () -> evidence.services.clear());
    }
}
