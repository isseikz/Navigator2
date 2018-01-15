package com.example.issei.navigator2;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanCallback;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.iid.FirebaseInstanceId;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.exceptions.BleScanException;
import com.polidea.rxandroidble.internal.RxBleLog;
import com.polidea.rxandroidble.utils.ConnectionSharingAdapter;
import com.trello.rxlifecycle.RxLifecycle;
import com.trello.rxlifecycle.android.RxLifecycleAndroid;
import com.trello.rxlifecycle.components.support.RxAppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

/**
 * Created by issei on 2017/11/17.
 */

public class rxNavigatorService extends Service {
    private static final String TAG = "Navigator2";
    String toastText = "";
    String token = FirebaseInstanceId.getInstance().getToken();
    String refToken;

    int userId;
    int groupId;
    boolean registering = false;

    boolean running;

    MyFirebaseInstanceIdService myFirebaseInstanceIdService;

    static final int MESSAGE = 1;
    Messenger messenger = new Messenger(new IncomingHandler());
    Messenger replyMessenger;
    private final IBinder iBinder = new LocalBinder();

    static final int F_SWITCH_SERVICE = 0;
    static final int F_SET_DYNAMIC_REFERENCE = 1;
    static final int F_SET_STATIC_REFERENCE = 2;
    static final int F_SET_SHARE_POINT = 3;

    static final int FLAG_CURRENT_LOCATION = 0;
    static final int FLAG_BEARING = 1;
    static final int FLAG_LOCATION_BEARING = 2;
    static final int FLAG_WALKSTEP = 3;
    static final int FLAG_ROUTE_DIR = 4;

    static final int FLAG_INTR_STATE = 0;

    boolean routeDirectionNeeded = false;

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
    private ScanCallback scanCallback;
    static final int SCAN_PERIOD = 10000;
    static final byte[] CLIENT_CHARACTERISTIC_CONFIGURATION = new byte[]{0x29, 0x02};
    static final String UART_SERVICE = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    static final String UART_WRITE = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    static final String UART_READ = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";

    private GoogleApiClient googleApiClient;
    private Location currentLocation;
    private Location refPosition = new Location("");
    private Location YNU = new Location("");
    private float fBearing = 180;
    private float bearing = 0;
    private boolean positionSharing = false;
    int SharedId;

    double[][] route = new double[4][3];
    int routeId = 0;

    private boolean isBound = false;
    private static final byte COMMAND_INITIALIZE = 0;

    CompositeSubscription compositeSubscription;
    Subscription scanSubscription;
    RxBleDevice rxBleDevice;
    boolean isConnected = false;
    boolean found = false;
    private PublishSubject<Void> disconnectTriggerSubject = PublishSubject.create();
    private Observable<RxBleConnection> connectionObservable;
    MainApplication application;

    SensorManager sensorManager;
    Sensor sensor;
    float sensorValueX;
    float sensorValueY;
    float sensorValueZ;
    int sensorAccuracy;
    int sensorCnt = 0;


    Runnable showStatusRunnable = new Runnable() {
        @Override
        public void run() {
//            sendToast("Status: Scanning=" + String.valueOf(scanning) + ", Connected=" + String.valueOf(connected));
            if (running) {
                thisHandler.postDelayed(this, STATUS_UPDATE_PERIOD);
            }

        }
    };

    public class LocalBinder extends Binder {
        rxNavigatorService getService() {
            return rxNavigatorService.this;
        }
    }

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.getData().getString("command") != null) {
                String strCommand = msg.getData().getString("command");
                if (Objects.equals(strCommand, "notifyFootStep")) {
                    logAndSendMessage(TAG,"notify my footstep");
                    notifyMyFootstepV2 notify = new notifyMyFootstepV2();
                    Integer integers[] = {0,0};
                    notify.execute(integers);
                } else if (Objects.equals(strCommand, "Route:ON")) {
                    logAndSendMessage(TAG, "Route:ON");
                    routeDirectionNeeded = true;
                } else if (Objects.equals(strCommand, "Route:OFF")) {
                    routeDirectionNeeded = false;
                    logAndSendMessage(TAG, "Route:OFF");
                } else {
                }
            }
//            if (Objects.equals(msg.getData().getString("command"), "notifyFootStep")) {
//                notifyMyFootstepV2 notify = new notifyMyFootstepV2();
//                notify.execute();
//            }
            if (msg.getData().getString("user_id") != null) {
                userId = Integer.parseInt(msg.getData().getString("user_id"));
            }
            ;
            if (msg.what == MESSAGE) {
                Bundle bundle = msg.getData();
                replyMessenger = msg.replyTo;
//                logAndSendMessage(TAG, String.valueOf(bundle));
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

    ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            isBound = true;
            messenger = new Messenger(iBinder);
            sendByteMessage("command", SerialService.COMMAND_INIT);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            isBound = false;
        }
    };

    public void sendMessage(String key, String value) {
        if (isBound) {
            try {
                Message message = Message.obtain(null, SerialService.CODE_COMMAND, 0, 0);
                Bundle bundle = new Bundle();
                bundle.putString(key, value);
                message.setData(bundle);

                messenger.send(message);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public void sendByteMessage(String key, Byte value) {
        if (isBound) {
            try {
                Message message = Message.obtain(null, SerialService.CODE_COMMAND, 0, 0);
                Bundle bundle = new Bundle();
                bundle.putByte(key, value);
                message.setData(bundle);

                messenger.send(message);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public void logAndSendMessage(String... texts) {
        Log.i(texts[0], texts[1]);
        sendMessageToActivity(texts[1]);
    }

    private void sendMessageToActivity(Object... objects) {
        if (replyMessenger != null) {
            Message message = new Message();
            StringBuilder stringBuilder = new StringBuilder();
            for (int i = 0; i < objects.length; i++) {
                stringBuilder.append(objects[i]);
                if (i == 0) {
                    stringBuilder.append(": ");
                } else if (i != (objects.length - 1)) {
                    stringBuilder.append(", ");
                }
                ;
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

    public rxNavigatorService() {
        super();
    }

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
//            sendByteMessage("command", SerialService.COMMAND_WALKSTEP);
            Log.i(TAG, "RX Push Received!");

            Log.i(TAG, "Value:" + String.valueOf(intent.getStringExtra("data")));

            String strData = intent.getStringExtra("data");

//            Log.i(TAG,"user_id: " + intent.getStringExtra("user_id"));

//            TODO bluetooth
            byte[] buffer = new byte[5];
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
            byteBuffer.put((byte) FLAG_WALKSTEP)
                    .put((byte) ((byte) Integer.parseInt(String.valueOf(strData.charAt(0))) & 0xff))
                    .put((byte) ((byte) Integer.parseInt(String.valueOf(strData.charAt(1))) & 0xff))
                    .put((byte) ((byte) Integer.parseInt(String.valueOf(strData.charAt(2))) & 0xff))
                    .put((byte) ((byte) Integer.parseInt(String.valueOf(strData.charAt(3))) & 0xff));

            sendBle(buffer);
        }
    };

    private BroadcastReceiver broadcastReceiverToken = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            token = intent.getStringExtra("token");
            logAndSendMessage(TAG, "Token is changed: " + token);
        }
    };

    private BroadcastReceiver broadcastReceiverSerial = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            byte[] data = intent.getByteArrayExtra("data");
            logAndSendMessage(TAG, "Data received: " + String.valueOf(data));
        }
    };

    private BroadcastReceiver broadcastReceiverLog = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            logAndSendMessage(TAG, intent.getStringExtra("text"));
        }
    };

    private BroadcastReceiver broadcastReceiverFSBtn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            logAndSendMessage(TAG, "FootStep send");
            new notifyMyFootstep().execute();
        }
    };

    private BroadcastReceiver broadcastReceiverBLEBond = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            logAndSendMessage(TAG, intent.getStringExtra("ACTION_BOND_STATE_CHANGED"));
            Log.i(TAG, intent.getExtras().toString());
            switch (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                case BluetoothDevice.BOND_NONE:
                    logAndSendMessage(TAG, intent.getStringExtra("BOND_NONE"));
                    break;
                case BluetoothDevice.BOND_BONDED:
                    logAndSendMessage(TAG, intent.getStringExtra("BOND_BONDED"));
                    break;
                case BluetoothDevice.BOND_BONDING:
                    logAndSendMessage(TAG, intent.getStringExtra("BOND_BONDING"));
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        logAndSendMessage(TAG, "onCreate");
        toastText = "onCreate";

        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, new IntentFilter("FCM"));
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiverToken, new IntentFilter("FMS"));
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiverSerial, new IntentFilter("Serial"));
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiverLog, new IntentFilter("Log"));
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiverBLEBond, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));


        logAndSendMessage(TAG, "MainLooper: " + String.valueOf(Looper.getMainLooper().getClass()));


//        TWELITE制御用
//        Intent intent = new Intent(this,SerialService.class);
//        startService(intent);
//        bindService(intent,serviceConnection,BIND_AUTO_CREATE);

        compositeSubscription = new CompositeSubscription();

        bleHandler = new Handler();

        application = (MainApplication) this.getApplication();

        RxBleClient rxBleClient = application.getRxBleClient(this);
        RxBleClient.setLogLevel(RxBleLog.DEBUG);
        scanSubscription = rxBleClient.scanBleDevices(
                new com.polidea.rxandroidble.scan.ScanSettings.Builder()
//                        .setScanMode(com.polidea.rxandroidble.scan.ScanSettings.SCAN_MODE_BALANCED)
//                        .setCallbackType(com.polidea.rxandroidble.scan.ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                        .build(),
                new com.polidea.rxandroidble.scan.ScanFilter.Builder()
                        .setDeviceName("UART Service")
                        .build()
        )
                .observeOn(AndroidSchedulers.mainThread())
//                .doOnUnsubscribe(this::clearSubscription)
                .subscribe(rxBleScanResult -> {
                    if (!found) {
                        Log.i(TAG, "device registered");
                        found = true;
                        rxBleDevice = rxBleClient.getBleDevice(rxBleScanResult.getBleDevice().getMacAddress());
                        if (!isConnected) {
                            Log.i(TAG, "connecting...");

                            connectionObservable = prepareConnectionObservable();

                            compositeSubscription.add(
                                    connectionObservable
                                            .flatMap(rxBleConnection -> rxBleConnection.setupNotification(UUID.fromString(UART_READ)))
                                            .doOnNext(notificationObservable -> runOnUiThread(this::notificationHasBeenSetUp))
                                            .flatMap(notificationObservable -> notificationObservable)
                                            .observeOn(AndroidSchedulers.mainThread())
                                            .subscribe(this::onNotificationReceived, this::onNotificationSetupFailure)
                            );




//                            if (rxBleDevice.getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTED){

                            compositeSubscription.add(connectionObservable
                                    .flatMap(rxBleConnection -> rxBleConnection.writeCharacteristic(UUID.fromString(UART_WRITE), "test".getBytes()))
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .subscribe(
                                            bytes -> onWriteSuccess(),
                                            throwable -> {
                                                Log.i(TAG, "Write failure: " + throwable.getMessage());
                                            }
                                    ));



                        } else {
                            Log.i(TAG, "Connected");
                            isConnected = true;
                        }
                    }
                }, this::onScanFailure);

        compositeSubscription.add(scanSubscription);

        if (googleApiClient == null) {
            googleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(new GoogleApiCallbacks())
                    .addOnConnectionFailedListener(new GoogleApiConnectionFailedListener())
                    .addApi(LocationServices.API)
                    .build();
        }
        googleApiClient.connect();


        YNU.setLatitude(35.4741875);
        YNU.setLongitude(139.5932654);

        refPosition.setLatitude(35.4741875);
        refPosition.setLongitude(139.5932654);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> sensorListMag = sensorManager.getSensorList(Sensor.TYPE_MAGNETIC_FIELD);
        List<Sensor> sensorListAcc = sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER);
        if (sensorListMag.size() > 0){
            sensor = sensorListMag.get(0);
        } else {
            sensor = null;

        }
    }

    private class GoogleApiCallbacks implements GoogleApiClient.ConnectionCallbacks {

        @SuppressLint("MissingPermission")
        @Override
        public void onConnected(@Nullable Bundle bundle) {
            LocationRequest locationRequest = new LocationRequest();
            locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            locationRequest.setInterval(5000);

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

//    位置を取得した時に送信する
    class GoogleLocationListener implements LocationListener{

        @Override
        public void onLocationChanged(Location location) {
            logAndSendMessage(TAG, location.getLongitude() +", "+ location.getLatitude());
            logAndSendMessage(TAG,"user id: "+String.valueOf(userId));

            currentLocation = location;

            if (userId == 0 & !registering){
                registering = true;
                new registerUserId().execute(currentLocation.getLongitude(), currentLocation.getLatitude(), Double.parseDouble(String.valueOf(currentLocation.getBearing())),Double.parseDouble(String.valueOf(currentLocation.getSpeed())));
            }

            float tempBearing = location.getBearing();
            if (tempBearing != 0){
                fBearing = tempBearing;
                currentLocation.setBearing(fBearing);
            }

            if (refPosition != null){
//                目標地点の方向
//                bearing = currentLocation.bearingTo(refPosition) - fBearing > 0 ? currentLocation.bearingTo(refPosition) - fBearing : currentLocation.bearingTo(refPosition) - fBearing + 360 ;
                bearing = currentLocation.bearingTo(refPosition)> 0 ? currentLocation.bearingTo(refPosition) : currentLocation.bearingTo(refPosition) + 360 ; //真北に対する方角
                logAndSendMessage(TAG,"Bearing to ref: " + bearing);
//                sendToast("Bearing to Posi: " + String.valueOf(bearing));
//                logListAdapter.add("Bearing to Posi: " + String.valueOf(bearing));
            } else {
//                YNUの方向
//                bearing = currentLocation.bearingTo(YNU) - fBearing > 0 ? currentLocation.bearingTo(YNU) - fBearing : currentLocation.bearingTo(YNU) - fBearing + 360 ;
                bearing = currentLocation.bearingTo(YNU) > 0 ? currentLocation.bearingTo(YNU) : currentLocation.bearingTo(YNU) + 360 ; //真北に対する方角
                logAndSendMessage(TAG,"Bearing to YNU: " + bearing);
            }

            float fspeed = currentLocation.getSpeed()*10;
            logAndSendMessage(TAG, "Travel    Vef.   : " + String.valueOf(fspeed));
            int intSpeedBody = (int) fspeed;
            logAndSendMessage(TAG, "Travel    Vel.   : " + String.valueOf(intSpeedBody));
            if (intSpeedBody > 256){intSpeedBody = 256;};
            byte bSpeedBody = (byte) (intSpeedBody & 0xFF);

            int intSpeedRef = (int) refPosition.getSpeed();
            if (intSpeedRef > 256){intSpeedRef = 256;};
//                intSpeedLevelRef = Math.log(refPosition.getSpeed())/Math.log(1.5);  x=ln(speed)/ln(1.5) ←速度をいい感じに分類できる

//            float bearingRef = refPosition.getBearing() -fBearing;
            // 目標地点の移動方向（人間などを想定）
            float bearingRef = refPosition.getBearing();
            if (bearingRef < 0){bearingRef += 360;};
            // 現在地の移動方向
            if (fBearing < 0){fBearing += 360;};

//            byte bBearings   = (byte) ((byte) (((byte)(((int)(fBearing / 360 * 16))& 0x0f)) << 4) |((byte) ((int)(bearing /360 * 16))& 0x0f));
            byte bTravelBearing   = (byte)(((int)(fBearing / 360 * 256))& 0xff);
            byte bReferenceBearing =(byte)(((int)(bearing / 360 * 256))& 0xff);
            byte bRefSpeed   = (byte) ((byte) (intSpeedRef) & 0xFF);
            byte bRefBearing = (byte) ((int)(bearingRef / 360 * 16));
//            logAndSendMessage(TAG, "Travel    Dir.   : " + String.valueOf(fBearing));
//            logAndSendMessage(TAG, "Reference Dir.   : " + String.valueOf(bearing));
//            logAndSendMessage(TAG, "0x(TDir RefDir)  : " + String.valueOf(bBearings));
            logAndSendMessage(TAG, "Travel    Dir.   : " + String.valueOf(fBearing));
            logAndSendMessage(TAG, "Ref Point Vel.   : " + String.valueOf(bRefSpeed));
            logAndSendMessage(TAG, "Ref P Travel Dir.: " + String.valueOf(bRefBearing));

            int intLatitude  = (int) (location.getLatitude()*100000);
            int intLongitude = (int) (location.getLongitude()*100000);

            byte[] buffer = new byte[14];
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
            byteBuffer.put((byte) FLAG_LOCATION_BEARING)
                    .putInt(intLongitude)//経度
                    .putInt(intLatitude) //緯度
//                    .put(bBearings)      //現在値の進行方向・現在地から目標地点への方向
                    .put(bTravelBearing) //現在値の進行方向
                    .put(bRefSpeed)      //目標地点の移動速さ
                    .put(bRefBearing)    //目標地点の移動方角
                    .put(bSpeedBody)    //現在値の移動速さ
                    .put(bReferenceBearing);

            sendBle(buffer);

            if(routeDirectionNeeded){
                new getRoute().execute();
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
                    break;
                case F_SET_DYNAMIC_REFERENCE:
                    logAndSendMessage(TAG,"F_SET_DYNAMIC_REFERENCE");
                    groupId = intent.getExtras().getInt("id");
                    logAndSendMessage(TAG, String.valueOf(groupId));
                    Message message = Message.obtain();
                    Bundle bundle = new Bundle();
                    bundle.putInt("group_id",groupId);
                    message.setData(bundle);
                    try {
                        replyMessenger.send(message);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    thisHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (googleApiClient.isConnected()){
                                new updateRefPointV2().execute(currentLocation.getLongitude(), currentLocation.getLatitude(), Double.parseDouble(String.valueOf(currentLocation.getBearing())),Double.parseDouble(String.valueOf(currentLocation.getSpeed())));
                            }

                            if (running){
                                logAndSendMessage(TAG,"Reference received");
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
                    thisHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            logAndSendMessage(TAG,"Reference UPdated");
//                            new registerLocationSharing().execute(currentLocation.getLongitude(),currentLocation.getLatitude(),Double.parseDouble(String.valueOf(currentLocation.getBearing())),Double.parseDouble(String.valueOf(currentLocation.getSpeed())));
                            if (googleApiClient.isConnected()){
                                new makeGroupV2().execute(currentLocation.getLongitude(),currentLocation.getLatitude(),Double.parseDouble(String.valueOf(currentLocation.getBearing())),Double.parseDouble(String.valueOf(currentLocation.getSpeed())));
                            }
                        }
                    });
                    break;
                case MainActivity.F_SEND_COMPASS:
                    logAndSendMessage(TAG,"F_SEND_COMPASS");
                    if (sensor != null){
                        sensorManager.registerListener(sensorEventListener,sensor,SensorManager.SENSOR_DELAY_FASTEST);
                    } else {
                        logAndSendMessage(TAG,"I don't have E-Compass");
                    }

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

        startForeground(1, notification);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        logAndSendMessage(TAG,"onDestroy");
        sendToast("BLE Service is destroyed.");

        compositeSubscription.clear();
//        clearSubscription();
//        TODO ble
//        if (bleHandler!=null){
//            bleHandler.removeCallbacksAndMessages(bleScanRunnable);
//        }
        if (thisHandler!=null){
            thisHandler.removeCallbacksAndMessages(showStatusRunnable);
        }
        if (serviceHandler!=null){
            serviceHandler.removeCallbacksAndMessages(null);
        }
        if (mainHandler!=null){
            mainHandler.removeCallbacksAndMessages(null);
        }
//        if (bluetoothGatt != null){
//            bluetoothGatt.close();
//        }
        if (googleApiClient.isConnected()){
            googleApiClient.disconnect();
        }

//        unbindService(serviceConnection);
//        stopService(new Intent(this,SerialService.class));
        stopForeground(true);

        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiverToken);

        running = false;
    }

    public class registerUserId extends AsyncTask<Double, Void,Integer>{
        @Override
        protected Integer doInBackground(Double... floats) {
            HttpsURLConnection con = null;
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append("https://peaceful-caverns-31016.herokuapp.com/api/v2/application/register?lon=")
                    .append(floats[0].toString())
                    .append("&lat=")
                    .append(floats[1].toString())
                    .append("&bea=")
                    .append(floats[2].toString())
                    .append("&spd=")
                    .append(floats[3].toString())
                    .append("&token=")
                    .append(token);
            Log.i(TAG,"URL: " + new String(urlBuilder));
            String method = "GET";
            SharedId = 0;

            try{
                URL url = new URL(new String(urlBuilder));
                con = (HttpsURLConnection) url.openConnection();
                con.setRequestMethod(method);
                con.setInstanceFollowRedirects(false);
                con.setDoInput(true);
                con.setDoOutput(false);

                con.connect();

                InputStream in = con.getInputStream();
                String strJson = readInputStream(in);
                userId  = new JSONObject(strJson).getInt("user_id");
                groupId = new JSONObject(strJson).getInt("group_id");
                Log.i(TAG,"GroupId/userid/: "+String.valueOf(groupId)+"/"+String.valueOf(userId));
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (JSONException e) {
                e.printStackTrace();
            }

            Message message = Message.obtain();
            Bundle bundle = new Bundle();
            bundle.putInt("user_id",userId);
            bundle.putInt("group_id",groupId);
            message.setData(bundle);
            try {
                replyMessenger.send(message);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            return userId;
        }

        @Override
        protected void onPostExecute(Integer id) {
            super.onPostExecute(id);

            logAndSendMessage(TAG, "SharedId: "+String.valueOf(id));
            positionSharing = true;
            running = true;
            SharedId = id;

        }

        public String readInputStream(InputStream in) throws IOException {
            StringBuffer sb = new StringBuffer();
            String st = "";

            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            while ((st = br.readLine()) != null){
                Log.i(TAG,st);
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

    public class registerLocationSharing extends AsyncTask<Double, Void,Integer>{
        @Override
        protected Integer doInBackground(Double... floats) {
            HttpsURLConnection con = null;
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append("https://peaceful-caverns-31016.herokuapp.com/api/v1/application/registration?lon=")
                    .append(floats[0].toString())
                    .append("&lat=")
                    .append(floats[1].toString())
                    .append("&bea=")
                    .append(floats[2].toString())
                    .append("&spd=")
                    .append(floats[3].toString())
                    .append("&token=")
                    .append(token);
            Log.i(TAG,"URL: " + new String(urlBuilder));
            String method = "POST";
            SharedId = 0;

            try{
                URL url = new URL(new String(urlBuilder));
                con = (HttpsURLConnection) url.openConnection();
                con.setRequestMethod(method);
                con.setInstanceFollowRedirects(false);
                con.setDoInput(true);
                con.setDoOutput(true);

                con.connect();

                InputStream in = con.getInputStream();
                String strJson = readInputStream(in);
                SharedId = new JSONObject(strJson).getInt("id");
                Log.i(TAG,String.valueOf(SharedId));
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return SharedId;
        }

        @Override
        protected void onPostExecute(Integer id) {
            super.onPostExecute(id);

            logAndSendMessage(TAG, "SharedId: "+String.valueOf(id));
            positionSharing = true;
            running = true;
            SharedId = id;

        }

        public String readInputStream(InputStream in) throws IOException {
            StringBuffer sb = new StringBuffer();
            String st = "";

            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            while ((st = br.readLine()) != null){
                Log.i(TAG,st);
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

    public class registerUserIdAndAccessToGroupV2 extends AsyncTask<Double, Void,Integer>{
        @Override
        protected Integer doInBackground(Double... floats) {
            HttpsURLConnection con = null;
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append("https://peaceful-caverns-31016.herokuapp.com/api/v2/application/register/")
                    .append(String.valueOf(groupId))
                    .append("?lon=")
                    .append(floats[0].toString())
                    .append("&lat=")
                    .append(floats[1].toString())
                    .append("&bea=")
                    .append(floats[2].toString())
                    .append("&spd=")
                    .append(floats[3].toString())
                    .append("&token=")
                    .append(token);
            Log.i(TAG,"URL: " + new String(urlBuilder));
            String method = "POST";
            SharedId = 0;

            try{
                URL url = new URL(new String(urlBuilder));
                con = (HttpsURLConnection) url.openConnection();
                con.setRequestMethod(method);
                con.setInstanceFollowRedirects(false);
                con.setDoInput(true);
                con.setDoOutput(true);

                con.connect();

                InputStream in = con.getInputStream();
                String strJson = readInputStream(in);
                userId  = new JSONObject(strJson).getInt("user_id");
                groupId = new JSONObject(strJson).getInt("group_id");
                Log.i(TAG,String.valueOf(groupId)+"/"+String.valueOf(userId));
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return SharedId;
        }

        @Override
        protected void onPostExecute(Integer id) {
            super.onPostExecute(id);

            logAndSendMessage(TAG, "SharedId: "+String.valueOf(id));
            positionSharing = true;
            running = true;
            SharedId = id;

            Message message = Message.obtain();
            Bundle bundle = new Bundle();
            bundle.putString("user_id",String.valueOf(id));
            message.setData(bundle);
            try {
                replyMessenger.send(message);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

        }

        public String readInputStream(InputStream in) throws IOException {
            StringBuffer sb = new StringBuffer();
            String st = "";

            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            while ((st = br.readLine()) != null){
                Log.i(TAG,st);
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

    public class makeGroupV2 extends AsyncTask<Double, Void,Integer>{
        @Override
        protected Integer doInBackground(Double... doubles) {
            HttpsURLConnection con = null;
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append("https://peaceful-caverns-31016.herokuapp.com/api/v2/application/refPoint/")
                    .append(String.valueOf(userId))
                    .append("?lon=")
                    .append(doubles[0].toString())
                    .append("&lat=")
                    .append(doubles[1].toString())
                    .append("&bea=")
                    .append(doubles[2].toString())
                    .append("&spd=")
                    .append(doubles[3].toString())
                    .append("&token=")
                    .append(token);
            Log.i(TAG,"URL: " + new String(urlBuilder));
            String method = "GET";

            try{
                URL url = new URL(new String(urlBuilder));
                con = (HttpsURLConnection) url.openConnection();
                con.setRequestMethod(method);
                con.setInstanceFollowRedirects(false);
                con.setDoInput(true);
                con.setDoOutput(false);

                con.connect();

                InputStream in = con.getInputStream();

                JSONObject jsonObject = new JSONObject(readInputStream(in));
                groupId = jsonObject.getInt("group_id");

                Message message = Message.obtain();
                Bundle bundle = new Bundle();
                bundle.putInt("group_id",groupId);
                message.setData(bundle);
                replyMessenger.send(message);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (JSONException e) {
                e.printStackTrace();
            } catch (RemoteException e) {
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

    public class updateRefPointV2 extends AsyncTask<Double, Void,Integer>{
        @Override
        protected Integer doInBackground(Double... doubles) {
            HttpsURLConnection con = null;
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append("https://peaceful-caverns-31016.herokuapp.com/api/v2/application/refPoint/")
                    .append(String.valueOf(groupId))
                    .append("/")
                    .append(String.valueOf(userId))
                    .append("?lon=")
                    .append(doubles[0].toString())
                    .append("&lat=")
                    .append(doubles[1].toString())
                    .append("&bea=")
                    .append(doubles[2].toString())
                    .append("&spd=")
                    .append(doubles[3].toString())
                    .append("&token=")
                    .append(token);
            Log.i(TAG,"URL: " + new String(urlBuilder));
            String method = "GET";

            try{
                URL url = new URL(new String(urlBuilder));
                con = (HttpsURLConnection) url.openConnection();
                con.setRequestMethod(method);
                con.setInstanceFollowRedirects(false);
                con.setDoInput(true);
                con.setDoOutput(false);

                con.connect();

                InputStream in = con.getInputStream();

                JSONObject json = new JSONObject(readInputStream(in));
                refPosition.setLatitude(json.getDouble("reference_latitude"));
                refPosition.setLongitude(json.getDouble("reference_longitude"));

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

    public class updateLocationSharing extends AsyncTask<Double, Void,Integer>{
        @Override
        protected Integer doInBackground(Double... doubles) {
            HttpsURLConnection con = null;
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append("https://peaceful-caverns-31016.herokuapp.com/api/v1/application/")
                    .append(SharedId)
                    .append("?lon=")
                    .append(doubles[0].toString())
                    .append("&lat=")
                    .append(doubles[1].toString())
                    .append("&bea=")
                    .append(doubles[2].toString())
                    .append("&spd=")
                    .append(doubles[3].toString())
                    .append("&token=")
                    .append(token);
            Log.i(TAG,"URL: " + new String(urlBuilder));
            String method = "POST";

            try{
                URL url = new URL(new String(urlBuilder));
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

                try{
                    refToken = jsonObject.getString("token");
                    Log.i(TAG,refToken);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            logAndSendMessage(TAG,"refToken="+refToken);
                        }
                    });
                }catch (JSONException e){

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

    public class notifyMyFootstepV2 extends AsyncTask<Integer, Void,Integer>{
        @Override
        protected Integer doInBackground(Integer... integers) {
            HttpsURLConnection con = null;
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append("https://peaceful-caverns-31016.herokuapp.com/api/v2/application/notify/")
                    .append(String.valueOf(groupId)).append("/")
                    .append(String.valueOf(userId));
            Log.i(TAG,"URL: " + new String(urlBuilder));
            String method = "GET";

            urlBuilder.append("?flag=");
            switch (integers[0]){
                case FLAG_INTR_STATE:
                    urlBuilder.append(integers[0] == 0 ? "0000" : integers[0]);
                    break;
                default:
                    urlBuilder.append("0000");
            }
            logAndSendMessage(TAG,"URL: " +urlBuilder.toString());

            try{
                URL url = new URL(new String(urlBuilder));
                con = (HttpsURLConnection) url.openConnection();
                con.setRequestMethod(method);
                con.setInstanceFollowRedirects(false);
                con.setDoInput(true);
                con.setDoOutput(false);

                con.connect();

                InputStream in = con.getInputStream();

                JSONObject jsonObject = new JSONObject(readInputStream(in));
                groupId = jsonObject.getInt("group_id");

                Message message = Message.obtain();
                Bundle bundle = new Bundle();
                bundle.putInt("group_id",groupId);
                message.setData(bundle);
                replyMessenger.send(message);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (JSONException e) {
                e.printStackTrace();
            } catch (RemoteException e) {
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

    public class notifyMyFootstep extends AsyncTask<Void, Void,Integer>{
        @Override
        protected Integer doInBackground(Void... voids) {
            HttpsURLConnection con = null;
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append("https://fcm.googleapis.com/fcm/send");

            Log.i(TAG,"URL: " + new String(urlBuilder));
            String method = "POST";
            try{
                URL url = new URL(new String(urlBuilder));
                con = (HttpsURLConnection) url.openConnection();
                con.setRequestMethod(method);
                con.setInstanceFollowRedirects(false);
                con.setDoInput(true);
                con.setDoOutput(true);
                con.setRequestProperty("Authorization","key=AAAA210pqpM:APA91bFfAogCaB2xesRHJXPzSSaxFyC1X19m9ggy6bA5_fB9yoAqZ1Mzd3-kqjA3JrjJgXefqZm4SrAcGEIotCFNapOl0qBjy0Dtnz6L1FhO8XxWTQGIQ-ZmFHgcmumdLRRlol_Ld25m");
                con.setRequestProperty("Content-Type","application/json");

                JSONObject jsonParam = new JSONObject();
                jsonParam.put("to", refToken);

                DataOutputStream os = new DataOutputStream(con.getOutputStream());
                os.writeBytes(jsonParam.toString());

                os.flush();
                os.close();

                InputStream in = con.getInputStream();
                readInputStream(in);

                con.disconnect();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            con.disconnect();

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

    public class getRoute extends AsyncTask<String, Void,JSONObject>{

        @Override
        protected JSONObject doInBackground(String... params) {
            Log.i(TAG,params[0]);
            HttpsURLConnection con = null;
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append("https://maps.googleapis.com/maps/api/directions/json?origin=")
                    .append(params[0])
                    .append("&destination=")
                    .append(params[1])
                    .append("&mode=walking&key=%20AIzaSyAz6oV0_57kPlmxfcP2TZ2oJXAO9d3mAzw");
            Log.i(TAG,"URL: " + new String(urlBuilder));
            String method = "POST";

            try{
                URL url = new URL(new String(urlBuilder));
                con = (HttpsURLConnection) url.openConnection();
                con.setRequestMethod(method);
                con.setInstanceFollowRedirects(false);
                con.setDoInput(true);
                con.setDoOutput(true);

                con.connect();

                InputStream in = con.getInputStream();
                String strJson = readInputStream(in);
//                Log.i(TAG,strJson);
                return new JSONObject(strJson);
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

        @Override
        protected void onPostExecute(JSONObject jsonObject) {
            super.onPostExecute(jsonObject);

            try {
                if (!Objects.equals(jsonObject.getString("status"), "OVER_QUERY_LIMIT")){
                    double lat = Double.valueOf(jsonObject.getJSONArray("routes").getJSONObject(0).getJSONArray("legs").getJSONObject(0).getJSONArray("steps").getJSONObject(0).getJSONObject("end_location").getString("lat"));
                    double lng = Double.valueOf(jsonObject.getJSONArray("routes").getJSONObject(0).getJSONArray("legs").getJSONObject(0).getJSONArray("steps").getJSONObject(0).getJSONObject("end_location").getString("lng"));
                    Log.i(TAG,"Point:"+String.valueOf(lat)+","+String.valueOf(lng));
                    route[routeId][0]=lat;
                    route[routeId][1]=lng;
                    Location loc = new Location("");
                    loc.setLatitude(lat);
                    loc.setLongitude(lng);
                    route[routeId][2]=currentLocation.bearingTo(loc);
                    logAndSendMessage(TAG,"Dir:"+String.valueOf(currentLocation.bearingTo(loc)));
                    routeId = (routeId+1)%4;
                    if (routeId==0){
                        Log.i(TAG,"RouteArray");
                        Log.i(TAG,route.toString());

                        int NofRoute = 1;
                        for( int i = 0;i<2;i++){
                            for(int j=0;j<1;j++){
                                for (int k=i;k<2;k++) {
                                    for (int l = j; j < 2; j++) {
                                        if (route[i][j] != route[k][l]) {
                                            NofRoute++;
                                        }
                                    }
                                }
                            }
                        }

//                         TODO ble
//                        if (bluetoothGatt != null && connected){
//                            Boolean successed = false;
//
                            byte[] buffer = new byte[3];
                            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
                            byteBuffer.put((byte) FLAG_ROUTE_DIR);
                            byte bRouteDir0 = 0x00;
                            byte bRouteDir1 = 0x00;
                            for (int i = 0;i<4;i++) {
                                if (route[i][2] < 0) {
                                    route[i][2] += 360;
                                }
                            }
                            bRouteDir0 = (byte) ((((byte) ((int) route[0][2] * 16 / 360) & 0x0F) << 4) | (((byte) ((int) route[1][2] * 16 / 360) & 0x0F)));
                            bRouteDir1 = (byte) ((((byte) ((int) route[2][2] * 16 / 360) & 0x0F) << 4) | (((byte) ((int) route[3][2] * 16 / 360) & 0x0F)));
                            byteBuffer.put(bRouteDir0).put(bRouteDir1);

                            sendBle(buffer);
//
//                            logAndSendMessage(TAG, "(byte) RouteDir:"+new String(buffer));
//                            for (byte b : buffer) {
//                                logAndSendMessage(TAG, String.valueOf(b & 0xff));
//                                System.out.print(" ");
//                            }
//
//                            if (routeDirectionNeeded){
//                                bluetoothGattService = bluetoothGatt.getService(UUID.fromString(UART_SERVICE));
//                                bluetoothGattCharacteristic = bluetoothGattService.getCharacteristic(UUID.fromString(UART_WRITE));
//                                bluetoothGattCharacteristic.setValue(buffer);
//                                bluetoothGattCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
//                                //
//                                if (!successed){
//                                    logAndSendMessage(TAG,"Write:   "+new String(buffer));
//                                    successed = bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic);
//                                }
//                            }
//                        }

                    }
                } else {
                    Log.e(TAG,"OVER_QUERY_LIMIT");
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        public String readInputStream(InputStream in) throws IOException {
            StringBuffer sb = new StringBuffer();
            String st = "";

            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            while ((st = br.readLine()) != null){
//                Log.i(TAG,st);
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



//    RxAndroidBLE関連のメソッド


    private void clearSubscription() {
        Log.i(TAG,"clearSubscription");
        scanSubscription = null;
//        Subscription subscription = (Subscription) connectionObservable;
//        subscription.unsubscribe();
//        resultsAdapter.clearScanResults();
//        updateButtonUIState();
    }

    private void onScanFailure(Throwable throwable) {
        Log.i(TAG,"onScanFailure:" + throwable.getMessage());
        if (throwable instanceof BleScanException) {
            handleBleScanException((BleScanException) throwable);
        }
    }

    private void onConnectionFailure(Throwable throwable) {
        Log.i(TAG,"onConnectionFailure: "+ throwable.getMessage());
        //noinspection ConstantConditions
//        Snackbar.make(findViewById(android.R.id.content), "Connection error: " + throwable, Snackbar.LENGTH_SHORT).show();
    }

    private void handleBleScanException(BleScanException bleScanException) {
        final String text;

        switch (bleScanException.getReason()) {
            case BleScanException.BLUETOOTH_NOT_AVAILABLE:
                text = "Bluetooth is not available";
                break;
            case BleScanException.BLUETOOTH_DISABLED:
                text = "Enable bluetooth and try again";
                break;
            case BleScanException.LOCATION_PERMISSION_MISSING:
                text = "On Android 6.0 location permission is required. Implement Runtime Permissions";
                break;
            case BleScanException.LOCATION_SERVICES_DISABLED:
                text = "Location services needs to be enabled on Android 6.0";
                break;
            case BleScanException.SCAN_FAILED_ALREADY_STARTED:
                text = "Scan with the same filters is already started";
                break;
            case BleScanException.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                text = "Failed to register application for bluetooth scan";
                break;
            case BleScanException.SCAN_FAILED_FEATURE_UNSUPPORTED:
                text = "Scan with specified parameters is not supported";
                break;
            case BleScanException.SCAN_FAILED_INTERNAL_ERROR:
                text = "Scan failed due to internal error";
                break;
            case BleScanException.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES:
                text = "Scan cannot start due to limited hardware resources";
                break;
            case BleScanException.UNDOCUMENTED_SCAN_THROTTLE:
                text = "UNDOCUMENTED_SCAN_THROTTLE";
                break;
            case BleScanException.UNKNOWN_ERROR_CODE:
            case BleScanException.BLUETOOTH_CANNOT_START:
            default:
                text = "Unable to start scanning";
                break;
        }
        Log.w("EXCEPTION", text, bleScanException);
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }


    private rx.Observable<RxBleConnection> prepareConnectionObservable(){
//private Subscription prepareConnectionObservable(){
        Log.i(TAG,"prepareConnectionObservable");
        return rxBleDevice
                .establishConnection(true)
                .takeUntil(disconnectTriggerSubject)
                .compose(new ConnectionSharingAdapter());
    }

    private void onConnectionFinished() {
        Log.i(TAG,"onConnectionFinished");
        prepareConnectionObservable()
                .flatMap(rxBleConnection -> rxBleConnection.setupNotification(UUID.fromString(UART_READ))
                        .flatMap(bytes -> rxBleConnection.writeCharacteristic(UUID.fromString(UART_WRITE),getInputBytes())))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(bytes -> onWriteSuccess(),
                        this::onWriteFailure
                );

    }

    private void onReadFailure(Throwable throwable) {
        String text = "onReadFailure:" +  throwable.getMessage();
        logAndSendMessage(TAG,text);

    }

    private void onWriteFailure(Throwable throwable){
        String text = "onWriteFailure:" +  throwable.getMessage();
        logAndSendMessage(TAG,text);
    }
    private void onWriteSuccess(){
        String text = "onWriteSuccess";
        logAndSendMessage(TAG,text);
    }

    private byte[] getInputBytes() {
        return HexString.hexToBytes("Test");
    }

    private void notificationHasBeenSetUp(){
        String text = "notification has been setup";
        logAndSendMessage(TAG,text);

    }

    private void onNotificationReceived(byte[] bytes) {
        String text = "notification received:" +  new String(bytes);
        logAndSendMessage(TAG,text);
        Pattern p = Pattern.compile("(IntrState:)(\\d+)");
        Matcher m = p.matcher(new String(bytes));
        logAndSendMessage(TAG,new String(bytes));
        if (m.find()){
            logAndSendMessage(TAG,"Found!!!");
            logAndSendMessage(TAG,"IntrState:" + m.group(2));
            logAndSendMessage(TAG,"State" + m.group(2));
            Integer integers[] = {FLAG_INTR_STATE,Integer.parseInt(m.group(2))} ;
            new notifyMyFootstepV2().execute(integers);
        }
    }

    private void onNotificationSetupFailure(Throwable throwable) {
        String text = "notification failure:" +  throwable.getMessage();
        logAndSendMessage(TAG,text);

//        compositeSubscription.add(scanSubscription);

        compositeSubscription.add(
                connectionObservable
                .flatMap(rxBleConnection -> rxBleConnection.setupNotification(UUID.fromString(UART_READ)))
                .doOnNext(notificationObservable -> runOnUiThread(this::notificationHasBeenSetUp))
                .flatMap(notificationObservable -> notificationObservable)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onNotificationReceived, this::onNotificationSetupFailure)
//                        .subscribe(this::onNotificationReceived)
        );
    }

    private void runOnUiThread(Runnable runnable){
        bleHandler.post(runnable);
    }

    private boolean sendBle(byte[] buffer){
        if (rxBleDevice!= null && rxBleDevice.getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTED){

            logAndSendMessage(TAG, new String(buffer));
            for (byte b : buffer) {
                logAndSendMessage(TAG, String.valueOf(b & 0xff));
                System.out.print(" ");
            }
            compositeSubscription.add(
                    connectionObservable
                            .flatMap(rxBleConnection -> rxBleConnection.writeCharacteristic(UUID.fromString(UART_WRITE),buffer))
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(
                                    bytes -> onWriteSuccess(),
                                    throwable -> {
                                        Log.i(TAG,"Write failure: " + throwable.getMessage());
                                    }
                            )
            );
            return true;
        }else{
            logAndSendMessage(TAG,"Something will be sent, but connection is disable.");
            return false;
        }
    }

    private void recconectBle(){
        compositeSubscription.clear();
        compositeSubscription.add(scanSubscription);
    }
    //    RxAndroidBLE関連のメソッドおわり

    //    センサー取得用イベントリスナ

    SensorEventListener sensorEventListener = new SensorEventListener() {

        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            sensorValueX = sensorEvent.values[0];
            sensorValueY = sensorEvent.values[1];
            sensorValueZ = sensorEvent.values[2];
            sensorAccuracy = sensorEvent.accuracy;
            if (sensorCnt++ > 100){
                sensorCnt = 0;
                logAndSendMessage(TAG,"ValueX: " + String.valueOf(sensorValueX));
                logAndSendMessage(TAG,"ValueX: " + String.valueOf(sensorValueY));
                logAndSendMessage(TAG,"Accuracy: " + String.valueOf(sensorAccuracy));
            };
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {
            float compassBearing = (float) ((float) Math.atan2(sensorValueY,sensorValueX) * 180 / - Math.PI);
//            float compassBearing = sensorManager.get

            logAndSendMessage(TAG,"ValueX: " + String.valueOf(sensorValueX));
            logAndSendMessage(TAG,"ValueY: " + String.valueOf(sensorValueY));

            logAndSendMessage(TAG,"Compass: " + String.valueOf(compassBearing));
            logAndSendMessage(TAG,"Accuracy: " + String.valueOf(sensorAccuracy));

            if (i == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM){
                logAndSendMessage(TAG,"Accuracy OK: ");

                compassBearing = compassBearing > 0 ? compassBearing : compassBearing + 360 ;
                byte bCompassBearing =(byte)(((int)(compassBearing / 360 * 256))& 0xff);

                byte[] buffer = new byte[2];
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
                byteBuffer.put((byte) MainActivity.F_SEND_COMPASS);
                byteBuffer.put(bCompassBearing);

                sendBle(buffer);
                if (sensorManager!= null){
                    sensorManager.unregisterListener(this);
                }
            }

        }
    };
    //    センサー取得用イベントリスナおわり

}
