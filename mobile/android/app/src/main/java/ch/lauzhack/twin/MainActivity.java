package ch.lauzhack.twin;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TwinAppPlugin.class); // local plugin: must be registered before the bridge starts
        super.onCreate(savedInstanceState);
    }
}
