package com.example.issei.navigator2;


import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.Toast;

import com.google.firebase.iid.FirebaseInstanceId;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.exceptions.BleScanException;
import com.polidea.rxandroidble.internal.RxBleLog;
import com.polidea.rxandroidble.scan.ScanFilter;
import com.polidea.rxandroidble.scan.ScanSettings;
import com.polidea.rxandroidble.utils.ConnectionSharingAdapter;
import com.trello.rxlifecycle.components.support.RxAppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.subjects.PublishSubject;

import static com.trello.rxlifecycle.android.ActivityEvent.PAUSE;

public class MainActivity extends RxAppCompatActivity {
    SharedPreferences sharedPreferences;
    static final int userIdNotRegistered = 0;
    int userId = userIdNotRegistered;
    int groupId;

    Handler handler;
    Handler bleHandler;
    static int SCAN_PERIOD = 50000;

    static String TAG = "Navigator2";
    static int REQUEST_ENABLE_BT = 0x001;
    static int REQUEST_ENABLE_LOCATION = 0x002;
    static final String UART_SERVICE  = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    static final String UART_WRITE    = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    static final String UART_READ     = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";

    static final int FLAG_CURRENT_LOCATION = 0;
    static final int FLAG_BEARING = 1;
    static final int FLAG_LOCATION_BEARING = 2;
//    GoogleApiClient googleApiClient;
    boolean SetRefPosition = false;
    Location currentLocation;
    Location YNU = new Location("");
    Location refPosition;
    float fBearing = 180;
    float bearing  = 0;

    EditText editTextLat;
    EditText editTextLong;
    Button   btnSetLocation;
    ListView logArea;
    public ArrayAdapter logListAdapter;

    EditText editTextId;
    Button btnGetLoc;
    Button btnGetShareId;
    int    sharingMode;
    boolean registered;
    Button btnButton2;
    boolean service = false;
    public Switch switchConnectDevice;
    Switch serviceStatusSwitch;
    Switch roadDirectoinNeededSwitch;
    Button btnSendFootStep;

    JSONObject jsonObject;
    int id=0;

    static final int F_SWITCH_SERVICE = 0;
    static final int F_SET_DYNAMIC_REFERENCE = 1;
    static final int F_SET_STATIC_REFERENCE = 2;
    static final int F_SET_SHARE_POINT = 3;

    Messenger messenger;
    boolean isBound;

    String refToken;

    ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            isBound = true;
            messenger = new Messenger(iBinder);

            sendMessage("user_id",String.valueOf(userId));
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            isBound = false;
        }
    };

    Messenger replyMessenger = new Messenger(new HandlerReplyMsg());

    Subscription scanSubscription;
    RxBleDevice rxBleDevice;
    boolean isConnected = false;
    boolean found = false;
    private PublishSubject<Void> disconnectTriggerSubject = PublishSubject.create();
    UUID characteristicUuid;
    private Observable<RxBleConnection> connectionObservable;

    class HandlerReplyMsg extends Handler{
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg.obj != null){
                String receivedMessage = msg.obj.toString();
//            String receivedMessage = msg.getData()
                Log.i(TAG,receivedMessage);
                logListAdapter.add(receivedMessage);
                logArea.smoothScrollToPosition(logListAdapter.getCount()-1);

                if (receivedMessage.matches("SharedId:.*")){
                    Pattern p = Pattern.compile("[0-9]");
                    Matcher m = p.matcher(receivedMessage);

                    int id = 0;
                    int cnt= 0;
                    int[] arrId = new int[10];
                    while (m.find()) {
                        arrId[cnt] = Integer.parseInt(m.group());
                        cnt++;
                    }
                    Log.i(TAG, String.valueOf(arrId));
                    for (int i=0;i<cnt;i++){
                        id += arrId[i] * Math.pow(10,cnt-1-i);
                    }
                    Log.i(TAG, String.valueOf(id));

                    try{
                        editTextId.setText(String.valueOf(id));
                    }catch (IllegalStateException e){

                    }
                } else if(receivedMessage.matches("refToken=.+")){
                    Pattern p = Pattern.compile("refToken=(.+)");
                    Matcher m = p.matcher(receivedMessage);

                    Log.i(TAG, String.valueOf(m.matches()));
                    Log.i(TAG, String.valueOf(m.groupCount()));
                    Log.i(TAG, String.valueOf(m.group(1)));
                    refToken = String.valueOf(m.group(1));

                    switchConnectDevice.setChecked(true);
                }
            }
            if(msg.getData().getInt("user_id",0) != 0){
                userId = msg.getData().getInt("user_id");
                sharedPreferences.edit().putInt(getString(R.string.user_id),userId).apply();
            }
            if(msg.getData().getInt("group_id",0) != 0){
                groupId = msg.getData().getInt("group_id");
                editTextId.setText(String.valueOf(groupId));
            }
        }
    }

    public void sendMessage(String key, String value){
        if (isBound){
            try {
                Message message = Message.obtain(null, NavigatorService.MESSAGE,1,1);
                message.replyTo = replyMessenger;

                Bundle bundle = new Bundle();
                bundle.putString(key, value);
                message.setData(bundle);

                messenger.send(message);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        int REQUEST_ENABLE_BT = 1;
        this.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);


        sharedPreferences = this.getPreferences(Context.MODE_PRIVATE);
        userId = sharedPreferences.getInt(getString(R.string.user_id),userIdNotRegistered);

        String refreshedToken = FirebaseInstanceId.getInstance().getToken();
        Log.i(TAG,"Refreshed token: "+refreshedToken);

        handler = new Handler();
        refPosition = new Location("");

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            finish();
        };

        editTextLat = (EditText) findViewById(R.id.editTextLat);
        editTextLong = (EditText) findViewById(R.id.editTextLong);
        btnSetLocation = (Button) findViewById(R.id.buttonSetLoc);
        logArea = (ListView) findViewById(R.id.logArea);
        logListAdapter = new ArrayAdapter<String>(this,R.layout.log_area);
        logArea.setAdapter(logListAdapter);
        logListAdapter.add(TAG + ": onCreate");


        serviceStatusSwitch = findViewById(R.id.ServiceStatusSwitch);
//        Intent intent = new Intent(MainActivity.this,NavigatorService.class);
//        intent.putExtra("Flag",F_SWITCH_SERVICE);
//        startService(intent);
//        bindService(intent,serviceConnection, Context.BIND_AUTO_CREATE);
//        serviceStatusSwitch.setChecked(true);
//        serviceStatusSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
//            @Override
//            public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
//                if (checked){
//                    startService(intent);
//                    bindService(intent,serviceConnection, Context.BIND_AUTO_CREATE);
//                }else{
//                    unbindService(serviceConnection);
//                    stopService(intent);
//                }
//            }
//        });

//        connectionObservable = prepareConnectionObservable();

        RxBleClient rxBleClient = RxBleClient.create(this);
        RxBleClient.setLogLevel(RxBleLog.DEBUG);
        scanSubscription = rxBleClient.scanBleDevices(
                new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                .build(),
                new ScanFilter.Builder()
                .setDeviceName("UART Service")
                .build()
        )
                .observeOn(AndroidSchedulers.mainThread())
                .doOnUnsubscribe(this::clearSubscription)
                .subscribe(rxBleScanResult -> {
                    if (!found){
                        Log.i(TAG,"device registered");
                        found = true;
                        rxBleDevice = rxBleClient.getBleDevice(rxBleScanResult.getBleDevice().getMacAddress());
                        if (!isConnected){
                            Log.i(TAG,"connecting...");

                            connectionObservable = prepareConnectionObservable();

//                            prepareConnectionObservable()
////                                    .flatMap(RxBleConnection::discoverServices)
////                                    .flatMap(rxBleDeviceServices -> rxBleDeviceServices.getCharacteristic(characteristicUuid))
//                                    .observeOn(AndroidSchedulers.mainThread())
//                                    .subscribe(
//                                            bluetoothGattCharacteristic -> {
//                                                Log.i(TAG,"connection has been established");
//                                            },
//                                            this::onConnectionFailure,
//                                            this::onConnectionFinished
//                                    );

//                            prepareConnectionObservable()
//                                    .flatMap(rxBleConnection -> rxBleConnection.readCharacteristic(UUID.fromString(UART_READ))
//                                            .flatMap(bytes -> rxBleConnection.writeCharacteristic(UUID.fromString(UART_WRITE), getInputBytes())))
//                                    .observeOn(AndroidSchedulers.mainThread())
//                                    .subscribe(bytes -> {
//                                        Log.i(TAG,"Received: " + new String(bytes));
//                                    },this::onReadFailure);
////                                    .subscribe(bytes -> onWriteSuccess(),
////                                            this::onWriteFailure
//                                    );
// うまく受信できたやつ
                            connectionObservable
                                    .flatMap(rxBleConnection -> rxBleConnection.setupNotification(UUID.fromString(UART_READ)))
                                    .doOnNext(notificationObservable -> runOnUiThread(this::notificationHasBeenSetUp))
                                    .flatMap(notificationObservable -> notificationObservable)
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .subscribe(this::onNotificationReceived, this::onNotificationSetupFailure);

//                            if (rxBleDevice.getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTED){
                                connectionObservable
                                        .flatMap(rxBleConnection -> rxBleConnection.writeCharacteristic(UUID.fromString(UART_WRITE),"test".getBytes()))
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(
                                                bytes -> onWriteSuccess(),
                                                throwable -> {
                                                    Log.i(TAG,"Write failure: " + throwable.getMessage());
                                                }
                                        );
//                            }

//                            connectionObservable
//                                    .flatMap(rxBleConnection -> rxBleConnection.readCharacteristic(UUID.fromString(UART_READ)))
//                                    .observeOn(AndroidSchedulers.mainThread())
//                                    .subscribe(bytes -> {
//                                        Log.i(TAG,"Received: " + new String(bytes));
//                                    },this::onReadFailure);

                        } else {
                            Log.i(TAG,"Connected");
                            isConnected = true;
                        }
                    }
                }, this::onScanFailure);



//        scanSubscription.unsubscribe();

        roadDirectoinNeededSwitch = findViewById(R.id.switchRoadDirNeeded);
        roadDirectoinNeededSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                if (b){
                    sendMessage("command","Route:ON");
                }else {
                    sendMessage("command","Route:OFF");
                }
            }
        });

        switchConnectDevice = findViewById(R.id.switchConnectDevice);
        switchConnectDevice.setClickable(false);

        btnSendFootStep = findViewById(R.id.btnSendFS);
        btnSendFootStep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (groupId != 0){
                    sendMessage("command","notifyFootStep");
//                    new notifyMyFootstepV2().execute();
                }else{
                    Toast.makeText(MainActivity.this, "まずは共有先を登録", Toast.LENGTH_SHORT).show();
                }
            }
        });

        editTextId = (EditText) findViewById(R.id.idArea);
        btnGetLoc = (Button) findViewById(R.id.btnGetLoc);
        btnGetLoc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sharingMode = 0;
                if (!editTextId.getText().equals("") ){
                    id = Integer.parseInt(String.valueOf(editTextId.getText()));
                    Intent intent = new Intent(MainActivity.this,NavigatorService.class);
                    intent.putExtra("Flag",F_SET_DYNAMIC_REFERENCE);
                    intent.putExtra("id",id);
                    startService(intent);

//                    final Runnable runnable = new Runnable() {
//                        @Override
//                        public void run() {
//                            new getReferencePosition().execute();
//                            handler.postDelayed(this,5000);
//                        }
//                    };
//                    handler.post(runnable);
                    SetRefPosition = true;
                }else{
                    Toast.makeText(MainActivity.this, "指定のidから目的地を指定します", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnGetShareId =findViewById(R.id.btnGetShareId);
        btnGetShareId.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this,NavigatorService.class);
                intent.putExtra("Flag",F_SET_SHARE_POINT);
                startService(intent);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound){
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    public class notifyMyFootstepV2 extends AsyncTask<Double, Void,Integer>{
        @Override
        protected Integer doInBackground(Double... doubles) {
            HttpsURLConnection con = null;
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append("https://peaceful-caverns-31016.herokuapp.com/api/v2/application/notify/")
                    .append(String.valueOf(groupId));
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

//                InputStream in = con.getInputStream();

//                JSONObject jsonObject = new JSONObject(readInputStream(in));
//                groupId = jsonObject.getInt("group_id");
//
//                Message message = Message.obtain();
//                Bundle bundle = new Bundle();
//                bundle.putInt("group_id",groupId);
//                message.setData(bundle);
//                replyMessenger.send(message);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
//            } catch (JSONException e) {
//                e.printStackTrace();
//            } catch (RemoteException e) {
//                e.printStackTrace();
            }
            return null;
        }

        public String readInputStream(InputStream in) throws IOException {
            StringBuffer sb = new StringBuffer();
            String st = "";

            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            while ((st = br.readLine()) != null){
//                logAndSendMessage(TAG,st);
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

    private void requestBLEEnable(){
        Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
    }

    public class registerLocationSharing extends AsyncTask<Double, Void,Integer>{
        @Override
        protected Integer doInBackground(Double... floats) {
            HttpsURLConnection con = null;
            String urlSt = "https://peaceful-caverns-31016.herokuapp.com/api/v1/application/registration?lon="
                    + floats[0].toString()
                    +"&lat="
                    + floats[1].toString()
                    +"&bea="
                    + floats[2].toString()
                    +"&spd="
                    + floats[3].toString();
            Log.i(TAG,"URL: " + urlSt);
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
                String strJson = readInputStream(in);
                id = new JSONObject(strJson).getInt("id");
                Log.i(TAG,String.valueOf(id));
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
        protected void onPostExecute(Integer integer) {
            super.onPostExecute(integer);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    editTextId.setText(String.valueOf(id));
                }
            });

            Intent intent = new Intent(MainActivity.this,NavigatorService.class);
            intent.putExtra("Flag",F_SET_SHARE_POINT);
            intent.putExtra("id",id);
            startService(intent);
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

    public class updateLocationSharing extends AsyncTask<Double, Void,Integer>{
        @Override
        protected Integer doInBackground(Double... doubles) {
            HttpsURLConnection con = null;
            String urlSt = "https://peaceful-caverns-31016.herokuapp.com/api/v1/application/"
                    +String.valueOf(id)
                    +"?lon="
                    + doubles[0].toString()
                    +"&lat="
                    + doubles[1].toString()
                    +"&bea="
                    + doubles[2].toString()
                    +"&spd="
                    + doubles[3].toString();
            Log.i(TAG,"URL: " + urlSt);
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

    public class getReferencePosition extends AsyncTask<Void, Void,Integer>{
        @Override
        protected Integer doInBackground(Void... voids) {
            HttpsURLConnection con = null;
            String urlSt = "https://peaceful-caverns-31016.herokuapp.com/api/v1/application/"
                    +String.valueOf(id);
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
                refPosition.setLatitude(jsonObject.getDouble("latitude"));
                refPosition.setLongitude(jsonObject.getDouble("longitude"));
                refPosition.setBearing((float) jsonObject.getDouble("bearing"));
                refPosition.setSpeed((float) jsonObject.getDouble("speed"));

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

    private void clearSubscription() {
        Log.i(TAG,"clearSubscription");
        scanSubscription = null;
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
//                text = String.format(
//                        Locale.getDefault(),
//                        "Android 7+ does not allow more scans. Try in %d seconds",
//                        "Android 7+ does not allow more scans. Try in %d seconds",
//                        secondsTill(bleScanException.getRetryDateSuggestion())
//                );
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

//    private boolean isConnected(){
//        Log.i(TAG,"isConnected");
//        return rxBleDevice.getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTED;
//    }

    private rx.Observable<RxBleConnection> prepareConnectionObservable(){
        return rxBleDevice
                .establishConnection(true)
                .takeUntil(disconnectTriggerSubject)
                .compose(bindUntilEvent(PAUSE))
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
        Log.e(TAG,text);
        logListAdapter.add(text);
        logArea.smoothScrollToPosition(logListAdapter.getCount()-1);


        //noinspection ConstantConditions
//        Snackbar.make(findViewById(R.id.main), "Read error: " + throwable, Snackbar.LENGTH_SHORT).show();
    }

    private void onWriteFailure(Throwable throwable){
        String text = "onWriteFailure:" +  throwable.getMessage();
        Log.e(TAG,text);
        logListAdapter.add(text);
        logArea.smoothScrollToPosition(logListAdapter.getCount()-1);
    }
    private void onWriteSuccess(){
        String text = "onWriteSuccess";
        logListAdapter.add(text);
        logArea.smoothScrollToPosition(logListAdapter.getCount()-1);
        Log.i(TAG,text);
    }

    private byte[] getInputBytes() {
        return HexString.hexToBytes("Test");
    }

    private void notificationHasBeenSetUp(){
        String text = "notificatiion has beem setup";
        Log.i(TAG,text);
        logListAdapter.add(text);
        logArea.smoothScrollToPosition(logListAdapter.getCount()-1);

    }

    private void onNotificationReceived(byte[] bytes) {
        String text = "notification received:" +  new String(bytes);
        Log.i(TAG,text);
        logListAdapter.add(text);
        logArea.smoothScrollToPosition(logListAdapter.getCount()-1);
        //noinspection ConstantConditions
//        Snackbar.make(findViewById(R.id.main), "Change: " + HexString.bytesToHex(bytes), Snackbar.LENGTH_SHORT).show();
    }

    private void onNotificationSetupFailure(Throwable throwable) {
        String text = "notificatiion failure:" +  throwable.getMessage();
        Log.e(TAG,text);
        logListAdapter.add(text);
        logArea.smoothScrollToPosition(logListAdapter.getCount()-1);

        connectionObservable
                .flatMap(rxBleConnection -> rxBleConnection.setupNotification(UUID.fromString(UART_READ)))
                .doOnNext(notificationObservable -> runOnUiThread(this::notificationHasBeenSetUp))
                .flatMap(notificationObservable -> notificationObservable)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onNotificationReceived, this::onNotificationSetupFailure);
        //noinspection ConstantConditions
//        Snackbar.make(findViewById(R.id.main), "Notifications error: " + throwable, Snackbar.LENGTH_SHORT).show();
    }
}
