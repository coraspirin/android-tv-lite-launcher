package local.kutu.transfer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** Which address to show on the TV, and which callers are allowed to reach us. */
final class Net {

    private Net() {
    }

    /** The box's own LAN IPv4, or null when it is not on a network. */
    static String localAddress() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && isPrivate(a)) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * RFC1918 / CGNAT / link-local only.
     *
     * The box runs firmware frozen at 2021 and that cannot be fixed, so the project's standing
     * mitigation is to keep it off the internet. This check is the cheap enforcement of that:
     * even a router port-forward misconfigured onto 8787 still would not hand the box out,
     * because the peer address would not be private.
     */
    static boolean isPrivate(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;   // 100.64/10 CGNAT
        }
        return false;
    }
}
