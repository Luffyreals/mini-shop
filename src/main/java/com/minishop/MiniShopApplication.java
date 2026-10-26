package com.minishop;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Root of the component scan. It has no {@code main}: the only entry point is {@code desktop.Launcher}, which
 * builds the Spring context in {@code MiniShopApp.init()} (ADR-0014).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MiniShopApplication {

}
