/**
 * Host half of the phone-mirror bundle.
 *
 * The entry is deliberately thin: `logic.js` holds the pipeline and the routes
 * and is imported with a cache-busting query on every activation, so a change
 * to the mirror only needs the plugin to be toggled instead of the whole Harness
 * process to be restarted. This file itself stays stable.
 */
export const inject = ['webServer']

/**
 * Load the current implementation and mount it on this context.
 * @param ctx - host context carrying the browser HTTP carrier.
 */
export async function apply(ctx) {
  const url = new URL(`./logic.js?activation=${Date.now()}`, import.meta.url).href
  const logic = await import(url)
  await logic.mount(ctx)
}
