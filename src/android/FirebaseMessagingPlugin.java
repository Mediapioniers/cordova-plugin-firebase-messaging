package by.chemerisuk.cordova.firebase;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.content.SharedPreferences;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

import by.chemerisuk.cordova.support.CordovaMethod;
import by.chemerisuk.cordova.support.ReflectiveCordovaPlugin;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.RemoteMessage;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.InstanceIdResult;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Set;
import me.leolin.shortcutbadger.ShortcutBadger;


public class FirebaseMessagingPlugin extends ReflectiveCordovaPlugin {
    private static final String TAG = "FirebaseMessagingPlugin";

    private JSONObject lastBundle;
    private boolean isBackground = false;
    private boolean forceShow = false;
    private CallbackContext tokenRefreshCallback;
    private CallbackContext foregroundCallback;
    private CallbackContext backgroundCallback;
    private static FirebaseMessagingPlugin instance;

    @Override
    protected void pluginInitialize() {
        FirebaseMessagingPlugin.instance = this;

        lastBundle = getNotificationData(cordova.getActivity().getIntent());

        Context context = cordova.getActivity().getApplicationContext();
        // cleanup badge value initially
        ShortcutBadger.applyCount(context, 0);
    }

    @CordovaMethod
    private void subscribe(String topic, final CallbackContext callbackContext) {
        FirebaseMessaging.getInstance().subscribeToTopic(topic)
            .addOnCompleteListener(cordova.getActivity(), new OnCompleteListener<Void>() {
                @Override
                public void onComplete(Task<Void> task) {
                    if (task.isSuccessful()) {
                        callbackContext.success();
                    } else {
                        callbackContext.error(task.getException().getMessage());
                    }
                }
            });
    }

    @CordovaMethod
    private void unsubscribe(String topic, final CallbackContext callbackContext) {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
            .addOnCompleteListener(cordova.getActivity(), new OnCompleteListener<Void>() {
                @Override
                public void onComplete(Task<Void> task) {
                    if (task.isSuccessful()) {
                        callbackContext.success();
                    } else {
                        callbackContext.error(task.getException().getMessage());
                    }
                }
            });
    }

    @CordovaMethod
    private void revokeToken(CallbackContext callbackContext) throws IOException {
        FirebaseInstanceId.getInstance().deleteInstanceId();

        callbackContext.success();
    }

    @CordovaMethod
    private void getToken(String type, final CallbackContext callbackContext) {
        if (type != null) {
            callbackContext.sendPluginResult(
                new PluginResult(PluginResult.Status.OK, (String)null));
        } else {
            FirebaseInstanceId.getInstance().getInstanceId()
                .addOnCompleteListener(cordova.getActivity(), new OnCompleteListener<InstanceIdResult>() {
                    @Override
                    public void onComplete(Task<InstanceIdResult> task) {
                        if (task.isSuccessful()) {
                            callbackContext.success(task.getResult().getToken());
                        } else {
                            callbackContext.error(task.getException().getMessage());
                        }
                    }
                });
        }
    }

    @CordovaMethod
    private void onTokenRefresh(CallbackContext callbackContext) {
        instance.tokenRefreshCallback = callbackContext;
    }

    @CordovaMethod
    private void onMessage(CallbackContext callbackContext) {
        instance.foregroundCallback = callbackContext;
    }

    @CordovaMethod
    private void onBackgroundMessage(CallbackContext callbackContext) {
        instance.backgroundCallback = callbackContext;

        if (lastBundle != null) {
            sendNotification(lastBundle, callbackContext);
            lastBundle = null;
        }
    }

    @CordovaMethod
    private void setBadge(int value, CallbackContext callbackContext) {
        if (value >= 0) {
            Context context = cordova.getActivity().getApplicationContext();
            ShortcutBadger.applyCount(context, value);

            callbackContext.success();
        } else {
            callbackContext.error("Badge value can't be negative");
        }
    }

    @CordovaMethod
    private void getBadge(CallbackContext callbackContext) {
        Context context = cordova.getActivity();
        SharedPreferences settings = context.getSharedPreferences("badge", Context.MODE_PRIVATE);
        callbackContext.success(settings.getInt("badge", 0));
    }

    @CordovaMethod
    private void requestPermission(JSONObject options, CallbackContext callbackContext) throws JSONException {
        Context context = cordova.getActivity().getApplicationContext();

        this.forceShow = options.optBoolean("forceShow");

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            callbackContext.success();
        } else {
            callbackContext.error("Notifications permission is not granted");
        }
    }

    @CordovaMethod
    private void createChannel(JSONObject channel, CallbackContext callbackContext) throws JSONException {
        // only call on Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                final NotificationManager notificationManager = (NotificationManager) cordova.getActivity()
                        .getSystemService(Context.NOTIFICATION_SERVICE);

                String packageName = cordova.getActivity().getApplicationContext().getPackageName();
                NotificationChannel mChannel = new NotificationChannel(channel.getString("id"),
                        channel.optString("description", ""),
                        channel.optInt("importance", NotificationManager.IMPORTANCE_DEFAULT));

                int lightColor = channel.optInt("lightColor", -1);
                if (lightColor != -1) {
                    mChannel.setLightColor(lightColor);
                }

                int visibility = channel.optInt("visibility", NotificationCompat.VISIBILITY_PUBLIC);
                mChannel.setLockscreenVisibility(visibility);

                boolean badge = channel.optBoolean("badge", true);
                mChannel.setShowBadge(badge);

                String sound = channel.optString("sound", "default");
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build();

                if (sound.equals("ringtone")) {
                    mChannel.setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI, audioAttributes);
                } else if (sound != null && sound.isEmpty()) {
                    // Disable sound for this notification channel if an empty string is passed.
                    // https://stackoverflow.com/a/47144981/6194193
                    mChannel.setSound(null, null);
                } else if (sound != null && !sound.contentEquals("default")) {
                    Uri soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + packageName + "/raw/" + sound);
                    mChannel.setSound(soundUri, audioAttributes);
                } else {
                    mChannel.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes);
                }

                // If vibration settings is an array set vibration pattern, else set enable
                // vibration.
                JSONArray pattern = channel.optJSONArray("vibration");
                if (pattern != null) {
                    int patternLength = pattern.length();
                    long[] patternArray = new long[patternLength];
                    for (int i = 0; i < patternLength; i++) {
                        patternArray[i] = pattern.optLong(i);
                    }
                    mChannel.setVibrationPattern(patternArray);
                } else {
                    boolean vibrate = channel.optBoolean("vibration", true);
                    mChannel.enableVibration(vibrate);
                }

                notificationManager.createNotificationChannel(mChannel);
                callbackContext.success();
            } catch(Exception e) {
                callbackContext.error(e.getMessage());
            }
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        JSONObject notificationData = getNotificationData(intent);
        if (instance != null && notificationData != null) {
            sendNotification(notificationData, instance.backgroundCallback);
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        this.isBackground = true;
    }

    @Override
    public void onResume(boolean multitasking) {
        this.isBackground = false;
    }

    static void sendNotification(RemoteMessage remoteMessage) {
        JSONObject notificationData = new JSONObject(remoteMessage.getData());
        RemoteMessage.Notification notification = remoteMessage.getNotification();
        try {
            if (notification != null) {
                JSONObject jsonNotification = new JSONObject();
                jsonNotification.put("body", notification.getBody());
                jsonNotification.put("title", notification.getTitle());
                jsonNotification.put("sound", notification.getSound());
                jsonNotification.put("icon", notification.getIcon());
                jsonNotification.put("tag", notification.getTag());
                jsonNotification.put("color", notification.getColor());
                jsonNotification.put("clickAction", notification.getClickAction());

                notificationData.put("gcm", jsonNotification);
            }
            notificationData.put("google.message_id", remoteMessage.getMessageId());
            notificationData.put("google.sent_time", remoteMessage.getSentTime());

            if (instance != null) {
                CallbackContext callbackContext = instance.isBackground ?
                    instance.backgroundCallback : instance.foregroundCallback;
                instance.sendNotification(notificationData, callbackContext);
            }
        } catch (JSONException e) {
            Log.e(TAG, "sendNotification", e);
        }
    }

    static void sendInstanceId(String instanceId) {
        if (instance != null) {
            if (instance.tokenRefreshCallback != null && instanceId != null) {
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, instanceId);
                pluginResult.setKeepCallback(true);
                instance.tokenRefreshCallback.sendPluginResult(pluginResult);
            }
        }
    }

    static boolean isForceShow() {
        return instance != null && instance.forceShow;
    }

    private void sendNotification(JSONObject notificationData, CallbackContext callbackContext) {
        if (callbackContext != null) {
            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, notificationData);
            pluginResult.setKeepCallback(true);
            callbackContext.sendPluginResult(pluginResult);
        }
    }

    private JSONObject getNotificationData(Intent intent) {
        Bundle bundle = intent.getExtras();

        if (bundle == null) {
            return null;
        }

        if (!bundle.containsKey("google.message_id") && !bundle.containsKey("google.sent_time")) {
            return null;
        }

        try {
            JSONObject notificationData = new JSONObject();
            Set<String> keys = bundle.keySet();
            for (String key : keys) {
                notificationData.put(key, bundle.get(key));
            }
            return notificationData;
        } catch (JSONException e) {
            Log.e(TAG, "getNotificationData", e);
            return null;
        }
    }
}
