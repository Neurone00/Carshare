package io.github.neurone00.adblock.core

import io.github.neurone00.adblock.core.filter.DomainHash
import io.github.neurone00.adblock.core.filter.FilterBuilder
import io.github.neurone00.adblock.core.filter.LongHashSet
import io.github.neurone00.adblock.core.filter.Rule
import io.github.neurone00.adblock.core.filter.RuleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilterTest {
    @Test
    fun parsesHostsPlainAndAdblock() {
        assertEquals(Rule("ads.example.com"), RuleParser.parseLine("0.0.0.0 ads.example.com"))
        assertEquals(Rule("ads.example.com"), RuleParser.parseLine("127.0.0.1\tads.example.com # comment"))
        assertEquals(Rule("ads.example.com"), RuleParser.parseLine("ADS.Example.com."))
        assertEquals(Rule("ads.example.com"), RuleParser.parseLine("*.ads.example.com"))
        assertEquals(Rule("ads.example.com"), RuleParser.parseLine("||ads.example.com^"))
        assertEquals(Rule("ads.example.com"), RuleParser.parseLine("||ads.example.com^\$important"))
        assertEquals(Rule("cdn.example.com", allow = true), RuleParser.parseLine("@@||cdn.example.com^"))
        assertEquals(Rule("cdn.example.com", allow = true), RuleParser.parseLine("@@cdn.example.com"))
    }

    @Test
    fun ignoresWhatItCannotExpress() {
        assertNull(RuleParser.parseLine("# just a comment"))
        assertNull(RuleParser.parseLine("! adblock comment"))
        assertNull(RuleParser.parseLine("[Adblock Plus 2.0]"))
        assertNull(RuleParser.parseLine("127.0.0.1 localhost"))
        assertNull(RuleParser.parseLine("::1 ip6-localhost"))
        assertNull(RuleParser.parseLine("com"))                        // whole TLD
        assertNull(RuleParser.parseLine("||example.com/ads^"))         // path
        assertNull(RuleParser.parseLine("||example.com^\$third-party")) // modifier we don't do
        assertNull(RuleParser.parseLine("example.com##.banner"))       // cosmetic
        assertEquals(Rule("ads.example.com"), RuleParser.parseLine("0.0.0.0 ads.example.com #trailing"))
        assertNull(RuleParser.parseLine("|http://example.com/"))
        assertNull(RuleParser.parseLine("0.0.0.0 0.0.0.0"))
        assertNull(RuleParser.parseLine("bad..label.com"))
        assertNull(RuleParser.parseLine("has space.com x"))
    }

    @Test
    fun suffixMatchingBlocksSubdomains() {
        val f = FilterBuilder(8).apply {
            addLine("0.0.0.0 doubleclick.net")
            addLine("||ads.example.com^")
            addLine("@@||safe.ads.example.com^")
        }.build()
        assertTrue(f.isBlocked("doubleclick.net"))
        assertTrue(f.isBlocked("stats.g.doubleclick.net"))
        assertTrue(f.isBlocked("DoubleClick.NET"))
        assertTrue(f.isBlocked("ads.example.com"))
        assertTrue(f.isBlocked("x.ads.example.com"))
        assertFalse(f.isBlocked("safe.ads.example.com"))
        assertFalse(f.isBlocked("api.safe.ads.example.com"))
        assertFalse(f.isBlocked("example.com"))
        assertFalse(f.isBlocked("notdoubleclick.net"))
        assertFalse(f.isBlocked("net"))
        assertFalse(f.isBlocked("localhost"))
        assertEquals(2, f.blockedCount)
        assertEquals(1, f.allowedCount)
    }

    @Test
    fun suffixHashesMatchDirectHashes() {
        val seen = ArrayList<Long>()
        DomainHash.anySuffix("a.b.example.com") { seen.add(it); false }
        assertEquals(listOf(DomainHash.of("com"), DomainHash.of("example.com"), DomainHash.of("b.example.com"), DomainHash.of("a.b.example.com")), seen)
        assertEquals(DomainHash.of("Foo.Bar"), DomainHash.of("foo.bar"))
    }

    @Test
    fun longHashSetGrowsAndFinds() {
        val s = LongHashSet(4)
        val n = 50_000
        for (i in 0 until n) assertTrue(s.add(i * 7919L + 2))
        assertEquals(n, s.size)
        for (i in 0 until n) assertTrue((i * 7919L + 2) in s)
        assertFalse(123456789L in s)
        assertFalse(s.add(2L)) // duplicate
        assertTrue(s.add(0L)); assertTrue(0L in s)
    }
}
