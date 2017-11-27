package com.example.issei.navigator2;

import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v7.app.AppCompatActivity;
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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

public class MainActivity extends AppCompatActivity {

    Handler handler;
    Handler bleHandler;
    static int SCAN_PERIOD = 50000;

    static String TAG = "Navigator2";
    static int REQUEST_ENABLE_BT = 0x001;
    static int REQUEST_ENABLE_LOCATION = 0x002;
    static final String UART_SERVICE  = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    static final String UART_WRITE    = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    static final String UART_READ     = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";

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
    Switch serviceStatusSwitch;

    EditText editTextId;
    Button btnGetLoc;
    Button btnGetShareId;
    int    sharingMode;
    boolean registered;
    Button btnButton2;
    boolean service = false;

    JSONObject jsonObject;
    int id=0;

    static final int F_SWITCH_SERVICE = 0;
    static final int F_SET_DYNAMIC_REFERENCE = 1;
    static final int F_SET_STATIC_REFERENCE = 2;
    static final int F_SET_SHARE_POINT = 3;

    Messenger messenger;
    boolean isBound;

    ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            isBound = true;
            messenger = new Messenger(iBinder);

            sendMessage("yeah-key","yeah-value");
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
            String receivedMessage = msg.obj.toString();
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
        Intent intent = new Intent(MainActivity.this,NavigatorService.class);
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
                }else{
                    unbindService(serviceConnection);
                    stopService(intent);
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

                    final Runnable runnable = new Runnable() {
                        @Override
                        public void run() {
                            new getReferencePosition().execute();
                            handler.postDelayed(this,5000);
                        }
                    };
                    handler.post(runnable);
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
}
