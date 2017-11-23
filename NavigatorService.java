package com.example.issei.navigator2;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by issei on 2017/11/17.
 */

public class NavigatorService extends Service {
    private static final String TAG = NavigatorService.class.getName();
    String toastText = "";

    private Context context = this;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Handler thisHandler = new Handler();
    private Looper serviceLooper;
    private ServiceHandler serviceHandler;
    private Handler bleHandler;
    private boolean scanning = false;
    private boolean connected = false;
    static final int STATUS_UPDATE_PERIOD = 5000;

//    Variables for Bluetooth
    private BluetoothAdapter   bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt      bluetoothGatt;
    private ScanCallback       scanCallback;
    static final int          SCAN_PERIOD = 10000;
    static final String UART_SERVICE  = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    static final String UART_WRITE    = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    static final String UART_READ     = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";

    private final class ServiceHandler extends Handler{
        public ServiceHandler(Looper looper){
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stopSelf(msg.arg1);
        }
    }


    public NavigatorService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG,"onCreate");
        toastText = "onCreate";

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(),"onCreate",Toast.LENGTH_SHORT).show();
//                stopSelf();
            }
        });

        thisHandler.post(new Runnable() {
            @Override
            public void run() {
                sendToast("Status: Scanning="+String.valueOf(scanning)+", Connected="+String.valueOf(connected));
                thisHandler.postDelayed(this,STATUS_UPDATE_PERIOD);
            }
        });

        startBleScan();
    }

    private void startBleScan(){
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        List<ScanFilter> scanFilterList = new ArrayList<>();
        ScanFilter scanFilter = new ScanFilter.Builder()
                .setDeviceName("UART Service")
                .build();
        scanFilterList.add(scanFilter);
        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();
        scanCallback = new BleScanCallback();
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        scanning = true;
        bluetoothLeScanner.startScan(scanFilterList,scanSettings,scanCallback);
        bleHandler = new Handler();
        bleHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (scanning){
                    scanning = false;
                    sendToast("Device not found...");
                    bluetoothLeScanner.stopScan(scanCallback);
                    stopThisService();
                }
            }
        },SCAN_PERIOD);
    }

    private class BleScanCallback extends ScanCallback{
        public BleScanCallback() {
            super();
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            Log.i(TAG,"onScanResult: "+callbackType);
            if (scanning){
                bluetoothLeScanner.stopScan(scanCallback);
                BluetoothDevice device = result.getDevice();
                Log.i(TAG,device.toString());
                GattCallback gattCallback = new GattCallback();
                device.connectGatt(context,true,gattCallback);
                scanning = false;
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            scanning = false;
            Log.i(TAG,"onScanFailed: " + String.valueOf(errorCode));
            sendToast("Scanning was failed: "+ String.valueOf(errorCode));
        }
    }

    private void sendToast(String text){
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(),text,Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void stopThisService(){
        sendToast("The BLE Service is cancelled");
        if (bleHandler!=null){
            bleHandler.removeCallbacksAndMessages(null);
        }
        if (thisHandler!=null){
            thisHandler.removeCallbacksAndMessages(null);
        }
        if (serviceHandler!=null){
            serviceHandler.removeCallbacksAndMessages(null);
        }
        if (mainHandler!=null){
            mainHandler.removeCallbacksAndMessages(null);
        }
        if (bluetoothGatt != null){
            bluetoothGatt.close();
        }
        stopSelf();
    }

    private class GattCallback extends BluetoothGattCallback{
        public GattCallback() {
            super();
        }

        @Override
        public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status);
        }

        @Override
        public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            super.onPhyRead(gatt, txPhy, rxPhy, status);
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED){
                Log.i(TAG,"Connected");
                sendToast("Connected!");
                connected = true;
                gatt.discoverServices();
            } else if(status == BluetoothGatt.GATT_FAILURE) {
                Log.i(TAG, "GATT: Unknown Error");
                sendToast("Unknown error has occurred!");
                gatt.disconnect();
            }

            if(newState == BluetoothGatt.STATE_DISCONNECTED){
                Log.i(TAG,"GATT: The profile is in disconnected state");
                startBleScan();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            Log.i(TAG, "onServicesDiscovered");
            if (status == BluetoothGatt.GATT_SUCCESS){
                Log.i(TAG, String.valueOf(gatt.getServices()));
                bluetoothGatt = gatt;
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            super.onReliableWriteCompleted(gatt, status);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            super.onReadRemoteRssi(gatt, rssi, status);
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
        }
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        Log.i(TAG,"onStart");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent notificationIntent = new Intent(this,MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle("Foot Navigator")
                .setContentText("Now Alive...")
                .setContentIntent(pendingIntent)
                .setWhen(System.currentTimeMillis())
                .build();

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(10,notification);

//        startForeground(1, notification);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG,"onDestroy");
        sendToast("BLE Service is destroyed.");
        if (bleHandler!=null){
            bleHandler.removeCallbacksAndMessages(null);
        }
        if (thisHandler!=null){
            thisHandler.removeCallbacksAndMessages(null);
        }
        if (serviceHandler!=null){
            serviceHandler.removeCallbacksAndMessages(null);
        }
        if (mainHandler!=null){
            mainHandler.removeCallbacksAndMessages(null);
        }
        if (bluetoothGatt != null){
            bluetoothGatt.close();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.i(TAG,"onConfigurationChanged");
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.i(TAG,"onLowMemory");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.i(TAG,"onTrimMemory");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(fd, writer, args);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
