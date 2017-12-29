package com.example.issei.navigator2;

import android.app.Application;
import android.content.Context;

import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.internal.RxBleLog;

/**
 * Created by issei on 2017/12/29.
 */

public class MainApplication extends Application{
    private RxBleClient rxBleClient;

    public static RxBleClient getRxBleClient(Context context) {
        MainApplication application = (MainApplication) context.getApplicationContext();
        return application.rxBleClient;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        rxBleClient = RxBleClient.create(this);
        RxBleClient.setLogLevel(RxBleLog.DEBUG);
    }
}
