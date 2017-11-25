package com.example.issei.navigator2;

import android.Manifest;
import android.app.Activity;
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
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

/**
 * Created by issei on 2017/11/17.
 */

public class NavigatorService extends Service {
    private static final String TAG = NavigatorService.class.getName();
    String toastText = "";

    boolean running;

    static final int MESSAGE = 1;
    Messenger messenger = new Messenger(new IncomingHandler());
    Messenger replyMessenger;
    private final IBinder iBinder = new LocalBinder();
    private final Random  randomGenerator = new Random();

    static final int F_SWITCH_SERVICE = 0;
    static final int F_SET_DYNAMIC_REFERENCE = 1;
    static final int F_SET_STATIC_REFERENCE = 2;
    static final int F_SET_SHARE_POINT = 3;

    static final int FLAG_CURRENT_LOCATION = 0;
    static final int FLAG_BEARING = 1;
    static final int FLAG_LOCATION_BEARING = 2;

    private Context context = this;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Handler thisHandler = new Handler();
    private Handler bleHandler;
    private boolean scanning = false;
    private boolean connected = false;
    static final int STATUS_UPDATE_PERIOD = 5000;
    ServiceHandler serviceHandler;

    NotificationManager notificationManager;

    //    Variables for Bluetooth
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private ScanCallback scanCallback;
    static final int SCAN_PERIOD = 10000;
    static final byte[] CLIENT_CHARACTERISTIC_CONFIGURATION = new byte[]{0x29,0x02};
    static final String UART_SERVICE = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    static final String UART_WRITE = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    static final String UART_READ = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
    BluetoothGattService bluetoothGattService;
    BluetoothGattCharacteristic bluetoothGattCharacteristic;

    private GoogleApiClient googleApiClient;
    private Location currentLocation;
    private Location refPosition = new Location("");
    private Location YNU = new Location("");
    private float fBearing=180;
    private float bearing = 0;
    private boolean positionSharing = false;
    int SharedId;
    int ReferenceId;

    Runnable bleScanRunnable = new Runnable() {
        @Override
        public void run() {
            if (scanning) {
                scanning = false;
                logAndSendMessage(TAG,"Device not found...");
                bluetoothLeScanner.stopScan(scanCallback);
                if (running){
                    startBleScan();
                }
//                    stopThisService();
            }
        }
    };

    Runnable showStatusRunnable = new Runnable() {
        @Override
        public void run() {
//            sendToast("Status: Scanning=" + String.valueOf(scanning) + ", Connected=" + String.valueOf(connected));
            if (running){
                thisHandler.postDelayed(this, STATUS_UPDATE_PERIOD);
            }

        }
    };

    public class LocalBinder extends Binder{
        NavigatorService getService(){
         return NavigatorService.this;
        }
    }

    class IncomingHandler extends Handler{
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MESSAGE){
                Bundle bundle = msg.getData();
                replyMessenger = msg.replyTo;
                sendMessageToActivity("Navigator Service is now bound");
            }
        }
    }

    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
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

    public void logAndSendMessage(String... texts){
        Log.i(texts[0],texts[1]);
        sendMessageToActivity(texts[1]);
    }

    private void sendMessageToActivity(Object... objects) {
        if (replyMessenger != null){
            Message message = new Message();
            StringBuilder stringBuilder = new StringBuilder();
            for (int i=0;i < objects.length;i++){
                stringBuilder.append(objects[i]);
                if (i==0){
                    stringBuilder.append(": ");
                } else if (i!=(objects.length-1)){
                    stringBuilder.append(", ");
                };
            }
            String text = new String(stringBuilder);
            message.obj = text;
            try {
                replyMessenger.send(message);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public NavigatorService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        logAndSendMessage(TAG, "onCreate");
        toastText = "onCreate";

        logAndSendMessage(TAG, "MainLooper: " + String.valueOf(Looper.getMainLooper().getClass()));

//        sendToast("onCreate");

        thisHandler.post(showStatusRunnable);

        startBleScan();

        if (googleApiClient == null) {
            googleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(new GoogleApiCallbacks())
                    .addOnConnectionFailedListener(new GoogleApiConnectionFailedListener())
                    .addApi(LocationServices.API)
                    .build();
        }
        googleApiClient.connect();
    }

    private void startBleScan() {
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
        bluetoothLeScanner.startScan(scanFilterList, scanSettings, scanCallback);
        bleHandler = new Handler();
        bleHandler.postDelayed(bleScanRunnable, SCAN_PERIOD);
    }

    private class BleScanCallback extends ScanCallback {
        public BleScanCallback() {
            super();
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            logAndSendMessage(TAG, "onScanResult: " + callbackType);
            if (scanning) {
                bluetoothLeScanner.stopScan(scanCallback);
                BluetoothDevice device = result.getDevice();
                logAndSendMessage(TAG, device.toString());
                GattCallback gattCallback = new GattCallback();
                device.connectGatt(context, true, gattCallback);
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
            logAndSendMessage(TAG, "onScanFailed: " + String.valueOf(errorCode));
            sendToast("Scanning was failed: " + String.valueOf(errorCode));
        }
    }

    private class GoogleApiCallbacks implements GoogleApiClient.ConnectionCallbacks {

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            LocationRequest locationRequest = new LocationRequest();
            locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            locationRequest.setInterval(5000);

            if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, new GoogleLocationListener());
        }

        @Override
        public void onConnectionSuspended(int i) {

        }
    }

    private class GoogleApiConnectionFailedListener implements GoogleApiClient.OnConnectionFailedListener{

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }
    }

    class GoogleLocationListener implements LocationListener{

        @Override
        public void onLocationChanged(Location location) {
            logAndSendMessage(TAG, location.getLongitude() +", "+ location.getLatitude());

            currentLocation = location;
            YNU.setLatitude(35.4741875);
            YNU.setLongitude(139.5932654);

            float tempBearing = location.getBearing();
            if (tempBearing != 0){
                fBearing = tempBearing;
                currentLocation.setBearing(fBearing);
            }

            if (refPosition != null){
                bearing = currentLocation.bearingTo(refPosition) - fBearing > 0 ? currentLocation.bearingTo(refPosition) - fBearing : currentLocation.bearingTo(refPosition) - fBearing + 360 ;
//                sendToast("Bearing to Posi: " + String.valueOf(bearing));
//                logListAdapter.add("Bearing to Posi: " + String.valueOf(bearing));
            } else {
                bearing = currentLocation.bearingTo(YNU) - fBearing > 0 ? currentLocation.bearingTo(YNU) - fBearing : currentLocation.bearingTo(YNU) - fBearing + 360 ;
//                sendToast("Bearing to YNU: " + String.valueOf(bearing));
//                logListAdapter.add("Bearing to YNU: " + String.valueOf(bearing));
            }

            int intSpeedRef = (int) refPosition.getSpeed();
            if (intSpeedRef > 256){intSpeedRef = 256;};
//                intSpeedLevelRef = Math.log(refPosition.getSpeed())/Math.log(1.5);  x=ln(speed)/ln(1.5) ←速度をいい感じに分類できる

            float bearingRef = refPosition.getBearing() -fBearing;
            if (bearingRef < 0){bearingRef += 360;};

            if (fBearing < 0){fBearing += 360;};

            byte bBearings   = (byte) ((byte) (((byte)(((int)(fBearing / 360 * 16))& 0x0f)) << 4) |((byte) ((int)(bearing /360 * 16))& 0x0f));
            byte bRefSpeed   = (byte) ((byte) (intSpeedRef) & 0xFF);
            byte bRefBearing = (byte) ((int)(bearingRef / 360 * 16));
            logAndSendMessage(TAG, "fBearing  = " + String.valueOf(fBearing));
            logAndSendMessage(TAG, "bearing   = " + String.valueOf(bearing));
            logAndSendMessage(TAG, "bBearings = " + String.valueOf(bBearings));
            logAndSendMessage(TAG, "bRefSpeed = " + String.valueOf(bRefSpeed));
            logAndSendMessage(TAG, "bRefBearing = " + String.valueOf(bRefBearing));

            int intLatitude  = (int) (location.getLatitude()*100000);
            int intLongitude = (int) (location.getLongitude()*100000);

            logAndSendMessage(TAG,"GATT: "+(bluetoothGatt!=null)+" Connected: " + String.valueOf(connected ));
            if (bluetoothGatt != null && connected){
                Boolean successed = false;

                byte[] buffer = new byte[12];
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
                byteBuffer.put((byte) FLAG_LOCATION_BEARING)
                        .putInt(intLongitude)
                        .putInt(intLatitude)
                        .put(bBearings)
                        .put(bRefSpeed)
                        .put(bRefBearing);
                logAndSendMessage(TAG, new String(buffer));
                for (byte b : buffer) {
                    logAndSendMessage(TAG, String.valueOf(b & 0xff));
                    System.out.print(" ");
                }

                bluetoothGattService = bluetoothGatt.getService(UUID.fromString(UART_SERVICE));
                bluetoothGattCharacteristic = bluetoothGattService.getCharacteristic(UUID.fromString(UART_WRITE));
                bluetoothGattCharacteristic.setValue(buffer);
                bluetoothGattCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
//
                if (!successed){
                    logAndSendMessage(TAG,"Write:   "+new String(buffer));
                    successed = bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic);
                }
            }

            if (positionSharing){
                new updateLocationSharing().execute(currentLocation.getLongitude(), currentLocation.getLatitude(), Double.parseDouble(String.valueOf(currentLocation.getBearing())),Double.parseDouble(String.valueOf(currentLocation.getSpeed())));
            }

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
        logAndSendMessage(TAG,"Navigator Service is now stopping...");

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
                logAndSendMessage(TAG,"Connected");
                sendToast("Connected!");
                connected = true;
                gatt.discoverServices();
            } else if(status == BluetoothGatt.GATT_FAILURE) {
                logAndSendMessage(TAG, "GATT: Unknown Error");
                sendToast("Unknown error has occurred!");
                gatt.disconnect();
            }

            if(newState == BluetoothGatt.STATE_DISCONNECTED){
                logAndSendMessage(TAG,"GATT: The profile is in disconnected state");
                startBleScan();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            logAndSendMessage(TAG, "onServicesDiscovered");
            if (status == BluetoothGatt.GATT_SUCCESS){
                bluetoothGatt = gatt;
                List<BluetoothGattService> gattServiceList = gatt.getServices();
                logAndSendMessage(TAG,"onServicesDiscovered " + gattServiceList.toString());

                for(BluetoothGattService service : gattServiceList){
                    logAndSendMessage(TAG, "UUID: "+ String.valueOf(service.getUuid()));
                    if (!UUID.fromString(UART_SERVICE).equals(service.getUuid())){
                        continue;
                    }
                    List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
                    for (BluetoothGattCharacteristic characteristic : characteristics){
                        logAndSendMessage(TAG, "Characteristic: "+String.valueOf(characteristic.getUuid()));
                        if (!UUID.fromString(UART_READ).equals(characteristic.getUuid())){
                            continue;
                        };
                        final int properties = characteristic.getProperties();
                        if ((properties | BluetoothGattCharacteristic.PROPERTY_NOTIFY)>0){
                            gatt.setCharacteristicNotification(characteristic,true);

                            ByteBuffer bb = ByteBuffer.wrap(CLIENT_CHARACTERISTIC_CONFIGURATION);
                            long high = bb.getLong();
                            long low  = bb.getLong();
                            UUID uuid = new UUID(high,low);
                            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(uuid);
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);
                        }else {
                            logAndSendMessage(TAG,"Properties does not support notify.");
                        }
                    }
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            logAndSendMessage(TAG,"onCharacteristicRead: " + status);

        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            logAndSendMessage(TAG,"onCharacteristicWrite: " + status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
//            logAndSendMessage(TAG,"onCharacteristicChanged");
            byte[] bytes = characteristic.getValue();
            String string = null;
            try {
                string = new String(bytes,"UTF-8");
                logAndSendMessage(TAG,"Receive: "+string);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
            logAndSendMessage(TAG,"onDescriptorRead");
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            logAndSendMessage(TAG,"onDescriptorWrite");
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            super.onReliableWriteCompleted(gatt, status);
            logAndSendMessage(TAG,"onReliableWriteCompleted");
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            super.onReadRemoteRssi(gatt, rssi, status);
            logAndSendMessage(TAG,"onReadRemoteRssi");
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            logAndSendMessage(TAG,"onMtuChanged");
        }
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        logAndSendMessage(TAG,"onStart");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent!= null && intent.hasExtra("Flag")){
            switch (intent.getExtras().getInt("Flag")){
                case F_SWITCH_SERVICE:
                    logAndSendMessage(TAG,"F_SWITCH_SERVICE");
                    logAndSendMessage(TAG, String.valueOf(running));

                    running = true;
//                    if (running){
//                        stopThisService();
//                        running = false;
//                    };
                    break;
                case F_SET_DYNAMIC_REFERENCE:
                    logAndSendMessage(TAG,"F_SET_DYNAMIC_REFERENCE");
                    ReferenceId = intent.getExtras().getInt("id");
                    logAndSendMessage(TAG, String.valueOf(ReferenceId));
                    thisHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            new getReferencePosition().execute(ReferenceId);
                            if (running){
                                new Handler().postDelayed(this,STATUS_UPDATE_PERIOD);
                            }
                        }
                    });
                    running = true;
                    break;
                case F_SET_STATIC_REFERENCE:
                    logAndSendMessage(TAG,"F_SET_STATIC_REFERENCE");
                    double longitude = intent.getExtras().getDouble("longitude");
                    double latitude = intent.getExtras().getDouble("latitude");
                    refPosition.setLongitude(longitude);
                    refPosition.setLatitude(latitude);
                    logAndSendMessage(TAG, String.valueOf(longitude) + latitude);
                    running = true;
                    break;
                case F_SET_SHARE_POINT:
                    SharedId = intent.getExtras().getInt("id");
                    logAndSendMessage(TAG, String.valueOf(SharedId));
                    positionSharing = true;
                    running = true;
                    break;
            }
        }

        Intent notificationIntent = new Intent(this,MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle("Foot Navigator")
                .setContentText("Now Alive...")
                .setContentIntent(pendingIntent)
                .setWhen(System.currentTimeMillis())
                .build();

//        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
//        notificationManager.notify(10,notification);

        startForeground(1, notification);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        logAndSendMessage(TAG,"onDestroy");
        sendToast("BLE Service is destroyed.");
        if (bleHandler!=null){
            bleHandler.removeCallbacksAndMessages(bleScanRunnable);
        }
        if (thisHandler!=null){
            thisHandler.removeCallbacksAndMessages(showStatusRunnable);
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
        if (googleApiClient.isConnected()){
            googleApiClient.disconnect();
        }

        stopForeground(true);

        running = false;
    }

    public class updateLocationSharing extends AsyncTask<Double, Void,Integer>{
        @Override
        protected Integer doInBackground(Double... doubles) {
            HttpsURLConnection con = null;
            String urlSt = "https://peaceful-caverns-31016.herokuapp.com/api/v1/application/"
                    +String.valueOf(SharedId)
                    +"?lon="
                    + doubles[0].toString()
                    +"&lat="
                    + doubles[1].toString()
                    +"&bea="
                    + doubles[2].toString()
                    +"&spd="
                    + doubles[3].toString();
            logAndSendMessage(TAG,"URL: " + urlSt);
            String method = "POST";

            try{
                URL url = new URL(urlSt);
                con = (HttpsURLConnection) url.openConnection();
                con.setRequestMethod(method);
                con.setInstanceFollowRedirects(false);
                con.setDoInput(true);
                con.setDoOutput(true);

                con.connect();

                InputStream in = con.getInputStream();
                readInputStream(in);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }

        public String readInputStream(InputStream in) throws IOException {
            StringBuffer sb = new StringBuffer();
            String st = "";

            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            while ((st = br.readLine()) != null){
                logAndSendMessage(TAG,st);
                sb.append(st);
            }
            try {
                in.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return sb.toString();
        }
    }

    public class getReferencePosition extends AsyncTask<Integer, Void,Integer>{
        @Override
        protected Integer doInBackground(Integer... integers) {
            HttpsURLConnection con = null;
            String urlSt = "https://peaceful-caverns-31016.herokuapp.com/api/v1/application/"
                    +String.valueOf(integers[0]);
            String method = "GET";

            try{
                URL url = new URL(urlSt);
                con = (HttpsURLConnection) url.openConnection();
                con.setRequestMethod(method);
                con.setInstanceFollowRedirects(false);
                con.setDoInput(true);
                con.setDoOutput(false);

                con.connect();

                InputStream in = con.getInputStream();

                JSONObject jsonObject = new JSONObject(readInputStream(in));
                try{
                    refPosition.setLatitude(jsonObject.getDouble("latitude"));
                } catch(JSONException e){
                    refPosition.setLatitude(jsonObject.getDouble(null));
                }

                try{
                    refPosition.setLongitude(jsonObject.getDouble("longitude"));
                } catch(JSONException e){
                    refPosition.setLongitude(jsonObject.getDouble(null));
                }

                try{
                    refPosition.setBearing((float) jsonObject.getDouble("bearing"));
                } catch(JSONException e){
                    refPosition.setBearing((float) jsonObject.getDouble(null));
                }

                try{
                    refPosition.setSpeed((float) jsonObject.getDouble("speed"));
                } catch(JSONException e){
                    refPosition.setSpeed((float) jsonObject.getDouble(null));
                }
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return null;
        }

        public String readInputStream(InputStream in) throws IOException {
            StringBuffer sb = new StringBuffer();
            String st = "";

            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            while ((st = br.readLine()) != null){
                logAndSendMessage(TAG,st);
                sb.append(st);
            }
            try {
                in.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return sb.toString();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        logAndSendMessage(TAG,"onConfigurationChanged");
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        logAndSendMessage(TAG,"onLowMemory");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        logAndSendMessage(TAG,"onTrimMemory");
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
        return messenger.getBinder();
    }
}
