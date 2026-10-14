package fr.tibkpl.openut2004;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Android supplies the LAN address and DNS; Linux keeps its loopback and name resolution. */
final class LanNetwork {
    private WifiManager.MulticastLock reception;
    LanNetwork(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            reception = wifi.createMulticastLock("Unreal-LAN");
            reception.setReferenceCounted(false);
            reception.acquire();
        }
    }
    void close() { if (reception != null && reception.isHeld()) reception.release(); }

    static String prepare(Context context, File rootfs) throws IOException {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties properties = connectivity == null ? null : connectivity.getLinkProperties(connectivity.getActiveNetwork());
        String address = null;
        if (connectivity != null) {
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (capabilities == null || (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                LinkProperties lan = connectivity.getLinkProperties(network);
                if (lan == null) continue;
                for (LinkAddress link : lan.getLinkAddresses()) {
                    InetAddress candidate = link.getAddress();
                    if (candidate instanceof Inet4Address && !candidate.isLoopbackAddress()
                            && !candidate.isAnyLocalAddress() && !candidate.isLinkLocalAddress()) {
                        address = candidate.getHostAddress();
                        break;
                    }
                }
                if (address != null) { properties = lan; break; }
            }
        }
        File etc = new File(rootfs, "etc");
        if (!etc.isDirectory() && !etc.mkdirs()) throw new IOException("Creation du dossier reseau impossible");
        Files.write(new File(etc, "hosts").toPath(), ("127.0.0.1 localhost\n::1 localhost\n"
                + (address == null ? "" : address + " android-handheld\n")).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(etc, "nsswitch.conf").toPath(), "hosts: files dns\n".getBytes(StandardCharsets.UTF_8));
        StringBuilder dns = new StringBuilder();
        if (properties != null) {
            for (InetAddress server : properties.getDnsServers()) dns.append("nameserver ").append(server.getHostAddress()).append('\n');
        }
        if (dns.length() == 0) dns.append("nameserver 1.1.1.1\n");
        Files.write(new File(etc, "resolv.conf").toPath(), dns.toString().getBytes(StandardCharsets.UTF_8));
        Log.i("UnrealNetwork", address == null ? "Reseau : adresse automatique du moteur" : "LAN : interface IPv4 Android selectionnee");
        return address;
    }
}
