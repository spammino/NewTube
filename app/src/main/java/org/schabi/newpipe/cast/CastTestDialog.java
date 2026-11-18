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
 * plays a short test video on the chosen one. What the Chromecast reports is shown as it is, to
 * help diagnose problems.
 */
public final class CastTestDialog {

    private static final String[] VIDEO_NAMES = {"Blender (https)", "Google (http)"};
    private static final String[] VIDEO_URLS = {
            "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4",
            "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
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
                .setAdapter(adapter, (d, which) -> chooseVideo(activity, devices.get(which)))
                .setNeutralButton(R.string.cast_test_stop,
                        (d, which) -> new Thread(CastTestDialog::stopActive, "cast-stop").start())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> discovery.stop());
        discovery.start();
        dialog.show();
    }

    private static void chooseVideo(final Activity activity, final CastDiscovery.Device device) {
        new AlertDialog.Builder(activity)
                .setTitle(device.name)
                .setItems(VIDEO_NAMES,
                        (d, which) -> startTest(activity, device, VIDEO_URLS[which]))
                .show();
    }

    private static void startTest(final Activity activity, final CastDiscovery.Device device,
                                  final String videoUrl) {
        final CastClient.Listener listener = new CastClient.Listener() {
            private String lastShown = "";

            @Override
            public void onMediaStatus(final CastClient.MediaStatus status) {
                final String text = status.toString();
                if (!text.equals(lastShown)) {
                    lastShown = text;
                    toast(activity, activity.getString(R.string.cast_test_status, text));
                }
            }

            @Override
            public void onClosed(final IOException reason) {
                if (reason != null) {
                    toast(activity, activity.getString(R.string.cast_test_failed,
                            reason.toString()));
                }
            }
        };

        new Thread(() -> {
            try {
                stopActive();
                final CastClient client = CastClient.connect(device.host, device.port, listener);
                setActive(client);
                client.launchDefaultReceiver();
                final CastClient.MediaStatus first =
                        client.load(videoUrl, "video/mp4", true, 0);
                toast(activity, activity.getString(R.string.cast_test_started, device.name
                        + (first == null ? "" : " (" + first + ")")));
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
