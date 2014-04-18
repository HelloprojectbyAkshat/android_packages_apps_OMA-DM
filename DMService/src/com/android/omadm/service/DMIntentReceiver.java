/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.omadm.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class DMIntentReceiver extends BroadcastReceiver {
    private static final String TAG = "DMIntentReceiver";
    private static final boolean DBG = DMClientService.DBG;

    private int mUIMode = -1;

    private byte[] mData;

    private static final String ALERT_TYPE_DOWNLOADANDUPDATE
            = "org.openmobilealliance.dm.firmwareupdate.downloadandupdate";

    private static final String RP_OPERATIONS_FACTORYRESET
            = "./ManagedObjects/LAWMO/Operations/FactoryReset";

    private static final String RP_EXT_OPERATIONS_RESET
            = "./ManagedObjects/LAWMO/Ext/Operations/Reset";

    private static final String ACTION_NOTIFY_RESULT_TO_SERVER
            = "com.android.omadm.service.notify_result_to_server";

    private static final String ACTION_NOTIFY_START_UP_DMSERVICE
            = "com.android.omadm.service.start_up";

    private static final String DEV_DETAIL = "devdetail";

    private static final String WIFI_MAC_ADDR = "wifimacaddr";

    private static final String PRE_FW_VER = "prefwversion";

    private static final String CURR_FW_VER = "currfwversion";

    private static final String LAST_UPD_TIME = "lastupdatetime";

    private static boolean initialWapPending;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (DBG) Log.d(TAG, "Received new intent: " + action);

        if (action.equals(DMIntent.ACTION_WAP_PUSH_RECEIVED_INTERNAL)) {
            handleWapPushIntent(context, intent);
        } else if (action.equals(DMIntent.ACTION_CALL_AND_DATA_STATE_READY)) {
            handleCallDataStateReadyIntent(context);
        } else if (action.equals(DMIntent.ACTION_CLOSE_NOTIFICATION_INFO)) {
            DMHelper.cancelNotification(context, DMHelper.NOTIFICATION_INFORMATIVE_ID);
        } else if (action.equals(DMIntent.ACTION_USER_CONFIRMED_DM_SESSION)) {
            handleUserConfirmedSession(context);
        } else if (action.equals(DMIntent.ACTION_CLIENT_INITIATED_FOTA_SESSION)) {
            handleClientInitiatedFotaIntent(context, intent);
        } else if (action.equals(DMIntent.ACTION_TIMER_ALERT)) {
            handleTimeAlertIntent(context);
        } else if (action.equals(DMIntent.DM_SERVICE_RESULT_INTENT)) {
            handleDmServiceResult(context, intent);
        } else if (action.equals(ACTION_NOTIFY_RESULT_TO_SERVER)) {
            // FIXME old comment: change this to the DMIntent name
            handleNotifyResultToServer(context, intent);
        } else if (action.equals(DMIntent.ACTION_APN_STATE_ACTIVE_READY)) {
            handleApnStateActive(context);
        } else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
            Log.d(TAG, "Ignoring Intent.ACTION_BOOT_COMPLETED");
            //if (!(isPhoneTypeLTE() || isPhoneTypeCDMA3G(context))) {
            //    saveDevDetail(context);
            //    handleBootCompletedIntent(context);
            //}
        } else if (action.equals(ACTION_NOTIFY_START_UP_DMSERVICE)) {
            if (isPhoneTypeLTE()) {
                saveDevDetail(context);
                SharedPreferences p = context.getSharedPreferences(DMHelper.IMEI_PREFERENCE_KEY, 0);
                String currGsmImei = p.getString(DMHelper.IMEI_VALUE_KEY, "");
                if (currGsmImei != null && currGsmImei.equals(intent.getStringExtra("gsmimei"))) {
                    Log.d(TAG, "IMEI already stored, continuing");
                } else {
                    SharedPreferences.Editor ed = p.edit();
                    ed.putString(DMHelper.IMEI_VALUE_KEY, intent.getStringExtra("gsmimei"));
                    ed.commit();
                }
            } else if (isPhoneTypeCDMA3G(context)) {
                SharedPreferences p = context.getSharedPreferences(DMHelper.AKEY_PREFERENCE_KEY, 0);
                SharedPreferences.Editor ed = p.edit();
                ed.putString(DMHelper.AKEY_VALUE_KEY, intent.getStringExtra("akey"));
                ed.commit();
            }
            handleBootCompletedIntent(context);
        } else if (action.equals(DMIntent.ACTION_INJECT_PACKAGE_0_INTERNAL)) {
            String strServerID = intent.getStringExtra(DMIntent.FIELD_SERVERID);
            if (strServerID == null || strServerID.trim().isEmpty()) {
                Log.d(TAG, "Error! Can't inject package0. The required extras parameter '" +
                        DMIntent.FIELD_SERVERID + "' is null or an empty string.");
                return;
            }

            Intent newIntent = new Intent(DMIntent.LAUNCH_INTENT);
            newIntent.putExtra(DMIntent.FIELD_REQUEST_ID, System.currentTimeMillis());
            newIntent.putExtra(DMIntent.FIELD_TYPE, DMIntent.TYPE_CLIENT_SESSION_REQUEST);
            newIntent.putExtra(DMIntent.FIELD_SERVERID, strServerID);
            Log.d(TAG, "XXX received ACTION_INJECT_PACKAGE_0_INTERNAL, starting"
                    + " TYPE_CLIENT_SESSION_REQUEST with ID "
                    + newIntent.getLongExtra(DMIntent.FIELD_REQUEST_ID, 1234));
            newIntent.setClass(context, DMClientService.class);
            context.startService(newIntent);
        } else if (action.equals(DMIntent.ACTION_SET_SERVER_CONFIG)) {
            Log.d(TAG, "ACTION_SET_SERVER_CONFIG received");
            String hostUrl = intent.getStringExtra(DMIntent.FIELD_SERVER_URL);
            String proxyAddress = intent.getStringExtra(DMIntent.FIELD_PROXY_ADDRESS);
            Log.d(TAG, "server URL: " + hostUrl + " proxy address: " + proxyAddress);
            DMHelper.setServerUrl(context, hostUrl);
            DMHelper.setProxyHostname(context, proxyAddress);
        }
    }

    // handle client-initiated FOTA intents
    private void handleClientInitiatedFotaIntent(Context context, Intent intent) {
        String strServerID = intent.getStringExtra(DMIntent.FIELD_SERVERID);
        if (TextUtils.isEmpty(strServerID)) {
            Log.d(TAG, "Error! Can't start FOTA session: " +
                    DMIntent.FIELD_SERVERID + " is null or an empty string.");
            return;
        }
        String alertString = intent.getStringExtra(DMIntent.FIELD_ALERT_STR);
        if (TextUtils.isEmpty(alertString)) {
            Log.d(TAG, "Error! Can't start FOTA session: " +
                    DMIntent.FIELD_ALERT_STR + " is null or an empty string.");
            return;
        }

        setState(context, DMHelper.STATE_APPROVED_BY_USER);
        long requestID = System.nanoTime();

        // Save pending info here
        SharedPreferences p = context.getSharedPreferences(DMHelper.DM_PREFERENCES_KEY, 0);
        SharedPreferences.Editor ed = p.edit();
        ed.putInt(DMHelper.DM_SESSION_TYPE_KEY, DMIntent.TYPE_FOTA_CLIENT_SESSION_REQUEST);
        ed.putLong(DMHelper.MESSAGE_TIMESTAMP_ID_KEY, requestID);
        ed.putString(DMHelper.FOTA_SERVER_ID_KEY, strServerID);
        ed.putString(DMHelper.FOTA_ALERT_STRING_KEY, alertString);
        ed.commit();

        if (isWifiConnected(context) || isDataNetworkAcceptable(context)) {
            if (!isWifiConnected(context) && isDataNetworkAcceptable(context) && isPhoneTypeLTE()) {
                Log.d(TAG, "handleClientInitiatedFotaIntent, start apn monitoring service"
                        + " for requestID " + requestID);
                setFotaApnState(context, DMHelper.FOTA_APN_STATE_START_DM_SESSION);
                startApnStateMonitoringService(context);
            } else {
                Log.d(TAG, "handleClientInitiatedFotaIntent starting DM session");
                startDMSession(context);
            }
        } else {
            Log.d(TAG, "handleClientInitiatedFotaIntent: start data/call state monitoring");
            startDataAndCallStateMonitoringService(context);
        }
    }

    // handle SMS and WAP Push intents;
    private void handleWapPushIntent(Context context, Intent intent) {

        int currentState = getState(context);

        Log.d(TAG, "handleWapPushIntent() current state: " + currentState);

        // if current state is already "session in progress" - ignore new message;
        // otherwise remove old message and process a new one.
        if (currentState == DMHelper.STATE_SESSION_IN_PROGRESS) {
            Log.e(TAG, "current state is 'Session-in-Progress', ignoring new message.");
            return;
        }

        DMHelper.cleanAllResources(context);

        //clean fota apn resources and stop using fota apn
        if (isPhoneTypeLTE()) {
            int mgetFotaApnState = getFotaApnState(context);
            Log.d(TAG, "handleWapPushIntent, check if necessary to stop using fota apn "
                    + mgetFotaApnState);
            if (mgetFotaApnState != DMHelper.FOTA_APN_STATE_INIT) {
                // resetting FOTA APN STATE
                Log.d(TAG, "XXX resetting FOTA APN state");
                setFotaApnState(context, DMHelper.FOTA_APN_STATE_INIT);
                stopUsingFotaApn(context);
                DMHelper.cleanFotaApnResources(context);
            }
        }

        //parse & save message; get UI mode
        boolean result = parseAndSaveWapPushMessage(context, intent);

        if (!result) {
            Log.e(TAG, "handleWapPushIntent(): error in parseAndSaveWapPushMessage()");
            DMHelper.cleanAllResources(context);
            return;
        }

        if (treeExist(context) || !isPhoneTypeLTE()) {
            //check UI mode and prepare and start process
            preprocess(context, currentState);
        } else {
            Log.d(TAG, "WapPush arrived before tree initialization");
            initialWapPending = true;
            Intent intentConnmoInit = new Intent("com.android.omadm.service.wait_timer_alert");
            context.sendBroadcast(intentConnmoInit);
        }
    }

    private void handleCallDataStateReadyIntent(Context context) {
        // check if message is not expired
        if (DMHelper.isMessageExpired(context)) {
            DMHelper.cleanAllResources(context);
            Log.d(TAG, "handleCallDataStateReadyIntent(): message is expired.");
            return;
        }

        int currentState = getState(context);

        // nothing there
        if (currentState == DMHelper.STATE_IDLE) {
            DMHelper.cleanAllResources(context);
            Log.d(TAG, "Call/data state changed: there is no message to proceed.");
            return;
        }

        if (currentState == DMHelper.STATE_SESSION_IN_PROGRESS) {
            Log.d(TAG, "Call/data state changed: session in progress; doing nothing.");
            return;
        }

        if (isWifiConnected(context) || isDataNetworkAcceptable(context)) {
            if (!isWifiConnected(context) && isDataNetworkAcceptable(context) && isPhoneTypeLTE()) {
                Log.d(TAG, "handleCallDataStateReadyIntent, start apn monitoring service");
                setFotaApnState(context, DMHelper.FOTA_APN_STATE_START_DM_SESSION);
                startApnStateMonitoringService(context);
            } else {
                Log.d(TAG, "handleCallDataStateReadyIntent: start DM session");
                startDMSession(context);
            }
        } else {
            Log.d(TAG, "handleCallDataStateReadyIntent: restart data/call state monitoring");
            startDataAndCallStateMonitoringService(context);
        }
    }

    private void handleUserConfirmedSession(Context context) {
        // check if message is not expired
        if (DMHelper.isMessageExpired(context)) {
            DMHelper.cleanAllResources(context);
            Log.d(TAG, "handleUserConfirmedSession(): message is expired.");
            return;
        }

        startProcess(context);
    }

    // handle boot completed intent
    private void handleBootCompletedIntent(Context context) {
        // Check if DM tree already has been generated. Start service to generate tree
        // in case if required. It may happened only once during first boot.
        if (!treeExist(context)) {
            Log.d(TAG, "Boot completed: there is no DM Tree. Start service to generate tree.");
            Intent intent = new Intent(DMIntent.LAUNCH_INTENT);
            intent.putExtra("NodePath", ".");
            intent.putExtra(DMIntent.FIELD_TYPE, DMIntent.TYPE_UNITEST_GET_STRING_NODE);
            intent.putExtra(DMIntent.FIELD_REQUEST_ID, -2L);
            intent.setClass(context, DMClientService.class);
            context.startService(intent);
            if (!initialWapPending) {
                Log.d(TAG, "handleBootCompletedIntent, no initial WapPush pending.");
                DMHelper.cleanAllResources(context);
                return;
            } else {
                Log.d(TAG, "handleBootCompletedIntent, initial WapPush pending.");
                initialWapPending = false;
                setState(context, DMHelper.STATE_PENDING_MESSAGE);
            }
        }

//        if (isPhoneTypeLTE()) {
//            int fotaApnState = getFotaApnState(context);
//            Log.d(TAG, "handleBootCompletedIntent, check if need to stop using fota apn "
//                    + fotaApnState);
//            if (fotaApnState != DMHelper.FOTA_APN_STATE_INIT) {
                // resetting FOTA APN STATE
//                setFotaApnState(context, DMHelper.FOTA_APN_STATE_INIT);
                //stopUsingFotaApn();
//                DMHelper.cleanFotaApnResources(context);
//            }
            // stopUsingFotaApn();
//        }

        int currentState = getState(context);

        if (currentState == DMHelper.STATE_IDLE) {
            Log.d(TAG, "Boot completed: there is no message to proceed.");
            return;
        }

        // check if message is not expired
        if (DMHelper.isMessageExpired(context)) {
            DMHelper.cleanAllResources(context);
            Log.d(TAG, "handleBootCompletedIntent(): the message is expired.");
            return;
        }

        // initiate mUIMode and mData from preferences
        if (!initFromSharedPreferences(context)) {
            DMHelper.cleanAllResources(context);
            Log.d(TAG, "handleBootCompletedIntent(): cannot init from shared preferences");
            return;
        }

        preprocess(context, currentState);
    }

    // handle time alert intent (all instances)
    private void handleTimeAlertIntent(Context context) {
        int currentState = getState(context);
        switch (currentState) {
            case DMHelper.STATE_IDLE:
                // nothing there
                DMHelper.cleanAllResources(context);
                Log.d(TAG, "Time alert: there is no message to proceed.");
                break;


        }

        if (currentState == DMHelper.STATE_IDLE) {
            // nothing there
            DMHelper.cleanAllResources(context);
            Log.d(TAG, "Time alert: there is no message to proceed.");
        } else if (DMHelper.isMessageExpired(context)) {
            // check if message is not expired
            DMHelper.cleanAllResources(context);
            Log.d(TAG, "Warning from handleTimeAlertIntent(): the message is expired.");
        } else if (currentState == DMHelper.STATE_SESSION_IN_PROGRESS) {
            // session in progress; doing nothing.
            Log.d(TAG, "Time alert: session in progress; doing nothing.");
            DMHelper.subscribeForTimeAlert(context,
                    DMHelper.TIME_CHECK_STATUS_AFTER_STARTING_DM_SERVICE);
        } else if (currentState == DMHelper.STATE_APPROVED_BY_USER) {
            // approved by user: try to start session or data/call monitoring service
            Log.d(TAG, "Time alert: state 'approved by user'; starting process");
            startProcess(context);
        } else if (currentState == DMHelper.STATE_PENDING_MESSAGE) {
            // approved by user: try to start session or data/call monitoring service pending
            Log.d(TAG,
                    "Time alert: state 'pending message'; read from preferences starting preprocess");

            // initiate mUIMode and mData from preferences
            if (!initFromSharedPreferences(context)) {
                DMHelper.cleanAllResources(context);
                Log.d(TAG,
                        "Warning from handleTimeAlertIntent(): cannot init from shared preferences");
                return;
            }
            preprocess(context, currentState);
        } else {
            Log.e(TAG, "Error from handleTimeAlertIntent(): unknown state " + currentState);
        }
    }

    private static void handleNotifyResultToServer(Context context, Intent intent) {
        Log.d(TAG, "Inside handleNotifyResultToServer");

        // Save message
        SharedPreferences p = context.getSharedPreferences(DMHelper.FOTA_APN_PREFERENCE_KEY, 0);
        SharedPreferences.Editor ed = p.edit();

        ed.putString(DMHelper.LAWMO_RESULT_KEY, intent.getStringExtra(DMIntent.FIELD_LAWMO_RESULT));
        ed.putString(DMHelper.FOTA_RESULT_KEY, intent.getStringExtra(DMIntent.FIELD_FOTA_RESULT));
        ed.putString(DMHelper.PKG_URI_KEY, intent.getStringExtra(DMIntent.FIELD_PKGURI));
        ed.putString(DMHelper.ALERT_TYPE_KEY, intent.getStringExtra(DMIntent.FIELD_ALERTTYPE));
        ed.putString(DMHelper.CORRELATOR_KEY, intent.getStringExtra(DMIntent.FIELD_CORR));
        ed.putString(DMHelper.SERVER_ID_KEY, intent.getStringExtra(DMIntent.FIELD_SERVERID));

        ed.commit();

        if (isDataNetworkAcceptable(context) && !isWifiConnected(context) && isPhoneTypeLTE()) {
            int mgetFotaApnState = getFotaApnState(context);
            if (mgetFotaApnState != DMHelper.FOTA_APN_STATE_INIT) {
                Log.d(TAG, "there must be a pending session, return");
                return;
            }
            // for LTE and eHRPD coverage , switch the apn before FDM
            Log.d(TAG, "handleNotifyResultToServer starting FOTA APN");
            setFotaApnState(context, DMHelper.FOTA_APN_STATE_REPORT_DM_SESSION);
            startApnStateMonitoringService(context);
        } else {
            sendNotifyIntent(context);
        }
    }

    private void handleApnStateActive(Context context) {
        Log.d(TAG, "Inside handleApnStateActive");
        int fotaApnState = getFotaApnState(context);
        Log.d(TAG, "FOTA APN state is " + fotaApnState);

        if (fotaApnState == DMHelper.FOTA_APN_STATE_REPORT_DM_SESSION) {
            setFotaApnState(context, DMHelper.FOTA_APN_STATE_REPORT_DM_SESSION_RPTD);
            sendNotifyIntent(context);
        } else if (fotaApnState == DMHelper.FOTA_APN_STATE_START_DM_SESSION) {
            setFotaApnState(context, DMHelper.FOTA_APN_STATE_START_DM_SESSION_RPTD);
            // check if message is not expired
            if (DMHelper.isMessageExpired(context)) {
                DMHelper.cleanAllResources(context);
                Log.d(TAG, "Warning from handleApnStateActive(): the message is expired.");
                return;
            }

            int currentState = getState(context);

            // nothing to do here
            if (currentState == DMHelper.STATE_IDLE) {
                DMHelper.cleanAllResources(context);
                Log.d(TAG, "handleApnStateActive(): there is no message to proceed.");
                return;
            }

            if (currentState == DMHelper.STATE_SESSION_IN_PROGRESS) {
                Log.d(TAG, "handleApnStateActive(): session in progress; doing nothing.");
                return;
            }

            startDMSession(context);
        } else {
            Log.d(TAG, "handleApnStateActive: NO ACTION NEEDED");
        }
    }

    // check UI mode and prepare and start process
    private void preprocess(Context context, int currentState) {

        setState(context, currentState);

        Log.d(TAG, "From preprocess().... Current state = " + currentState);

        // check UI mode. If updates has been replaced with the new one and user already
        // confirmed - we are skipping confirmation.
        if (mUIMode == DMHelper.UI_MODE_CONFIRMATION
                && currentState != DMHelper.STATE_APPROVED_BY_USER) {

            // user confirmation is required
            Log.d(TAG, "User confirmation is required");
            DMHelper.postConfirmationNotification(context);
            setState(context, DMHelper.STATE_PENDING_MESSAGE);

            // check and repost notification in case user cancels it
            DMHelper.subscribeForTimeAlert(context,
                    DMHelper.TIME_CHECK_NOTIFICATION_AFTER_SUBSCRIPTION);

            return;
        }

        if (mUIMode == DMHelper.UI_MODE_INFORMATIVE) {
            // required notification, just inform the user
            Log.d(TAG, "User notification is required");
            DMHelper.postInformativeNotification_message1(context);
        } else {
            Log.d(TAG, "Silent DM session: silent mode or user already has approved.");
        }

        // try to start DM session or start Data and Call State Monitoring Service
        startProcess(context);
    }

    // parse data from intent; set UI mode; save required data.
    private boolean parseAndSaveWapPushMessage(Context context, Intent intent) {

        // Parse message
        Bundle bdl = intent.getExtras();
        byte[] data = bdl.getByteArray("data");
        mData = data;

        if (data == null || data.length < 25) {
            Log.e(TAG, "parseAndSaveWapPushMessage: data[] is null or length < 25.");
            return false;
        }

        // first 16 bytes - digest
        int version = ((data[17] >> 6) & 0x3) | ((data[16]) << 2);
        int uiMode = (data[17] >> 4) & 0x3;
        int indicator = (data[17] >> 3) & 0x1;
        int sessionId = ((data[21] & 0xff) << 8) | data[22];
        int serverIdLength = data[23];    // must be equal to data.length-24
        String serverId = new String(data, 24, serverIdLength, StandardCharsets.UTF_8);
        mUIMode = uiMode;

        if (DBG) {
            Log.i(TAG, "Get Provision Package0"
                    + " version:" + version
                    + " uiMode:" + uiMode
                    + " indicator:" + indicator
                    + " sessionId:" + sessionId
                    + " serverId:" + serverId);
        }

        // Save message
        SharedPreferences p = context.getSharedPreferences(DMHelper.DM_PREFERENCES_KEY, 0);
        SharedPreferences.Editor ed = p.edit();
        //ed.putInt("type", DMIntent.TYPE_PKG0_NOTIFICATION);
        //ed.putLong(DMHelper.REQUEST_ID_KEY, System.currentTimeMillis());
        ed.putInt("length", data.length);
        ed.putInt(DMHelper.DM_SESSION_TYPE_KEY, DMIntent.TYPE_PKG0_NOTIFICATION);
        ed.putLong(DMHelper.MESSAGE_TIMESTAMP_ID_KEY, System.nanoTime());
        ed.putInt(DMHelper.DM_UI_MODE_KEY, uiMode);

        ed.commit();

//        PendingResult pendingResult = goAsync();
//        DMParseSaveWapMsgRunnable dmParseSaveWapMsgRunnable
//                = new DMParseSaveWapMsgRunnable(pendingResult);
//        Thread dmParseSaveWapMsgThread = new Thread(dmParseSaveWapMsgRunnable);
//        dmParseSaveWapMsgThread.start();

        // TODO: move to worker thread
        try {
            FileOutputStream out = new FileOutputStream(DMHelper.POSTPONED_DATA_PATH);
            out.write(mData);
            out.close();
        } catch (IOException e) {
            Log.e(TAG, "IOException while creating dmpostponed.dat", e);
        }

        return true;
    }

// TODO: remove or uncomment to use for saving file asynchronously
//    class DMParseSaveWapMsgRunnable implements Runnable {
//        /** Pending result to call finish() when thread returns. */
//        private final PendingResult mPendingResult;
//
//        DMParseSaveWapMsgRunnable(PendingResult pendingResult) {
//            mPendingResult = pendingResult;
//        }
//
//        @Override
//        public void run() {
//            Log.d(TAG, "Enter dmParseSaveWapMsgThread tid=" + Thread.currentThread().getId());
//            try {
//                FileOutputStream out = new FileOutputStream(DMHelper.POSTPONED_DATA_PATH);
//                out.write(mData);
//                out.close();
//            } catch (IOException e) {
//                Log.e(TAG, "IOException while creating dmpostponed.dat", e);
//            } finally {
//                mPendingResult.finish();
//            }
//        }
//    }

    //try to start DM session or starts Data and Call State Monitoring Service
    private void startProcess(Context context) {

        //wrj348 - VZW customization: reject the wap push if phone is in ECB mode or Roaming
        if (!allowDMSession()) {
            return;
        }

        setState(context, DMHelper.STATE_APPROVED_BY_USER);

        // try to start DM session or start monitoring service.
        if (isWifiConnected(context) || isDataNetworkAcceptable(context)) {
            if ((!isWifiConnected(context)) && isDataNetworkAcceptable(context) && isPhoneTypeLTE()) {
                Log.d(TAG, "startProcess(), start apn state monitoring service");
                setFotaApnState(context, DMHelper.FOTA_APN_STATE_START_DM_SESSION);
                //start apn state monitoring service
                startApnStateMonitoringService(context);
            } else {
                startDMSession(context);
            }
            //clearSharedProperties();
        } else {
            startDataAndCallStateMonitoringService(context);
        }
    }

    private static boolean allowDMSession() {
        if (isInECBMode()) {
            return false;
        }

        Log.i(TAG, "DMSession allowed - don't reject");
        return true;
    }

    /**
     * Returns whether phone is in emergency callback mode.
     * @return true if the phone is in ECB mode; false if not
     */
    private static boolean isInECBMode() {
        boolean ecbMode = SystemProperties.getBoolean(
                TelephonyProperties.PROPERTY_INECM_MODE, false);
        Log.i(TAG, "Phone ECB status: " + ecbMode);
        return ecbMode;
    }

    //start DM session.
    private void startDMSession(Context context) {
        if (DBG) logd("startDMSession");
        // get request ID from the shared preferences (the message time stamp used)
        SharedPreferences p = context.getSharedPreferences(DMHelper.DM_PREFERENCES_KEY, 0);

        long requestID = p.getLong(DMHelper.MESSAGE_TIMESTAMP_ID_KEY, -1);
        int type = p.getInt(DMHelper.DM_SESSION_TYPE_KEY, -1);

        // create intent and start DM service
        Intent intent = new Intent(DMIntent.LAUNCH_INTENT);
        intent.putExtra(DMIntent.FIELD_REQUEST_ID, requestID);
        intent.putExtra(DMIntent.FIELD_TYPE, type);

        if (type == DMIntent.TYPE_FOTA_CLIENT_SESSION_REQUEST) {
            String serverID = p.getString(DMHelper.FOTA_SERVER_ID_KEY, null);
            String alertString = p.getString(DMHelper.FOTA_ALERT_STRING_KEY, null);
            intent.putExtra(DMIntent.FIELD_TYPE, DMIntent.TYPE_FOTA_CLIENT_SESSION_REQUEST);
            intent.putExtra(DMIntent.FIELD_SERVERID, serverID);
            intent.putExtra(DMIntent.FIELD_ALERT_STR, alertString);
            Log.d(TAG, "starting TYPE_FOTA_CLIENT_SESSION_REQUEST: serverID="
                    + serverID + " alertString=" + alertString
                    + " requestID=" + requestID);
        } else {
            // package 0 notification
            intent.putExtra(DMIntent.FIELD_TYPE, type);

            if (mData == null) { // session has not been started right away after receiving a message.
                mData = setDataFromFile(context);

                if (mData == null) {
                    Log.d(TAG, "Error. Cannot read data from file dmpostponed.dat");
                    DMHelper.cleanAllResources(context);
                    return;
                }
            }

            intent.putExtra(DMIntent.FIELD_PKG0, mData);
        }

        increaseDMSessionAttempt(context);

        setState(context, DMHelper.STATE_SESSION_IN_PROGRESS);

        DMHelper.subscribeForTimeAlert(context,
                DMHelper.TIME_CHECK_STATUS_AFTER_STARTING_DM_SERVICE);

        intent.setClass(context, DMClientService.class);
        context.startService(intent);
    }

    //start Data and Call State Monitoring Service
    private static void startDataAndCallStateMonitoringService(Context context) {
        DMHelper.subscribeForTimeAlert(context,
                DMHelper.TIME_CHECK_STATUS_AFTER_STARTING_MONITORING_SERVICE);
        Intent intent = new Intent(DMIntent.ACTION_START_STATE_MONITORING_SERVICE);
        intent.setClass(context, DataAndCallStateMonitoringService.class);
        context.startService(intent);
    }

    // Verify session result: if result is successful, clean all resources.
    // Otherwise, try to resubmit session request.
    private void handleDmServiceResult(Context context, Intent intent) {
        // check if request ID from incoming intent match to the one which has been sent and saved
        // get request ID from the shared preferences (the message time stamp used)
        SharedPreferences p = context.getSharedPreferences(DMHelper.DM_PREFERENCES_KEY, 0);
        SharedPreferences.Editor ed = p.edit();
        long savedRequestId = p.getLong(DMHelper.MESSAGE_TIMESTAMP_ID_KEY, 0);
        long receivedRequestId = intent.getLongExtra(DMIntent.FIELD_REQUEST_ID, -1);

        if (receivedRequestId == -2) {
            Log.d(TAG, "handleDmServiceResult, tree initialisation session.");
            return;
        }

        // clear fota apn resources and stop using fota apn
        if (isPhoneTypeLTE()) {
            int fotaApnState = getFotaApnState(context);
            Log.d(TAG, "handleDmServiceResult, chk if need to stop using fota apn "
                    + fotaApnState);
            if (fotaApnState != DMHelper.FOTA_APN_STATE_INIT) {
                // resetting FOTA APN STATE
                setFotaApnState(context, DMHelper.FOTA_APN_STATE_INIT);
                stopUsingFotaApn(context);
                // removing shared prefs settings
                DMHelper.cleanFotaApnResources(context);
            }
            stopUsingFotaApn(context);
        }

        if (savedRequestId != receivedRequestId) {
            Log.e(TAG, "request ID " + receivedRequestId + " from result intent doesn't "
                    + "match saved request ID " + savedRequestId + ", ignored");
//            return;
        }

        int sessionResult = intent.getIntExtra(DMIntent.FIELD_DMRESULT, -1);

        int uiMode = p.getInt(DMHelper.DM_UI_MODE_KEY, -1);
        mUIMode = uiMode;
        Log.d(TAG, "mUIMode is: " + uiMode);
        if (uiMode == DMHelper.UI_MODE_INFORMATIVE) {
            if (sessionResult == DMResult.SYNCML_DM_SUCCESS) {
                Log.d(TAG, "Displaying success notification message2");
                DMHelper.postInformativeNotification_message2_success(context);
            } else {
                Log.d(TAG, "Displaying Fail notification message2");
                DMHelper.postInformativeNotification_message2_fail(context);
            }
            ed.putInt(DMHelper.DM_UI_MODE_KEY, -1);
            ed.commit();
        }

        if (sessionResult == DMResult.SYNCML_DM_SUCCESS) {
            DMHelper.cleanAllResources(context);
            Log.d(TAG, "Finished success.");
            return;
        }

        if (!canRestartSession(context, p)) {
            DMHelper.cleanAllResources(context);
            return;
        }

        // update status in the preferences
        setState(context, DMHelper.STATE_APPROVED_BY_USER);

        //subscribe for the time alert to start DM session again after TIME_BETWEEN_SESSION_ATTEMPTS.
        DMHelper.subscribeForTimeAlert(context, DMHelper.TIME_BETWEEN_SESSION_ATTEMPTS);
    }

    // check if session request can be resubmitted (if message still valid and
    // number of tries doesn't exceed MAX)
    private static boolean canRestartSession(Context context, SharedPreferences p) {
        int numberOfSessionAttempts = p.getInt(DMHelper.DM_SESSION_ATTEMPTS_KEY, -1);

        // check if max number has not been exceeded
        if (numberOfSessionAttempts > DMHelper.MAX_SESSION_ATTEMPTS) {
            Log.d(TAG, "Error. Number of attempts to start DM session exceed MAX.");
            return false;
        }

        // check if message is expired or not
        if (DMHelper.isMessageExpired(context)) {
            Log.d(TAG, "Error from canRestartSession(): the message is expired.");
            return false;
        }

        return true;
    }

    // set current state
    private static void setState(Context context, int state) {
        SharedPreferences p = context.getSharedPreferences(DMHelper.DM_PREFERENCES_KEY, 0);
        SharedPreferences.Editor ed = p.edit();
        ed.putInt(DMHelper.STATE_KEY, state);
        ed.commit();
    }

    /**
     * Get current state from shared prefs. If state is "Session In Progress", verify that the DM
     * session didn't fail and also has the same status, otherwise current state will be changed
     * to "Approved by User" and will be ready to handle a request for a new DM session.
     *
     * @param context the context to use
     * @return the current state
     */
    private static int getState(Context context) {
        SharedPreferences p = context.getSharedPreferences(DMHelper.DM_PREFERENCES_KEY, 0);
        int currentState = p.getInt(DMHelper.STATE_KEY, 0);

        if (currentState == DMHelper.STATE_SESSION_IN_PROGRESS
                && !DMClientService.sIsDMSessionInProgress) {
            currentState = DMHelper.STATE_APPROVED_BY_USER;
            setState(context, currentState);
        }
        return currentState;
    }

    // increase attempt to start DM session
    private static void increaseDMSessionAttempt(Context context) {
        SharedPreferences p = context.getSharedPreferences(DMHelper.DM_PREFERENCES_KEY, 0);
        int numberOfSessionAttempts = p.getInt(DMHelper.DM_SESSION_ATTEMPTS_KEY, 0);
        SharedPreferences.Editor ed = p.edit();
        ed.putInt(DMHelper.DM_SESSION_ATTEMPTS_KEY, (numberOfSessionAttempts + 1));
        ed.commit();
    }

    // check and initialize variables from preferences
    private boolean initFromSharedPreferences(Context context) {
        SharedPreferences p = context.getSharedPreferences(DMHelper.DM_PREFERENCES_KEY, 0);
        long timestamp = p.getLong(DMHelper.MESSAGE_TIMESTAMP_ID_KEY, -1);
        mUIMode = p.getInt(DMHelper.DM_UI_MODE_KEY, -1);
        mData = setDataFromFile(context);
        boolean success = !(timestamp <= 0 || mUIMode < 0);
        if (DBG) logd("initFromSharedPreferences: " + (success ? "ok" : "fail"));
        return success;
    }

    private static byte[] setDataFromFile(Context context) {
        SharedPreferences p = context.getSharedPreferences(DMHelper.DM_PREFERENCES_KEY, 0);
        int length = p.getInt("length", -1);

        if (length <= 0) {
            //Log.d(TAG, "Error. Invalid postponed data length.");
            return null;
        }

        byte[] data = new byte[length];

        try {
            FileInputStream in = new FileInputStream(DMHelper.POSTPONED_DATA_PATH);
            if (in.read(data) <= 0) {
                Log.d(TAG, "Invalid postponed data.");
                in.close();
                return null;
            }
            in.close();
            return data;
        } catch (IOException e) {
            Log.e(TAG, "IOException", e);
            return null;
        }
    }

    private static boolean treeExist(Context context) {
        if (context != null) {
            String strTreeHomeDir = context.getFilesDir().getAbsolutePath() + "/dm";
            File dirDes = new File(strTreeHomeDir);

            if (dirDes.exists() && dirDes.isDirectory()) {
                Log.d(TAG, "DM Tree exists:" + strTreeHomeDir);
                return true;
            } else {
                Log.d(TAG, "DM Tree NOT exists:" + strTreeHomeDir);
                return false;
            }
        } else {
            return false;
        }
    }

    //apn state monitoring service
    private static void startApnStateMonitoringService(Context context) {
        Log.d(TAG, "Inside startApnStateMonitoringService");
        //DMHelper.subscribeForTimeAlert(context, DMHelper.TIME_CHECK_STATUS_APN_STATE);
        Intent intent = new Intent("com.android.omadm.service.apn_state_monitoring_service");
        intent.setClass(context, ApnStateMonitoringService.class);
        context.startService(intent);
    }

    //stop apn state monitoring service
    private static void stopApnStateMonitoringService(Context context) {
        Log.d(TAG, "Inside stopApnStateMonitoringService");
        //DMHelper.subscribeForTimeAlert(context, DMHelper.TIME_CHECK_STATUS_APN_STATE);
        Intent intent = new Intent("com.android.omadm.service.apn_state_monitoring_service");
        intent.setClass(context, ApnStateMonitoringService.class);
        context.stopService(intent);
    }

    // set current state
    private static void setFotaApnState(Context context, int state) {
        Log.d(TAG, "setFotaApnState: " + state);
        SharedPreferences p = context.getSharedPreferences(DMHelper.FOTA_APN_PREFERENCE_KEY, 0);
        SharedPreferences.Editor ed = p.edit();
        ed.putInt(DMHelper.FOTA_APN_STATE_KEY, state);
        ed.commit();
    }

    // get current state.
    private static int getFotaApnState(Context context) {
        SharedPreferences p = context.getSharedPreferences(DMHelper.FOTA_APN_PREFERENCE_KEY, 0);
        return p.getInt(DMHelper.FOTA_APN_STATE_KEY, 0);
    }

    /**
     * Stop using the FOTA APN.
     * @param context the BroadcastReceiver context
     */
    private static void stopUsingFotaApn(Context context) {
        Log.d(TAG, "stopUsingFotaApn");

        ConnectivityManager connMgr = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        int result = connMgr.stopUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
                Phone.FEATURE_ENABLE_FOTA);
        if (result != -1) {
            Log.w(TAG, "stopUsingNetworkFeature result=" + result);
        }
        stopApnStateMonitoringService(context);
    }


    // Function which will send intents to start FDM
    private static void sendNotifyIntent(Context context) {
        Log.d(TAG, "Inside sendNotifyIntent");

        SharedPreferences p = context.getSharedPreferences(DMHelper.FOTA_APN_PREFERENCE_KEY, 0);
        String lawmoResult = p.getString(DMHelper.LAWMO_RESULT_KEY, null);
        String fotaResult = p.getString(DMHelper.FOTA_RESULT_KEY, null);
        String pkgURI = p.getString(DMHelper.PKG_URI_KEY, null);
        String alertType = p.getString(DMHelper.ALERT_TYPE_KEY, null);
        String correlator = p.getString(DMHelper.CORRELATOR_KEY, null);
        String serverID = p.getString(DMHelper.SERVER_ID_KEY, null);

        Log.d(TAG, "sendNotifyIntent Input==>\n" + " lawmoResult="
                + lawmoResult + '\n' + "fotaResult="
                + fotaResult + '\n' + " pkgURI="
                + pkgURI + '\n' + " alertType="
                + alertType + '\n' + " serverID="
                + serverID + '\n' + " correlator="
                + correlator);

        if (alertType.equals(ALERT_TYPE_DOWNLOADANDUPDATE)) {
            // Need to send an intent for doing a FOTA FDM session
            Intent fotafdmintent = new Intent(DMIntent.LAUNCH_INTENT);
            fotafdmintent.putExtra(DMIntent.FIELD_TYPE, DMIntent.TYPE_FOTA_NOTIFY_SERVER);
            fotafdmintent.putExtra(DMIntent.FIELD_FOTA_RESULT, fotaResult);
            fotafdmintent.putExtra(DMIntent.FIELD_PKGURI, pkgURI);
            fotafdmintent.putExtra(DMIntent.FIELD_ALERTTYPE, alertType);
            fotafdmintent.putExtra(DMIntent.FIELD_SERVERID, serverID);
            fotafdmintent.putExtra(DMIntent.FIELD_CORR, correlator);
            fotafdmintent.setClass(context, DMClientService.class);
            context.startService(fotafdmintent);
        } else if (pkgURI.equals(RP_OPERATIONS_FACTORYRESET) || pkgURI
                .equals(RP_EXT_OPERATIONS_RESET)) {
            // LAWMO FDM session
            Intent lawmofdmintent = new Intent(DMIntent.LAUNCH_INTENT);
            lawmofdmintent.putExtra(DMIntent.FIELD_TYPE, DMIntent.TYPE_LAWMO_NOTIFY_SESSION);
            lawmofdmintent.putExtra(DMIntent.FIELD_LAWMO_RESULT, lawmoResult);
            lawmofdmintent.putExtra(DMIntent.FIELD_PKGURI, pkgURI);
            lawmofdmintent.putExtra(DMIntent.FIELD_ALERTTYPE, "");
            lawmofdmintent.putExtra(DMIntent.FIELD_CORR, "");
            lawmofdmintent.setClass(context, DMClientService.class);
            context.startService(lawmofdmintent);
        } else {
            // just return for now
            Log.d(TAG, "No Action, Just return for now");
        }
    }

    private static boolean isWifiConnected(Context context) {
        Log.d(TAG, "Inside isWifiConnected");
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }

        NetworkInfo ni = cm.getActiveNetworkInfo();

        // return true only when WiFi is connected
        return ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
    }


    private static boolean isPhoneTypeLTE() {
        return DMSettingsHelper.isPhoneTypeLTE();
    }

    private static boolean isPhoneTypeCDMA3G(Context context) {
        TelephonyManager tm = (TelephonyManager) context
                .getSystemService(Context.TELEPHONY_SERVICE);
        if ((tm.getCurrentPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) && !isPhoneTypeLTE()) {
            Log.d(TAG, "3G CDMA phone");
            return true;
        }
        Log.d(TAG, "Non-CDMA or 4G Device");
        return false;
    }

    // check if we can set up a mobile data connection on this network type
    private static boolean isDataNetworkAcceptable(Context context) {
        TelephonyManager tm = (TelephonyManager) context
                .getSystemService(Context.TELEPHONY_SERVICE);

        int callState = tm.getCallState();
        if (callState != TelephonyManager.CALL_STATE_IDLE) {
            if (DBG) logd("Call state not idle: " + callState);
            return false;
        }

        int dataNetworkType = tm.getDataNetworkType();
        switch (dataNetworkType) {
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_LTE:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                if (DBG) logd("Data network type is acceptable: " + dataNetworkType);
                return true;

            default:
                if (DBG) logd("Data network type is not acceptable: " + dataNetworkType);
                return false;
        }
    }

    private static void saveDevDetail(Context context) {
        Log.d(TAG, "Inside saveDevDetail");

        String swVer = SystemProperties.get("ro.build.version.full");
        if (TextUtils.isEmpty(swVer)) {
            swVer = "Unknown";
        }

        SharedPreferences p = context.getSharedPreferences(DEV_DETAIL, 0);
        String currFwV = p.getString(CURR_FW_VER, null);
        //String preFwV = p.getString(PRE_FW_VER, null);

        SharedPreferences.Editor ed = p.edit();
        if (TextUtils.isEmpty(currFwV)) {
            Log.d(TAG, "First powerup or powerup after FDR, save current SwV");
            ed.putString(CURR_FW_VER, swVer);
        } else if (!(currFwV.equals(swVer))) {
            Log.d(TAG, "System Update success, save previous FwV and LastUpdateTime");
            ed.putString(PRE_FW_VER, currFwV);
            ed.putString(CURR_FW_VER, swVer);
            SimpleDateFormat simpleDateFormat;
            if (isPhoneTypeLTE()) {
                simpleDateFormat = new SimpleDateFormat("MM:dd:yyyy:HH:mm", Locale.US);
                simpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            } else {
                simpleDateFormat = new SimpleDateFormat("MM:dd:yy:HH:mm:ss:z", Locale.US);
            }
            String currTime = simpleDateFormat.format(new Date(System.currentTimeMillis()));
            ed.putString(LAST_UPD_TIME, currTime);
        }
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo wi = wm.getConnectionInfo();
        String wMacAddr = (wi == null) ? null : wi.getMacAddress();
        Log.d(TAG, "WiFi Mac address " + wMacAddr);
        if (!TextUtils.isEmpty(wMacAddr)) {
            ed.putString(WIFI_MAC_ADDR, wMacAddr);
        }
        ed.commit();
    }

    private static final void logd(String msg) {
        Log.d(TAG, msg);
    }
}