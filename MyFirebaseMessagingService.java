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
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by issei on 2017/11/27.
 */

public class MyFirebaseMessagingService extends FirebaseMessagingService {
    static final String TAG = "Navigator2";
    public MyFirebaseMessagingService() {
        super();
    }

    Intent intent = new Intent(this,SerialService.class);

    boolean isBound = false;
    Messenger messenger;

    ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            isBound = true;
            messenger = new Messenger(iBinder);
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
        }
    }

    public void sendByteMessage(String key, Byte value){
        if (isBound){
            try {
                Message message = Message.obtain(null, NavigatorService.MESSAGE,0,0);
                message.replyTo = replyMessenger;

                Bundle bundle = new Bundle();
                bundle.putByte(key, value);
                message.setData(bundle);

                messenger.send(message);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Log.i(TAG, String.valueOf(remoteMessage.getData()));

        bindService(intent,serviceConnection,0);
        if (isBound){
            sendByteMessage("command",SerialService.COMMAND_INIT);
        }
        unbindService(serviceConnection);
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
