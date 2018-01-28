package com.example.issei.navigator2;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
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
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.iid.FirebaseInstanceId;


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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.net.ssl.HttpsURLConnection;


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
    static final int ADD_LOG = 2;
    static final int BLE_DATA = 3;
    Messenger messenger = new Messenger(new IncomingHandler());
    Messenger bleServiceMessenger;
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
    private boolean isBleServiceBound = false;
    private static final byte COMMAND_INITIALIZE = 0;

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

            if (msg.getData().getString("user_id") != null) {
                userId = Integer.parseInt(msg.getData().getString("user_id"));
            }

            switch (msg.what){
                case MESSAGE:
                    Bundle bundle = msg.getData();
                    replyMessenger = msg.replyTo;
//                logAndSendMessage(TAG, String.valueOf(bundle));
                    break;
                case ADD_LOG:
                    logAndSendMessage(MainApplication.TAG,msg.getData().getString("text"));
                    break;
                case BLE_DATA:
                    byte[] data = msg.getData().getByteArray("data");
                    if (data != null){
                        logAndSendMessage(MainApplication.TAG,new String(data));
                        byte flag = data[0];
                        logAndSendMessage(MainApplication.TAG,Integer.toHexString(data[0]));
                        switch (flag){
                            case (byte) 0x49:  // "I" (リードスイッチ読取)
                            Byte[] byteList = BLEService.bleDataByte(data);
                                sendBle(data);
                            shareReedV2 sr = new shareReedV2();
                            sr.execute(byteList);
                        }
                    }
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

            int flag = intent.getIntExtra("flag",0);
            switch (flag){
                case 1: // ReedSwitch
                    byte[] dataArr = intent.getByteArrayExtra("arrData");
                    sendBle(createReedData(dataArr));
                    break;
            }
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

        application = (MainApplication) this.getApplication();
        startBleService();

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

        stopBleSubscription();
        if (thisHandler!=null){
            thisHandler.removeCallbacksAndMessages(showStatusRunnable);
        }
        if (serviceHandler!=null){
            serviceHandler.removeCallbacksAndMessages(null);
        }
        if (mainHandler!=null){
            mainHandler.removeCallbacksAndMessages(null);
        }
        if (googleApiClient.isConnected()){
            googleApiClient.disconnect();
        }
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

    public class shareReedV2 extends AsyncTask<Byte, Void,Integer>{
        @Override
        protected Integer doInBackground(Byte... bytes) {
            HttpsURLConnection con = null;
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append("https://peaceful-caverns-31016.herokuapp.com/api/v2/application/shareReed/")
                    .append(String.valueOf(groupId)).append("/")
                    .append(String.valueOf(userId)).append("/")
                    .append(Arrays.toString(bytes));
            Log.i(TAG,"URL: " + new String(urlBuilder));
            String method = "GET";

            urlBuilder.append("?flag=0001");

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

    ServiceConnection bleServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            bleServiceMessenger = new Messenger(iBinder);
            isBleServiceBound = true;
            Message message = Message.obtain(null,BLEService.MESSENGER_REG_NAV,0,0);
            message.replyTo = messenger;
            try {
                bleServiceMessenger.send(message);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            bleServiceMessenger = null;
            isBleServiceBound = false;
        }
    };


    private void startBleService(){
        Intent intent = new Intent(getApplicationContext(),BLEService.class);
        bindService(intent,bleServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void stopBleSubscription(){
        unbindService(bleServiceConnection);
    }


    private boolean sendBle(byte[] buffer){

        if (isBleServiceBound){
            Log.i(MainApplication.TAG,"send to BLEService: " + new String(buffer));
            Message message = Message.obtain(null,BLEService.MESSENGER_BLE_SEND,1,1);
            message.what = BLEService.MESSENGER_BLE_SEND;
            message.replyTo = replyMessenger;

            Bundle bundle = new Bundle();
            bundle.putByteArray("sendBleData",buffer);

            message.setData(bundle);
            try {
                bleServiceMessenger.send(message);
                return true;
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

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

    //  リードスイッチ振動共有

    //byte[]型のデータにリードスイッチ用フラグを追加する
    private byte[] createReedData(byte[] data){
        int len = data.length;
        byte[] returnData = new byte[len+1];
        returnData[0] = Byte.parseByte("I");
        System.arraycopy(data, 0, returnData, 1, len);
        return returnData;
    }

    // byte[]型のリードスイッチデータを時系列データに変換する
    private ArrayList<Byte> dataToSwitchLog(byte[] dataArr){
        ArrayList<Byte> byteArrayList = new ArrayList<>();
        for (byte  data : dataArr) {
            int duration = data & 0x00001111;
            for (int cnt = 0;cnt<duration;cnt++){
                byteArrayList.add((byte) (data & 0x11110000));
            }
        }
        return byteArrayList;
    }

    // 2つの時系列データをフィルタ処理する
    static final int RAW_FILTER = 0;
    static final int AND_FILTER = 1;
    static final int OR_FILTER = 2;
    static final int NOR_FILTER = 3;
    static final int NAND_FILTER = 4;
    static final int XOR_FILTER = 5;

    private ArrayList<Byte> filterBetween(ArrayList<Byte> arrayList1, ArrayList<Byte> arrayList2, int method){
        int size1 = arrayList1.size();
        int size2 = arrayList2.size();
        int length = 0;
        // 2つのログのサイズ調整（小さい方は繰り返す）
        if (size1 > size2){
            for (int cnt = 0;cnt< size1 -size2;cnt++){
                arrayList2.add(arrayList2.get(cnt));
            }
            length = size1;
        }else if(size1 < size2){
            for (int cnt = 0;cnt< size2 -size1;cnt++){
                arrayList1.add(arrayList1.get(cnt));
            }
            length = size2;
        }else{
            length = size1;
        }

        ArrayList<Byte> filteredArray = new ArrayList<Byte>();
        for (int cnt = 0;cnt<length;cnt++){
            switch (method){
                case AND_FILTER:
                    filteredArray.add((byte) (arrayList1.get(cnt) & arrayList2.get(cnt)));
                    break;
                case OR_FILTER:
                    filteredArray.add((byte) (arrayList1.get(cnt) | arrayList2.get(cnt)));
                    break;
                case XOR_FILTER:
                    filteredArray.add((byte) (arrayList1.get(cnt) ^ arrayList2.get(cnt)));
                    break;
                default:
                    filteredArray.add((byte) 0);
            }
        }
        return filteredArray;
    }

    // ArrayList<Byte> をbyte[]に変換する
    private byte[] arrayListByteToByteArray(ArrayList<Byte> arrayList){
        int size = arrayList.size();
        byte[] byteArray = new byte[size];
        for (int i = 0;i<size;i++){
            byteArray[i] = arrayList.get(i);
        }
        return byteArray;
    }



    // リードスイッチ振動共有おわり

}
