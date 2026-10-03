package dev.cyberblock.client;

/**
 * Marker for the toasts Cyberblock draws itself (plan retest3 section 1.5): ToastManagerMixin keeps every other toast (advancements,
 * recipes, tutorials) off the host's picture but lets a Toast that implements this through. The UI track's CyberToast implements it.
 */
public interface AllowedToast {
}
