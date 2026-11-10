package com.clipisle.app.bt

/** Transmission upstream bootstrap set; these are DHT routers, not torrent trackers. */
internal object BtDhtBootstrap {
 val nodes = listOf("dht.libtorrent.org:25401", "dht.transmissionbt.com:6881", "router.bittorrent.com:6881")
 val settingsValue = nodes.distinct().joinToString(",")
}
