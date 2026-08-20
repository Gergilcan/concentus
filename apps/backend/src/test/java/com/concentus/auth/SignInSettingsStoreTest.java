package com.concentus.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whether this installation asks people to sign in.
 *
 * <p>Kept in a file rather than the database for the same reason the storage setting is: it
 * decides how the security filter chain is built, which happens before anything has a connection.
 * The shell reads it when it starts the backend, so what matters here is that the file is
 * readable, that a damaged one does not lock somebody out of the screen that would fix it, and
 * that a deployment configured from properties refuses to be changed from inside the app.
 */
class SignInSettingsStoreTest {

    private static SignInSettingsStore storeIn(Path dataDir) {
        return new SignInSettingsStore(dataDir.toString(), new ObjectMapper());
    }

    @Test
    void a_fresh_install_does_not_ask_for_a_sign_in(@TempDir Path dataDir) {
        assertThat(storeIn(dataDir).load().required()).isFalse();
    }

    @Test
    void the_choice_survives_being_written(@TempDir Path dataDir) {
        SignInSettingsStore store = storeIn(dataDir);

        store.save(true);

        assertThat(storeIn(dataDir).load().required()).isTrue();
        // The shell reads this file by name when it starts the backend, so the name is part of the
        // contract rather than an implementation detail.
        assertThat(Files.exists(dataDir.resolve("sign-in.json"))).isTrue();
    }

    @Test
    void it_can_be_turned_back_off(@TempDir Path dataDir) {
        SignInSettingsStore store = storeIn(dataDir);
        store.save(true);

        store.save(false);

        assertThat(storeIn(dataDir).load().required()).isFalse();
    }

    // The only interface for fixing this is inside the application, so refusing to start would lock
    // somebody out of the one place they could correct it.
    @Test
    void a_damaged_file_reads_as_off_rather_than_failing(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("sign-in.json"), "{not json at all");

        assertThat(storeIn(dataDir).load().required()).isFalse();
    }

    // A deployment anyone can reach must not be able to switch its own front door off through its
    // own front door: there, the answer comes from configuration.
    @Test
    void a_deployment_with_no_data_directory_will_not_be_changed_from_inside() {
        SignInSettingsStore store = new SignInSettingsStore("", new ObjectMapper());

        assertThat(store.isSupported()).isFalse();
        assertThat(store.load().required()).isFalse();
        assertThatThrownBy(() -> store.save(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration");
    }
}
