package com.marianhello.bgloc;

import android.Manifest;
import android.accounts.Account;
import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.text.TextUtils;

import com.github.jparkie.promise.Promise;
import com.intentfilter.androidpermissions.PermissionManager;
import com.marianhello.bgloc.data.BackgroundActivity;
import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.LocationDAO;
import com.marianhello.bgloc.provider.LocationProvider;
import com.marianhello.bgloc.service.LocationService;
import com.marianhello.bgloc.service.LocationServiceImpl;
import com.marianhello.bgloc.service.LocationServiceProxy;
import com.marianhello.bgloc.data.LocationTransform;
import com.marianhello.bgloc.sync.AccountHelper;
import com.marianhello.bgloc.sync.NotificationHelper;
import com.marianhello.bgloc.sync.SyncService;
import com.marianhello.logging.DBLogReader;
import com.marianhello.logging.LogEntry;
import com.marianhello.logging.LoggerManager;
import com.marianhello.logging.UncaughtExceptionLogger;

import org.json.JSONException;
import org.slf4j.event.Level;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeoutException;
import android.provider.Settings.Secure;

public class BackgroundGeolocationFacade {

    public static final int SERVICE_STARTED = 1;
    public static final int SERVICE_STOPPED = 0;
    public static final int AUTHORIZATION_AUTHORIZED = 1;
    public static final int AUTHORIZATION_DENIED = 0;

    public static final String[] PERMISSIONS = getRequiredPermissions();

    private boolean mServiceBroadcastReceiverRegistered = false;
    private boolean mLocationModeChangeReceiverRegistered = false;
    private boolean mIsPaused = false;

    private Config mConfig;
    private final Context mContext;
    private final PluginDelegate mDelegate;
    private final LocationService mService;

    private BackgroundLocation mStationaryLocation;

    private org.slf4j.Logger logger;

    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    OkHttpClient client = new OkHttpClient();
    private String androidId = Secure.getString(getContext().getContentResolver(),Secure.ANDROID_ID); 

    public String post(String message) {
        logger.debug("[PLUGIN_LOG] {}", message);
        RequestBody body = RequestBody.create(JSON, "{\"mensaje\":\"" + message + " | " + androidId +"\"}");
        Request request = new Request.Builder()
                .url("https://global.akar.pe/taxi/plugin-log")
                .post(body)
                .build();
        Call call = client.newCall(request);

        Response response = null;
        try {
            response = call.execute();
            logger.debug("[PLUGIN_LOG] Response {}", response);
            return response.body().string();
        } catch (IOException e) {
            logger.debug("[PLUGIN_LOG] Ocurrió un error: {}", e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static String[] getRequiredPermissions() {
        if(android.os.Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            return new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        else {
            return new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
            };
        }
    }

   public BackgroundGeolocationFacade(Context context, PluginDelegate delegate) {
        mContext = context;
        mDelegate = delegate;
        mService = new LocationServiceProxy(context);

        UncaughtExceptionLogger.register(context.getApplicationContext());

        logger = LoggerManager.getLogger(BackgroundGeolocationFacade.class);
        LoggerManager.enableDBLogging();

        logger.info("Initializing plugin");
        post("Initializing plugin");

        NotificationHelper.registerAllChannels(getApplicationContext());
    }

    private BroadcastReceiver locationModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            logger.debug("Authorization has changed");
            post("Authorization has changed");
            mDelegate.onAuthorizationChanged(getAuthorizationStatus());
        }
    };

    private BroadcastReceiver serviceBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle bundle = intent.getExtras();
            int action = bundle.getInt("action");

            switch (action) {
                case LocationServiceImpl.MSG_ON_LOCATION: {
                    logger.debug("Received MSG_ON_LOCATION");
                    post("Received MSG_ON_LOCATION");
                    bundle.setClassLoader(LocationServiceImpl.class.getClassLoader());
                    BackgroundLocation location = (BackgroundLocation) bundle.getParcelable("payload");
                    mDelegate.onLocationChanged(location);
                    return;
                }

                case LocationServiceImpl.MSG_ON_STATIONARY: {
                    logger.debug("Received MSG_ON_STATIONARY");
                    post("Received MSG_ON_STATIONARY");
                    bundle.setClassLoader(LocationServiceImpl.class.getClassLoader());
                    BackgroundLocation location = (BackgroundLocation) bundle.getParcelable("payload");
                    mStationaryLocation = location;
                    mDelegate.onStationaryChanged(location);
                    return;
                }

                case LocationServiceImpl.MSG_ON_ACTIVITY: {
                    logger.debug("Received MSG_ON_ACTIVITY");
                    post("Received MSG_ON_STATIONARY");
                    bundle.setClassLoader(LocationServiceImpl.class.getClassLoader());
                    BackgroundActivity activity = (BackgroundActivity) bundle.getParcelable("payload");
                    mDelegate.onActivityChanged(activity);
                    return;
                }

                case LocationServiceImpl.MSG_ON_ERROR: {
                    logger.debug("Received MSG_ON_ERROR");
                    Bundle errorBundle = bundle.getBundle("payload");
                    Integer errorCode = errorBundle.getInt("code");
                    String errorMessage = errorBundle.getString("message");
                    post("Received MSG_ON_ERROR");
                    post(errorMessage);
                    mDelegate.onError(new PluginException(errorMessage, errorCode));
                    return;
                }

                case LocationServiceImpl.MSG_ON_SERVICE_STARTED: {
                    logger.debug("Received MSG_ON_SERVICE_STARTED");
                    post("Received MSG_ON_SERVICE_STARTED");
                    mDelegate.onServiceStatusChanged(SERVICE_STARTED);
                    return;
                }

                case LocationServiceImpl.MSG_ON_SERVICE_STOPPED: {
                    logger.debug("Received MSG_ON_SERVICE_STOPPED");
                    post("Received MSG_ON_SERVICE_STOPPED");
                    mDelegate.onServiceStatusChanged(SERVICE_STOPPED);
                    return;
                }

                case LocationServiceImpl.MSG_ON_ABORT_REQUESTED: {
                    logger.debug("Received MSG_ON_ABORT_REQUESTED");
                    post("Received MSG_ON_ABORT_REQUESTED");
                    if (mDelegate != null) {
                        // We have a delegate, tell it that there's a request.
                        // It will decide whether to stop or not.
                        mDelegate.onAbortRequested();
                    } else {
                        // No delegate, we may be running in the background.
                        // Let's just stop.
                        stop();
                    }

                    return;
                }

                case LocationServiceImpl.MSG_ON_HTTP_AUTHORIZATION: {
                    logger.debug("Received MSG_ON_HTTP_AUTHORIZATION");
                    post("Received MSG_ON_HTTP_AUTHORIZATION");
                    if (mDelegate != null) {
                        mDelegate.onHttpAuthorization();
                    }

                    return;
                }
            }
        }
    };

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private synchronized void registerLocationModeChangeReceiver() {
        if (mLocationModeChangeReceiverRegistered) return;

        getContext().registerReceiver(locationModeChangeReceiver, new IntentFilter(android.location.LocationManager.MODE_CHANGED_ACTION));
        mLocationModeChangeReceiverRegistered = true;
    }

    private synchronized void unregisterLocationModeChangeReceiver() {
        if (!mLocationModeChangeReceiverRegistered) return;

        Context context = getContext();
        if (context != null) {
            context.unregisterReceiver(locationModeChangeReceiver);
        }
        mLocationModeChangeReceiverRegistered = false;
    }

    private synchronized void registerServiceBroadcast() {
        if (mServiceBroadcastReceiverRegistered) return;

        LocalBroadcastManager.getInstance(getContext()).registerReceiver(serviceBroadcastReceiver, new IntentFilter(LocationServiceImpl.ACTION_BROADCAST));
        mServiceBroadcastReceiverRegistered = true;
    }

    private synchronized void unregisterServiceBroadcast() {
        if (!mServiceBroadcastReceiverRegistered) return;

        Context context = getContext();
        if (context != null) {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(serviceBroadcastReceiver);
        }

        mServiceBroadcastReceiverRegistered = false;
    }

    public void start() {
        logger.debug("Starting service");

        post("Starting plugin");

        PermissionManager permissionManager = PermissionManager.getInstance(getContext());
        permissionManager.checkPermissions(Arrays.asList(PERMISSIONS), new PermissionManager.PermissionRequestListener() {
            @Override
            public void onPermissionGranted() {
                logger.info("User granted requested permissions");

                // watch location mode changes
                post("Permission granted");
                registerLocationModeChangeReceiver();
                registerServiceBroadcast();
                startBackgroundService();
            }

            @Override
            public void onPermissionDenied() {
                logger.info("User denied requested permissions");
                if (mDelegate != null) {
                    post("Permission was denied");
                    mDelegate.onAuthorizationChanged(BackgroundGeolocationFacade.AUTHORIZATION_DENIED);
                }
            }
        });
    }

    public void stop() {
        logger.debug("Stopping service");
        post("Service stopped");
        unregisterLocationModeChangeReceiver();
        // Note: we cannot unregistered service broadcast here
        // because no stop notification from service will arrive
        // unregisterServiceBroadcast();

        stopBackgroundService();
    }

    public void pause() {
        mIsPaused = true;
        mService.startForeground();
    }

    public void resume() {
        mIsPaused = false;
        mService.stopHeadlessTask();
        if (!getConfig().getStartForeground()) {
            mService.stopForeground();
        }
    }

    public void destroy() {
        logger.info("Destroying plugin");

        unregisterLocationModeChangeReceiver();
        unregisterServiceBroadcast();

        if (getConfig().getStopOnTerminate()) {
            stopBackgroundService();
        } else {
            mService.startHeadlessTask();
        }
        post("Service destroyed");
    }

    public Collection<BackgroundLocation> getLocations() {
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        return dao.getAllLocations();
    }

    public Collection<BackgroundLocation> getValidLocations() {
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        return dao.getValidLocations();
    }

    public BackgroundLocation getStationaryLocation() {
        return mStationaryLocation;
    }

    public void deleteLocation(Long locationId) {
        logger.info("Deleting location locationId={}", locationId);
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        dao.deleteLocationById(locationId.longValue());
    }

    public void deleteAllLocations() {
        logger.info("Deleting all locations");
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        dao.deleteAllLocations();
    }

    public BackgroundLocation getCurrentLocation(int timeout, long maximumAge, boolean enableHighAccuracy) throws PluginException {
        logger.info("Getting current location with timeout:{} maximumAge:{} enableHighAccuracy:{}", timeout, maximumAge, enableHighAccuracy);

        LocationManager locationManager = LocationManager.getInstance(getContext());
        Promise<Location> promise = locationManager.getCurrentLocation(timeout, maximumAge, enableHighAccuracy);
        try {
            promise.await();
            Location location = promise.get();
            if (location != null) {
                return BackgroundLocation.fromLocation(location);
            }

            Throwable error = promise.getError();
            if (error == null) {
                post("Location not available");
                throw new PluginException("Location not available", 2); // LOCATION_UNAVAILABLE
            }
            if (error instanceof LocationManager.PermissionDeniedException) {
                logger.warn("Getting current location failed due missing permissions");
                post("Getting current location failed due missing permissions");
                throw new PluginException("Permission denied", 1); // PERMISSION_DENIED
            }
            if (error instanceof TimeoutException) {
                post("Location request timed out");
                throw new PluginException("Location request timed out", 3); // TIME_OUT
            }

            throw new PluginException(error.getMessage(), 2); // LOCATION_UNAVAILABLE
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting location", e);
            post("Interrupted while waiting location");
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting location", e);
        }
    }

    public void switchMode(final int mode) {
        mService.executeProviderCommand(LocationProvider.CMD_SWITCH_MODE, mode);
    }

    public void sendCommand(final int commandId) {
        mService.executeProviderCommand(commandId, 0);
    }

    public synchronized void configure(Config config) throws PluginException {
        try
        {
            Config newConfig = Config.merge(getStoredConfig(), config);
            persistConfiguration(newConfig);
            logger.debug("Service configured with: {}", newConfig.toString());
            mConfig = newConfig;
            mService.configure(newConfig);
        } catch (Exception e) {
            logger.error("Configuration error: {}", e.getMessage());
            throw new PluginException("Configuration error", e, PluginException.CONFIGURE_ERROR);
        }
    }

    public synchronized Config getConfig() {
        if (mConfig != null) {
            return mConfig;
        }

        try {
            mConfig = getStoredConfig();
        } catch (PluginException e) {
            logger.error("Error getting stored config will use default", e.getMessage());
            mConfig = Config.getDefault();
        }

        return mConfig;
    }

    public synchronized Config getStoredConfig() throws PluginException {
        try {
            ConfigurationDAO dao = DAOFactory.createConfigurationDAO(getContext());
            Config config = dao.retrieveConfiguration();
            if (config == null) {
                config = Config.getDefault();
            }
            return config;
        } catch (JSONException e) {
            logger.error("Error getting stored config: {}", e.getMessage());
            throw new PluginException("Error getting stored config", e, PluginException.JSON_ERROR);
        }
    }

    public Collection<LogEntry> getLogEntries(int limit) {
        DBLogReader logReader = new DBLogReader();
        return logReader.getEntries(limit, 0, Level.DEBUG);
    }

    public Collection<LogEntry> getLogEntries(int limit, int offset, String minLevel) {
        DBLogReader logReader = new DBLogReader();
        return logReader.getEntries(limit, offset, Level.valueOf(minLevel));
    }

    /**
     * Force location sync
     *
     * Method is ignoring syncThreshold and also user sync settings preference
     * and sync locations to defined syncUrl
     */
    public void forceSync() {
        logger.debug("Sync locations forced");
        ResourceResolver resolver = ResourceResolver.newInstance(getContext());
        Account syncAccount = AccountHelper.CreateSyncAccount(getContext(), resolver.getAccountName(),
                resolver.getAccountType());
        SyncService.sync(syncAccount, resolver.getAuthority(), true);
    }

    public int getAuthorizationStatus() {
        return hasPermissions() ? AUTHORIZATION_AUTHORIZED : AUTHORIZATION_DENIED;
    }

    public boolean hasPermissions() {
        return hasPermissions(getContext(), PERMISSIONS);
    }

    public boolean locationServicesEnabled() throws PluginException {
        Context context = getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int locationMode = 0;
            try {
                locationMode = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE);
                return locationMode != Settings.Secure.LOCATION_MODE_OFF;
            } catch (SettingNotFoundException e) {
                logger.error("Location services check failed", e);
                throw new PluginException("Location services check failed", e, PluginException.SETTINGS_ERROR);
            }
        } else {
            String locationProviders = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
            return !TextUtils.isEmpty(locationProviders);
        }
    }

    public void registerHeadlessTask(final String taskRunnerClass) {
        logger.info("Registering headless task: {}", taskRunnerClass);
        mService.registerHeadlessTask(taskRunnerClass);
    }

    private void startBackgroundService() {
        logger.info("Attempt to start bg service");
        post("Attempt to start bg service");
        if (mIsPaused) {
            mService.startForegroundService();
        } else {
            mService.start();
        }
    }

    private void stopBackgroundService() {
        logger.info("Attempt to stop bg service");
        post("Attempt to stop bg service");
        mService.stop();
    }

    public boolean isRunning() {
        return ((LocationServiceProxy) mService).isRunning();
    }

    private void persistConfiguration(Config config) throws NullPointerException {
        ConfigurationDAO dao = DAOFactory.createConfigurationDAO(getContext());
        dao.persistConfiguration(config);
    }

    private Context getContext() {
        return mContext;
    }

    private Context getApplicationContext() {
        return mContext.getApplicationContext();
    }

    public static void showAppSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }

    public static void showLocationSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }

    public static boolean hasPermissions(Context context, String[] permissions) {
        for (String perm: permissions) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sets a transform for each coordinate about to be committed (sent or saved for later sync).
     * You can use this for modifying the coordinates in any way.
     *
     * If the transform returns <code>null</code>, it will prevent the location from being committed.
     * @param transform - the transform listener
     */
    public static void setLocationTransform(LocationTransform transform) {
        LocationServiceImpl.setLocationTransform(transform);
    }

    public static LocationTransform getLocationTransform() {
        return LocationServiceImpl.getLocationTransform();
    }
}
