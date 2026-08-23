package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.license.License;
import com.concentus.license.LicenseService;
import com.concentus.license.TestLicenses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The license screen's write side. Reading — {@code GET /api/license} — is open to every signed-in
 * role, the same way the rest of {@code GET /api/**} is; there is nothing in a status worth hiding
 * from a Viewer. Installing a new key changes what the whole organization may do, so it follows the
 * same {@link OrgContext#requireAdmin()} gate {@code SettingsController#save} uses.
 */
class LicenseControllerTest {

    /** As {@code StorageControllerMigrationTest}'s: admin without a real signed-in session. */
    private static OrgContext adminContext() {
        return new OrgContext("default") {
            @Override
            public boolean isAdmin() {
                return true;
            }
        };
    }

    private static OrgContext nonAdminContext() {
        return new OrgContext("default") {
            @Override
            public boolean isAdmin() {
                return false;
            }
        };
    }

    @Test
    void a_non_admin_post_is_refused(@TempDir Path dir) throws Exception {
        LicenseService license = TestLicenses.serviceOn(dir);
        LicenseController controller = new LicenseController(license, nonAdminContext());

        assertThatThrownBy(() -> controller.install(
                new LicenseController.InstallRequest(TestLicenses.token("individual-test.license"))))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        // Refused before anything was written: the license in effect is still whatever it was.
        assertThat(license.status().valid()).isFalse();
    }

    @Test
    void an_admin_post_installs_the_license(@TempDir Path dir) throws Exception {
        LicenseService license = TestLicenses.serviceOn(dir);
        LicenseController controller = new LicenseController(license, adminContext());

        ResponseEntity<?> response = controller.install(
                new LicenseController.InstallRequest(TestLicenses.token("individual-test.license")));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(license.status().tier()).isEqualTo(License.TIER_INDIVIDUAL);
    }

    // Desktop installs still run through a signed-in account — OrgContext's own docs: "There is no
    // longer a mode without accounts" — the single admin the first-run wizard creates. This proves
    // the gate above is exactly as no-op for that admin as SettingsController#save already is: it
    // does not accidentally lock a desktop install out of its own license screen.
    @Test
    void the_admin_gate_does_not_get_in_the_way_of_a_desktop_installs_own_admin(@TempDir Path dir)
            throws Exception {
        LicenseService license = TestLicenses.serviceOn(dir);
        LicenseController controller = new LicenseController(license, adminContext());

        ResponseEntity<?> response = controller.install(
                new LicenseController.InstallRequest(TestLicenses.token("enterprise-test.license")));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(license.enterpriseActive()).isTrue();
    }

    @Test
    void reading_the_status_needs_no_admin(@TempDir Path dir) throws Exception {
        LicenseService license = TestLicenses.serviceOn(dir);
        LicenseController controller = new LicenseController(license, nonAdminContext());

        assertThat(controller.status()).isNotNull(); // does not throw for a non-admin caller
    }
}
