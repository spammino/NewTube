package org.schabi.newpipe.cast;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.schabi.newpipe.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A temporary dialog to try the Chromecast sender: it lists the devices found on the network and
 * plays a short test video on the chosen one. What the Chromecast reports is shown as it is, and
 * a trace of all the messages is shown at the end, to help diagnose problems.
 */
public final class CastTestDialog {

    private static final long LOG_DELAY_MS = 6000;
    private static final int BUFFER_SIZE = 16 * 1024;

    private static CastClient activeClient;
    private static CastProxyServer activeProxy;

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
        final StringBuffer trace = new StringBuffer();
        final long start = System.currentTimeMillis();
        final CastClient.Logger logger = line -> trace.append('+')
                .append(System.currentTimeMillis() - start).append("ms ")
                .append(line).append("\n\n");
        trace.append("Test: video inside the app, served by the phone\n\n");

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
                        logger, false);
                final CastProxyServer proxy =
                        CastProxyServer.start(readTestVideo(activity), "video/mp4", logger);
                setActive(client, proxy);
                client.launchDefaultReceiver();
                final String url = proxy.getUrl(client.getLocalAddress());
                trace.append("Address given to the Chromecast: ").append(url).append("\n\n");
                final CastClient.MediaStatus first = client.load(url, "video/mp4", true, 0);
                toast(activity, activity.getString(R.string.cast_test_started, device.name
                        + (first == null ? "" : " (" + first + ")")));
            } catch (final IOException e) {
                trace.append("ERROR: ").append(e).append("\n\n");
                stopActive();
                toast(activity, activity.getString(R.string.cast_test_failed, e.toString()));
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

    private static byte[] readTestVideo(final Activity activity) throws IOException {
        try (InputStream in = activity.getResources().openRawResource(R.raw.cast_test)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[BUFFER_SIZE];
            int read = in.read(buffer);
            while (read >= 0) {
                out.write(buffer, 0, read);
                read = in.read(buffer);
            }
            return out.toByteArray();
        }
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

    private static synchronized void setActive(final CastClient client,
                                               final CastProxyServer proxy) {
        activeClient = client;
        activeProxy = proxy;
    }

    private static synchronized void stopActive() {
        if (activeClient != null) {
            activeClient.stopReceiver();
            activeClient.close();
            activeClient = null;
        }
        if (activeProxy != null) {
            activeProxy.stop();
            activeProxy = null;
        }
    }

    private static void toast(final Activity activity, final String message) {
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }
}
