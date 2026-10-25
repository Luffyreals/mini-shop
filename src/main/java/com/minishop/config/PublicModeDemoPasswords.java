package com.minishop.config;

import com.minishop.user.DemoAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In public mode, resets the passwords of the two demo accounts to the configured ones (BR-008-2, AC-008-3), so the
 * app is never reachable while {@code admin@minishop.local} still has the dev password.
 * <p>Why a {@link SmartInitializingSingleton} and not an {@code ApplicationRunner} (like {@code DataInitializer}):
 * {@code afterSingletonsInstantiated()} runs once every singleton exists (Flyway has migrated, the transaction manager
 * is ready) but <em>before</em> {@code finishRefresh()}. Boot creates Tomcat early but detaches its connector, and only
 * re-attaches it in {@code WebServerStartStopLifecycle.start()} during {@code finishRefresh()}. So no request can ever
 * be served before this reset. An {@code ApplicationRunner} runs after Tomcat has started accepting requests.
 * <p>When the reset updates 0 rows there are no demo accounts. On an empty {@code users} table {@code DataInitializer}
 * then seeds them with the same configured passwords; if the table already holds other users, the seed does not run and
 * no demo account will exist (the log line says only that none was found). The log never contains a password or an
 * email.
 */
@Component
@Profile("public")
class PublicModeDemoPasswords implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PublicModeDemoPasswords.class);

    private final SeedProperties seed;
    private final DemoAccountService demoAccounts;

    PublicModeDemoPasswords(SeedProperties seed, DemoAccountService demoAccounts) {
        this.seed = seed;
        this.demoAccounts = demoAccounts;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int updated = demoAccounts.resetDemoPasswords(seed.adminPassword(), seed.userPassword());
        if (updated > 0) {
            log.info("Public mode: demo passwords updated for {} account(s)", updated);
        } else {
            log.info("Public mode: no demo accounts found, so no password was reset");
        }
    }
}
