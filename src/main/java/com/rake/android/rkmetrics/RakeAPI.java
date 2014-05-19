package com.rake.android.rkmetrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class RakeAPI {
    public static final String VERSION = "r0.5.0_c0.3.5";
    private boolean isDevServer = false;

    private static final String LOGTAG = "RakeAPI";

    private static final DateFormat baseTimeFormat = new SimpleDateFormat("yyyyMMddHHmmssSSS");

    static {
        baseTimeFormat.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
    }

    private static final DateFormat localTimeFormat = new SimpleDateFormat("yyyyMMddHHmmssSSS");

    // Maps each token to a singleton RakeAPI instance
    private static Map<String, Map<Context, RakeAPI>> sInstanceMap = new HashMap<String, Map<Context, RakeAPI>>();


    private final Context mContext;
    private final SystemInformation mSystemInformation;
    private final AnalyticsMessages mMessages;

    private final String mToken;

    private final SharedPreferences mStoredPreferences;

    // Persistent members. These are loaded and stored from our preferences.
    private JSONObject mSuperProperties;


    private RakeAPI(Context context, String token) {
        mContext = context;
        mToken = token;

        mMessages = getAnalyticsMessages();
        mSystemInformation = getSystemInformation();

        mStoredPreferences = context.getSharedPreferences("com.rake.android.rkmetrics.RakeAPI_" + token, Context.MODE_PRIVATE);
        readSuperProperties();
    }


    public static RakeAPI getInstance(Context context, String token, Boolean isDevServer) {
        synchronized (sInstanceMap) {
            Context appContext = context.getApplicationContext();
            Map<Context, RakeAPI> instances = sInstanceMap.get(token);

            if (instances == null) {
                instances = new HashMap<Context, RakeAPI>();
                sInstanceMap.put(token, instances);
            }

            RakeAPI instance = instances.get(appContext);

            if (instance == null) {
                instance = new RakeAPI(appContext, token);
                instances.put(appContext, instance);

                instance.isDevServer = isDevServer;
                if (isDevServer) {
                    instance.setRakeServer(context, RakeConfig.DEV_BASE_ENDPOINT);
                } else {
                    instance.setRakeServer(context, RakeConfig.BASE_ENDPOINT);
                }
            }
            return instance;
        }
    }

    public static void setFlushInterval(Context context, long milliseconds) {
        AnalyticsMessages msgs = AnalyticsMessages.getInstance(context);
        msgs.setFlushInterval(milliseconds);
    }

    public void track(JSONObject properties) {
        Date now = new Date();
        try {

            JSONObject dataObj = new JSONObject();
            JSONObject propertiesObj = new JSONObject();

            // rake token
            propertiesObj.put("token", mToken);

            // time
            propertiesObj.put("base_time", baseTimeFormat.format(now));
            propertiesObj.put("local_time", localTimeFormat.format(now));


            // 1. super properties
            for (Iterator<?> iter = mSuperProperties.keys(); iter.hasNext(); ) {
                String key = (String) iter.next();
                propertiesObj.put(key, mSuperProperties.get(key));
            }

            // 2. custom properties
            if (properties != null) {
                for (Iterator<?> iter = properties.keys(); iter.hasNext(); ) {
                    String key = (String) iter.next();
                    propertiesObj.put(key, properties.get(key));
                }
            }

            // 3-1. sentinel(schema) meta data
            JSONObject sentinel_meta;
            String schemaId = null;
            JSONObject fieldOrder = null;
            JSONArray encryptionFields = null;
            if (propertiesObj.has("sentinel_meta")) {
                sentinel_meta = propertiesObj.getJSONObject("sentinel_meta");

                schemaId = (String) sentinel_meta.get("_$ssSchemaId");
                fieldOrder = sentinel_meta.getJSONObject("_$ssFieldOrder");
                encryptionFields = sentinel_meta.getJSONArray("_$encryptionFields");

                propertiesObj.remove("sentinel_meta");
                dataObj.put("_$schemaId", schemaId);
                dataObj.put("_$fieldOrder", fieldOrder);
                dataObj.put("_$encryptionFields", encryptionFields);
            }


            // 3-2. auto : device info
            // get only values in fieldOrder
            JSONObject defaultProperties = getDefaultEventProperties();
            if (defaultProperties != null) {
                for (Iterator<?> iter = defaultProperties.keys(); iter.hasNext(); ) {
                    String key = (String) iter.next();
                    boolean addToProperties = true;

                    if (schemaId != null) {
                        if (fieldOrder.has(key)) {
                            addToProperties = true;
                        } else {
                            addToProperties = false;
                        }
                    }
                    if (addToProperties) {
                        propertiesObj.put(key, defaultProperties.get(key));
                    }
                }
            }

            dataObj.put("properties", propertiesObj);

            mMessages.eventsMessage(dataObj);
        } catch (JSONException e) {
            Log.e(LOGTAG, "Exception tracking event ", e);
        }
    }

    private void setRakeServer(Context context, String server) {

        AnalyticsMessages msgs = AnalyticsMessages.getInstance(context);
        msgs.setEndpointHost(server);

    }

    public static void setDebug(Boolean debug) {
        RakeConfig.DEBUG = debug;
        Log.d(LOGTAG, "RakeConfig.DEBUG : " + RakeConfig.DEBUG);
    }


    public void flush() {
        if (RakeConfig.DEBUG)
            Log.d(LOGTAG, "flushEvents");


        mMessages.postToServer();
    }


    public void registerSuperProperties(JSONObject superProperties) {
        if (RakeConfig.DEBUG)
            Log.d(LOGTAG, "registerSuperProperties");

        for (Iterator<?> iter = superProperties.keys(); iter.hasNext(); ) {
            String key = (String) iter.next();
            try {
                mSuperProperties.put(key, superProperties.get(key));
            } catch (JSONException e) {
                Log.e(LOGTAG, "Exception registering super property.", e);
            }
        }

        storeSuperProperties();
    }

    public void unregisterSuperProperty(String superPropertyName) {
        mSuperProperties.remove(superPropertyName);

        storeSuperProperties();
    }


    public void registerSuperPropertiesOnce(JSONObject superProperties) {
        if (RakeConfig.DEBUG)
            Log.d(LOGTAG, "registerSuperPropertiesOnce");

        for (Iterator<?> iter = superProperties.keys(); iter.hasNext(); ) {
            String key = (String) iter.next();
            if (!mSuperProperties.has(key)) {
                try {
                    mSuperProperties.put(key, superProperties.get(key));
                } catch (JSONException e) {
                    Log.e(LOGTAG, "Exception registering super property.", e);
                }
            }
        }// for

        storeSuperProperties();
    }

    public void clearSuperProperties() {
        if (RakeConfig.DEBUG)
            Log.d(LOGTAG, "clearSuperProperties");
        mSuperProperties = new JSONObject();
    }


    // Package-level access. Used (at least) by GCMReceiver
    // when OS-level events occur.
//    interface InstanceProcessor {
//        public void process(RakeAPI m);
//    }
//
//    static void allInstances(InstanceProcessor processor) {
//        synchronized (sInstanceMap) {
//            for (Map<Context, RakeAPI> contextInstances : sInstanceMap.values()) {
//                for (RakeAPI instance : contextInstances.values()) {
//                    processor.process(instance);
//                }
//            }
//        }
//    }


    private JSONObject getDefaultEventProperties() throws JSONException {

        JSONObject ret = new JSONObject();

        ret.put("rake_lib", "android");
        ret.put("rake_lib_version", VERSION);
        ret.put("os_name", "Android");
        ret.put("os_version", Build.VERSION.RELEASE == null ? "UNKNOWN" : Build.VERSION.RELEASE);
        ret.put("manufacturer", Build.MANUFACTURER == null ? "UNKNOWN" : Build.MANUFACTURER);
        ret.put("device_model", Build.MODEL == null ? "UNKNOWN" : Build.MODEL);
        ret.put("device_id", mSystemInformation.getDeviceId());

        DisplayMetrics displayMetrics = mSystemInformation.getDisplayMetrics();
        int displayWidth = displayMetrics.widthPixels;
        int displayHeight = displayMetrics.heightPixels;
        StringBuilder resolutionBuilder = new StringBuilder();

        ret.put("screen_height", displayWidth);
        ret.put("screen_width", displayHeight);
        ret.put("resolution", resolutionBuilder.append(displayWidth).append("*").append(displayHeight).toString());

        String applicationVersionName = mSystemInformation.getAppVersionName();
        ret.put("app_version", applicationVersionName == null ? "UNKNOWN" : applicationVersionName);

        String carrier = mSystemInformation.getCurrentNetworkOperator();
        ret.put("carrier_name", (null != carrier && carrier.length() > 0) ? carrier : "UNKNOWN");

        Boolean isWifi = mSystemInformation.isWifiConnected();
        ret.put("network_type", isWifi == null ? "UNKNOWN" : isWifi.booleanValue() == true ? "WIFI" : "NOT WIFI");

        ret.put("language_code", mContext.getResources().getConfiguration().locale.getCountry());

        return ret;
    }


    private void readSuperProperties() {
        String props = mStoredPreferences.getString("super_properties", "{}");
        if (RakeConfig.DEBUG)
            Log.d(LOGTAG, "Loading Super Properties " + props);

        try {
            mSuperProperties = new JSONObject(props);
        } catch (JSONException e) {
            Log.e(LOGTAG, "Cannot parse stored superProperties");
            mSuperProperties = new JSONObject();
            storeSuperProperties();
        }
    }

    private void storeSuperProperties() {
        String props = mSuperProperties.toString();

        if (RakeConfig.DEBUG)
            Log.d(LOGTAG, "Storing Super Properties " + props);
        SharedPreferences.Editor prefsEditor = mStoredPreferences.edit();
        prefsEditor.putString("super_properties", props);
        prefsEditor.commit();
    }


    private AnalyticsMessages getAnalyticsMessages() {
        return AnalyticsMessages.getInstance(mContext);
    }

    private SystemInformation getSystemInformation() {
        return new SystemInformation(mContext);
    }

    void clearPreferences() {
        // Will clear distinct_ids, superProperties,
        // and waiting People Analytics properties. Will have no effect
        // on messages already queued to send with AnalyticsMessages.

        SharedPreferences.Editor prefsEdit = mStoredPreferences.edit();
        prefsEdit.clear().commit();
        readSuperProperties();
    }
}
