package com.example.issei.navigator2;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.internal.RxBleLog;

/**
 * Created by issei on 2017/12/29.
 */

public class MainApplication extends Application{
    static final String TAG = "Navigator2";
    private RxBleClient rxBleClient;
    private BluetoothAdapter bluetoothAdapter;


    public static RxBleClient getRxBleClient(Context context) {
        Log.d(TAG,"MainApplication/getBleClient");
        MainApplication application = (MainApplication) context.getApplicationContext();
        return application.rxBleClient;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG,"MainApplication/onCreate");
//        rxBleClient = RxBleClient.create(this);
//        RxBleClient.setLogLevel(RxBleLog.DEBUG);
    }

    @Override
    public void onTerminate() {
        stopBleService();
        super.onTerminate();
    }

    public void stopBleService(){
        Intent intent = new Intent(getApplicationContext(),BLEService.class);
        stopService(intent);
    }
}