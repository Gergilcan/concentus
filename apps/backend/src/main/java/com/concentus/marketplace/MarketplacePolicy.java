package com.concentus.marketplace;

import com.concentus.auth.AccountStore;
import com.concentus.auth.Accounts;
import com.concentus.auth.ConcentusUserDetails;
import com.concentus.config.Settings;
import com.concentus.config.SettingsCatalog;
import org.springframework.stereotype.Component;

/**
 * Who may do what on the marketplace — the rules of §4 of the design, in one place.
 *
 * <p>Roles come from the principal, which carries the role held in the organization the caller
 * is working in. Curators are not a role: they are the administrators of one organization, named
 * by the setting {@link SettingsCatalog#MARKETPLACE_CURATOR_ORGANIZATION} and defaulting to the
 * oldest one — so a deployment with a single organization behaves exactly as "the admin
 * approves", and nobody has to be given anything new to make that true.
 */
@Component
public class MarketplacePolicy {

    private final Settings settings;
    private final AccountStore accounts;
    private final MarketplaceStore store;

    public MarketplacePolicy(Settings settings, AccountStore accounts, MarketplaceStore store) {
        this.settings = settings;
        this.accounts = accounts;
        this.store = store;
    }

    /** The curating organization's id, or null on a database with no organization at all. */
    public String curatorOrganizationId() {
        String configured = settings.installationWide(SettingsCatalog.MARKETPLACE_CURATOR_ORGANIZATION, "");
        if (configured != null && !configured.isBlank()) return configured.trim();
        return store.oldestOrganizationId().orElse(null);
    }

    /**
     * Whether this person administers the curating organization.
     *
     * <p>Read from the principal when that is the organization they are working in — the
     * principal's role IS the membership's — and from the memberships table otherwise, so an
     * admin of the curating organization keeps curating while working somewhere else.
     */
    public boolean isCurator(ConcentusUserDetails user) {
        String curatorOrg = curatorOrganizationId();
        if (user == null || curatorOrg == null) return false;
        if (curatorOrg.equals(user.organizationId())) return isAdmin(user.role());
        return accounts.membership(user.userId(), curatorOrg).map(m -> isAdmin(m.role())).orElse(false);
    }

    public MarketplaceStore.Viewer viewerFor(ConcentusUserDetails user) {
        return new MarketplaceStore.Viewer(user.userId(), user.organizationId(), isCurator(user));
    }

    /** MEMBER and above publish — to their organization, or as a submission to everyone. */
    public boolean canPublish(ConcentusUserDetails user) {
        return Accounts.atLeast(user.role(), Accounts.ROLE_MEMBER);
    }

    /** OPERATOR and above install: it creates a resource, the same right the resource's own panel asks for. */
    public boolean canInstall(ConcentusUserDetails user) {
        return Accounts.atLeast(user.role(), Accounts.ROLE_OPERATOR);
    }

    public boolean isAuthor(MarketplaceItem item, ConcentusUserDetails user) {
        return item.author() != null && user.userId() != null && user.userId().equals(item.author().userId());
    }

    /**
     * Edit and delete go together: the author; a curator, for a global item; the administrator of
     * the organization that published it. Never a built-in — the bundle is its author.
     */
    public boolean canEdit(MarketplaceItem item, ConcentusUserDetails user, boolean curator) {
        if (item.builtIn() || !canPublish(user)) return false;
        if (isAuthor(item, user)) return true;
        if (item.isGlobal() && curator) return true;
        return item.organizationId() != null && item.organizationId().equals(user.organizationId())
                && isAdmin(user.role());
    }

    /** Approve and reject: curators, on global items that are not the bundle's. */
    public boolean canCurate(MarketplaceItem item, boolean curator) {
        return curator && item.isGlobal() && !item.builtIn();
    }

    private static boolean isAdmin(String role) {
        return Accounts.ROLE_ADMIN.equalsIgnoreCase(role);
    }
}
