package au.opendepth.app.util

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Looks server names up with the phone's own DNS and, only when that can't answer, with
 * Cloudflare's public DNS over HTTPS. Some phone DNS set-ups (filters, VPNs, a mobile
 * network having a bad day) answer for some servers and not others, which left the sea
 * conditions without their satellite pictures. Only the name of the data server being
 * fetched is sent, and only when the phone's DNS has failed.
 */
object FallbackDns : Dns {
    private const val KEEP_MS = 10 * 60_000L
    private val found = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    private val overHttps: Dns by lazy {
        val bootstrap = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            // By address, so this lookup doesn't itself need DNS.
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"),
                InetAddress.getByName("2606:4700:4700::1111"), InetAddress.getByName("2606:4700:4700::1001")
            )
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        try {
            return Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            found[hostname]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.let { return it.second }
            val addresses = try {
                overHttps.lookup(hostname)
            } catch (second: Exception) {
                throw e
            }
            if (addresses.isEmpty()) throw e
            found[hostname] = System.currentTimeMillis() to addresses
            return addresses
        }
    }
}
