package com.example.issei.navigator2;

import android.util.Log;

import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.FirebaseInstanceIdService;

/**
 * Created by issei on 2017/11/27.
 */

public class MyFirebaseInstanceIdService extends FirebaseInstanceIdService {
    static final String TAG = "Navigator2";
    public MyFirebaseInstanceIdService() {
        super();
    }

    @Override
    public void onTokenRefresh() {
        super.onTokenRefresh();
        String refreshedToken = FirebaseInstanceId.getInstance().getToken();
        Log.i(TAG,"Refreshed token: "+refreshedToken);
    }
}