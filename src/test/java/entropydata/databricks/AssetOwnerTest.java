package entropydata.databricks;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import entropydata.databricks.DatabricksProperties.AssetsProperties;
import entropydata.sdk.EntropyDataClient;
import entropydata.sdk.client.ApiException;
import entropydata.sdk.client.model.Team;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AssetOwnerTest {

  private final EntropyDataClient client = mock(EntropyDataClient.class, RETURNS_DEEP_STUBS);

  @Test
  void stopsTheConnectorWhenTheOwnerIsNotATeam() {
    when(client.getTeamsApi().getTeam("team-typo")).thenThrow(new ApiException(404, "Not Found"));

    assertThatThrownBy(() -> AssetOwner.requireExistingTeam(client, assets("team-typo")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("team-typo");
  }

  @Test
  void acceptsAnExistingTeam() {
    when(client.getTeamsApi().getTeam("team-data-platform")).thenReturn(new Team());

    assertThatCode(() -> AssetOwner.requireExistingTeam(client, assets("team-data-platform"))).doesNotThrowAnyException();
  }

  @Test
  void asksNothingWhenNoOwnerIsConfigured() {
    var untouchedClient = mock(EntropyDataClient.class);

    AssetOwner.requireExistingTeam(untouchedClient, assets(""));

    verifyNoInteractions(untouchedClient);
  }

  private static AssetsProperties assets(String owner) {
    return new AssetsProperties(true, "databricks-assets", Duration.parse("PT10M"), null, null, null, owner);
  }
}
