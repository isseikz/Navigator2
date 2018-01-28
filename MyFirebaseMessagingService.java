package com.example.issei.navigator2;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by issei on 2017/11/27.
 */

public class MyFirebaseMessagingService extends FirebaseMessagingService {
    static final String TAG = "Navigator2";
    private LocalBroadcastManager localBroadcastManager;

    @Override
    public void onCreate() {
        super.onCreate();
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Log.i(TAG, "FCM: " + String.valueOf(remoteMessage.getData()));

        Intent intent = new Intent("FCM");
        intent.putExtra("data",remoteMessage.getData().get("flag"));
//        intent.putExtra("user_id",remoteMessage.getData().get("user_id") != null ? remoteMessage.getData().get("user_id") : "4444" );

        if (remoteMessage.getData().get("flag").equals("0001")){
            intent.putExtra("arrData",remoteMessage.getData().get("arrData"));
        }

        localBroadcastManager.sendBroadcast(intent);
    }

    @Override
    public void onDeletedMessages() {
        super.onDeletedMessages();
    }

    @Override
    public void onMessageSent(String s) {
        super.onMessageSent(s);
    }

    @Override
    public void onSendError(String s, Exception e) {
        super.onSendError(s, e);

    }
}
