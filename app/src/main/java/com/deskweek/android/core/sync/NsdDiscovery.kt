package com.deskweek.android.core.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * 局域网发现（P3）：DNS-SD / mDNS 查找 `_deskweek-sync._tcp`。
 *
 * TXT 只携带协议版本与最小标识，不广播任务内容（协议文档 §2）。
 * 发现结果只是地址候选，仍然要过信任表/配对，不能凭发现就授权。
 *
 * 注意：模拟器的用户态网络通常不转发组播，发现能力只能在真实局域网上验证；
 * 本仓库的自动化测试只覆盖「解析与端点选择」这类纯逻辑。
 */
class NsdDiscovery(private val context: Context) {

    interface Listener {
        fun onFound(host: String, port: Int, name: String, groupId: String?)
        fun onLost(name: String)
        fun onError(message: String)
    }

    companion object {
        const val SERVICE_TYPE = "_deskweek-sync._tcp"
        private const val TAG = "DeskWeekNsd"

        /** 从 TXT 记录里取 groupId，缺省返回 null */
        fun groupIdOf(info: NsdServiceInfo): String? =
            info.attributes?.get("group")?.toString(Charsets.UTF_8)
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var running = false

    fun start(listener: Listener) {
        val manager = nsdManager ?: run {
            listener.onError("本机没有 NSD 服务")
            return
        }
        if (running) return
        running = true

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "discovery started")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType != SERVICE_TYPE && !info.serviceType.startsWith(SERVICE_TYPE)) return
                resolve(manager, info, listener)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                listener.onLost(info.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                running = false
                listener.onError("发现启动失败：$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                running = false
                listener.onError("发现停止失败：$errorCode")
            }
        }
        discoveryListener = discovery
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure {
                running = false
                listener.onError("发现不可用：${it.message}")
            }
    }

    fun stop() {
        val manager = nsdManager ?: return
        val listener = discoveryListener ?: return
        runCatching { manager.stopServiceDiscovery(listener) }
        discoveryListener = null
        running = false
    }

    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, info: NsdServiceInfo, listener: Listener) {
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // 早期版本解析经常失败，报给 UI 但不打断整个发现
                listener.onError("解析 ${serviceInfo.serviceName} 失败：$errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                listener.onFound(
                    host = host,
                    port = serviceInfo.port,
                    name = serviceInfo.serviceName,
                    groupId = groupIdOf(serviceInfo),
                )
            }
        })
    }
}
