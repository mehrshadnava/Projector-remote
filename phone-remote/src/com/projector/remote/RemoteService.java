package com.projector.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Owns the Bluetooth keyboard/mouse role. Runs as a foreground service because Android drops the
 * HID registration of apps in the background (e.g. while you switch to YouTube).
 */
public class RemoteService extends Service {
    interface Listener { void onStatus(String text, int colorRes); }

    static final int KEYBOARD = 1, MOUSE = 2, CONSUMER = 3; // HID report IDs
    static final int PLAY_PAUSE = 0xCD, SCREEN_TOGGLE = 0x192; // 0x192 "AL Calculator": the projector's Screen-off helper listens for it
    private static final long RETRY_MS = 4000;
    private static final String CHANNEL = "remote";

    /** Composite HID descriptor: keyboard (ID 1), relative mouse with wheel (ID 2), consumer keys (ID 3). */
    private static final byte[] DESCRIPTOR = hex(
            // keyboard: modifier byte, reserved byte, 6 key slots
            "05 01 09 06 A1 01 85 01 05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02"
            + " 95 01 75 08 81 01 95 06 75 08 15 00 25 65 05 07 19 00 29 65 81 00 C0"
            // mouse: 3 buttons, X, Y, wheel (signed bytes, relative)
            + " 05 01 09 02 A1 01 85 02 09 01 A1 00 05 09 19 01 29 03 15 00 25 01 95 03 75 01 81 02"
            + " 95 01 75 05 81 03 05 01 09 30 09 31 09 38 15 81 25 7F 75 08 95 03 81 06 C0 C0"
            // consumer control: one 16-bit usage (Home, Back, volume, play/pause, power...)
            + " 05 0C 09 01 A1 01 85 03 15 00 26 FF 03 19 00 2A FF 03 75 10 95 01 81 00 C0");

    static RemoteService instance;
    static Listener listener;
    static String statusText = "Starting…";
    static int statusColor = R.color.warn;

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice host;
    private boolean registered;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("MainActivity", MODE_PRIVATE); // same file the activity used before, keeps the saved projector
        adapter = getSystemService(BluetoothManager.class).getAdapter();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Remote connection", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                hid = (BluetoothHidDevice) proxy;
                register();
            }
            @Override public void onServiceDisconnected(int profile) {
                hid = null; host = null; registered = false;
                status("Bluetooth stopped", R.color.bad);
            }
        }, BluetoothProfile.HID_DEVICE);
        handler.post(retry);
    }

    /** Notification buttons. */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if ("screen".equals(a)) consumer(SCREEN_TOGGLE);
        else if ("play".equals(a)) consumer(PLAY_PAUSE);
        else if ("stop".equals(a)) stopSelf();
        return START_NOT_STICKY;
    }

    private void register() {
        hid.registerApp(new BluetoothHidDeviceAppSdpSettings("Projector Remote", "Phone remote", "Projector",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR), null, null, getMainExecutor(), callback);
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean reg) {
            registered = reg;
            if (!reg) { host = null; status("Reconnecting…", R.color.warn); return; } // retry loop re-registers
            if (plugged != null) hid.connect(plugged); else connectSaved();
        }

        @Override public void onConnectionStateChanged(BluetoothDevice d, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = d;
                prefs.edit().putString("host", d.getAddress()).apply();
                status("Connected · " + d.getName(), R.color.ok);
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                status("Connecting…", R.color.warn);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && (host == null || d.equals(host))) {
                host = null;
                status("Reconnecting…", R.color.warn);
            }
        }
    };

    /** Every few seconds: re-register if Android dropped us, reconnect if the projector went away. */
    private final Runnable retry = new Runnable() {
        @Override public void run() {
            if (!adapter.isEnabled()) status("Bluetooth is off", R.color.bad);
            else if (hid != null && !registered) register();
            else if (hid != null && host == null) connectSaved();
            handler.postDelayed(this, RETRY_MS);
        }
    };

    private void connectSaved() {
        String saved = prefs.getString("host", null);
        if (saved != null) hid.connect(adapter.getRemoteDevice(saved));
        else status("Not connected · tap here", R.color.bad);
    }

    void connectTo(BluetoothDevice d) {
        if (hid == null) return;
        status("Connecting…", R.color.warn);
        hid.connect(d);
    }

    boolean connected() { return host != null; }

    // ---- HID reports; return false when not connected ----

    private boolean send(int id, byte[] data) {
        return hid != null && host != null && hid.sendReport(host, id, data);
    }

    boolean key(int modifier, int usage) {
        return send(KEYBOARD, new byte[] {(byte) modifier, 0, (byte) usage, 0, 0, 0, 0, 0}) & send(KEYBOARD, new byte[8]);
    }

    boolean consumer(int usage) {
        return send(CONSUMER, new byte[] {(byte) usage, (byte) (usage >> 8)}) & send(CONSUMER, new byte[2]);
    }

    boolean mouse(int buttons, int dx, int dy, int wheel) {
        return send(MOUSE, new byte[] {(byte) buttons, (byte) dx, (byte) dy, (byte) wheel});
    }

    // ---- status + notification ----

    private void status(String text, int colorRes) {
        statusText = text;
        statusColor = colorRes;
        if (listener != null) listener.onStatus(text, colorRes);
        if (instance == this) getSystemService(NotificationManager.class).notify(1, notification());
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tab_remote)
                .setContentTitle("Projector remote")
                .setContentText(statusText)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(action(R.drawable.ic_power, "Screen", "screen"))
                .addAction(action(R.drawable.ic_playpause, "Play/Pause", "play"))
                .addAction(action(R.drawable.ic_back, "Stop", "stop"))
                .build();
    }

    private Notification.Action action(int icon, String title, String act) {
        PendingIntent pi = PendingIntent.getService(this, act.hashCode(),
                new Intent(this, RemoteService.class).setAction(act), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (hid != null) {
            hid.unregisterApp();
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
        }
        instance = null;
        status("Remote stopped · tap here", R.color.bad);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    private static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = (byte) Integer.parseInt(parts[i], 16);
        return out;
    }
}
