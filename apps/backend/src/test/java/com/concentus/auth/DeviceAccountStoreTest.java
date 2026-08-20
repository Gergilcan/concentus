package com.concentus.auth;

import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accounts one browser has signed in with.
 *
 * <p>Nothing here grants access — the rows record what this browser already proved, with each
 * account's own password or provider. So what the tests are about is the boundary: that a device
 * is offered only its own accounts, that an id from a request cannot become an account it never
 * signed into, and that forgetting one is complete.
 */
class DeviceAccountStoreTest {

    private static DeviceAccountStore storeOn(DataSource ds, int rememberMeDays) {
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        return new DeviceAccountStore(new JdbcTemplate(ds), rememberMeDays);
    }

    private static Accounts.UserAccount account(String id, String email, String role) {
        return new Accounts.UserAccount(id, "default", email, "hash", role, true, 0L);
    }

    @Test
    void an_account_signed_into_here_can_be_returned_to() {
        DeviceAccountStore store = storeOn(TestDatabase.freshDatabase("device_1"), 30);

        store.attach("device-a", account("u1", "gerard@tecnovent.com", "ADMIN"));

        assertThat(store.isAttached("device-a", "u1")).isTrue();
        assertThat(store.forDevice("device-a")).extracting(DeviceAccountStore.Attached::email)
                .containsExactly("gerard@tecnovent.com");
    }

    // The boundary the switch endpoint rests on: the account id arrives from a request, and the
    // device id from a cookie this application set. Only their pairing is authorization.
    @Test
    void an_account_signed_into_somewhere_else_is_not_this_browsers_to_use() {
        DeviceAccountStore store = storeOn(TestDatabase.freshDatabase("device_2"), 30);
        store.attach("device-a", account("u1", "gerard@tecnovent.com", "ADMIN"));

        assertThat(store.isAttached("device-b", "u1")).isFalse();
        assertThat(store.forDevice("device-b")).isEmpty();
    }

    @Test
    void a_browser_with_no_device_id_has_no_accounts() {
        DeviceAccountStore store = storeOn(TestDatabase.freshDatabase("device_3"), 30);
        store.attach("device-a", account("u1", "a@b.com", "ADMIN"));

        assertThat(store.forDevice(null)).isEmpty();
        assertThat(store.isAttached(null, "u1")).isFalse();
    }

    // Signing in again is what keeps an attachment alive, not what duplicates it.
    @Test
    void signing_in_again_refreshes_rather_than_repeats() {
        DeviceAccountStore store = storeOn(TestDatabase.freshDatabase("device_4"), 30);

        store.attach("device-a", account("u1", "old@tecnovent.com", "ADMIN"));
        store.attach("device-a", account("u1", "new@tecnovent.com", "ADMIN"));

        assertThat(store.forDevice("device-a")).hasSize(1);
        // The address travels with the row so the switcher can name accounts without reading every
        // user, which means it has to follow a rename.
        assertThat(store.forDevice("device-a").getFirst().email()).isEqualTo("new@tecnovent.com");
    }

    @Test
    void the_most_recently_used_account_comes_first() throws InterruptedException {
        DeviceAccountStore store = storeOn(TestDatabase.freshDatabase("device_5"), 30);
        store.attach("device-a", account("u1", "first@tecnovent.com", "ADMIN"));
        Thread.sleep(5);
        store.attach("device-a", account("u2", "second@tecnovent.com", "VIEWER"));
        Thread.sleep(5);
        store.touch("device-a", "u1");

        assertThat(store.forDevice("device-a")).extracting(DeviceAccountStore.Attached::userId)
                .containsExactly("u1", "u2");
    }

    @Test
    void forgetting_an_account_here_leaves_the_others_alone() {
        DeviceAccountStore store = storeOn(TestDatabase.freshDatabase("device_6"), 30);
        store.attach("device-a", account("u1", "a@b.com", "ADMIN"));
        store.attach("device-a", account("u2", "c@d.com", "VIEWER"));

        store.detach("device-a", "u2");

        assertThat(store.forDevice("device-a")).extracting(DeviceAccountStore.Attached::userId)
                .containsExactly("u1");
    }

    // An attachment that outlived the session it stands for would offer a sign-in screen with
    // extra steps, so it expires on the same window as staying signed in.
    @Test
    void an_attachment_older_than_the_sign_in_window_is_gone() {
        DataSource ds = TestDatabase.freshDatabase("device_7");
        DeviceAccountStore store = storeOn(ds, 30);
        store.attach("device-a", account("u1", "a@b.com", "ADMIN"));
        // Reached into rather than waited out: the alternative is a test that takes a month.
        new JdbcTemplate(ds).update("update device_accounts set last_used_at = ?",
                System.currentTimeMillis() - java.time.Duration.ofDays(31).toMillis());

        assertThat(store.forDevice("device-a")).isEmpty();
        assertThat(store.isAttached("device-a", "u1")).isFalse();
    }
}
