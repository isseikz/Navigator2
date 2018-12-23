package com.example.issei.navigator2;

import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.internal.RxBleLog;
import com.polidea.rxandroidble.scan.ScanFilter;
import com.polidea.rxandroidble.scan.ScanSettings;
import com.polidea.rxandroidble.utils.ConnectionSharingAdapter;

import java.sql.Time;
import java.util.Objects;
import java.util.Observable;
import java.util.Observer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

public class BLEService extends Service {
    Handler bleServiceHandler = new Handler();
    Messenger messenger;
    Messenger replyMessenger;
    static final int MESSENGER_BLE_SEND = 0;
    static final int MESSENGER_REG_NAV = 1;

    RxBleClient rxBleClient;
    RxBleDevice rxBleDevice;
    RxBleConnection rxBleConnection;
    String macAddress;
    boolean writeFlag;
    Byte[] writeCharacters;
    boolean run;

    Subscription scanSubscription;
    Subscription notifySubscription;
    Subscription sendBleSubscription;
    Subscription connectionSubscription;
    rx.Observable<RxBleConnection> connectObservable;
    PublishSubject<byte[]> subject = PublishSubject.create();

    CompositeSubscription compositeSubscription = new CompositeSubscription();

    private PublishSubject<Void> disconnectTriggerSubject = PublishSubject.create();

    static final String UART_SERVICE = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    static final String UART_WRITE = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    static final String UART_READ = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";


    public BLEService() {
        messenger = new Messenger(new IncomingHandler());
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(MainApplication.TAG,this.getClass().getSimpleName());
        return messenger.getBinder();
    }

    class IncomingHandler extends Handler{
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            Log.i(MainApplication.TAG,"handleMessage: " + msg.getData().toString());
            switch (msg.what){
                case MESSENGER_REG_NAV:
                    replyMessenger = msg.replyTo;
                    break;
                case MESSENGER_BLE_SEND:
                    byte[] dataBytes = msg.getData().getByteArray("sendBleData");
                    if (dataBytes != null){
                        sendBle(dataBytes);
                    }
                    break;
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(MainApplication.TAG,"BLEService: onCreate");

        rxBleClient = RxBleClient.create(getApplicationContext());
        RxBleClient.setLogLevel(RxBleLog.VERBOSE);
//        rxBleClient = MainApplication.getRxBleClient(getApplicationContext());
        scanSubscription = getScanSubscription(rxBleClient);
    }

    @Override
    public void onDestroy() {
        Log.i(MainApplication.TAG,"BLEService: onDestroy");
        clearSubscription();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(MainApplication.TAG,"onUnbind");
        clearSubscription();
        run = false;
        stopSelf();
        return super.onUnbind(intent);
    }

    private Subscription getScanSubscription(RxBleClient client){
        Log.i(MainApplication.TAG,"getScanSubscription");
        return client.scanBleDevices(
                new ScanSettings.Builder().build(),
                new ScanFilter.Builder().setDeviceName("UART Service").build()
        )
                .doOnSubscribe(() -> {Log.i(MainApplication.TAG,"scanSubscription was subscribed");})
                .doOnUnsubscribe(()-> {Log.i(MainApplication.TAG,"ScanSubscription was unsubscribed");})
                .subscribe(
                scanResult -> {
                    Log.i(MainApplication.TAG,"ScanResult: "+ scanResult.toString());
                    if (macAddress == null){
                        macAddress = scanResult.getBleDevice().getMacAddress();
                        Log.i(MainApplication.TAG,"Device Registered: " + macAddress);
                        scanSubscription.unsubscribe();
                        rxBleDevice=scanResult.getBleDevice();
                        observeConnection(rxBleDevice);
                        connectBleSubscription(scanResult.getBleDevice(),false);
                    } else {
                        rxBleDevice = rxBleClient.getBleDevice(macAddress);
                        observeConnection(rxBleDevice);
                        connectBleSubscription(rxBleDevice,false);
                    }
                },
                this::throwErrorToLog
        );
    }

    private rx.Observable<RxBleConnection> prepareConnectionObservable(RxBleDevice device,boolean autoConnect){
        Log.i(MainApplication.TAG,"prepareConnectionObservable");
        return device
                .establishConnection(autoConnect)
                .takeUntil(disconnectTriggerSubject)
                .compose(new ConnectionSharingAdapter());
    }

    private void connectBleSubscription(RxBleDevice device,boolean autoConnect){
        Log.i(MainApplication.TAG,"connectBleSubscription");
        connectObservable = prepareConnectionObservable(device,autoConnect);
        notifySubscription = connectObservable
                        .flatMap(rxBleConnection -> rxBleConnection.setupNotification(UUID.fromString(UART_READ)))
                        .flatMap(observable -> observable)
                        .doOnSubscribe(() -> {Log.i(MainApplication.TAG,"connectBleSubscription was subscribed");})
                        .doOnUnsubscribe(()->{Log.i(MainApplication.TAG,"connectBleSubscription was unsubscribed");})
                        .subscribe(bytes -> {
                                Log.i(MainApplication.TAG,"Received: " + new String(bytes));
                                sendReceivedData(bytes);
                            }
                            , this::throwErrorToLog);
    }

    private void observeConnection(RxBleDevice device){
        connectionSubscription = device.observeConnectionStateChanges()
                .subscribe(rxBleConnectionState -> {
                    Log.i(MainApplication.TAG,"BLE connection: "+rxBleConnectionState.toString());
                    sendLog(rxBleConnectionState.toString());
                    switch (rxBleConnectionState){
                        case DISCONNECTED:
                            resetBleSubscription();
                    }
                },this::throwErrorToLog);
    }

    private void sendBle(byte[] data){
        if (rxBleDevice != null && rxBleDevice.getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTED){
            Log.i(MainApplication.TAG,"method write data");
            sendBleSubscription = connectObservable
                    .flatMap(rxBleConnection1 -> rxBleConnection1.writeCharacteristic(UUID.fromString(UART_WRITE),data))
                    .doOnSubscribe(() -> {Log.i(MainApplication.TAG,"Write subscribes");})
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            bytes -> {Log.i(MainApplication.TAG,"Write: " + new String(bytes));},
                            this::throwErrorToLog
                    );
        }else{
            Log.i(MainApplication.TAG,"sendBle: Any device is not connected...");
        }
    }

    private void sendLog(String text){
        Message message = Message.obtain(null,rxNavigatorService.ADD_LOG,0,0);
        Bundle bundle = new Bundle();
        bundle.putString("text",text);
        message.setData(bundle);
        try {
            replyMessenger.send(message);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void sendReceivedData(byte[] data){
        Message message = Message.obtain(null,rxNavigatorService.BLE_DATA,0,0);
        Bundle bundle = new Bundle();
        bundle.putByteArray("data",data);
        Log.i(MainApplication.TAG,rxNavigatorService.bytesToHex(data));
        message.setData(bundle);
        try {
            replyMessenger.send(message);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void throwErrorToLog(Throwable throwable){
        Log.e(MainApplication.TAG,"throwErrorToLog");
        Log.e(MainApplication.TAG,"Error: " + throwable.getCause() + "\n" + "Message: " + throwable.getMessage());
        sendLog("Error: " + throwable.getCause() + "\n" + "Message: " + throwable.getMessage());
    }

    private void clearSubscription(){
        if(scanSubscription!=null){
            Log.i(MainApplication.TAG,"scanSubscription is cleared");
            scanSubscription.unsubscribe();
            scanSubscription = null;
        }
        if (notifySubscription != null){
            Log.i(MainApplication.TAG,"notifySubscription is cleared");
            notifySubscription.unsubscribe();
            notifySubscription=null;
        }
        if (sendBleSubscription != null){
            Log.i(MainApplication.TAG,"sendBleSubscription is cleared");
            sendBleSubscription.unsubscribe();
            sendBleSubscription = null;
        }
        if (connectionSubscription != null){
            Log.i(MainApplication.TAG,"connectionSubscription is cleared");
            connectionSubscription.unsubscribe();
            connectionSubscription = null;
        }
    }

    private void resetBleSubscription(){
        Log.i(MainApplication.TAG,"resetSubscription");
        if (rxBleDevice.getConnectionState() != RxBleConnection.RxBleConnectionState.CONNECTING){
            clearSubscription();
            connectBleSubscription(rxBleDevice,true);
        }
    }

//    1バイト目を取り除いたデータをbyte[]で返す
    public static byte[] bleData(byte[] data){
        int len = data.length;
        byte[] byteArray = new byte[len-1];
        for (int i =0;i<len-1;i++){
            byteArray[i] = data[i+1];
        }
        return byteArray;
    }

//    1バイト目を取り除いたデータをByte[]で返す
    public static Byte[] bleDataByte(byte[] data){
        int len = data.length;
        Byte[] byteArray = new Byte[len - 1];
        for (int i =0;i<len-1;i++){
            byteArray[i] = data[i+1];
        }
        return byteArray;
    }
}
