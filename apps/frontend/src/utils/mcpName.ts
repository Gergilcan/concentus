/**
 * The CLI's name for an MCP server, as a person should read it.
 *
 * <p>`claude mcp add` takes letters, digits, hyphens and underscores and nothing else, so a block
 * called "Ryze Google Ads" is registered as `Ryze_Google_Ads` (see McpRegistry.cliName on the
 * backend, which is the other half of this). Underscores are only ever a space that had to be
 * spelled differently, so turning them back is lossless — and a list of servers reads like names
 * instead of like identifiers.
 *
 * <p>Labels only. A command somebody is meant to copy — `claude mcp login "Ryze_Google_Ads"` —
 * keeps the real name, because that is the one the CLI answers to.
 */
export function mcpDisplayName(cliName: string): string {
  return cliName.replace(/_/g, ' ')
}
