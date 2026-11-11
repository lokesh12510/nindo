package app.nindo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParserTest {
    private val news = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
<title>"Python when:7d" - Google News</title>
<item><title>Python 3.15 beta is out - The Hindu</title><link>https://news.google.com/rss/articles/abc?oc=5</link>
<pubDate>Tue, 10 Nov 2026 08:00:00 GMT</pubDate><source url="https://www.thehindu.com">The Hindu</source></item>
<item><title>No source here</title><link>https://example.com/b</link></item>
<item><title>Missing link</title></item>
</channel></rss>"""

    private val yt = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
 <title>Corey Schafer</title>
 <entry><id>yt:video:AAAAAAAAAAA</id><yt:videoId>AAAAAAAAAAA</yt:videoId><title>Python Tutorial: Loops</title>
  <link rel="alternate" href="https://www.youtube.com/watch?v=AAAAAAAAAAA"/><published>2026-11-09T15:00:00+00:00</published></entry>
 <entry><yt:videoId>BBBBBBBBBBB</yt:videoId><title>Quick tip</title>
  <link rel="alternate" href="https://www.youtube.com/shorts/BBBBBBBBBBB"/><published>2026-11-10T15:00:00+00:00</published></entry>
</feed>"""

    @Test fun readsGoogleNews() {
        val items = FeedParser.parse(news)
        assertEquals(2, items.size)
        assertEquals("Python 3.15 beta is out", items[0].title)
        assertEquals("The Hindu", items[0].source)
        assertEquals(1794297600000L, items[0].publishedMs)
        assertEquals(0L, items[1].publishedMs)
    }

    @Test fun readsYouTubeAndSpotsShorts() {
        val items = FeedParser.parse(yt)
        assertEquals(2, items.size)
        assertEquals("AAAAAAAAAAA", items[0].videoId)
        assertTrue(items[0].publishedMs > 0)
        assertFalse(FeedParser.isShort(items[0].url))
        assertTrue(FeedParser.isShort(items[1].url))
    }

    @Test fun badXmlIsEmptyNotACrash() {
        assertTrue(FeedParser.parse("<html><body>Too many requests</body>").isEmpty())
        assertTrue(FeedParser.parse("").isEmpty())
        assertTrue(FeedParser.parse("""<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "b">]><rss><channel><item><title>&a;</title><link>x</link></item></channel></rss>""").isEmpty())
    }

    @Test fun channelLinks() {
        assertEquals("UCCezIgC97PvUuR4_gbFUs5g", FeedParser.channelIdIn("https://www.youtube.com/channel/UCCezIgC97PvUuR4_gbFUs5g?si=x"))
        assertNull(FeedParser.channelPageFor("UCCezIgC97PvUuR4_gbFUs5g"))
        assertEquals("https://www.youtube.com/@coreyms", FeedParser.channelPageFor("@coreyms"))
        assertEquals("https://www.youtube.com/@coreyms", FeedParser.channelPageFor("coreyms"))
        assertEquals("https://www.youtube.com/@coreyms", FeedParser.channelPageFor("https://m.youtube.com/@coreyms"))
        assertEquals("https://www.youtube.com/c/Coreyms", FeedParser.channelPageFor("youtube.com/c/Coreyms"))
        assertNull(FeedParser.channelPageFor("corey schafer python"))
        assertEquals("UCCezIgC97PvUuR4_gbFUs5g",
            FeedParser.channelIdInPage("""<html><link rel="canonical" href="https://www.youtube.com/channel/UCCezIgC97PvUuR4_gbFUs5g"></html>"""))
        assertEquals("UCCezIgC97PvUuR4_gbFUs5g", FeedParser.channelIdInPage("""{"externalId":"UCCezIgC97PvUuR4_gbFUs5g"}"""))
        assertEquals("https://www.youtube.com/watch?v=AAAAAAAAAAA", FeedParser.channelPageFor("https://youtu.be/AAAAAAAAAAA?si=x"))
        assertEquals("UCCezIgC97PvUuR4_gbFUs5g", FeedParser.channelIdInPage(
            """{"channelId":"UC0000000000000000000000","videoDetails":{"videoId":"AAAAAAAAAAA","title":"x","channelId":"UCCezIgC97PvUuR4_gbFUs5g"}}"""))
        assertEquals("UCCezIgC97PvUuR4_gbFUs5g", FeedParser.channelIdInPage(
            """<link rel="alternate" type="application/rss+xml" href="https://www.youtube.com/feeds/videos.xml?channel_id=UCCezIgC97PvUuR4_gbFUs5g">"""))
    }

    @Test fun videoIds() {
        assertEquals("AAAAAAAAAAA", FeedParser.videoIdIn("https://youtu.be/AAAAAAAAAAA"))
        assertEquals("AAAAAAAAAAA", FeedParser.videoIdIn("https://www.youtube.com/watch?v=AAAAAAAAAAA&t=3"))
    }

    @Test fun playlistFeedsComeFirst() {
        val f = FeedParser.videoFeedsFor("UCCezIgC97PvUuR4_gbFUs5g")
        assertEquals("https://www.youtube.com/feeds/videos.xml?playlist_id=UULFCezIgC97PvUuR4_gbFUs5g", f[0])
        assertTrue(f[1].contains("channel_id=UCCezIgC97PvUuR4_gbFUs5g"))
        assertTrue(f[2].endsWith("playlist_id=UUCezIgC97PvUuR4_gbFUs5g"))
    }

    @Test fun starterChannels() {
        assertEquals(3, StarterChannels.forLabel("Python").size)
        assertTrue(StarterChannels.strict("Learn Python"))
        assertFalse(StarterChannels.strict("Web development"))
        assertTrue(StarterChannels.forLabel("Guitar").isEmpty())
    }
}
