package net.kismetwireless.android.smarterwifimanager;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.telephony.CellLocation;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class SmarterWifiService extends Service {
    public enum ControlType {
        CONTROL_DISABLED, CONTROL_USER, CONTROL_RANGE, CONTROL_TOWERID, CONTROL_GEOFENCE, CONTROL_BLUETOOTH, CONTROL_TIME
    }

    public enum WifiState {
        // Hard blocked, on, off, idle, ignore
        WIFI_BLOCKED, WIFI_ON, WIFI_OFF, WIFI_IDLE, WIFI_IGNORE
    }

    // Are we autolearning, and if not, why not
    public enum LearningState {
        LEARNING_OFF, LEARNING_ON, LEARNING_IDLE, LEARNING_SSIDBLACKLIST
    }

    public enum TowerType {
        TOWER_UNKNOWN, TOWER_BLOCK, TOWER_ENABLE
    }

    private boolean shutdown = false;

    SharedPreferences preferences;

    TelephonyManager telephonyManager;
    SmarterPhoneListener phoneListener;
    WifiManager wifiManager;
    ConnectivityManager connectivityManager;
    private NotificationManager notificationManager;

    private boolean proctorWifi = true;
    private boolean learnWifi = true;

    private int enableWaitSeconds = 1;
    private int disableWaitSeconds = 30;

    private WifiState userOverrideState = WifiState.WIFI_IGNORE;

    private String currentSsid;
    private CellLocationCommon currentCellLocation;
    private TowerType currentTowerType = TowerType.TOWER_UNKNOWN;

    private ControlType lastControlReason = ControlType.CONTROL_TOWERID;

    private Handler timerHandler = new Handler();

    private SmarterDBSource dbSource;

    private NotificationCompat.Builder notificationBuilder;

    public static abstract class SmarterServiceCallback {
        protected ControlType controlType;
        protected WifiState wifiState;
        protected String lastSsid;
        protected TowerType towerType;
        protected LearningState learningState;

        public void wifiStateChanged(String ssid, WifiState state, ControlType type) {
            lastSsid = ssid;
            wifiState = state;
            controlType = type;

            return;
        }

        public void towerStateChanged(long towerid, TowerType type) {
            towerType = type;

            return;
        }
    }

    ArrayList<SmarterServiceCallback> callbackList = new ArrayList<SmarterServiceCallback>();

    // Minimal in the extreme binder which returns the service for direct calling
    public class ServiceBinder extends Binder {
        SmarterWifiService getService() {
            return SmarterWifiService.this;
        }
    }

    private ServiceBinder serviceBinder = new ServiceBinder();
    private Context context;

    private SmarterServiceCallback notifcationCallback = new SmarterServiceCallback() {
        @Override
        public void wifiStateChanged(String ssid, WifiState state, ControlType type) {
            super.wifiStateChanged(ssid, state, type);

            int wifiIconId = R.drawable.custom_wifi_inactive;
            String wifiText = "";
            String reasonText = "";

            switch (state) {
                case WIFI_IDLE:
                    wifiIconId = R.drawable.custom_wifi_inactive;
                    wifiText = "Wi-Fi idle / disconnected";
                    break;
                case WIFI_BLOCKED:
                    wifiIconId = R.drawable.custom_wifi_disabled_tower;
                    wifiText = "Wi-Fi ";
                    break;
                case WIFI_ON:
                    wifiIconId = R.drawable.custom_wifi_enabled;
                    wifiText = "Wi-Fi enabled";
                    break;
                case WIFI_OFF:
                    wifiIconId = R.drawable.custom_wifi_inactive;
                    wifiText = "Wi-Fi turned off";
                    break;
                case WIFI_IGNORE:
                    wifiIconId = R.drawable.custom_wifi_enabled;
                    wifiText = "Wi-Fi management disabled";
                    break;

                default:
                    wifiIconId = R.drawable.custom_wifi_inactive;
            }

            reasonText = SmarterWifiService.controlTypeToText(lastControlReason);

            notificationBuilder.setSmallIcon(wifiIconId);
            notificationBuilder.setContentTitle(wifiText);
            notificationBuilder.setContentText(reasonText);

            notificationManager.notify(0, notificationBuilder.build());
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        context = this;

        preferences = PreferenceManager.getDefaultSharedPreferences(context);

        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);

        try {
            dbSource = new SmarterDBSource(context);
        } catch (SQLiteException e) {
            Log.d("smarter", "failed to open database: " + e);
        }

        // Default network state
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        phoneListener = new SmarterPhoneListener();

        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(phoneListener, PhoneStateListener.LISTEN_CELL_LOCATION);

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        notificationBuilder = new NotificationCompat.Builder(context);

        // Make the notification
        notificationBuilder.setSmallIcon(R.drawable.custom_wifi_enabled);
        notificationBuilder.setPriority(NotificationCompat.PRIORITY_MIN);
        notificationBuilder.setOnlyAlertOnce(true);
        notificationBuilder.setOngoing(true);

        /*
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pIntent = PendingIntent.getActivity(this, 0, intent, 0);
        notificationBuilder.addAction(R.drawable.custom_wifi_disabled, "Disable", pIntent);
        notificationBuilder.addAction(R.drawable.custom_wifi_enabled, "Enable", pIntent);
        */

        // Kick an update
        setCurrentTower(new CellLocationCommon((CellLocation) null));
        updatePreferences();

        notificationManager.notify(0, notificationBuilder.build());

        addCallback(notifcationCallback);

    }

    @Override
    public IBinder onBind(Intent intent) {
        return serviceBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        shutdown = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public void updatePreferences() {
        learnWifi = preferences.getBoolean(getString(R.string.pref_learn), true);
        proctorWifi = preferences.getBoolean(getString(R.string.pref_enable), true);

        configureWifiState();
    }

    public void shutdownService() {
        shutdown = true;
        timerHandler.removeCallbacks(wifiDisableTask);
        telephonyManager.listen(phoneListener, 0);
    }

    private void startWifiEnable() {
        // Restart the enable
        timerHandler.removeCallbacks(wifiEnableTask);

        timerHandler.postDelayed(wifiEnableTask, enableWaitSeconds * 1000);

        Log.d("smarter", "Starting countdown of " + enableWaitSeconds + " to enable wifi");

    }

    private void startWifiShutdown() {
        // Restart the countdown incase we got called while a countdown was already running
        timerHandler.removeCallbacks(wifiDisableTask);

        // Set a timer - if we haven't connected to a network by the time this
        // expires, shut down wifi again
        timerHandler.postDelayed(wifiDisableTask, disableWaitSeconds * 1000);

        Log.d("smarter", "Starting countdown of " + disableWaitSeconds + " to shut down wifi");
    }

    private Runnable wifiEnableTask = new Runnable() {
        public void run() {
            if (shutdown) return;

            if (!proctorWifi) return;

            Log.d("smarter", "enabling wifi");
            wifiManager.setWifiEnabled(true);
        }
    };

    private Runnable wifiDisableTask = new Runnable() {
        public void run() {
            if (shutdown) return;

            // If we're not proctoring wifi...
            if (!proctorWifi) return;

            if (getWifiState() == WifiState.WIFI_ON) {
                Log.d("smarter", "We were going to shut down wifi, but it's connected now");
                return;
            }

            Log.d("smarter", "Shutting down wi-fi, we haven't gotten a link");
            wifiManager.setWifiEnabled(false);
        }
    };

    // Set current tower or fetch current tower
    private void handleCellLocation(CellLocation location) {
        if (location == null) {
            location = telephonyManager.getCellLocation();
        }

        setCurrentTower(new CellLocationCommon(location));
    }

    private class SmarterPhoneListener extends PhoneStateListener {
        @Override
        public void onCellLocationChanged(CellLocation location) {
            handleCellLocation(location);
        }
    }

    // Set the current tower and figure out what our tower state is
    private void setCurrentTower(CellLocationCommon curloc) {
        if (curloc == null) {
            curloc = new CellLocationCommon(telephonyManager.getCellLocation());
        }

        currentCellLocation = curloc;

        currentTowerType = TowerType.TOWER_UNKNOWN;

        if (curloc.getTowerId() > 0 && learnWifi) {
            // If we know this tower already, set type to enable
            if (dbSource.queryTowerMapped(curloc.getTowerId())) {
                Log.d("smarter", "Found known tower");
                currentTowerType = TowerType.TOWER_ENABLE;
            }

            // If we're associated to a wifi, map the tower
            // TODO add blacklisting of towers, etc, here
            if (getWifiState() == WifiState.WIFI_ON) {
                Log.d("smarter", "New tower, Wi-Fi enabled, learning tower");
                dbSource.mapTower(getCurrentSsid(), curloc.getTowerId());
                currentTowerType = TowerType.TOWER_ENABLE;
            }

            triggerCallbackTowerChanged();
            configureWifiState();

            return;
        }

        triggerCallbackTowerChanged();
    }

    public void addCallback(SmarterServiceCallback cb) {
        synchronized (callbackList) {
            callbackList.add(cb);
        }

        // Call our CBs immediately for setup
        cb.towerStateChanged(currentCellLocation.getTowerId(), currentTowerType);
        cb.wifiStateChanged(getCurrentSsid(), getWifiState(), lastControlReason);

    }

    public void removeCallback(SmarterServiceCallback cb) {
        synchronized (callbackList) {
            callbackList.remove(cb);
        }
    }

    public void triggerCallbackTowerChanged() {
       synchronized (callbackList) {
           for (SmarterServiceCallback cb : callbackList) {
               cb.towerStateChanged(currentCellLocation.getTowerId(), currentTowerType);
           }
       }
    }

    public void triggerCallbackWifiChanged() {
        synchronized (callbackList) {
            for (SmarterServiceCallback cb: callbackList) {
                cb.wifiStateChanged(getCurrentSsid(), getWifiState(), lastControlReason);
            }
        }
    }

    public void configureWifiState() {
        WifiState curstate = getWifiState();
        WifiState targetstate = getShouldWifiBeEnabled();

        if (curstate == WifiState.WIFI_ON || curstate == WifiState.WIFI_IDLE) {
            if (targetstate == WifiState.WIFI_BLOCKED) {
                Log.d("smarter", "Target state: Blocked, shutting down wifi now, " + controlTypeToText(lastControlReason));
                timerHandler.removeCallbacks(wifiEnableTask);
                wifiManager.setWifiEnabled(false);
            } else if (targetstate == WifiState.WIFI_OFF) {
                Log.d("smarter", "Target state: Off, scheduling shutdown, " + controlTypeToText(lastControlReason));
                timerHandler.removeCallbacks(wifiEnableTask);
                startWifiShutdown();
            }
        } else {
            if (targetstate == WifiState.WIFI_ON) {
                Log.d("smarter", "Target state: On, scheduling bringup, " + controlTypeToText(lastControlReason));
                timerHandler.removeCallbacks(wifiDisableTask);
                startWifiEnable();
            }
        }

        triggerCallbackWifiChanged();
    }

    // Based on everything we know, should wifi be enabled?
    // WIFI_ON - Turn it on
    // WIFI_OFF - Start a shutdown timer
    // WIFI_BLOCKED - Kill it immediately
    // WIFI_IDLE - Do nothing
    public WifiState getShouldWifiBeEnabled() {
        WifiState curstate = getWifiState();

        // We're not looking at all
        if (proctorWifi == false) {
            lastControlReason = ControlType.CONTROL_DISABLED;
            return WifiState.WIFI_IGNORE;
        }

        // User requests override everything
        if (userOverrideState == WifiState.WIFI_OFF) {
            lastControlReason = ControlType.CONTROL_USER;
            return WifiState.WIFI_BLOCKED;
        }

        if (userOverrideState == WifiState.WIFI_ON) {
            lastControlReason = ControlType.CONTROL_USER;
            return WifiState.WIFI_ON;
        }

        if (learnWifi) {
            if (currentTowerType == TowerType.TOWER_BLOCK) {
                lastControlReason = ControlType.CONTROL_TOWERID;
                return WifiState.WIFI_BLOCKED;
            }

            if (currentTowerType == TowerType.TOWER_ENABLE) {
                lastControlReason = ControlType.CONTROL_RANGE;
                return WifiState.WIFI_ON;
            }

            if (currentTowerType == TowerType.TOWER_UNKNOWN &&
                    curstate == WifiState.WIFI_ON) {
                lastControlReason = ControlType.CONTROL_RANGE;
                return WifiState.WIFI_ON;
            }

            if (currentTowerType == TowerType.TOWER_UNKNOWN &&
                    curstate == WifiState.WIFI_IDLE) {
                lastControlReason = ControlType.CONTROL_RANGE;
                return WifiState.WIFI_OFF;
            }

            lastControlReason = ControlType.CONTROL_RANGE;
            return WifiState.WIFI_OFF;
        }

        return WifiState.WIFI_IGNORE;
    }

    public WifiState getWifiState() {
        if (!proctorWifi) {
            lastControlReason = ControlType.CONTROL_DISABLED;
            return WifiState.WIFI_IGNORE;
        }

        int rawstate = wifiManager.getWifiState();

        boolean rawwifienabled = false;

        if (rawstate == WifiManager.WIFI_STATE_ENABLED || rawstate == WifiManager.WIFI_STATE_ENABLING)
            rawwifienabled = true;

        NetworkInfo rawni = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        boolean rawnetenabled = false;

        if (rawni != null && rawni.isConnected())
            rawnetenabled = true;

        if (rawwifienabled && rawnetenabled) {
            currentSsid = wifiManager.getConnectionInfo().getSSID();
            return WifiState.WIFI_ON;
        }

        if (rawwifienabled && !rawnetenabled) {
            currentSsid = null;
            return WifiState.WIFI_IDLE;
        }

        return WifiState.WIFI_OFF;
    }

    public String getCurrentSsid() {
        if (currentSsid == null && getWifiState() == WifiState.WIFI_ON)
            currentSsid = wifiManager.getConnectionInfo().getSSID();

        return currentSsid;
    }

    public Long getCurrentTower() {
        return currentCellLocation.getTowerId();
    }

    public boolean getAirplaneMode() {
        return Settings.System.getInt(context.getContentResolver(), Settings.System.AIRPLANE_MODE_ON, 0) != 0;
    }

    public boolean getWifiStateEnabled(WifiState state) {
        if (state == WifiState.WIFI_ON || state == WifiState.WIFI_IDLE)
            return true;

        return false;
    }

    static public String controlTypeToText(ControlType t) {
        switch (t) {
            case CONTROL_DISABLED:
                return "Wi-Fi management disabled";
            case CONTROL_BLUETOOTH:
                return "Bluetooth";
            case CONTROL_GEOFENCE:
                return "Geofence";
            case CONTROL_RANGE:
                return "Auto-learned location";
            case CONTROL_TIME:
                return "Time range";
            case CONTROL_TOWERID:
                return "Tower ID";
            case CONTROL_USER:
                return "User override";
        }

        return "Unknown";
    }

    public ArrayList<SsidBlacklistEntry> getSsidBlacklist() {
        ArrayList<SsidBlacklistEntry> blist = new ArrayList<SsidBlacklistEntry>();
        List<WifiConfiguration> wic = wifiManager.getConfiguredNetworks();

        for (WifiConfiguration w : wic) {
            blist.add(dbSource.getSsidBlacklisted(w.SSID));
        }

        return blist;
    }

    public void setSsidBlacklist(SsidBlacklistEntry ssid, boolean blacklisted) {
        dbSource.setSsidBlacklisted(ssid, blacklisted);
    }

}