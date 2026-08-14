package com.concentus.model;

/**
 * What installing a runtime would run on this machine.
 *
 * <p>Fetched before anything happens so the UI can show the command next to the button that runs
 * it. A null {@code command} is not a failure: it means there is no unattended installer worth
 * running here, and {@code reason} says why — which is the honest answer for a Linux distribution
 * whose package manager needs a decision this app should not make.
 */
public record RuntimeInstallPlan(String runtime, String command, String reason) {
}
