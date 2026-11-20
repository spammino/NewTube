package org.schabi.newpipe.cast;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.schabi.newpipe.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A temporary dialog to try the Chromecast sender: it lists the devices found on the network and
 * plays a short test video on the chosen one. What the Chromecast reports is shown as it is, and
 * a trace of all the messages is shown at the end, to help diagnose problems.
 */
public final class CastTestDialog {

    private static final String[] TEST_NAMES = {
            "Google (http) - base",
            "Google (http) - completo",
            "Blender (https) - base",
            "Blender (https) - completo"
    };
    private static final String[] TEST_URLS = {
            "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4",
            "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4"
    };
    /** In the "completo" tests the messages carry the extra fields other senders send. */
    private static final boolean[] TEST_RICH = {false, true, false, true};

    private static final long LOG_DELAY_MS = 6000;
    private static final long RICH_LOAD_DELAY_MS = 1500;

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
                .setAdapter(adapter, (d, which) -> chooseTest(activity, devices.get(which)))
                .setNeutralButton(R.string.cast_test_stop,
                        (d, which) -> new Thread(CastTestDialog::stopActive, "cast-stop").start())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> discovery.stop());
        discovery.start();
        dialog.show();
    }

    private static void chooseTest(final Activity activity, final CastDiscovery.Device device) {
        new AlertDialog.Builder(activity)
                .setTitle(device.name)
                .setItems(TEST_NAMES, (d, which) -> startTest(activity, device, which))
                .show();
    }

    private static void startTest(final Activity activity, final CastDiscovery.Device device,
                                  final int test) {
        final StringBuffer trace = new StringBuffer();
        final long start = System.currentTimeMillis();
        final CastClient.Logger logger = line -> trace.append('+')
                .append(System.currentTimeMillis() - start).append("ms ")
                .append(line).append("\n\n");
        trace.append("Test: ").append(TEST_NAMES[test]).append("\n\n");

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
                trace.append("(connection closed: ").append(reason).append(")\n\n");
            }
        };

        new Thread(() -> {
            try {
                stopActive();
                final CastClient client = CastClient.connect(device.host, device.port, listener,
                        logger, TEST_RICH[test]);
                setActive(client);
                client.launchDefaultReceiver();
                if (TEST_RICH[test]) {
                    Thread.sleep(RICH_LOAD_DELAY_MS);
                }
                final CastClient.MediaStatus first =
                        client.load(TEST_URLS[test], "video/mp4", true, 0);
                toast(activity, activity.getString(R.string.cast_test_started, device.name
                        + (first == null ? "" : " (" + first + ")")));
            } catch (final IOException e) {
                trace.append("ERROR: ").append(e).append("\n\n");
                stopActive();
                toast(activity, activity.getString(R.string.cast_test_failed, e.toString()));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            try {
                Thread.sleep(LOG_DELAY_MS); // let the Chromecast report what happens next
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            showTrace(activity, trace.toString());
        }, "cast-test").start();
    }

    private static void showTrace(final Activity activity, final String trace) {
        activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                .setTitle(R.string.cast_test_log_title)
                .setMessage(trace)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.cast_test_copy, (d, which) -> {
                    final ClipboardManager clipboard = (ClipboardManager)
                            activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cast log", trace));
                    Toast.makeText(activity, R.string.cast_test_copied, Toast.LENGTH_SHORT)
                            .show();
                })
                .show());
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
