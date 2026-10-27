package com.minishop.desktop;

import java.net.URI;
import javafx.application.HostServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ExternalLinks} thật, bọc {@link HostServices#showDocument}. {@code HostServices} do
 * {@link MiniShopApp#createContext} đăng ký làm bean; context của test không có nó, nên được lấy chậm qua
 * {@code ObjectProvider}: thiếu thì chỉ ghi một dòng WARN khi mở.
 * <p>
 * Chỉ mở địa chỉ {@code https://}. Địa chỉ không bao giờ vào log (có thể chứa dữ liệu của người dùng).
 */
@Component
class FxExternalLinks implements ExternalLinks {

    private static final Logger LOG = LoggerFactory.getLogger(FxExternalLinks.class);
    private static final String HTTPS = "https://";

    private final ObjectProvider<HostServices> hostServices;

    FxExternalLinks(ObjectProvider<HostServices> hostServices) {
        this.hostServices = hostServices;
    }

    @Override
    public void open(String httpsUrl) {
        if (!isHttps(httpsUrl)) {
            LOG.warn("Refused to open a link that is not https");
            return;
        }
        HostServices services = hostServices.getIfAvailable();
        if (services == null) {
            LOG.warn("Cannot open a link: no HostServices in this context");
            return;
        }
        try {
            services.showDocument(httpsUrl);
        } catch (RuntimeException e) {
            LOG.warn("Could not open a link in the browser: {}", e.getClass().getSimpleName());
        }
    }

    /** {@code https://} (không phân biệt hoa thường), có phần máy chủ và là URI hợp lệ. */
    static boolean isHttps(String url) {
        if (url == null || !url.regionMatches(true, 0, HTTPS, 0, HTTPS.length())) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            return uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
