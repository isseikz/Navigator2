package com.example.issei.navigator2;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.DataSetObserver;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.Api;
import com.google.android.gms.common.api.GoogleApi;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.FusedLocationProviderApi;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResult;
import com.google.android.gms.location.LocationSettingsStates;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.security.auth.login.LoginException;

public class MainActivity extends AppCompatActivity implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, LocationListener {
    BluetoothAdapter bluetoothAdapter;
    BluetoothLeScanner bluetoothLeScanner;
    HashMap scanResults;
    ScanCallback scanCallback;
    Boolean scanning       = false;
    Boolean connected      = false;

    BluetoothGatt bluetoothGatt;

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
    GoogleApiClient googleApiClient;
    Location currentLocation;
    Location YNU = new Location("");
    Location refPosition;
    float fBearing = 180;
    float bearing  = 0;

    EditText editTextLat;
    EditText editTextLong;
    Button   btnSetLocation;
    ListView logArea;
    ArrayAdapter logListAdapter;

    EditText editTextId;
    Button btnGetLoc;
    Button btnGetShareId;
    int    sharingMode;
    boolean registered;

    JSONObject jsonObject;
    int id=0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        handler = new Handler();
        refPosition = new Location("");

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            finish();
        };

        editTextLat = (EditText) findViewById(R.id.editTextLat);
        editTextLong = (EditText) findViewById(R.id.editTextLong);
        btnSetLocation = (Button) findViewById(R.id.buttonSetLoc);
        logArea = (ListView) findViewById(R.id.logArea);
        logListAdapter = new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1);
        logArea.setAdapter(logListAdapter);

        editTextId = (EditText) findViewById(R.id.idArea);
        btnGetLoc = (Button) findViewById(R.id.btnGetLoc);
        btnGetLoc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sharingMode = 0;
                id = Integer.parseInt(String.valueOf(editTextId.getText()));

                final Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        new getReferencePosition().execute();
                        handler.postDelayed(this,5000);
                    }
                };
                handler.post(runnable);
            }
        });

        btnGetShareId = (Button) findViewById(R.id.btnGetShareId);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        StartBleScan();

        if (googleApiClient == null) {
            googleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(LocationServices.API)
                    .build();
        }
    }

    @Override
    protected void onStart() {
        googleApiClient.connect();
        super.onStart();
    }

    @Override
    protected void onStop() {

        bluetoothGatt.disconnect();

        googleApiClient.disconnect();
        super.onStop();
    }

    private void StartBleScan() {
        if (!hasBLEPermissions() || scanning) {
            return;
        }
        List<ScanFilter> scanFilterList = new ArrayList<>();
        ScanFilter scanFilter1 = new ScanFilter.Builder()
                .setDeviceName("UART Service")
                .build();
        scanFilterList.add(scanFilter1);
        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();
        scanResults = new HashMap<>();
        scanCallback = new BLEScanCallback();

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        bluetoothLeScanner.startScan(scanFilterList, scanSettings, scanCallback);
        scanning = true;

        bleHandler = new Handler();
        bleHandler.postDelayed(this::stopLeScan, SCAN_PERIOD);

    }

    private void stopLeScan() {
        Log.i(TAG, String.valueOf(scanning)+", "+(bluetoothAdapter!=null)+", "+bluetoothAdapter.isEnabled()+", "+(bluetoothLeScanner != null));
        if (scanning && (bluetoothAdapter != null) && (bluetoothAdapter.isEnabled()) && bluetoothLeScanner != null) {
            Log.i(TAG,"stopScan");
            bluetoothLeScanner.stopScan(scanCallback);
            scanComplete();
        } else if (scanning){
            Log.i(TAG,"stopScan: device not found");
            logListAdapter.add("stopLeScan: Device not found");
        }
        scanCallback = null;
        bleHandler = null;
        scanning = false;
    }

    private void scanComplete() {
        if (scanResults.isEmpty()) {
            Log.i(TAG,"Scan result is empty");
            return;
        }
        for (Object deviceAddress : scanResults.keySet()) {
            Log.i(TAG, "Found devices: " + deviceAddress.toString());
        }
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        LocationRequest locationRequest = new LocationRequest();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(5000);

        btnSetLocation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    refPosition.setLongitude(Double.parseDouble(editTextLong.getText().toString()));
                    refPosition.setLatitude(Double.parseDouble(editTextLat.getText().toString()));
                    logListAdapter.add("onClick: Reference position was changed");
                    logListAdapter.add("Position: " + String.valueOf(refPosition.getLongitude()) + ", " + String.valueOf(refPosition.getLatitude()));
                } catch (NumberFormatException e){
                    refPosition = null;
                }
            }
        });

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }

        LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, this::onLocationChanged);
        btnGetShareId.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sharingMode = 1;
            }
        });
    }


    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    @Override
    public void onLocationChanged(Location location) {

        logListAdapter.add("Current location: "+location.getLongitude()+", "+location.getLatitude());
//        switch (sharingMode){
//            case 0: //get reference position
                currentLocation = location;
                YNU.setLatitude(35.4741875);
                YNU.setLongitude(139.5932654);

                float tempBearing = location.getBearing();
                if (tempBearing != 0){
                    fBearing = tempBearing;
                    currentLocation.setBearing(fBearing);
                }

                if (refPosition != null){
                    bearing = currentLocation.bearingTo(refPosition) - fBearing;
                    logListAdapter.add("Bearing to Posi: " + String.valueOf(bearing));
                } else {
                    bearing = currentLocation.bearingTo(YNU) - fBearing;
                    logListAdapter.add("Bearing to YNU: " + String.valueOf(bearing));
                }
                if (bearing < 0){bearing += 360;};

                int intSpeedRef = (int) refPosition.getSpeed();
                if (intSpeedRef > 256){intSpeedRef = 256;};
//                intSpeedLevelRef = Math.log(refPosition.getSpeed())/Math.log(1.5);  x=ln(speed)/ln(1.5) ←速度をいい感じに分類できる

                float bearingRef = refPosition.getBearing() -fBearing;
                if (bearingRef < 0){bearingRef += 360;};

                if (fBearing < 0){fBearing += 360;};

                byte bBearings   = (byte) ((byte) (((byte)(((int)(fBearing / 360 * 16))& 0x0f)) << 4) |((byte) ((int)(bearing /360 * 16))& 0x0f));
                byte bRefSpeed   = (byte) ((byte) (intSpeedRef) & 0xFF);
                byte bRefBearing = (byte) ((int)(bearingRef / 360 * 16));
                Log.i(TAG, "fBearing  = " + String.valueOf(fBearing));
                Log.i(TAG, "bearing   = " + String.valueOf(bearing));
                Log.i(TAG, "bBearings = " + String.valueOf(bBearings));
                Log.i(TAG, "bRefSpeed = " + String.valueOf(bRefSpeed));
                Log.i(TAG, "bRefBearing = " + String.valueOf(bRefBearing));

                int intLatitude  = (int) (location.getLatitude()*100000);
                int intLongitude = (int) (location.getLongitude()*100000);



                Log.i(TAG,location.toString());
                Log.i(TAG,"GATT: "+(bluetoothGatt!=null)+" Connected: " + String.valueOf(connected ));
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
                    Log.i(TAG, new String(buffer));
                    for (byte b : buffer) {
                        Log.i(TAG, String.valueOf(b & 0xff));
                        System.out.print(" ");
                    }

                    BluetoothGattService bluetoothGattService = bluetoothGatt.getService(UUID.fromString(UART_SERVICE));
                    BluetoothGattCharacteristic bluetoothGattCharacteristic = bluetoothGattService.getCharacteristic(UUID.fromString(UART_WRITE));
                    bluetoothGattCharacteristic.setValue(buffer);
                    bluetoothGattCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

                    if (!successed){
                        Log.i(TAG,"Sending...");
                        successed = bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic);
                    }
                }
//                break;
//            case 1: // send reference position
        if (sharingMode > 0){
            if (!registered){
                Log.i(TAG,"register ref");
                logListAdapter.add("registering location as ref.");
                new registerLocationSharing().execute(location.getLongitude(), location.getLatitude(), Double.parseDouble(String.valueOf(location.getBearing())),Double.parseDouble(String.valueOf(location.getSpeed())));
                registered = true;
            } else {
                Log.i(TAG,"update ref");
                logListAdapter.add("update ref position");
                new updateLocationSharing().execute(location.getLongitude(), location.getLatitude(), Double.parseDouble(String.valueOf(location.getBearing())),Double.parseDouble(String.valueOf(location.getSpeed())));
            }
//            break;
        }
    }

    private class BLEScanCallback extends ScanCallback{

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
//            addScanResult(result);
            BluetoothDevice device = result.getDevice();
            String deviceAddress = device.getAddress();
            stopLeScan();
            Log.i(TAG,"Device Found: "+deviceAddress);
            GattClientCallback gattClientCallback = new GattClientCallback();
            device.connectGatt(MainActivity.this,false,gattClientCallback);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
//            for (ScanResult result : results){
//                addScanResult(result);
//            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
        }

        void addScanResult(ScanResult result){
            stopLeScan();
            BluetoothDevice device = result.getDevice();
            String deviceAddress = device.getAddress();
            Log.i(TAG,"Device Found: "+deviceAddress);
            scanResults.put(deviceAddress,device.getUuids());
            connectDevice(device);
        }

        void connectDevice(BluetoothDevice device){
            Log.i(TAG,"Connect to: " +device.toString());
            GattClientCallback gattClientCallback = new GattClientCallback();
            bluetoothGatt = device.connectGatt(MainActivity.this,false,gattClientCallback);
        }

        class GattClientCallback extends BluetoothGattCallback{
            public GattClientCallback() {
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
                Log.i(TAG, "onConnectionStateChange");
                Log.i(TAG, "status: " + String.valueOf(status) + " newState: " + String.valueOf(newState));

                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
                    Log.i(TAG, "CONNECTED");
                    gatt.discoverServices();
                    connected = true;
                    return;
                }

                if (status == BluetoothGatt.GATT_FAILURE) {
                    Log.i(TAG, "GATT_FAILURE");
                    disconnectGattServer();
//                    StartBleScan();
                    return;
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "GATT_NOT SUCCESS");
                    disconnectGattServer();
                    StartBleScan();
                    return;
                } else {
                    Log.i(TAG,"status; GATT Success");
                }


                if (newState == BluetoothProfile.STATE_CONNECTED){
                    Log.i(TAG,"STATE_CONNECTED");
                    gatt.discoverServices();
                    connected = true;

                } else if(newState == BluetoothProfile.STATE_DISCONNECTED){
                    Log.i(TAG,"STATE_DISCONNECTED");
                    connected = false;
                    StartBleScan();
                } else {
                    Log.e(TAG, "Unknown state");
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                super.onServicesDiscovered(gatt, status);
                if (status == BluetoothGatt.GATT_SUCCESS){
                    Log.i(TAG, String.valueOf(gatt.getServices()));
                    bluetoothGatt = gatt;
                    return;
                } else {
                    Log.e(TAG,"onServiceDiscovered");
                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                super.onCharacteristicRead(gatt, characteristic, status);
                Log.i(TAG,characteristic.getStringValue(0));

            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                super.onCharacteristicWrite(gatt, characteristic, status);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                super.onCharacteristicChanged(gatt, characteristic);
                Log.i(TAG,gatt.toString());
                Log.i(TAG, Arrays.toString(characteristic.getValue()));
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

            void disconnectGattServer(){
                connected = false;
                if(bluetoothGatt != null){
                    bluetoothGatt.disconnect();
                    bluetoothGatt.close();
                }
            }
        }
    }

    private boolean hasBLEPermissions(){
        if (bluetoothAdapter==null || !bluetoothAdapter.isEnabled()){
            requestBLEEnable();
            return false;
        }else if(!hasLocationPermissions()){
            requestLocationEnable();
            return false;
        }
        return true;
    }

    private boolean hasLocationPermissions(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        };
        return true;
    }

    private void requestBLEEnable(){
        Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
    }

    private void requestLocationEnable(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQUEST_ENABLE_LOCATION);
        }
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
