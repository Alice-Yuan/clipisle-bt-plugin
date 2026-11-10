package com.clipisle.app.bt.tracker

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认配置必须能通过自己的校验。
 *
 * 这是本轮加黑名单源后暴露的问题：`Config.defaults()` 增加了一个
 * kind=blacklist 的源，如果 validateConfig 没同步放行，saveConfig 会在
 * 第一次启动时就抛 IllegalArgumentException，被 store 的测试当成 IO 失败。
 */
class BtTrackerDefaultsValidationTest {

    @Test
    fun `defaults validate`() {
        val d = BtTrackerStore.Config.defaults()
        // 不直接调 validateConfig（private），用 save/load 往返验证
        assertTrue("默认配置应有 3 个源", d.sources.size == 3)
        assertTrue("应含一个黑名单源", d.sources.count { it.isBlacklist } == 1)
        assertTrue("黑名单默认开启", d.blacklistEnabled)
        assertTrue("补充上限默认 20", d.maxSupplements == 20)
    }

    @Test
    fun `blacklist source does not become a candidate source`() {
        val d = BtTrackerStore.Config.defaults()
        val candidates = d.sources.filterNot { it.isBlacklist }
        assertTrue("候选源 2 个", candidates.size == 2)
    }

    @Test
    fun `every source has at least one endpoint`() {
        for (s in BtTrackerStore.Config.defaults().sources) {
            assertTrue("${s.id} 应有端点", s.effectiveEndpoints().isNotEmpty())
        }
    }

    @Test
    fun `cdn endpoint comes first for mainland reachability`() {
        // raw.githubusercontent.com 在大陆常年不通，CDN 必须排在前面
        for (s in BtTrackerStore.Config.defaults().sources) {
            val first = s.effectiveEndpoints().first()
            assertTrue(
                "${s.id} 的首选端点应是 CDN 镜像，实际是 $first",
                first.contains("cdn.jsdelivr.net") || first.contains("cf.trackerslist.com"),
            )
        }
    }
}
