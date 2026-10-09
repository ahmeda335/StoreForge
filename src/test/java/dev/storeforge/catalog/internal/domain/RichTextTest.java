package dev.storeforge.catalog.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RichTextTest {

    @Test
    void formattingThatStorefrontsNeedSurvives() {
        String html = "<h2>Care</h2><p>Wipe with a <strong>dry</strong> cloth.</p><ul><li>Brass</li><li>Linen</li></ul>";
        assertThat(RichText.sanitize(html)).isEqualTo(html);
    }

    @Test
    void scriptsEventHandlersAndActiveContentAreRemoved() {
        assertThat(RichText.sanitize("<p>Lamp<script>steal(document.cookie)</script></p>")).isEqualTo("<p>Lamp</p>");
        assertThat(RichText.sanitize("<img src=x onerror=alert(1)>Nice")).isEqualTo("Nice");
        assertThat(RichText.sanitize("<p onclick=\"alert(1)\" style=\"position:fixed\">Hi</p>")).isEqualTo("<p>Hi</p>");
        assertThat(RichText.sanitize("<iframe src=https://evil.example></iframe><form action=/x><input name=card></form>ok")).isEqualTo("ok");
        assertThat(RichText.sanitize("<svg><script>alert(1)</script></svg>x")).isEqualTo("x");
    }

    @Test
    void linksKeepOnlySafeTargetsAndCannotPassAuthority() {
        assertThat(RichText.sanitize("<a href=\"javascript:alert(1)\">click</a>")).isEqualTo("click");
        assertThat(RichText.sanitize("<a href=\"https://maker.example/care\" target=\"_blank\">guide</a>"))
                .isEqualTo("<a href=\"https://maker.example/care\" rel=\"nofollow noopener noreferrer\">guide</a>");
    }

    @Test
    void textIsEscapedAndNullIsEmpty() {
        assertThat(RichText.sanitize("5 < 6 & \"quotes\"")).isEqualTo("5 &lt; 6 &amp; &#34;quotes&#34;");
        assertThat(RichText.sanitize(null)).isEmpty();
    }
}
