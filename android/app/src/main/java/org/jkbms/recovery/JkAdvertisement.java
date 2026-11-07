package org.jkbms.recovery;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Scan-time evidence, kept separate from protocol confirmation after connecting. */
public final class JkAdvertisement {
    public static final UUID SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");

    public enum Match {
        SIGNATURE("JK advertisement signature", true),
        NAME("JK device name", true),
        SERVICE_ONLY("Possible JK: FFE0 service", true),
        OTHER("No JK advertisement match", false),
        NOT_CONNECTABLE("Not connectable", false);

        public final String description;
        public final boolean candidate;

        Match(String description, boolean candidate) {
            this.description = description;
            this.candidate = candidate;
        }
    }

    public final String name;
    public final Set<UUID> services;
    public final Set<Integer> manufacturerIds;
    public final boolean connectable;

    public JkAdvertisement(String name, Set<UUID> services, Set<Integer> manufacturerIds, boolean connectable) {
        this.name = name == null ? "" : name;
        this.services = Collections.unmodifiableSet(new HashSet<>(services));
        this.manufacturerIds = Collections.unmodifiableSet(new HashSet<>(manufacturerIds));
        this.connectable = connectable;
    }

    public Match match() {
        if (!connectable) return Match.NOT_CONNECTABLE;
        boolean service = services.contains(SERVICE);
        if (service && (manufacturerIds.contains(0x0B65) || manufacturerIds.contains(0x4B4A))) return Match.SIGNATURE;
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("JK") || normalized.startsWith("JK_") || normalized.startsWith("JK-")
                || normalized.startsWith("JK BMS") || normalized.startsWith("JKBMS")
                || normalized.startsWith("JIKONG")) return Match.NAME;
        return service ? Match.SERVICE_ONLY : Match.OTHER;
    }

    /** Advertising and scan-response packets may carry different parts of the identity. */
    public JkAdvertisement merge(JkAdvertisement latest) {
        Set<UUID> mergedServices = new HashSet<>(services);
        mergedServices.addAll(latest.services);
        Set<Integer> mergedManufacturers = new HashSet<>(manufacturerIds);
        mergedManufacturers.addAll(latest.manufacturerIds);
        return new JkAdvertisement(latest.name.isEmpty() ? name : latest.name,
                mergedServices, mergedManufacturers, latest.connectable);
    }

    public boolean sameEvidence(JkAdvertisement other) {
        return name.equals(other.name) && services.equals(other.services)
                && manufacturerIds.equals(other.manufacturerIds) && connectable == other.connectable;
    }
}
