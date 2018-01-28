package com.example.issei.navigator2;


import android.*;
import android.Manifest;
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
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static com.trello.rxlifecycle.android.ActivityEvent.PAUSE;

public class MainActivity extends AppCompatActivity {
    SharedPreferences sharedPreferences;
    static final int userIdNotRegistered = 0;
    int userId = userIdNotRegistered;
    int groupId;

    private static final int LOCATION_REQUEST_CODE = 34;

    Handler handler;
    Handler bleHandler;
    static int SCAN_PERIOD = 50000;

    static String TAG = "Navigator2";
    static int REQUEST_ENABLE_BT = 0x001;
    static int REQUEST_ENABLE_LOCATION = 0x002;

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

//    UI
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
    Button btnSendBear;
    RadioGroup radioGroupReed;

    JSONObject jsonObject;
    int id=0;

    static final int F_SWITCH_SERVICE = 0;
    static final int F_SET_DYNAMIC_REFERENCE = 1;
    static final int F_SET_STATIC_REFERENCE = 2;
    static final int F_SET_SHARE_POINT = 3;
    static final int F_SEND_COMPASS = 4;

    Messenger messenger;
    boolean isBound;

    String refToken;

    boolean autoScroll;

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

    class HandlerReplyMsg extends Handler{
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg.obj != null){
                String receivedMessage = msg.obj.toString();
//            String receivedMessage = msg.getData()
                Log.i(TAG,receivedMessage);

                if (autoScroll){
                    logListAdapter.add(receivedMessage);
                    logArea.smoothScrollToPosition(logListAdapter.getCount()-1);
                }

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
                Message message = Message.obtain(null, rxNavigatorService.MESSAGE,1,1);
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode){
            case LOCATION_REQUEST_CODE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED){
                    startNavigatorService();
                }else{
                    Toast.makeText(this, "To use this app, please allow location permission", Toast.LENGTH_LONG).show();
                    serviceStatusSwitch = findViewById(R.id.ServiceStatusSwitch);
                    serviceStatusSwitch.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this,Manifest.permission.ACCESS_FINE_LOCATION)){
                                ActivityCompat.requestPermissions(MainActivity.this,new String[]{Manifest.permission.ACCESS_FINE_LOCATION} ,LOCATION_REQUEST_CODE);
                            }
                        }
                    });
                }
        }
    }

    private void startNavigatorService(){
        serviceStatusSwitch = findViewById(R.id.ServiceStatusSwitch);
        Intent intent = new Intent(MainActivity.this,rxNavigatorService.class);
        intent.putExtra("Flag",F_SWITCH_SERVICE);
        startService(intent);
        bindService(intent,serviceConnection, Context.BIND_AUTO_CREATE);
        serviceStatusSwitch.setChecked(true);
        serviceStatusSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
                if (checked){
                    startService(intent);
                    bindService(intent,serviceConnection, Context.BIND_AUTO_CREATE);
                    isBound = true;
                }else{
                    if (isBound){
                        unbindService(serviceConnection);
                    }
                    stopService(intent);
                    isBound = false;
                }
            }
        });

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
                    Intent intent = new Intent(MainActivity.this,rxNavigatorService.class);
                    intent.putExtra("Flag",F_SET_DYNAMIC_REFERENCE);
                    intent.putExtra("id",id);
                    startService(intent);

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
                Intent intent = new Intent(MainActivity.this,rxNavigatorService.class);
                intent.putExtra("Flag",F_SET_SHARE_POINT);
                startService(intent);
            }
        });

        btnSendBear = findViewById(R.id.btnSendCompass);
        btnSendBear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this,rxNavigatorService.class);
                intent.putExtra("Flag",F_SEND_COMPASS);
                startService(intent);
            }
        });

        // TODO サービス開始時に設定してる変数を全て送る必要がありそう
        radioGroupReed = (RadioGroup) findViewById(R.id.radioGroup);
        radioGroupReed.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup radioGroup, int id) {
                Message message = Message.obtain(null,rxNavigatorService.REED_FILTER,0,0);
                Bundle bundle = new Bundle();
                int filterSelector = 0;
                switch (id){
                    case R.id.radio_and:
                        filterSelector=rxNavigatorService.AND_FILTER;
                        break;
                    case R.id.radio_or:
                        filterSelector=rxNavigatorService.OR_FILTER;
                        break;
                    case R.id.radio_xor:
                        filterSelector=rxNavigatorService.XOR_FILTER;
                        break;
                    case R.id.radio_raw:
                        filterSelector=rxNavigatorService.RAW_FILTER;
                        break;
                }
                bundle.putInt("filter",filterSelector);
                message.setData(bundle);
                try {
                    messenger.send(message);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
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
        autoScroll = false;

        if (ContextCompat.checkSelfPermission(MainActivity.this, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(getApplicationContext(), android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this,Manifest.permission.ACCESS_FINE_LOCATION)){
                Toast.makeText(this, "Please allow location recognition.", Toast.LENGTH_SHORT).show();
            } else {
                ActivityCompat.requestPermissions(MainActivity.this,new String[]{Manifest.permission.ACCESS_FINE_LOCATION} ,LOCATION_REQUEST_CODE);
            }
        } else {
            startNavigatorService();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        autoScroll = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        autoScroll = false;
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

            Intent intent = new Intent(MainActivity.this,rxNavigatorService.class);
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
}
