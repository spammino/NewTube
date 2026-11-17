package org.schabi.newpipe.cast;

import android.app.Activity;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.schabi.newpipe.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A temporary dialog to try the Chromecast sender: it lists the devices found on the network and
 * plays a short test video on the chosen one. Errors are shown as they are, to help diagnose
 * problems.
 */
public final class CastTestDialog {

    private static final String TEST_VIDEO_URL =
            "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4";

    private static final CastClient.Listener NO_OP_LISTENER = new CastClient.Listener() {
        @Override
        public void onMediaStatus(final CastClient.MediaStatus status) {
        }

        @Override
        public void onClosed(final IOException reason) {
        }
    };

    private static CastClient activeClient;

    private CastTestDialog() {
    }

    public static void show(final Activity activity) {
        final List<CastDiscovery.Device> devices = new ArrayList<>();
        final ArrayAdapter<String> adapter =
                new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1);

        final CastDiscovery discovery = new CastDiscovery(activity, found -> {
            devices.clear();
            devices.addAll(found);
            adapter.clear();
            for (final CastDiscovery.Device device : found) {
                adapter.add(device.name);
            }
            adapter.notifyDataSetChanged();
        });

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.cast_test_title)
                .setAdapter(adapter, (d, which) -> startTest(activity, devices.get(which)))
                .setNeutralButton(R.string.cast_test_stop,
                        (d, which) -> new Thread(CastTestDialog::stopActive, "cast-stop").start())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> discovery.stop());
        discovery.start();
        dialog.show();
    }

    private static void startTest(final Activity activity, final CastDiscovery.Device device) {
        new Thread(() -> {
            try {
                stopActive();
                final CastClient client =
                        CastClient.connect(device.host, device.port, NO_OP_LISTENER);
                setActive(client);
                client.launchDefaultReceiver();
                client.load(TEST_VIDEO_URL, "video/mp4", true, 0);
                toast(activity, activity.getString(R.string.cast_test_started, device.name));
            } catch (final IOException e) {
                stopActive();
                toast(activity, activity.getString(R.string.cast_test_failed, e.toString()));
            }
        }, "cast-test").start();
    }

    private static synchronized void setActive(final CastClient client) {
        activeClient = client;
    }

    private static synchronized void stopActive() {
        if (activeClient != null) {
            activeClient.stopReceiver();
            activeClient.close();
            activeClient = null;
        }
    }

    private static void toast(final Activity activity, final String message) {
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }
}
