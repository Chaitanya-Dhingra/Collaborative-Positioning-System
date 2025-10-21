package com.example.collaborativepositioning.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Data packet for sharing GNSS and location data between devices
 */
public class GnssDataPacket {

    private String deviceId;
    private long timestamp;
    private double latitude;
    private double longitude;
    private double altitude;
    private float accuracy;

    private List<SatelliteMeasurement> measurements;

    public static class SatelliteMeasurement {
        public int svid;
        public double carrierFrequencyHz;
        public double pseudorangeRateMs;
        public double cn0DbHz;

        public SatelliteMeasurement(int svid, double carrierFreq, double prRate, double cn0) {
            this.svid = svid;
            this.carrierFrequencyHz = carrierFreq;
            this.pseudorangeRateMs = prRate;
            this.cn0DbHz = cn0;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("svid", svid);
            obj.put("carrierFreq", carrierFrequencyHz);
            obj.put("prRate", pseudorangeRateMs);
            obj.put("cn0", cn0DbHz);
            return obj;
        }

        public static SatelliteMeasurement fromJson(JSONObject obj) throws JSONException {
            return new SatelliteMeasurement(
                    obj.getInt("svid"),
                    obj.getDouble("carrierFreq"),
                    obj.getDouble("prRate"),
                    obj.getDouble("cn0")
            );
        }
    }

    public GnssDataPacket() {
        this.measurements = new ArrayList<>();
    }

    // Getters and Setters
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public double getAltitude() { return altitude; }
    public void setAltitude(double altitude) { this.altitude = altitude; }

    public float getAccuracy() { return accuracy; }
    public void setAccuracy(float accuracy) { this.accuracy = accuracy; }

    public List<SatelliteMeasurement> getMeasurements() { return measurements; }
    public void setMeasurements(List<SatelliteMeasurement> measurements) {
        this.measurements = measurements;
    }

    public void addMeasurement(int svid, double carrierFreq, double prRate, double cn0) {
        measurements.add(new SatelliteMeasurement(svid, carrierFreq, prRate, cn0));
    }

    /**
     * Serialize to JSON string for transmission
     */
    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("deviceId", deviceId);
            obj.put("timestamp", timestamp);
            obj.put("latitude", latitude);
            obj.put("longitude", longitude);
            obj.put("altitude", altitude);
            obj.put("accuracy", accuracy);

            JSONArray measArray = new JSONArray();
            for (SatelliteMeasurement m : measurements) {
                measArray.put(m.toJson());
            }
            obj.put("measurements", measArray);

            return obj.toString();
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Deserialize from JSON string
     */
    public static GnssDataPacket fromJson(String jsonString) {
        try {
            JSONObject obj = new JSONObject(jsonString);
            GnssDataPacket packet = new GnssDataPacket();

            packet.setDeviceId(obj.getString("deviceId"));
            packet.setTimestamp(obj.getLong("timestamp"));
            packet.setLatitude(obj.getDouble("latitude"));
            packet.setLongitude(obj.getDouble("longitude"));
            packet.setAltitude(obj.getDouble("altitude"));
            packet.setAccuracy((float) obj.getDouble("accuracy"));

            JSONArray measArray = obj.getJSONArray("measurements");
            List<SatelliteMeasurement> measurements = new ArrayList<>();
            for (int i = 0; i < measArray.length(); i++) {
                measurements.add(SatelliteMeasurement.fromJson(measArray.getJSONObject(i)));
            }
            packet.setMeasurements(measurements);

            return packet;
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Get human-readable summary
     */
    public String getSummary() {
        return String.format("Device: %s\nLat: %.6f, Lon: %.6f\nSatellites: %d\nAccuracy: %.2fm",
                deviceId.substring(0, Math.min(8, deviceId.length())),
                latitude, longitude, measurements.size(), accuracy);
    }
}