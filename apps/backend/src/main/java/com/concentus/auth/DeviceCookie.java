package com.concentus.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * The opaque id that says which browser this is.
 *
 * <p>Not a fingerprint of the machine — a value this application minted and set, which the person
 * can drop by clearing their cookies. It names nothing and proves nothing on its own; it is the
 * key under which {@link DeviceAccountStore} records the accounts this browser has already signed
 * into.
 *
 * <p><b>HttpOnly.</b> The point of keeping the switcher's state on the server is that no second
 * credential ends up somewhere a page can read. A device id that script could read would be that
 * credential, and the arrangement would be back where it started.
 */
public final class DeviceCookie {

    public static final String NAME = "concentus_device";

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceCookie() {
    }

    /** The browser's id, or null when it has none yet. */
    public static String of(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * The browser's id, minting and setting one if this is the first time.
     *
     * @param days how long it lives — the same window as staying signed in, since an id that
     *             outlived every account attached to it would only ever name an empty list
     */
    public static String ensure(HttpServletRequest request, HttpServletResponse response, int days) {
        String existing = of(request);
        if (existing != null) return existing;

        String id = HexFormat.of().formatHex(randomBytes());
        Cookie cookie = new Cookie(NAME, id);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) Duration.ofDays(Math.max(1, days)).toSeconds());
        // Lax rather than Strict: a sign-in through an identity provider comes back as a
        // top-level navigation from another site, and Strict would withhold the cookie on exactly
        // that request — the one where the account is attached.
        cookie.setAttribute("SameSite", "Lax");
        cookie.setSecure(request.isSecure());
        response.addCookie(cookie);
        return id;
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
