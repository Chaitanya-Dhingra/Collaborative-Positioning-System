package com.example.collaborativepositioning.network;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.example.collaborativepositioning.model.GnssDataPacket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages Wi-Fi Direct P2P connections for collaborative positioning
 */
public class WiFiDirectManager {

    private static final String TAG = "WiFiDirectManager";
    private static final int SERVER_PORT = 8888;
    private static final int SOCKET_TIMEOUT = 5000;

    private Context context;
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;

    private List<WifiP2pDevice> peers = new ArrayList<>();
    private boolean isGroupOwner = false;
    private String groupOwnerAddress;

    private ExecutorService executorService;
    private ServerSocket serverSocket;
    private List<Socket> clientSockets = new ArrayList<>();

    private WiFiDirectListener listener;

    public interface WiFiDirectListener {
        void onPeersAvailable(List<WifiP2pDevice> peerList);
        void onConnectionInfoAvailable(WifiP2pInfo info);
        void onDataReceived(GnssDataPacket packet);
        void onStatusChanged(String status);
    }

    public WiFiDirectManager(Context context, WiFiDirectListener listener) {
        this.context = context;
        this.listener = listener;
        this.executorService = Executors.newCachedThreadPool();

        wifiP2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = wifiP2pManager.initialize(context, context.getMainLooper(), null);

        setupIntentFilter();
        setupBroadcastReceiver();
    }

    private void setupIntentFilter() {
        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    private void setupBroadcastReceiver() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        listener.onStatusChanged("Wi-Fi P2P Enabled");
                    } else {
                        listener.onStatusChanged("Wi-Fi P2P Disabled");
                    }
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    requestPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    requestConnectionInfo();
                } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                    // Device info changed
                }
            }
        };
    }

    public void register() {
        context.registerReceiver(receiver, intentFilter);
    }

    public void unregister() {
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException e) {
            // Already unregistered
        }
    }

    /**
     * Start discovering nearby peers
     */
    public void discoverPeers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                listener.onStatusChanged("Missing NEARBY_WIFI_DEVICES permission");
                return;
            }
        }

        wifiP2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                listener.onStatusChanged("Discovery started");
                Log.d(TAG, "Peer discovery initiated");
            }

            @Override
            public void onFailure(int reasonCode) {
                listener.onStatusChanged("Discovery failed: " + reasonCode);
                Log.e(TAG, "Peer discovery failed: " + reasonCode);
            }
        });
    }

    /**
     * Request list of discovered peers
     */
    private void requestPeers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        wifiP2pManager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
            @Override
            public void onPeersAvailable(WifiP2pDeviceList peerList) {
                peers.clear();
                peers.addAll(peerList.getDeviceList());
                listener.onPeersAvailable(peers);
                Log.d(TAG, "Peers available: " + peers.size());
            }
        });
    }

    /**
     * Connect to a specific peer
     */
    public void connectToPeer(WifiP2pDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                listener.onStatusChanged("Missing permissions");
                return;
            }
        }

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;

        wifiP2pManager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                listener.onStatusChanged("Connecting to " + device.deviceName);
                Log.d(TAG, "Connection initiated");
            }

            @Override
            public void onFailure(int reason) {
                listener.onStatusChanged("Connection failed: " + reason);
                Log.e(TAG, "Connection failed: " + reason);
            }
        });
    }

    /**
     * Request connection info after connection established
     */
    private void requestConnectionInfo() {
        wifiP2pManager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
            @Override
            public void onConnectionInfoAvailable(WifiP2pInfo info) {
                if (info.groupFormed) {
                    isGroupOwner = info.isGroupOwner;
                    if (info.groupOwnerAddress != null) {
                        groupOwnerAddress = info.groupOwnerAddress.getHostAddress();
                    }

                    listener.onConnectionInfoAvailable(info);

                    if (isGroupOwner) {
                        startServer();
                    } else {
                        connectToServer();
                    }
                }
            }
        });
    }

    /**
     * Start server (Group Owner)
     */
    private void startServer() {
        executorService.execute(() -> {
            try {
                serverSocket = new ServerSocket(SERVER_PORT);
                listener.onStatusChanged("Server started on port " + SERVER_PORT);
                Log.d(TAG, "Server socket opened");

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = serverSocket.accept();
                    clientSockets.add(clientSocket);
                    handleClient(clientSocket);
                    Log.d(TAG, "Client connected");
                }
            } catch (IOException e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            }
        });
    }

    /**
     * Handle client connection
     */
    private void handleClient(Socket socket) {
        executorService.execute(() -> {
            try {
                InputStream inputStream = socket.getInputStream();
                byte[] buffer = new byte[4096];

                while (!Thread.currentThread().isInterrupted()) {
                    int bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        String data = new String(buffer, 0, bytes);
                        GnssDataPacket packet = GnssDataPacket.fromJson(data);
                        if (packet != null) {
                            listener.onDataReceived(packet);
                            // Broadcast to other clients
                            broadcastToClients(data, socket);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Client handler error: " + e.getMessage());
            }
        });
    }

    /**
     * Connect to server (Client)
     */
    private void connectToServer() {
        executorService.execute(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(groupOwnerAddress, SERVER_PORT), SOCKET_TIMEOUT);
                clientSockets.add(socket);

                listener.onStatusChanged("Connected to server");
                Log.d(TAG, "Connected to group owner");

                // Listen for incoming data
                InputStream inputStream = socket.getInputStream();
                byte[] buffer = new byte[4096];

                while (!Thread.currentThread().isInterrupted()) {
                    int bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        String data = new String(buffer, 0, bytes);
                        GnssDataPacket packet = GnssDataPacket.fromJson(data);
                        if (packet != null) {
                            listener.onDataReceived(packet);
                        }
                    }
                }
            } catch (IOException e) {
                listener.onStatusChanged("Connection error: " + e.getMessage());
                Log.e(TAG, "Client connection error: " + e.getMessage());
            }
        });
    }

    /**
     * Send data to all connected peers
     */
    public void sendData(GnssDataPacket packet) {
        String jsonData = packet.toJson();
        if (jsonData == null) return;

        executorService.execute(() -> {
            byte[] bytes = jsonData.getBytes();

            for (Socket socket : new ArrayList<>(clientSockets)) {
                try {
                    if (socket.isConnected()) {
                        OutputStream outputStream = socket.getOutputStream();
                        outputStream.write(bytes);
                        outputStream.flush();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Send error: " + e.getMessage());
                    clientSockets.remove(socket);
                }
            }
        });
    }

    /**
     * Broadcast data to all clients except sender
     */
    private void broadcastToClients(String data, Socket excludeSocket) {
        byte[] bytes = data.getBytes();

        for (Socket socket : new ArrayList<>(clientSockets)) {
            if (socket != excludeSocket) {
                try {
                    if (socket.isConnected()) {
                        OutputStream outputStream = socket.getOutputStream();
                        outputStream.write(bytes);
                        outputStream.flush();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Broadcast error: " + e.getMessage());
                    clientSockets.remove(socket);
                }
            }
        }
    }

    /**
     * Disconnect from current group
     */
    public void disconnect() {
        // Close all sockets
        for (Socket socket : clientSockets) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        clientSockets.clear();

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Remove group
        wifiP2pManager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                listener.onStatusChanged("Disconnected");
                Log.d(TAG, "Group removed");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Remove group failed: " + reason);
            }
        });
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        unregister();
        disconnect();
        executorService.shutdown();
    }

    public boolean isGroupOwner() {
        return isGroupOwner;
    }

    public List<WifiP2pDevice> getPeers() {
        return new ArrayList<>(peers);
    }
}