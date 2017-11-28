package com.example.issei.navigator2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.FirebaseInstanceIdService;

/**
 * Created by issei on 2017/11/27.
 */

public class MyFirebaseInstanceIdService extends FirebaseInstanceIdService {
    static final String TAG = "Navigator2";
    private LocalBroadcastManager localBroadcastManager;

    public MyFirebaseInstanceIdService() {
        super();
    }

    @Override
    public void onTokenRefresh() {
        super.onTokenRefresh();
        String refreshedToken = FirebaseInstanceId.getInstance().getToken();
        Log.i(TAG,"Refreshed token: "+refreshedToken);

        Intent intent = new Intent("FMS");
        intent.putExtra("token",refreshedToken);
        localBroadcastManager=LocalBroadcastManager.getInstance(this);
        localBroadcastManager.sendBroadcast(intent);
    }
}