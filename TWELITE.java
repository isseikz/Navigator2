package com.example.issei.navigator2;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.EventListener;

import jp.ksksue.driver.serial.FTDriver;

interface deviceConListener extends EventListener{

}
