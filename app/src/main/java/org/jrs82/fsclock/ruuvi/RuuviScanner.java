package org.jrs82.fsclock.ruuvi;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/** Jatkuva BLE-skannaus RuuviTag-mainoksille. Suodattaa manufacturer ID 0x0499
 *  ja RAWv2 (data format 5) -paketit. Käynnistetään DreamServicen/Activityn
 *  elinkaaressa kun tabletti on latauksessa kiinni. */
public class RuuviScanner {

    private static final String TAG = "RuuviScanner";

    public interface Listener {
        /** Kutsutaan UI-säikeen ulkopuolella. */
        void onRuuviSample(RuuviSample sample);
    }

    private final Context appCtx;
    private final Listener listener;
    private BluetoothLeScanner scanner;
    private boolean running;

    public RuuviScanner(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
    }

    /** Käynnistää skannauksen. Palauttaa false jos BLE puuttuu tai lupia ei ole. */
    public synchronized boolean start() {
        if (running) return true;
        if (!hasPermission()) {
            Log.w(TAG, "BLE-lupa puuttuu, ei käynnistetä");
            return false;
        }
        BluetoothAdapter adapter;
        try {
            BluetoothManager bm = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Log.w(TAG, "BT-adapter null tai pois päältä");
                return false;
            }
            scanner = adapter.getBluetoothLeScanner();
        } catch (SecurityException e) {
            Log.w(TAG, "BT-adapter SecurityException", e);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "BT-adapter epäonnistui", e);
            return false;
        }
        if (scanner == null) {
            Log.w(TAG, "BluetoothLeScanner null");
            return false;
        }

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setManufacturerData(RuuviPacket.MANUFACTURER_ID, new byte[]{ (byte) RuuviPacket.FORMAT_RAW_V2 },
                        new byte[]{ (byte) 0xFF })
                .build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();

        try {
            scanner.startScan(filters, settings, callback);
            running = true;
            Log.i(TAG, "BLE-skannaus käynnistetty (manufacturer 0x0499 RAWv2)");
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "startScan SecurityException", e);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "startScan epäonnistui", e);
            return false;
        }
    }

    public synchronized void stop() {
        if (!running || scanner == null) {
            running = false;
            return;
        }
        BluetoothLeScanner s = scanner;
        try {
            s.stopScan(callback);
            Log.i(TAG, "BLE-skannaus pysäytetty");
        } catch (SecurityException e) {
            Log.w(TAG, "stopScan SecurityException", e);
        } catch (Exception e) {
            Log.w(TAG, "stopScan epäonnistui", e);
        } finally {
            running = false;
            scanner = null;
        }
    }

    public boolean isRunning() { return running; }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        // API ≤30: BLE-skannaus vaatii ACCESS_FINE_LOCATION runtime-luvan.
        return ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handle(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult r : results) handle(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "BLE-skannaus epäonnistui, errorCode=" + errorCode);
        }
    };

    private void handle(ScanResult result) {
        if (result == null || result.getScanRecord() == null) return;
        byte[] manuf = result.getScanRecord().getManufacturerSpecificData(RuuviPacket.MANUFACTURER_ID);
        if (manuf == null) return;
        RuuviPacket packet = RuuviPacket.parseRawV2(manuf);
        if (packet == null) return;
        String mac = result.getDevice() == null ? null : result.getDevice().getAddress();
        if (mac == null && packet.macFromPacket != null) mac = packet.macFromPacket;
        if (mac == null) return;
        RuuviSample sample = new RuuviSample(mac, result.getRssi(), System.currentTimeMillis(), packet);
        if (listener != null) {
            try { listener.onRuuviSample(sample); } catch (Exception e) {
                Log.w(TAG, "listener heitti", e);
            }
        }
    }
}
