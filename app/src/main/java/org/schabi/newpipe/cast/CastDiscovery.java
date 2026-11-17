package org.schabi.newpipe.cast;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the Chromecasts of the local network with Android's own service discovery
 * ({@link NsdManager}, mDNS). It does not use the Google Play Services nor any library.
 * <p>
 * All the callbacks are delivered on the main thread.
 * </p>
 */
public final class CastDiscovery {

    private static final String SERVICE_TYPE = "_googlecast._tcp.";
    /** The key of the DNS-SD text record that holds the name chosen by the owner. */
    private static final String ATTRIBUTE_FRIENDLY_NAME = "fn";

    public static final class Device {
        public final String id;
        public final String name;
        public final String host;
        public final int port;

        Device(final String id, final String name, final String host, final int port) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    public interface Listener {
        /** The list of the devices found so far changed. */
        void onDevicesChanged(List<Device> devices);
    }

    private final NsdManager nsdManager;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Device> devices = new LinkedHashMap<>();
    // NsdManager can resolve only one service at a time on many Android versions
    private final Deque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving = false;
    private boolean started = false;
    private NsdManager.DiscoveryListener discoveryListener;

    public CastDiscovery(final Context context, final Listener listener) {
        this.nsdManager = (NsdManager) context.getApplicationContext()
                .getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    /** Starts looking for devices. Call {@link #stop()} when done. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(final String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(final String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(final String serviceType, final int errorCode) {
                mainHandler.post(() -> started = false);
            }

            @Override
            public void onStopDiscoveryFailed(final String serviceType, final int errorCode) {
            }

            @Override
            public void onServiceFound(final NsdServiceInfo serviceInfo) {
                mainHandler.post(() -> {
                    if (started) {
                        resolveQueue.add(serviceInfo);
                        resolveNext();
                    }
                });
            }

            @Override
            public void onServiceLost(final NsdServiceInfo serviceInfo) {
                mainHandler.post(() -> {
                    if (devices.remove(serviceInfo.getServiceName()) != null) {
                        notifyChanged();
                    }
                });
            }
        };
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        resolveQueue.clear();
        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (final IllegalArgumentException ignored) {
            // discovery was not running
        }
    }

    @SuppressWarnings("deprecation") // the replacement needs a newer Android version
    private void resolveNext() {
        if (resolving || resolveQueue.isEmpty()) {
            return;
        }
        resolving = true;
        nsdManager.resolveService(resolveQueue.poll(), new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(final NsdServiceInfo serviceInfo, final int errorCode) {
                mainHandler.post(() -> {
                    resolving = false;
                    resolveNext();
                });
            }

            @Override
            public void onServiceResolved(final NsdServiceInfo serviceInfo) {
                mainHandler.post(() -> {
                    resolving = false;
                    addDevice(serviceInfo);
                    resolveNext();
                });
            }
        });
    }

    private void addDevice(final NsdServiceInfo info) {
        final InetAddress address = info.getHost();
        if (!started || address == null) {
            return;
        }
        String name = info.getServiceName();
        final byte[] friendlyName = info.getAttributes().get(ATTRIBUTE_FRIENDLY_NAME);
        if (friendlyName != null && friendlyName.length > 0) {
            name = new String(friendlyName, StandardCharsets.UTF_8);
        }
        devices.put(info.getServiceName(), new Device(info.getServiceName(), name,
                address.getHostAddress(), info.getPort()));
        notifyChanged();
    }

    private void notifyChanged() {
        listener.onDevicesChanged(new ArrayList<>(devices.values()));
    }
}
