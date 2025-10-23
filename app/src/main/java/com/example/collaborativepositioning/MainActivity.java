package com.example.collaborativepositioning;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.GnssMeasurementsEvent;
import android.location.GnssMeasurement;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Toast;
import android.view.View;
import android.content.Context;
import android.util.Log;

import com.example.collaborativepositioning.model.GnssDataPacket;
import com.example.collaborativepositioning.network.DeviceManager;
import com.example.collaborativepositioning.network.WiFiDirectManager;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements WiFiDirectManager.WiFiDirectListener, DeviceManager.DeviceUpdateListener {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int DATA_SHARE_INTERVAL_MS = 1000; // Share data every 1 second

    private LocationManager locationManager;
    private FusedLocationProviderClient fusedLocationClient;

    private TextView locationText, statusText, peersText;
    private Button showLocationButton, saveButton, discoverButton, disconnectButton;
    private ScrollView scrollView;

    private String latestGnssData = "No GNSS data yet";
    private String latestLatLon = "No location yet";

    // Phase 2: Networking components
    private WiFiDirectManager wifiDirectManager;
    private DeviceManager deviceManager;
    private GnssDataPacket currentPacket;
    private String myDeviceId;
    private Handler dataShareHandler;
    private Runnable dataShareRunnable;

    private boolean isNetworkingEnabled = false;
    private boolean hasLocation = false;
    private boolean hasGnssData = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize views
        locationText = findViewById(R.id.locationText);
        statusText = findViewById(R.id.statusText);
        peersText = findViewById(R.id.peersText);
        showLocationButton = findViewById(R.id.showLocationButton);
        saveButton = findViewById(R.id.saveButton);
        discoverButton = findViewById(R.id.discoverButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        scrollView = findViewById(R.id.scrollView);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Generate unique device ID
        myDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        Log.d(TAG, "My Device ID: " + myDeviceId);

        // Initialize Phase 2 components
        deviceManager = new DeviceManager(this);
        wifiDirectManager = new WiFiDirectManager(this, this);

        // Handler for periodic data sharing
        dataShareHandler = new Handler();
        dataShareRunnable = new Runnable() {
            @Override
            public void run() {
                if (isNetworkingEnabled && currentPacket != null) {
                    // Update my device in DeviceManager
                    deviceManager.updateDevice(currentPacket);
                    // Send to network
                    wifiDirectManager.sendData(currentPacket);
                    Log.d(TAG, "Shared data - Lat: " + currentPacket.getLatitude() +
                            ", Satellites: " + currentPacket.getMeasurements().size());
                }
                dataShareHandler.postDelayed(this, DATA_SHARE_INTERVAL_MS);
            }
        };

        checkPermissionsAndStart();
        setupButtonListeners();
    }

    private void setupButtonListeners() {
        // Show Location button
        showLocationButton.setOnClickListener(v -> {
            showLocationButton.setVisibility(View.GONE);
            scrollView.setVisibility(View.VISIBLE);
            saveButton.setVisibility(View.VISIBLE);
            discoverButton.setVisibility(View.VISIBLE);
            statusText.setVisibility(View.VISIBLE);
            peersText.setVisibility(View.VISIBLE);

            startLocationUpdates();
            startGnssLogging();
        });

        // Save button
        saveButton.setOnClickListener(v -> {
            String combinedData = latestLatLon + "\n" + latestGnssData + "\n" +
                    deviceManager.getDevicesSummary();
            saveLocationToFile(combinedData);
        });

        // Discover Peers button
        discoverButton.setOnClickListener(v -> {
            if (!isNetworkingEnabled) {
                if (!hasLocation || !hasGnssData) {
                    Toast.makeText(this, "Waiting for GPS fix...", Toast.LENGTH_SHORT).show();
                    return;
                }
                enableNetworking();
            }
            wifiDirectManager.discoverPeers();
            Toast.makeText(this, "Discovering nearby devices...", Toast.LENGTH_SHORT).show();
        });

        // Disconnect button
        disconnectButton.setOnClickListener(v -> {
            disableNetworking();
            Toast.makeText(this, "Disconnected from network", Toast.LENGTH_SHORT).show();
        });
    }

    private void checkPermissionsAndStart() {
        String[] permissions;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE
            };
        }

        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                Toast.makeText(this, "All permissions required for full functionality!",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        updateLocationData(location);
                    }
                });
    }

    private void updateLocationData(Location location) {
        hasLocation = true;
        latestLatLon = "Latitude: " + location.getLatitude() +
                "\nLongitude: " + location.getLongitude();

        // Update current packet
        if (currentPacket == null) {
            currentPacket = new GnssDataPacket();
        }
        currentPacket.setDeviceId(myDeviceId);
        currentPacket.setTimestamp(System.currentTimeMillis());
        currentPacket.setLatitude(location.getLatitude());
        currentPacket.setLongitude(location.getLongitude());
        currentPacket.setAltitude(location.getAltitude());
        currentPacket.setAccuracy(location.getAccuracy());

        Log.d(TAG, "Location updated: " + location.getLatitude() + ", " + location.getLongitude());

        // Update my device in DeviceManager immediately
        if (isNetworkingEnabled) {
            deviceManager.updateDevice(currentPacket);
        }

        updateDisplay();
    }

    private void startGnssLogging() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        locationManager.registerGnssMeasurementsCallback(new GnssMeasurementsEvent.Callback() {
            @Override
            public void onGnssMeasurementsReceived(GnssMeasurementsEvent eventArgs) {
                hasGnssData = true;
                StringBuilder sb = new StringBuilder();
                sb.append("SatID, CarrierFreq(Hz), PseudoRangeRate(m/s), CN0(dB-Hz)\n");

                // Clear previous measurements
                if (currentPacket == null) {
                    currentPacket = new GnssDataPacket();
                    currentPacket.setDeviceId(myDeviceId);
                }
                currentPacket.getMeasurements().clear();

                for (GnssMeasurement m : eventArgs.getMeasurements()) {
                    double carrierFreq = m.hasCarrierFrequencyHz() ?
                            m.getCarrierFrequencyHz() : 0;

                    sb.append(m.getSvid()).append(", ")
                            .append(carrierFreq != 0 ? carrierFreq : "N/A").append(", ")
                            .append(m.getPseudorangeRateMetersPerSecond()).append(", ")
                            .append(m.getCn0DbHz()).append("\n");

                    // Add to packet
                    currentPacket.addMeasurement(
                            m.getSvid(),
                            carrierFreq,
                            m.getPseudorangeRateMetersPerSecond(),
                            m.getCn0DbHz()
                    );
                }

                latestGnssData = sb.toString();

                // Update timestamp
                currentPacket.setTimestamp(System.currentTimeMillis());

                // Update my device in DeviceManager
                if (isNetworkingEnabled) {
                    deviceManager.updateDevice(currentPacket);
                }

                runOnUiThread(() -> updateDisplay());
            }
        });

        Toast.makeText(this, "GNSS Raw Data Logging Started", Toast.LENGTH_SHORT).show();
    }

    private void updateDisplay() {
        StringBuilder display = new StringBuilder();
        display.append("=== MY DEVICE ===\n");
        display.append("ID: ").append(myDeviceId.substring(0, Math.min(8, myDeviceId.length()))).append("\n");
        display.append(latestLatLon).append("\n\n");
        display.append(latestGnssData).append("\n");

        if (isNetworkingEnabled) {
            int deviceCount = deviceManager.getActiveDeviceCount();
            display.append("\n=== NEARBY DEVICES (").append(deviceCount).append(") ===\n");

            if (deviceCount == 0) {
                display.append("No other devices connected yet.\n");
                display.append("Make sure other devices are running and have pressed 'Discover'.\n");
            } else {
                display.append(deviceManager.getDevicesSummary());

                display.append("\n=== PROXIMITY ANALYSIS ===\n");
                String proximityData = deviceManager.getProximityAnalysis(myDeviceId);
                display.append(proximityData);

                Log.d(TAG, "Proximity Analysis:\n" + proximityData);
            }
        }

        locationText.setText(display.toString());
    }

    // WiFiDirectListener implementation
    @Override
    public void onPeersAvailable(List<WifiP2pDevice> peerList) {
        runOnUiThread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Discovered Devices (").append(peerList.size()).append("):\n");

            for (WifiP2pDevice device : peerList) {
                sb.append("• ").append(device.deviceName).append("\n");
            }

            peersText.setText(sb.toString());

            // Auto-connect to first peer for demo
            if (!peerList.isEmpty() && !wifiDirectManager.isGroupOwner()) {
                wifiDirectManager.connectToPeer(peerList.get(0));
            }
        });
    }

    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        runOnUiThread(() -> {
            String role = info.isGroupOwner ? "Group Owner (Server)" : "Client";
            statusText.setText("Status: Connected as " + role);
            disconnectButton.setVisibility(View.VISIBLE);

            Toast.makeText(this, "Connected! Role: " + role, Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Connected as: " + role);
        });
    }

    @Override
    public void onDataReceived(GnssDataPacket packet) {
        Log.d(TAG, "Data received from device: " + packet.getDeviceId().substring(0, 8) +
                " Lat: " + packet.getLatitude() + ", Satellites: " + packet.getMeasurements().size());

        deviceManager.updateDevice(packet);
        runOnUiThread(() -> updateDisplay());
    }

    @Override
    public void onStatusChanged(String status) {
        runOnUiThread(() -> {
            statusText.setText("Status: " + status);
            Log.d(TAG, "Status: " + status);
        });
    }

    // DeviceUpdateListener implementation
    @Override
    public void onDeviceAdded(String deviceId) {
        runOnUiThread(() -> {
            Toast.makeText(this, "New device connected!", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Device added: " + deviceId.substring(0, 8));
            updateDisplay();
        });
    }

    @Override
    public void onDeviceUpdated(String deviceId, GnssDataPacket packet) {
        runOnUiThread(() -> updateDisplay());
    }

    @Override
    public void onDeviceRemoved(String deviceId) {
        runOnUiThread(() -> {
            Toast.makeText(this, "Device disconnected", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Device removed: " + deviceId.substring(0, 8));
            updateDisplay();
        });
    }

    private void enableNetworking() {
        isNetworkingEnabled = true;
        wifiDirectManager.register();

        // Add my device to DeviceManager first
        if (currentPacket != null) {
            deviceManager.updateDevice(currentPacket);
            Log.d(TAG, "Added my device to DeviceManager");
        }

        dataShareHandler.post(dataShareRunnable);

        // Start timeout checker
        Handler timeoutHandler = new Handler();
        timeoutHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isNetworkingEnabled) {
                    deviceManager.checkTimeouts();
                    timeoutHandler.postDelayed(this, 5000);
                }
            }
        }, 5000);

        statusText.setText("Status: Networking Enabled");
        updateDisplay();
    }

    private void disableNetworking() {
        isNetworkingEnabled = false;
        dataShareHandler.removeCallbacks(dataShareRunnable);
        wifiDirectManager.disconnect();
        wifiDirectManager.unregister();
        deviceManager.clear();

        statusText.setText("Status: Offline");
        peersText.setText("Discovered Devices: None");
        disconnectButton.setVisibility(View.GONE);

        updateDisplay();
    }

    private void saveLocationToFile(String data) {
        try {
            String fileName = "gnss_combined_log_" + System.currentTimeMillis() + ".csv";
            File file = new File(getExternalFilesDir(null), fileName);
            FileWriter writer = new FileWriter(file, true);
            writer.append(data).append("\n----------------------\n");
            writer.flush();
            writer.close();
            Toast.makeText(this, "Data saved to " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error saving data", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isNetworkingEnabled) {
            wifiDirectManager.register();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isNetworkingEnabled) {
            wifiDirectManager.unregister();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isNetworkingEnabled) {
            disableNetworking();
        }
        wifiDirectManager.cleanup();
    }
}