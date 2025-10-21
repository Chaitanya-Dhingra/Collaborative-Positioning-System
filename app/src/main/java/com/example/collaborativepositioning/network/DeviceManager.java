package com.example.collaborativepositioning.network;

import com.example.collaborativepositioning.model.GnssDataPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages data from multiple connected devices
 * Tracks latest packets and handles timeouts
 */
public class DeviceManager {

    private static final long DEVICE_TIMEOUT_MS = 10000; // 10 seconds

    private Map<String, DeviceInfo> devices;
    private DeviceUpdateListener listener;

    public interface DeviceUpdateListener {
        void onDeviceAdded(String deviceId);
        void onDeviceUpdated(String deviceId, GnssDataPacket packet);
        void onDeviceRemoved(String deviceId);
    }

    public static class DeviceInfo {
        private String deviceId;
        private GnssDataPacket latestPacket;
        private long lastUpdateTime;
        private boolean isActive;

        public DeviceInfo(String deviceId) {
            this.deviceId = deviceId;
            this.isActive = true;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public String getDeviceId() { return deviceId; }
        public GnssDataPacket getLatestPacket() { return latestPacket; }
        public long getLastUpdateTime() { return lastUpdateTime; }
        public boolean isActive() { return isActive; }

        public void updatePacket(GnssDataPacket packet) {
            this.latestPacket = packet;
            this.lastUpdateTime = System.currentTimeMillis();
            this.isActive = true;
        }

        public boolean isTimedOut(long currentTime) {
            return (currentTime - lastUpdateTime) > DEVICE_TIMEOUT_MS;
        }

        public void setInactive() {
            this.isActive = false;
        }

        /**
         * Calculate distance to another device in meters (Haversine formula)
         */
        public double getDistanceTo(DeviceInfo other) {
            if (latestPacket == null || other.latestPacket == null) {
                return -1;
            }

            return calculateDistance(
                    latestPacket.getLatitude(), latestPacket.getLongitude(),
                    other.latestPacket.getLatitude(), other.latestPacket.getLongitude()
            );
        }

        /**
         * Get relative velocity estimate based on pseudorange rates
         */
        public double getRelativeVelocity(DeviceInfo other) {
            if (latestPacket == null || other.latestPacket == null) {
                return 0;
            }

            // Average pseudorange rate difference
            List<GnssDataPacket.SatelliteMeasurement> myMeas = latestPacket.getMeasurements();
            List<GnssDataPacket.SatelliteMeasurement> otherMeas = other.latestPacket.getMeasurements();

            if (myMeas.isEmpty() || otherMeas.isEmpty()) {
                return 0;
            }

            double totalDiff = 0;
            int count = 0;

            // Match satellites by SVID
            for (GnssDataPacket.SatelliteMeasurement m1 : myMeas) {
                for (GnssDataPacket.SatelliteMeasurement m2 : otherMeas) {
                    if (m1.svid == m2.svid) {
                        totalDiff += (m1.pseudorangeRateMs - m2.pseudorangeRateMs);
                        count++;
                    }
                }
            }

            return count > 0 ? totalDiff / count : 0;
        }
    }

    public DeviceManager(DeviceUpdateListener listener) {
        this.devices = new ConcurrentHashMap<>();
        this.listener = listener;
    }

    /**
     * Update device with new packet
     */
    public void updateDevice(GnssDataPacket packet) {
        String deviceId = packet.getDeviceId();

        DeviceInfo deviceInfo = devices.get(deviceId);
        if (deviceInfo == null) {
            deviceInfo = new DeviceInfo(deviceId);
            devices.put(deviceId, deviceInfo);
            if (listener != null) {
                listener.onDeviceAdded(deviceId);
            }
        }

        deviceInfo.updatePacket(packet);
        if (listener != null) {
            listener.onDeviceUpdated(deviceId, packet);
        }
    }

    /**
     * Get info for a specific device
     */
    public DeviceInfo getDevice(String deviceId) {
        return devices.get(deviceId);
    }

    /**
     * Get all active devices
     */
    public List<DeviceInfo> getAllDevices() {
        return new ArrayList<>(devices.values());
    }

    /**
     * Get all active devices (excluding timed out ones)
     */
    public List<DeviceInfo> getActiveDevices() {
        List<DeviceInfo> activeDevices = new ArrayList<>();
        long currentTime = System.currentTimeMillis();

        for (DeviceInfo info : devices.values()) {
            if (info.isActive() && !info.isTimedOut(currentTime)) {
                activeDevices.add(info);
            }
        }

        return activeDevices;
    }

    /**
     * Check for timed out devices and mark them inactive
     */
    public void checkTimeouts() {
        long currentTime = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        for (DeviceInfo info : devices.values()) {
            if (info.isTimedOut(currentTime)) {
                info.setInactive();
                if (listener != null) {
                    listener.onDeviceRemoved(info.getDeviceId());
                }
                toRemove.add(info.getDeviceId());
            }
        }

        // Remove timed out devices
        for (String deviceId : toRemove) {
            devices.remove(deviceId);
        }
    }

    /**
     * Get number of active devices
     */
    public int getActiveDeviceCount() {
        return getActiveDevices().size();
    }

    /**
     * Clear all devices
     */
    public void clear() {
        devices.clear();
    }

    /**
     * Calculate distance between two points using Haversine formula
     */
    private static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000; // Earth radius in meters

        double latDist = Math.toRadians(lat2 - lat1);
        double lonDist = Math.toRadians(lon2 - lon1);

        double a = Math.sin(latDist / 2) * Math.sin(latDist / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDist / 2) * Math.sin(lonDist / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c;
    }

    /**
     * Get summary of all devices
     */
    public String getDevicesSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Connected Devices: ").append(getActiveDeviceCount()).append("\n\n");

        for (DeviceInfo info : getActiveDevices()) {
            if (info.getLatestPacket() != null) {
                sb.append(info.getLatestPacket().getSummary());
                sb.append("\n---\n");
            }
        }

        return sb.toString();
    }

    /**
     * Get proximity analysis for a specific device
     */
    public String getProximityAnalysis(String referenceDeviceId) {
        DeviceInfo reference = devices.get(referenceDeviceId);
        if (reference == null || reference.getLatestPacket() == null) {
            return "No data for reference device";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Proximity Analysis:\n\n");

        for (DeviceInfo other : getActiveDevices()) {
            if (!other.getDeviceId().equals(referenceDeviceId)) {
                double distance = reference.getDistanceTo(other);
                double relVel = reference.getRelativeVelocity(other);

                sb.append(String.format("Device: %s\n",
                        other.getDeviceId().substring(0, Math.min(8, other.getDeviceId().length()))));
                sb.append(String.format("Distance: %.2f m\n", distance));
                sb.append(String.format("Rel. Velocity: %.2f m/s\n", relVel));

                // Simple collision warning
                if (distance > 0 && distance < 50 && relVel < -2) {
                    sb.append("⚠️ WARNING: Approaching!\n");
                } else if (distance > 0 && distance < 20) {
                    sb.append("⚠️ CAUTION: Close proximity\n");
                }

                sb.append("\n");
            }
        }

        return sb.toString();
    }
}