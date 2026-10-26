package com.minishop.desktop;

import javafx.application.Application;

/**
 * Điểm vào duy nhất của ứng dụng. Không kế thừa {@link Application}: khi JavaFX nằm trên classpath
 * (không phải module path), JVM từ chối chạy thẳng một lớp con của {@code Application}
 * ("JavaFX runtime components are missing"), nên cần lớp {@code main} trung gian này.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(MiniShopApp.class, args);
    }
}
