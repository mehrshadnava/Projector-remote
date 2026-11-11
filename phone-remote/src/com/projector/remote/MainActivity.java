package com.projector.remote;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** The remote's screen. The Bluetooth side lives in RemoteService so it survives switching apps. */
public class MainActivity extends Activity implements RemoteService.Listener {
    private static final int VOL_UP = 0xE9, VOL_DOWN = 0xEA, POWER = 0x30;

    private TextView status;
    private EditText typeField;
    private View[] pages;
    private TextView[] tabs;
    private SharedPreferences prefs;
    private boolean muteTyping;
    private int heldButtons; // mouse buttons held down by the Left/Right click buttons (for dragging)
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable repeater;
    private ObjectAnimator pulse, shake;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.main);
        prefs = getPreferences(MODE_PRIVATE);
        status = findViewById(R.id.status);
        typeField = findViewById(R.id.type);
        ((Trackpad) findViewById(R.id.pad)).act = this;
        ((DPad) findViewById(R.id.dpad)).act = this;
        pages = new View[] {findViewById(R.id.page_remote), findViewById(R.id.page_touch), findViewById(R.id.page_keys)};
        tabs = new TextView[] {findViewById(R.id.tab0), findViewById(R.id.tab1), findViewById(R.id.tab2)};
        showTab(prefs.getInt("tab", 0), false);
        wireKeys(findViewById(android.R.id.content));
        setupTyping(typeField);

        View power = findViewById(R.id.power);
        power.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            consumer(RemoteService.SCREEN_TOGGLE); // lamp off/on, projector stays awake
        });
        power.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            consumer(POWER);
            Toast.makeText(this, "Projector going to standby. Use its IR remote to turn it back on.", Toast.LENGTH_LONG).show();
            return true;
        });

        List<String> perms = new ArrayList<>(List.of(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE));
        if (android.os.Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(perms.toArray(new String[0]), 1);
        } else {
            startRemote();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startRemote();
        else setStatus("Bluetooth permission needed", R.color.bad);
    }

    private void startRemote() {
        BluetoothAdapter a = getSystemService(BluetoothManager.class).getAdapter();
        if (a == null) { setStatus("This phone has no Bluetooth", R.color.bad); return; }
        if (!a.isEnabled()) startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        startForegroundService(new Intent(this, RemoteService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        RemoteService.listener = this;
        onStatus(RemoteService.statusText, RemoteService.statusColor);
    }

    @Override
    protected void onPause() {
        RemoteService.listener = null;
        stopRepeat();
        super.onPause();
    }

    @Override
    public void onStatus(String text, int colorRes) { setStatus(text, colorRes); }

    /** Status pill: pick a paired device, or make the phone discoverable to pair a new one. */
    public void onConnect(View v) {
        RemoteService s = RemoteService.instance;
        if (s == null) { startRemote(); return; }
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
        String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) names[i] = devices.get(i).getName() + "  (" + devices.get(i).getAddress() + ")";
        new AlertDialog.Builder(this)
                .setTitle(names.length == 0 ? "No paired devices yet" : "Connect to projector")
                .setItems(names, (d, i) -> s.connectTo(devices.get(i)))
                .setNeutralButton("Pair new", (d, i) -> {
                    startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120));
                    Toast.makeText(this, "On the projector: Settings → Bluetooth → pair with this phone", Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void setStatus(String s, int colorRes) {
        status.setText(s);
        status.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(colorRes)));
        boolean busy = colorRes == R.color.warn;
        if (busy && pulse == null) {
            pulse = ObjectAnimator.ofFloat(status, "alpha", 1f, 0.45f);
            pulse.setDuration(600);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.start();
        } else if (!busy && pulse != null) {
            pulse.cancel();
            pulse = null;
            status.setAlpha(1f);
        }
    }

    // ---- UI ----

    public void onTab(View v) { showTab(Integer.parseInt((String) v.getTag()), true); }

    private void showTab(int i, boolean fromUser) {
        for (int j = 0; j < pages.length; j++) {
            pages[j].setVisibility(j == i ? View.VISIBLE : View.GONE);
            tabs[j].setSelected(j == i);
        }
        prefs.edit().putInt("tab", i).apply();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (i == 2 && fromUser) { typeField.requestFocus(); imm.showSoftInput(typeField, 0); }
        else imm.hideSoftInputFromWindow(typeField.getWindowToken(), 0);
        if (fromUser) tabs[i].performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /** Every view tagged "k:", "c:" or "m:" becomes a key: press on touch-down, release on touch-up. */
    private void wireKeys(View v) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) wireKeys(g.getChildAt(i));
        }
        if (!(v.getTag() instanceof String) || !((String) v.getTag()).matches("[kcm]:.*")) return;
        String tag = (String) v.getTag();
        char type = tag.charAt(0);
        int code = Integer.decode(tag.substring(2));
        v.setOnTouchListener((x, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { x.setPressed(true); press(x, type, code); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { x.setPressed(false); release(type, code); }
            return true;
        });
        v.setOnClickListener(x -> { press(x, type, code); release(type, code); }); // TalkBack double-tap
    }

    void press(View v, char type, int code) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (type == 'm') { heldButtons |= code; mouse(0, 0, 0, 0); return; }
        fire(type, code);
        if (repeats(type, code)) {
            repeater = new Runnable() {
                @Override public void run() { fire(type, code); handler.postDelayed(this, 90); }
            };
            handler.postDelayed(repeater, 400);
        }
    }

    void release(char type, int code) {
        if (type == 'm') { heldButtons &= ~code; mouse(0, 0, 0, 0); }
        stopRepeat();
    }

    private void stopRepeat() {
        if (repeater != null) { handler.removeCallbacks(repeater); repeater = null; }
    }

    /** Arrows, backspace and volume keep firing while held. */
    private static boolean repeats(char type, int code) {
        return type == 'k' ? (code >= 0x4F && code <= 0x52) || code == 0x2A : type == 'c' && (code == VOL_UP || code == VOL_DOWN);
    }

    private void fire(char type, int code) {
        if (type == 'k') key(0, code); else consumer(code);
    }

    /** The phone's own volume buttons control the projector while the app is open and connected. */
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        RemoteService s = RemoteService.instance;
        if (s != null && s.connected() && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            consumer(code == KeyEvent.KEYCODE_VOLUME_UP ? VOL_UP : VOL_DOWN);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private void setupTyping(EditText in) {
        in.addTextChangedListener(new TextWatcher() {
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (muteTyping) return;
                for (int i = 0; i < before; i++) key(0, 0x2A); // backspace
                for (int i = start; i < start + count; i++) {
                    int[] k = Keys.forChar(s.charAt(i));
                    if (k != null) key(k[0], k[1]);
                }
            }
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
        });
        in.setOnEditorActionListener((v, action, e) -> {
            key(0, 0x28); // Enter
            muteTyping = true;
            in.setText("");
            muteTyping = false;
            return true;
        });
    }

    // ---- send through the service; shake the status pill when not connected ----

    private void sent(boolean ok) {
        if (ok || (shake != null && shake.isRunning())) return;
        shake = ObjectAnimator.ofFloat(status, "translationX", 0, 14, -14, 8, -8, 0);
        shake.setDuration(350);
        shake.start();
    }

    void key(int modifier, int usage) {
        RemoteService s = RemoteService.instance;
        sent(s != null && s.key(modifier, usage));
    }

    void consumer(int usage) {
        RemoteService s = RemoteService.instance;
        sent(s != null && s.consumer(usage));
    }

    void mouse(int buttons, int dx, int dy, int wheel) {
        RemoteService s = RemoteService.instance;
        sent(s != null && s.mouse(buttons | heldButtons, dx, dy, wheel));
    }

    void click(int button) {
        mouse(button, 0, 0, 0);
        mouse(0, 0, 0, 0);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
