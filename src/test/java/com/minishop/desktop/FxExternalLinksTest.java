package com.minishop.desktop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.minishop.support.LogCapture;
import javafx.application.HostServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link FxExternalLinks}: only https is opened, the URL never reaches the log, a missing toolkit is a WARN. */
class FxExternalLinksTest {

    private static final String URL = "https://example.com/some/path?token=secret-value";

    @SuppressWarnings("unchecked")
    private final ObjectProvider<HostServices> provider = mock(ObjectProvider.class);
    private final HostServices hostServices = mock(HostServices.class);
    private final FxExternalLinks links = new FxExternalLinks(provider);

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "https://example.com/a/b?x=1#frag", "HTTPS://Example.com/x",
            "https://creativecommons.org/licenses/by-sa/4.0/", "https://127.0.0.1:8443/x"})
    void isHttps_acceptsHttpsUrlsWithAHost(String url) {
        assertThat(FxExternalLinks.isHttps(url)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "http://example.com", "ftp://example.com", "file:///C:/Windows/notepad.exe",
            "javascript:alert(1)", "mailto:a@b.c", "https:example.com", "https://", "https:///path-only",
            "https://exa mple.com", "https://example.com/a b", " https://example.com", "//example.com",
            "example.com", "data:text/html,hi"})
    void isHttps_refusesEverythingElse(String url) {
        assertThat(FxExternalLinks.isHttps(url)).isFalse();
    }

    @Test
    void open_httpsUrl_callsShowDocumentOnce() {
        when(provider.getIfAvailable()).thenReturn(hostServices);

        links.open(URL);

        verify(hostServices).showDocument(URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/x", "file:///C:/Windows/notepad.exe", "javascript:alert(1)", ""})
    void open_notHttps_opensNothingAndLogsAWarnWithoutTheUrl(String url) {
        when(provider.getIfAvailable()).thenReturn(hostServices);

        try (LogCapture log = LogCapture.of(LoggerFactory.getLogger(FxExternalLinks.class))) {
            links.open(url);

            verifyNoInteractions(hostServices);
            assertThat(log.events(Level.WARN)).hasSize(1);
            assertThat(log.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> !url.isEmpty() && message.contains(url));
        }
    }

    @Test
    void open_null_opensNothingAndDoesNotThrow() {
        links.open(null);

        verify(hostServices, never()).showDocument(anyString());
        verifyNoInteractions(provider);
    }

    @Test
    void open_withoutHostServices_logsOneWarnAndDoesNotThrow() {
        when(provider.getIfAvailable()).thenReturn(null);

        try (LogCapture log = LogCapture.of(LoggerFactory.getLogger(FxExternalLinks.class))) {
            links.open(URL);

            assertThat(log.events(Level.WARN)).hasSize(1);
            assertThat(log.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("secret-value"));
        }
    }

    @Test
    void open_whenTheBrowserCallFails_swallowsItAndLogsNoUrl() {
        when(provider.getIfAvailable()).thenReturn(hostServices);
        doThrow(new IllegalStateException("boom " + URL)).when(hostServices).showDocument(URL);

        try (LogCapture log = LogCapture.of(LoggerFactory.getLogger(FxExternalLinks.class))) {
            links.open(URL);

            assertThat(log.events(Level.WARN)).hasSize(1);
            assertThat(log.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("secret-value"));
        }
    }
}
