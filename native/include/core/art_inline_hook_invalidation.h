#pragma once

namespace vector::native {

/**
 * Configure per-process ART inline-hook invalidation state before LSPlant initialization.
 *
 * This is reset for every specialized process. system_server and normal apps that are not on the
 * compatibility list pass false, making the post-bootstrap invalidation call a no-op.
 *
 * Returns whether invalidation is armed. Enabling can fail when the pre-Vector libart executable
 * state cannot be captured safely; in that case invalidation remains disabled so existing native
 * hooks are never overwritten without a recoverable baseline.
 */
bool ConfigureArtInlineHookInvalidation(bool enabled);

/** Record/forget native libart.so targets installed through LSPlant's InitInfo hook handler. */
void RecordArtInlineHookInvalidationTarget(void *target);
void ForgetArtInlineHookInvalidationTarget(void *target);

/**
 * Run the one-shot compatibility invalidation after framework bootstrap and before app loading.
 *
 * For opted-in apps, replace executable libart.so segments containing recorded Vector/LSPlant
 * targets with clean private mappings, then restore executable pages that were already modified
 * before Vector installed its hooks. This intentionally does not perform normal Dobby hook teardown,
 * so LSPlant/Dobby trampoline and interceptor metadata remain intact while Vector's patched libart
 * entry code is removed.
 * Disabled/already-invalidated states are successful no-ops.
 */
bool InvalidateArtInlineHooksIfEnabled();

}  // namespace vector::native
