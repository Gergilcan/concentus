package com.concentus.license;

/** A token that is not a valid Concentus license, with the reason a human gets shown. */
public class InvalidLicenseException extends Exception {
    public InvalidLicenseException(String message) { super(message); }
    public InvalidLicenseException(String message, Throwable cause) { super(message, cause); }
}
