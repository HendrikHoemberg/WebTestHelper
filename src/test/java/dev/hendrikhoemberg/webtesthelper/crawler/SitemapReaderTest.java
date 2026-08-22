package dev.hendrikhoemberg.webtesthelper.crawler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SitemapReaderTest {

    @Test
    void locationsAreExtractedAndEntitiesDecoded() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>https://example.com/</loc><lastmod>2026-08-01</lastmod></url>
                  <url><loc>https://example.com/a?x=1&amp;y=2</loc></url>
                </urlset>
                """;
        assertThat(SitemapReader.locations(xml))
                .containsExactly("https://example.com/", "https://example.com/a?x=1&y=2");
        assertThat(SitemapReader.isIndex(xml)).isFalse();
    }

    @Test
    void aSitemapIndexIsRecognisedSoItsChildrenCanBeFetched() {
        String xml = """
                <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sitemap><loc>https://example.com/sitemap-1.xml</loc></sitemap>
                </sitemapindex>
                """;
        assertThat(SitemapReader.isIndex(xml)).isTrue();
        assertThat(SitemapReader.locations(xml)).containsExactly("https://example.com/sitemap-1.xml");
    }

    @Test
    void garbageIsNotAnException() {
        assertThat(SitemapReader.locations("<html>Seite nicht gefunden</html>")).isEmpty();
        assertThat(SitemapReader.locations("")).isEmpty();
    }
}