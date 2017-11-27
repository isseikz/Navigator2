package com.example.issei.navigator2;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.support.annotation.Nullable;
import android.util.Log;

import jp.ksksue.driver.serial.FTDriver;

/**
 * Created by issei on 2017/11/27.
 */

public class SerialService extends Service {
    private static final String TAG = "Navigator/SerialService";
    Handler thisHandler = new Handler();

    public static final int CODE_COMMAND = 1;
    public static final byte COMMAND_INIT = (byte) 0x00;

    private static final String ACTION_USB_PERMISSION ="jp.ksksue.tutorial.USB_PERMISSION";
    FTDriver device;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG,"onCreate");

        device = new FTDriver((UsbManager) getSystemService(getApplicationContext().USB_SERVICE));
        PendingIntent pendingIntent = PendingIntent.getBroadcast(SerialService.this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        device.setPermissionIntent(pendingIntent);


//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                vibrateMotor(1,100,50,200,100,300);
//            }
//        }).start();
//        new Thread(mLoop).start();
    }


    class IncomingHandler extends Handler{
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what){
                case CODE_COMMAND:
                    Log.i(TAG,"CODE_COMMAND");
                    switch (msg.getData().getByte("command")){
                        case COMMAND_INIT:
                            device.begin(FTDriver.BAUD115200);
                            if (device.isConnected()){
                                device.begin(FTDriver.BAUD115200);
                                vibrateMotor(device, 1,100,200,200,200,300);
                            }
                    }
                    break;
                default:
                break;
            }
        }
    }

    final Messenger messenger = new Messenger(new IncomingHandler());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG,"start: flag: " + String.valueOf(flags) + ", startId: "+String.valueOf(startId));

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG,"is bound");
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (device.isConnected()){
            device.end();
        }
    }

    public boolean isConnected(){
        return device.isConnected();
    }

    private boolean vibrateMotor(FTDriver device, int port, int... onOffDurationsMillis){
        if (device.isConnected()){
            String on,off;
            switch (port){
                case 1:
                    on = ":7880010101FFFFFFFFFFFFFFFF0D\r\n";
                    off= ":7880010001FFFFFFFFFFFFFFFF0E\r\n";
                    break;
                default:
                    on = ":7880010101FFFFFFFFFFFFFFFF0D\r\n";
                    off= ":7880010001FFFFFFFFFFFFFFFF0E\r\n";
                    break;
            }
            for (int i=0;i<onOffDurationsMillis.length;i++){
                if ((((double)i)/2 - Integer.valueOf(i/2)) == 0){
                    Log.i(TAG,"motor: on");
                    device.write(on.getBytes());
                } else {
                    Log.i(TAG,"motor: off");
                    device.write(off.getBytes());
                }
                try {
                    Thread.sleep(onOffDurationsMillis[i]);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            device.write(off);

            return true;
        } else {
            return false;
        }
    }

    private Runnable mLoop = new Runnable() {
        @Override
        public void run() {
            int i, len;

            // [FTDriver] Create Read Buffer
            byte[] rbuf = new byte[4096]; // 1byte <--slow-- [Transfer Speed] --fast--> 4096 byte
            for (; ; ) {
                if (device.isConnected()){
                    len = device.read(rbuf);
                    String str1 = new String(rbuf);
                } else {
                    return;
                }
            }
        }
    };
}
