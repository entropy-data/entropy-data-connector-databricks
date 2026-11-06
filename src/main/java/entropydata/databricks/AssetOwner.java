package entropydata.databricks;

import entropydata.databricks.DatabricksProperties.AssetsProperties;
import entropydata.sdk.EntropyDataClient;
import entropydata.sdk.client.ApiException;

/**
 * The team that owns synchronized assets in Entropy Data. Entropy Data only lets assets with an owner be edited, for example
 * to assign tags. The SDK keeps an owner set in Entropy Data, so this team only fills an empty one.
 */
final class AssetOwner {

  private AssetOwner() {
  }

  static String teamId(AssetsProperties assets) {
    var owner = assets == null ? null : assets.owner();
    return owner == null || owner.isBlank() ? null : owner.trim();
  }

  /**
   * Entropy Data refuses an asset whose owner is not a team, which would fail every asset of every run, so a mistyped team id
   * stops the connector at startup instead.
   */
  static void requireExistingTeam(EntropyDataClient client, AssetsProperties assets) {
    var teamId = teamId(assets);
    if (teamId == null) {
      return;
    }
    try {
      client.getTeamsApi().getTeam(teamId);
    } catch (ApiException e) {
      if (e.getCode() == 404) {
        throw new IllegalStateException("The asset owner " + teamId + " (ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_OWNER) is not the ID "
            + "of a team in Entropy Data. Use the ID shown on the team's page, not the team's display name.", e);
      }
      throw e;
    }
  }
}
