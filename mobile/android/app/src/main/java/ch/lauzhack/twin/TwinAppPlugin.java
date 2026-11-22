package ch.lauzhack.twin;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Facts about this build for the web UI (window.Capacitor.Plugins.TwinApp.info()).
 * `firebase`: the APK was built with google-services.json. Without it PushNotifications.register() throws inside
 * Firebase and Capacitor turns that into a crash, so the web UI must not call it.
 */
@CapacitorPlugin(name = "TwinApp")
public class TwinAppPlugin extends Plugin {

    @PluginMethod
    public void info(PluginCall call) {
        // The google-services Gradle plugin generates this string resource from google-services.json.
        int id = getContext().getResources().getIdentifier("google_app_id", "string", getContext().getPackageName());
        JSObject r = new JSObject();
        r.put("firebase", id != 0);
        call.resolve(r);
    }
}
